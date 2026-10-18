package app.slovnik;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Speech cache: every phrase the system TTS engine says is also rendered to a WAV file (filesDir/tts),
 * so the next time it plays instantly from disk without the engine — a big win with slow neural voices.
 *
 *  - cache hit  → MediaPlayer plays the file;
 *  - cache miss → the engine speaks live right away, then the phrase is rendered to a file in the background;
 *  - prefetch   → words of a review session are rendered ahead of time, one at a time.
 *
 * Keys include the engine, voice and accent, so switching any of them never plays a stale recording.
 * Everything except file listing runs on the UI thread; engine callbacks are posted back to it.
 */
final class TtsCache {
    static final long MAX_BYTES = 150L * 1024 * 1024;
    static final int MIN_FILE = 1000;   // anything smaller is a failed render

    private static final class Item {
        final String key, text, lang;
        Item(String key, String text, String lang) { this.key = key; this.text = text; this.lang = lang; }
    }

    private final File dir;
    private final Handler ui;
    private final ExecutorService bg;
    private TextToSpeech tts;
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private Item busy;            // being rendered to a file right now
    private String busyId;
    private String liveId;        // engine is speaking live
    private int seq = 0;
    private MediaPlayer player;

    // "voice all words" job: keys still to render, and counters for the progress shown on the page
    private final Set<String> jobKeys = new HashSet<>();
    private int jobTotal = 0, jobDone = 0, jobFailed = 0;

    TtsCache(File filesDir, Handler ui, ExecutorService bg) {
        this.dir = new File(filesDir, "tts");
        this.ui = ui;
        this.bg = bg;
        bg.execute(() -> {
            if (!dir.exists() && !dir.mkdirs()) return;
            File[] parts = dir.listFiles((d, n) -> n.endsWith(".part"));
            if (parts != null) for (File f : parts) f.delete();
        });
    }

    void attach(TextToSpeech t) {
        tts = t;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { ui.post(() -> ended(id, true)); }
            @Override @Deprecated public void onError(String id) { ui.post(() -> ended(id, false)); }
            @Override public void onError(String id, int code) { ui.post(() -> ended(id, false)); }
            @Override public void onStop(String id, boolean interrupted) { ui.post(() -> ended(id, false)); }
        });
        pump();
    }

    boolean ready() { return tts != null; }

    /* ---------- keys and files ---------- */

    private void setLang(String lang) {
        Locale loc = "en-GB".equals(lang) ? Locale.UK : Locale.US;
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.ENGLISH);
    }

    /** Call after setLang(): the chosen voice is part of the key. */
    private String key(String text, String lang) {
        String voice = "";
        try { Voice v = tts.getVoice(); if (v != null) voice = v.getName(); } catch (Exception ignored) { }
        String engine = tts.getDefaultEngine();
        String raw = engine + "|" + voice + "|" + lang + "|" + text.trim();
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private File file(String key) { return new File(dir, key + ".wav"); }

    private boolean cached(String key) { return file(key).length() > MIN_FILE; }

    /* ---------- speaking ---------- */

    void speak(String text, String lang) {
        if (tts == null) return;
        stopPlayer();
        setLang(lang);
        String k = key(text, lang);
        File f = file(k);
        if (cached(k)) {
            if (play(f)) {
                f.setLastModified(System.currentTimeMillis());
                MainActivity.log("озвучка из кэша: «" + clip(text) + "»");
                return;
            }
            f.delete();
        }
        // not cached yet: speak live now (this flushes the engine queue), render to a file afterwards
        if (busy != null) { requeueFront(busy); busy = null; busyId = null; }
        removeQueued(k);
        requeueFront(new Item(k, text, lang));
        liveId = "live:" + (++seq);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, liveId);
        MainActivity.log("озвучка движком, сохраню в кэш: «" + clip(text) + "»");
    }

    void stop() {
        stopPlayer();
        if (tts == null) return;
        if (busy != null) { requeueFront(busy); busy = null; busyId = null; }
        if (liveId != null) { liveId = null; }
        tts.stop();
        ui.postDelayed(this::pump, 300);
    }

    /** Render these phrases ahead of time (e.g. all words of a review session). */
    void prefetch(String[] texts, String lang) { prefetch(texts, lang, false); }

    /**
     * Render phrases ahead of time. With {@code job} the missing ones also count towards the
     * "voice all words" progress ({@link #progress()}).
     */
    void prefetch(String[] texts, String lang, boolean job) {
        if (tts == null || texts == null) return;
        setLang(lang);
        if (job && jobKeys.isEmpty()) { jobTotal = 0; jobDone = 0; jobFailed = 0; }
        int added = 0;
        for (String t : texts) {
            if (t == null) continue;
            String s = t.trim();
            if (s.isEmpty() || s.length() > 200) continue;
            String k = key(s, lang);
            if (cached(k)) continue;
            if (job && jobKeys.add(k)) jobTotal++;
            if (queued.contains(k) || (busy != null && busy.key.equals(k))) continue;
            queue.addLast(new Item(k, s, lang));
            queued.add(k);
            added++;
        }
        if (added > 0) MainActivity.log("озвучка: " + (job ? "озвучиваю все слова, недостающих " + jobKeys.size() : "заранее готовлю " + added));
        pump();
    }

    /** {"total":T,"done":D,"failed":F,"active":true|false} for the "voice all words" job. */
    String progress() {
        return "{\"total\":" + jobTotal + ",\"done\":" + jobDone + ",\"failed\":" + jobFailed + ",\"active\":" + !jobKeys.isEmpty() + "}";
    }

    /** Stop the "voice all words" job; phrases already rendered stay in the cache. */
    void cancelJob() {
        if (jobKeys.isEmpty()) return;
        queue.removeIf(i -> jobKeys.contains(i.key) && queued.remove(i.key));
        MainActivity.log("озвучка всех слов остановлена: готово " + jobDone + " из " + jobTotal);
        jobTotal = jobDone + jobFailed;
        jobKeys.clear();
    }

    private void jobFinished(String key, boolean ok) {
        if (!jobKeys.remove(key)) return;
        if (ok) jobDone++; else jobFailed++;
        if (jobKeys.isEmpty()) MainActivity.log("озвучка всех слов готова: " + jobDone + " из " + jobTotal + (jobFailed > 0 ? ", не вышло " + jobFailed : ""));
    }

    private void requeueFront(Item it) {
        if (queued.contains(it.key)) return;
        queue.addFirst(it);
        queued.add(it.key);
    }

    private void removeQueued(String key) {
        if (!queued.remove(key)) return;
        queue.removeIf(i -> i.key.equals(key));
    }

    /** Render the next phrase when the engine is free. */
    private void pump() {
        if (tts == null || liveId != null || busy != null) return;
        while (!queue.isEmpty()) {
            Item it = queue.pollFirst();
            queued.remove(it.key);
            if (cached(it.key)) { jobFinished(it.key, true); continue; }
            setLang(it.lang);
            busyId = "file:" + it.key + ":" + (++seq);
            File part = new File(dir, it.key + "." + seq + ".part");
            int r = tts.synthesizeToFile(it.text, new Bundle(), part, busyId);
            if (r == TextToSpeech.SUCCESS) { busy = it; return; }
            busyId = null;
            jobFinished(it.key, false);
        }
    }

    private void ended(String id, boolean ok) {
        if (id == null) return;
        if (id.startsWith("live:")) {
            if (id.equals(liveId)) liveId = null;
            pump();
            return;
        }
        if (!id.startsWith("file:")) return;
        String[] p = id.split(":");
        if (p.length < 3) return;
        File part = new File(dir, p[1] + "." + p[2] + ".part");
        boolean current = id.equals(busyId);
        if (ok && part.length() > MIN_FILE && part.renameTo(file(p[1]))) {
            trimLater();
            jobFinished(p[1], true);
        } else {
            part.delete();
            if (current) jobFinished(p[1], false);
        }
        if (current) { busy = null; busyId = null; }
        pump();
    }

    private boolean play(File f) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(f.getPath());
            mp.setOnCompletionListener(m -> { m.release(); if (player == m) player = null; });
            mp.prepare();
            mp.start();
            player = mp;
            return true;
        } catch (Exception e) {
            MainActivity.log("озвучка: файл кэша не играет (" + e.getMessage() + "), удаляю");
            return false;
        }
    }

    private void stopPlayer() {
        if (player == null) return;
        try { player.stop(); } catch (Exception ignored) { }
        player.release();
        player = null;
    }

    /* ---------- housekeeping ---------- */

    private void trimLater() {
        bg.execute(() -> {
            File[] fs = dir.listFiles((d, n) -> n.endsWith(".wav"));
            if (fs == null) return;
            long total = 0;
            for (File f : fs) total += f.length();
            if (total <= MAX_BYTES) return;
            Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int removed = 0;
            for (File f : fs) {
                if (total <= MAX_BYTES * 8 / 10) break;
                total -= f.length();
                if (f.delete()) removed++;
            }
            MainActivity.log("кэш озвучки переполнен: удалено " + removed + " старых фраз");
        });
    }

    /** {"files": N, "bytes": B} — safe from any thread. */
    String info() {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".wav"));
        long bytes = 0;
        int n = fs == null ? 0 : fs.length;
        if (fs != null) for (File f : fs) bytes += f.length();
        return "{\"files\":" + n + ",\"bytes\":" + bytes + "}";
    }

    void clear() {
        stop();
        queue.clear();
        queued.clear();
        jobKeys.clear();
        jobTotal = 0; jobDone = 0; jobFailed = 0;
        bg.execute(() -> {
            File[] fs = dir.listFiles();
            int n = 0;
            if (fs != null) for (File f : fs) if (f.delete()) n++;
            MainActivity.log("кэш озвучки очищен: " + n + " файлов");
        });
    }

    void release() {
        stopPlayer();
        queue.clear();
        queued.clear();
    }

    private static String clip(String s) { return s.length() > 40 ? s.substring(0, 40) + "…" : s; }
}
