package app.slovnik;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reminders built from the user's own words (read from the local copy of the dictionary, so they work offline).
 *
 *  daily  — at the chosen time, if words are due and there was no practice today: "12 words are waiting, e.g. «bemused»?"
 *  quiz   — up to 3 a day: one due word in the shade; "Show translation", then "Remember" / "Forgot" grade it in place
 *  streak — in the evening, if a streak of 2+ days would break today
 *  weekly — Sunday noon: words still without a translation
 *
 * Never inside quiet hours, at most 3 a day, nothing at all once you've practised today,
 * and the same word at most once a day in the shade.
 */
final class Notifications {
    static final String CHANNEL = "reminders";
    static final String ACTION_ALARM = "app.slovnik.ALARM";
    static final String ACTION_REVEAL = "app.slovnik.REVEAL";
    static final String ACTION_GRADE = "app.slovnik.GRADE";
    static final String[] KINDS = { "daily", "quiz", "streak", "weekly" };
    static final int ID_DAILY = 101, ID_QUIZ = 102, ID_STREAK = 103, ID_WEEKLY = 104;
    static final int MAX_PER_DAY = 3;

    static final long MIN = 60_000L, DAY = 86_400_000L;
    static final long[] INTERVALS = { 10 * MIN, DAY, 3 * DAY, 7 * DAY, 14 * DAY, 30 * DAY, 60 * DAY };
    static final int MAX_LV = 6;

    private Notifications() { }

    /* ---------- settings ---------- */

    static final class Settings {
        boolean on = true;
        int time = 19 * 60;      // minutes after midnight
        int quiz = 2;            // cards in the shade per day, 0–3
        int quietFrom = 22 * 60, quietTo = 9 * 60;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("notif", Context.MODE_PRIVATE); }

    static int hm(String s, int def) {
        if (s == null) return def;
        String[] p = s.trim().split(":");
        if (p.length != 2) return def;
        try {
            int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
            return h >= 0 && h < 24 && m >= 0 && m < 60 ? h * 60 + m : def;
        } catch (NumberFormatException e) { return def; }
    }

    static String hmText(int min) { return String.format(Locale.US, "%02d:%02d", min / 60, min % 60); }

    static Settings settings(Context c) {
        Settings s = new Settings();
        JSONObject n = null;
        try {
            String saved = prefs(c).getString("settings", "");
            if (!saved.isEmpty()) n = new JSONObject(saved);
        } catch (JSONException ignored) { }
        if (n == null) {
            synchronized (Store.LOCK) {
                JSONObject snap = Store.readSnapshot(c);
                JSONObject meta = snap == null ? null : snap.optJSONObject("meta");
                n = meta == null ? null : meta.optJSONObject("notif");
            }
        }
        if (n != null) {
            s.on = n.optBoolean("on", true);
            s.time = hm(n.optString("time", null), s.time);
            s.quiz = Math.max(0, Math.min(3, n.optInt("quiz", s.quiz)));
            s.quietFrom = hm(n.optString("quietFrom", null), s.quietFrom);
            s.quietTo = hm(n.optString("quietTo", null), s.quietTo);
        }
        return s;
    }

    static void saveSettings(Context c, String json) {
        prefs(c).edit().putString("settings", json == null ? "" : json).apply();
    }

    /* ---------- the user's data ---------- */

    static String day(long t) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t)); }

    static List<JSONObject> words(JSONObject snap) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = snap == null ? null : snap.optJSONArray("words");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) { JSONObject w = a.optJSONObject(i); if (w != null) out.add(w); }
        return out;
    }

    static int lv(JSONObject w) { JSONObject l = w.optJSONObject("lv"); return l == null ? 0 : l.optInt("en", 0); }
    static long due(JSONObject w) { JSONObject d = w.optJSONObject("due"); return d == null ? 0 : d.optLong("en", 0); }
    static boolean translated(JSONObject w) { return !w.optString("ru", "").trim().isEmpty(); }
    static boolean isDue(JSONObject w, long now) { return translated(w) && due(w) <= now; }

    /** Days with practice: what Словник recorded (meta.days) plus answers given right in the shade. */
    static Set<String> practiceDays(Context c, JSONObject snap) {
        Set<String> days = new HashSet<>(prefs(c).getStringSet("days", new HashSet<>()));
        JSONObject meta = snap == null ? null : snap.optJSONObject("meta");
        JSONArray a = meta == null ? null : meta.optJSONArray("days");
        if (a != null) for (int i = 0; i < a.length(); i++) days.add(a.optString(i));
        return days;
    }

    static int streak(Set<String> days, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        if (!days.contains(day(c.getTimeInMillis()))) c.add(Calendar.DAY_OF_YEAR, -1);
        int n = 0;
        while (days.contains(day(c.getTimeInMillis()))) { n++; c.add(Calendar.DAY_OF_YEAR, -1); }
        return n;
    }

    static String setName(JSONObject snap, String id) {
        JSONObject meta = snap == null ? null : snap.optJSONObject("meta");
        JSONArray sets = meta == null ? null : meta.optJSONArray("sets");
        if (sets == null || id == null || id.isEmpty()) return null;
        for (int i = 0; i < sets.length(); i++) {
            JSONObject s = sets.optJSONObject(i);
            if (s != null && id.equals(s.optString("id"))) return s.optString("name", null);
        }
        return null;
    }

    static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        String w = m10 == 1 && m100 != 11 ? one : m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20) ? few : many;
        return n + " " + w;
    }

    /* ---------- counters ---------- */

    static int sentToday(Context c) {
        SharedPreferences p = prefs(c);
        return day(System.currentTimeMillis()).equals(p.getString("countDay", "")) ? p.getInt("count", 0) : 0;
    }

    static void countSent(Context c) {
        SharedPreferences p = prefs(c);
        String today = day(System.currentTimeMillis());
        int n = today.equals(p.getString("countDay", "")) ? p.getInt("count", 0) : 0;
        p.edit().putString("countDay", today).putInt("count", n + 1).apply();
    }

    static Set<String> askedToday(Context c) {
        SharedPreferences p = prefs(c);
        if (!day(System.currentTimeMillis()).equals(p.getString("askedDay", ""))) return new HashSet<>();
        return new HashSet<>(p.getStringSet("asked", new HashSet<>()));
    }

    static void markAsked(Context c, String id) {
        Set<String> s = askedToday(c);
        s.add(id);
        prefs(c).edit().putString("askedDay", day(System.currentTimeMillis())).putStringSet("asked", s).apply();
    }

    static boolean inQuiet(Settings s, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (s.quietFrom == s.quietTo) return false;
        return s.quietFrom < s.quietTo ? (m >= s.quietFrom && m < s.quietTo) : (m >= s.quietFrom || m < s.quietTo);
    }

    /* ---------- schedule ---------- */

    static long nextAt(int minuteOfDay, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(Calendar.MINUTE, minuteOfDay % 60);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= now + MIN) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    static long nextWeekly(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || c.getTimeInMillis() <= now + MIN) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    /** Quiz slots spread evenly between the end of quiet hours (+2h) and an hour before the daily reminder. */
    static List<Integer> quizSlots(Settings s) {
        List<Integer> out = new ArrayList<>();
        if (s.quiz <= 0) return out;
        int start = s.quietTo + 120, end = s.time - 60;
        if (end - start < 60) { start = s.quietTo + 60; end = s.quietFrom - 60; }
        if (end <= start) { start = 11 * 60; end = 18 * 60; }
        for (int k = 0; k < s.quiz; k++) out.add(start + (int) ((k + 0.5) * (end - start) / s.quiz));
        return out;
    }

    static int streakMinute(Settings s) { return Math.max(s.quietTo + 60, Math.min(21 * 60, s.quietFrom - 60)); }

    static PendingIntent alarmIntent(Context c, String kind) {
        Intent i = new Intent(c, NotifReceiver.class).setAction(ACTION_ALARM).putExtra("kind", kind);
        return PendingIntent.getBroadcast(c, 300 + indexOf(kind), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static int indexOf(String kind) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return i;
        return 9;
    }

    /** Recomputes every alarm. Cheap; called on launch, after each reminder, on settings change and after reboot. */
    static Map<String, Long> scheduleAll(Context c) {
        Map<String, Long> next = new HashMap<>();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return next;
        for (String k : KINDS) am.cancel(alarmIntent(c, k));
        Settings s = settings(c);
        if (!s.on) { MainActivity.log("напоминания выключены"); return next; }
        long now = System.currentTimeMillis();
        next.put("daily", nextAt(s.time, now));
        long quiz = Long.MAX_VALUE;
        for (int m : quizSlots(s)) quiz = Math.min(quiz, nextAt(m, now));
        if (quiz != Long.MAX_VALUE) next.put("quiz", quiz);
        next.put("streak", nextAt(streakMinute(s), now));
        next.put("weekly", nextWeekly(now));
        StringBuilder b = new StringBuilder("расписание:");
        SimpleDateFormat f = new SimpleDateFormat("EEE dd.MM HH:mm", new Locale("ru"));
        for (String k : KINDS) {
            Long at = next.get(k);
            if (at == null) continue;
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c, k));
            b.append(' ').append(k).append('=').append(f.format(new Date(at)));
        }
        MainActivity.log(b.toString());
        return next;
    }

    /* ---------- posting ---------- */

    static boolean allowed(Context c) {
        if (Build.VERSION.SDK_INT >= 33
            && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        return nm != null && nm.areNotificationsEnabled();
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Повторение слов, карточки в шторке, серия занятий");
        nm.createNotificationChannel(ch);
    }

    static int accent(Context c) {
        return Build.VERSION.SDK_INT >= 31 ? c.getColor(android.R.color.system_accent1_500) : 0xFF8C0046;
    }

    static PendingIntent openTab(Context c, String tab, int req) {
        Intent i = new Intent(c, MainActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("slovnik://tab/" + tab))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent action(Context c, String action, String id, String grade, int req) {
        Intent i = new Intent(c, NotifReceiver.class).setAction(action).putExtra("id", id);
        if (grade != null) i.putExtra("grade", grade);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static Notification.Builder base(Context c) {
        ensureChannel(c);
        return new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(accent(c))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER);
    }

    static void show(Context c, int id, Notification n) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, n);
    }

    /** A scheduled alarm went off: check the rules, maybe post, then plan the next ones. */
    static void onAlarm(Context c, String kind) {
        try {
            Settings s = settings(c);
            long now = System.currentTimeMillis();
            String why = null;
            JSONObject snap;
            synchronized (Store.LOCK) { snap = Store.readSnapshot(c); }
            if (!s.on) why = "выключены";
            else if (!allowed(c)) why = "нет разрешения на уведомления";
            else if (inQuiet(s, now)) why = "тихие часы";
            else if (sentToday(c) >= MAX_PER_DAY) why = "уже " + MAX_PER_DAY + " за сегодня";
            else if (practiceDays(c, snap).contains(day(now))) why = "сегодня уже занимался";
            boolean posted = false;
            if (why == null) {
                posted = post(c, kind, snap, false);
                if (posted) countSent(c);
                else why = "нечего показать";
            }
            MainActivity.log("напоминание " + kind + ": " + (posted ? "показано" : "пропущено, " + why));
        } finally {
            scheduleAll(c);
        }
    }

    /** Debug menu: post right away, ignoring time, limits and conditions; falls back to sample content. */
    static boolean test(Context c, String kind) {
        if (!allowed(c)) { MainActivity.log("тест " + kind + ": нет разрешения на уведомления"); return false; }
        JSONObject snap;
        synchronized (Store.LOCK) { snap = Store.readSnapshot(c); }
        boolean ok = post(c, kind, snap, true);
        MainActivity.log("тест " + kind + ": " + (ok ? "показано" : "не показано"));
        return ok;
    }

    static boolean post(Context c, String kind, JSONObject snap, boolean test) {
        switch (kind) {
            case "daily": return postDaily(c, snap, test);
            case "quiz": return postQuiz(c, snap, test);
            case "streak": return postStreak(c, snap, test);
            case "weekly": return postWeekly(c, snap, test);
            default: return false;
        }
    }

    static boolean postDaily(Context c, JSONObject snap, boolean test) {
        long now = System.currentTimeMillis();
        List<JSONObject> due = new ArrayList<>();
        for (JSONObject w : words(snap)) if (isDue(w, now)) due.add(w);
        if (due.isEmpty() && !test) return false;
        String example = null;
        if (!due.isEmpty()) {
            due.sort((a, b) -> Integer.compare(lv(a), lv(b)));
            example = due.get(Math.min(due.size() - 1, (int) (Math.random() * Math.min(5, due.size())))).optString("en");
        }
        int n = due.isEmpty() ? 12 : due.size();
        String title = plural(n, "слово ждёт", "слова ждут", "слов ждут") + " повторения";
        String text = example != null ? "Например: как перевести «" + example + "»?" : "Например: как перевести «bemused»? (тест)";
        show(c, ID_DAILY, base(c).setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(openTab(c, "review", 401))
            .addAction(new Notification.Action.Builder(null, "Повторить", openTab(c, "review", 402)).build())
            .build());
        return true;
    }

    static JSONObject pickQuizWord(Context c, JSONObject snap, boolean test) {
        long now = System.currentTimeMillis();
        Set<String> asked = test ? new HashSet<>() : askedToday(c);
        JSONObject best = null;
        int bestScore = Integer.MAX_VALUE;
        for (JSONObject w : words(snap)) {
            if (!isDue(w, now) || asked.contains(w.optString("id"))) continue;
            boolean missed = w.optInt("bad", 0) > 0 && lv(w) <= 1;
            int score = (missed ? 0 : 100) + lv(w) * 10 + (int) (Math.random() * 5);
            if (score < bestScore) { bestScore = score; best = w; }
        }
        if (best == null && test) for (JSONObject w : words(snap)) if (translated(w)) { best = w; break; }
        if (best == null && test) {
            try {
                best = new JSONObject().put("id", "__sample").put("en", "eerie").put("ru", "жуткий, зловещий")
                    .put("note", "Тестовое слово: в словаре пока нет подходящих.").put("bad", 1);
            } catch (JSONException ignored) { }
        }
        return best;
    }

    static boolean postQuiz(Context c, JSONObject snap, boolean test) {
        JSONObject w = pickQuizWord(c, snap, test);
        if (w == null) return false;
        String id = w.optString("id"), en = w.optString("en");
        boolean missed = w.optInt("bad", 0) > 0 && lv(w) <= 1;
        String title = missed ? "В прошлый раз не вспомнилось" : "Помнишь?";
        String text = "«" + en + "» — что это значит?";
        if (!test) markAsked(c, id);
        show(c, ID_QUIZ, base(c).setContentTitle(title).setContentText(text)
            .setContentIntent(openTab(c, "review", 403))
            .addAction(new Notification.Action.Builder(null, "Показать перевод", action(c, ACTION_REVEAL, id, null, 404)).build())
            .build());
        return true;
    }

    static JSONObject findWord(JSONObject snap, String id) {
        for (JSONObject w : words(snap)) if (id.equals(w.optString("id"))) return w;
        return null;
    }

    /** "Show translation": the same notification turns into the back of the card with grade buttons. */
    static void reveal(Context c, String id) {
        JSONObject w;
        synchronized (Store.LOCK) { w = findWord(Store.readSnapshot(c), id); }
        if (w == null && "__sample".equals(id)) w = pickQuizWord(c, null, true);
        if (w == null) { cancel(c, ID_QUIZ); return; }
        StringBuilder body = new StringBuilder(w.optString("ru"));
        String note = w.optString("note", "").trim(), ctx = w.optString("context", "").trim();
        if (!note.isEmpty()) body.append("\n\n").append(note);
        if (!ctx.isEmpty()) body.append("\n\n«").append(ctx).append("»");
        show(c, ID_QUIZ, base(c).setContentTitle(w.optString("en")).setContentText(w.optString("ru"))
            .setStyle(new Notification.BigTextStyle().bigText(body.toString()))
            .setOnlyAlertOnce(true)
            .setContentIntent(openTab(c, "review", 405))
            .addAction(new Notification.Action.Builder(null, "Не помню", action(c, ACTION_GRADE, id, "bad", 406)).build())
            .addAction(new Notification.Action.Builder(null, "Помню", action(c, ACTION_GRADE, id, "good", 407)).build())
            .build());
    }

    /** Grades exactly like Словник does (EN → RU direction) and queues the change for the cloud. */
    static void grade(Context c, String id, String g) {
        long now = System.currentTimeMillis();
        String result;
        int newLv = 0;
        long next = 0;
        if ("__sample".equals(id)) {
            result = "тест";
        } else {
            synchronized (Store.LOCK) {
                JSONObject snap = Store.readSnapshot(c);
                JSONObject w = findWord(snap, id);
                if (w == null) { cancel(c, ID_QUIZ); return; }
                try {
                    JSONObject lvO = w.optJSONObject("lv"), dueO = w.optJSONObject("due");
                    if (lvO == null) { lvO = new JSONObject().put("en", 0).put("ru", 0); w.put("lv", lvO); }
                    if (dueO == null) { dueO = new JSONObject().put("en", 0).put("ru", 0); w.put("due", dueO); }
                    int l = lvO.optInt("en", 0);
                    if ("bad".equals(g)) { l = Math.max(0, l - 2); next = 10 * MIN; w.put("bad", w.optInt("bad", 0) + 1); }
                    else { l = Math.min(MAX_LV, l + 1); next = INTERVALS[l]; w.put("ok", w.optInt("ok", 0) + 1); }
                    lvO.put("en", l);
                    dueO.put("en", now + next);
                    newLv = l;
                    Store.write(Store.snapshot(c), snap.toString());
                    Store.queueWord(c, id, w);
                } catch (JSONException e) {
                    cancel(c, ID_QUIZ);
                    return;
                }
            }
            Set<String> days = new HashSet<>(prefs(c).getStringSet("days", new HashSet<>()));
            days.add(day(now));
            prefs(c).edit().putStringSet("days", days).apply();
            result = "уровень " + newLv + " из " + MAX_LV + ", снова через " + human(next);
        }
        MainActivity.log("оценка из шторки: " + id + " → " + g);
        show(c, ID_QUIZ, base(c).setContentTitle("bad".equals(g) ? "Ничего, повторим скоро" : "Засчитано")
            .setContentText(result)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(5000)
            .build());
    }

    static String human(long ms) {
        if (ms < 60 * MIN) return (ms / MIN) + " мин";
        if (ms < DAY) return (ms / (60 * MIN)) + " ч";
        return plural((int) (ms / DAY), "день", "дня", "дней");
    }

    static boolean postStreak(Context c, JSONObject snap, boolean test) {
        long now = System.currentTimeMillis();
        int n = streak(practiceDays(c, snap), now);
        if (n < 2 && !test) return false;
        if (n < 2) n = 3;
        String title = "Серия " + plural(n, "день", "дня", "дней");
        String text = "Пять карточек — и она продолжится" + (test ? " (тест)" : "");
        show(c, ID_STREAK, base(c).setContentTitle(title).setContentText(text)
            .setContentIntent(openTab(c, "review", 408))
            .addAction(new Notification.Action.Builder(null, "Повторить", openTab(c, "review", 409)).build())
            .build());
        return true;
    }

    static boolean postWeekly(Context c, JSONObject snap, boolean test) {
        Map<String, Integer> bySet = new HashMap<>();
        int total = 0;
        for (JSONObject w : words(snap)) {
            if (translated(w)) continue;
            total++;
            String set = w.optString("set", "");
            bySet.put(set, bySet.getOrDefault(set, 0) + 1);
        }
        if (total == 0 && !test) return false;
        String top = null;
        int topN = 0;
        for (Map.Entry<String, Integer> e : bySet.entrySet()) if (e.getValue() > topN) { topN = e.getValue(); top = e.getKey(); }
        String name = setName(snap, top);
        String title;
        if (total == 0) title = "2 слова без перевода (тест)";
        else if (name != null && topN == total) title = plural(total, "слово", "слова", "слов") + " из «" + name + "» без перевода";
        else title = plural(total, "слово", "слова", "слов") + " без перевода";
        show(c, ID_WEEKLY, base(c).setContentTitle(title).setContentText("Разобрать с Claude?")
            .setContentIntent(openTab(c, "words", 410))
            .build());
        return true;
    }

    static void cancel(Context c, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }
}
