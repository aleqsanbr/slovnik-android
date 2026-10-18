package app.slovnik;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.Gravity;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.WebResourceResponse;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.view.HapticFeedbackConstants;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceError;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * A thin shell around the Словник artifact on claude.ai.
 * - Only the artifact itself (plus Claude's sign-in pages) opens inside the app; every other link goes to the browser.
 * - The status and navigation bars take their colours from what the page actually draws,
 *   so they follow whatever Material You colour is chosen inside Словник.
 */
public class MainActivity extends Activity {

    static final String ARTIFACT_PATH = "/artifact/L2DuyM3QrRJHowUuoqp6mh";
    static final String START_URL = "https://claude.ai" + ARTIFACT_PATH;
    static final Set<String> TABS = new HashSet<>(Arrays.asList("review", "words", "add", "more"));
    /** claude.ai paths needed to sign in; everything else on claude.ai counts as "outside the artifact". */
    static final String[] AUTH_PREFIXES = { "/login", "/logout", "/magic-link", "/verify", "/api/", "/sso", "/oauth", "/cdn-cgi/" };
    static final int FILE_REQUEST = 41;
    /** Origin of the offline copy of Словник bundled in the APK (never fetched from the network). */
    static final String OFFLINE_BASE = "https://slovnik.local/index.html";
    static final long SAMPLE_EVERY_MS = 700;

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> pendingFiles;
    private final ChromeClient chrome = new ChromeClient();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int statusColor = 0, navColor = 0;
    private int headerPx = 0;
    private volatile boolean offline = false;
    /** Set by Словник itself (online or offline copy) once its script has started. */
    private volatile boolean alive = false;
    private boolean offlineFromAsset = false;
    private TextToSpeech tts;
    private volatile boolean ttsOk = false;
    private TtsCache ttsCache;
    /** Same lock the reminders use, so a grade from a notification never collides with the page. */
    private final Object store = Store.LOCK;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    /* ---------- in-app log (shake the phone to see it) ---------- */
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    static void log(String msg) {
        Log.i("Slovnik", msg);
        synchronized (LOG) {
            LOG.addLast(TS.format(new Date()) + "  " + msg);
            while (LOG.size() > 500) LOG.removeFirst();
        }
    }

    private static String shortUrl(String u) {
        if (u == null) return "null";
        if (u.startsWith("data:")) return "data:…";
        return u.length() > 110 ? u.substring(0, 110) + "…" : u;
    }

    private SensorManager sensors;
    private long lastShake = 0, firstJolt = 0;
    private AlertDialog logDialog;

    /* ---------- animated splash ---------- */
    private FrameLayout splash;
    private View splashIcon;
    private ObjectAnimator breathing;
    private volatile boolean prefSound = true, prefHaptics = true;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private int accentColor(boolean strong) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (isNight()) return getColor(strong ? android.R.color.system_accent1_200 : android.R.color.system_accent1_100);
            return getColor(strong ? android.R.color.system_accent1_600 : android.R.color.system_accent1_700);
        }
        return isNight() ? 0xFFB9C3FF : 0xFF4A5BB8;
    }

    private void showSplash(int bg) {
        splash = new FrameLayout(this);
        splash.setBackgroundColor(bg);
        splash.setClickable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        // the icon springs and grows past its own bounds: let it draw outside instead of being cut square
        col.setClipChildren(false);
        col.setClipToPadding(false);
        splash.setClipChildren(false);
        splash.setClipToPadding(false);

        // Same geometry as the launcher: a circle showing the middle 72 of the icon's 108 units.
        final int iconSize = dp(120);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackgroundColor(getColor(R.color.icon_bg));
        badge.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) { outline.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        badge.setClipToOutline(true);
        badge.setElevation(dp(6));
        ImageView art = new ImageView(this);
        art.setImageDrawable(getDrawable(R.drawable.ic_fg));
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // drawn at 80% of the launcher's scale, so the cards get room to breathe inside the circle
        int artSize = Math.round(iconSize * 108f / 72f * 0.8f);
        badge.addView(art, new FrameLayout.LayoutParams(artSize, artSize, Gravity.CENTER));
        badge.setClipChildren(true);
        splashIcon = badge;
        col.addView(splashIcon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView name = new TextView(this);
        name.setText("Словник");
        name.setTextSize(26);
        name.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        name.setTextColor(accentColor(true));
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = dp(14);
        col.addView(name, nlp);

        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accentColor(false)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(30), dp(30));
        slp.topMargin = dp(26);
        col.addView(spinner, slp);

        splash.addView(col, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));

        // icon pops in with a little spring, the name and loader fade up after it
        splashIcon.setScaleX(0.4f); splashIcon.setScaleY(0.4f); splashIcon.setAlpha(0f); splashIcon.setRotation(-12f);
        splashIcon.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f)
            .setDuration(620).setInterpolator(new OvershootInterpolator(2.2f))
            .withEndAction(this::startBreathing).start();
        for (View v : new View[] { name, spinner }) {
            v.setAlpha(0f); v.setTranslationY(dp(10));
            v.animate().alpha(1f).translationY(0).setStartDelay(260).setDuration(420).setInterpolator(new DecelerateInterpolator()).start();
        }
        if (prefHaptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        ui.postDelayed(() -> hideSplash(false), 12000);
    }

    private void startBreathing() {
        if (splash == null || splashIcon == null) return;
        breathing = ObjectAnimator.ofPropertyValuesHolder(splashIcon,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f));
        breathing.setDuration(900);
        breathing.setRepeatCount(ValueAnimator.INFINITE);
        breathing.setRepeatMode(ValueAnimator.REVERSE);
        breathing.start();
    }

    /** Hides the splash; when Словник is really ready it also chimes and gives a haptic "done". */
    private void hideSplash(boolean ready) {
        final FrameLayout s = splash;
        if (s == null) return;
        splash = null;
        if (breathing != null) { breathing.cancel(); breathing = null; }
        if (ready) {
            if (prefHaptics) root.performHapticFeedback(Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
            playReadyChime();
        }
        if (splashIcon != null) splashIcon.animate().scaleX(1.18f).scaleY(1.18f).setDuration(380).setInterpolator(new DecelerateInterpolator()).start();
        s.animate().alpha(0f).setStartDelay(ready ? 120 : 0).setDuration(380).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { root.removeView(s); }
        }).start();
        splashIcon = null;
    }

    /** A soft two-note bell, synthesized on the fly; silent when the phone is on silent/vibrate. */
    private void playReadyChime() {
        if (!prefSound) return;
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
        bg.execute(() -> {
            final int rate = 44100;
            final double len = 0.9;
            short[] pcm = new short[(int) (rate * len)];
            double[][] notes = { { 659.25, 0.00 }, { 987.77, 0.11 } };   // E5 then B5
            for (int i = 0; i < pcm.length; i++) {
                double t = (double) i / rate, v = 0;
                for (double[] n : notes) {
                    double tt = t - n[1];
                    if (tt < 0) continue;
                    double env = Math.min(1, tt / 0.008) * Math.exp(-tt / 0.22);
                    v += env * (Math.sin(2 * Math.PI * n[0] * tt) + 0.28 * Math.sin(2 * Math.PI * n[0] * 2.01 * tt) + 0.1 * Math.sin(2 * Math.PI * n[0] * 3.0 * tt));
                }
                pcm[i] = (short) Math.max(-32767, Math.min(32767, v * 0.18 * 32767));
            }
            AudioTrack track = null;
            try {
                track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
                track.write(pcm, 0, pcm.length);
                track.play();
                Thread.sleep((long) (len * 1000) + 150);
            } catch (Exception e) {
                log("звук заставки: " + e.getMessage());
            } finally {
                if (track != null) track.release();
            }
        });
    }

    /** Sound/vibration switches live in Словник's settings; read them from the saved copy of the dictionary. */
    private void loadFeedbackPrefs() {
        bg.execute(() -> {
            String text;
            synchronized (store) { text = readFile(snapFile()); }
            if (text.isEmpty()) return;
            try {
                JSONObject meta = new JSONObject(text).optJSONObject("meta");
                if (meta == null) return;
                prefSound = meta.optBoolean("sound", true);
                prefHaptics = meta.optBoolean("haptics", true);
            } catch (JSONException ignored) { }
        });
    }
    private final SensorEventListener shake = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            float g = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH;
            if (g < 2.6f) return;
            long now = System.currentTimeMillis();
            if (now - lastShake < 2500) return;
            if (firstJolt == 0 || now - firstJolt > 900) { firstJolt = now; return; }
            firstJolt = 0; lastShake = now;
            showLog();
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private void showLog() {
        if (logDialog != null && logDialog.isShowing()) return;
        StringBuilder sb = new StringBuilder();
        try {
            sb.append("Словник ").append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) { sb.append("Словник"); }
        sb.append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")");
        android.content.pm.PackageInfo wv = WebView.getCurrentWebViewPackage();
        if (wv != null) sb.append(" · WebView ").append(wv.versionName);
        sb.append("\nрежим: ").append(offline ? "офлайн" + (offlineFromAsset ? " (копия из APK)" : " (сохранённая копия)") : "онлайн")
          .append(" · страница стартовала: ").append(alive ? "да" : "нет")
          .append(" · сеть: ").append(networkInfo())
          .append("\nкопия словаря: ").append(snapFile().length()).append(" Б · копия страницы: ").append(pageFile().length())
          .append(" Б · URL: ").append(shortUrl(web.getUrl()))
          .append("\nкэш озвучки: ").append(ttsCache.info()).append("\n\n");
        synchronized (LOG) { for (String l : LOG) sb.append(l).append('\n'); }
        final String text = sb.toString();

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));

        logDialog = new AlertDialog.Builder(this)
            .setTitle("Лог")
            .setView(sv)
            .setPositiveButton("Скопировать", (d, w) -> {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Словник лог", text));
                Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("Действия", (d, w) -> showDebugActions())
            .setNegativeButton("Закрыть", null)
            .create();
        logDialog.show();
    }

    private void showDebugActions() {
        final String[] items = {
            "Открыть онлайн",
            "Открыть офлайн-копию",
            "Тест: ежедневное напоминание",
            "Тест: карточка в шторке",
            "Тест: защита серии",
            "Тест: слова без перевода",
            "Тест: все уведомления сразу",
            "Показать расписание напоминаний",
            "Очистить кэш озвучки"
        };
        new AlertDialog.Builder(this).setTitle("Действия").setItems(items, (d, which) -> {
            switch (which) {
                case 0: log("вручную → онлайн"); loadOnline(null); break;
                case 1: log("вручную → офлайн"); loadOffline(null); break;
                case 2: testNotif("daily"); break;
                case 3: testNotif("quiz"); break;
                case 4: testNotif("streak"); break;
                case 5: testNotif("weekly"); break;
                case 6:
                    for (int i = 0; i < Notifications.KINDS.length; i++) {
                        final String k = Notifications.KINDS[i];
                        ui.postDelayed(() -> testNotif(k), i * 700L);
                    }
                    break;
                case 7:
                    Notifications.scheduleAll(this);
                    showLog();
                    break;
                case 8:
                    ttsCache.clear();
                    Toast.makeText(this, "Кэш озвучки очищен", Toast.LENGTH_SHORT).show();
                    break;
                default: break;
            }
        }).show();
    }

    private void testNotif(String kind) {
        if (!Notifications.allowed(this)) {
            askNotifPermission(true);
            Toast.makeText(this, "Сначала разреши уведомления", Toast.LENGTH_SHORT).show();
            return;
        }
        Notifications.test(this, kind);
    }

    static final int NOTIF_PERMISSION_REQUEST = 77;

    /** Android 13+ needs an explicit yes for notifications; asked once automatically, or again from settings/tests. */
    private void askNotifPermission(boolean force) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences p = Notifications.prefs(this);
        if (!force && p.getBoolean("askedPermission", false)) return;
        p.edit().putBoolean("askedPermission", true).apply();
        requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, NOTIF_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIF_PERMISSION_REQUEST) {
            boolean ok = results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            log("разрешение на уведомления: " + (ok ? "дано" : "не дано"));
            if (ok) bg.execute(() -> Notifications.scheduleAll(this));
        }
    }

    /** Top of the artifact frame in CSS px (= height of claude.ai's bar above it), or -1 when not on the artifact. */
    static final String MEASURE_JS =
        "(function(){if(location.pathname.indexOf('/artifact/')!==0)return -1;" +
        "var f=document.querySelectorAll('iframe'),b=null,ba=0;" +
        "for(var i=0;i<f.length;i++){var r=f[i].getBoundingClientRect(),a=r.width*r.height;if(a>ba){ba=a;b=r;}}" +
        "if(!b||ba<40000)return -1;" +
        "var d=document.querySelectorAll('[role=dialog],[role=alertdialog]');" +
        "for(var j=0;j<d.length;j++){var q=d[j].getBoundingClientRect();if(q.width>0&&q.height>0)return -1;}" +
        "return b.top;})()";
    private boolean resumed = false;

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            measureHeader();
            sampleBars();
            if (resumed) ui.postDelayed(this, SAMPLE_EVERY_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        web = new WebView(this);
        // no Android 12+ "stretch" at the ends of the page: Словник scrolls like a native screen
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        loadFeedbackPrefs();
        bg.execute(() -> Notifications.scheduleAll(this));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyHeaderShift());

        int bg = defaultBackground();
        root.setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        setStatusColor(bg);
        setNavColor(bg);
        showSplash(bg);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        ttsCache = new TtsCache(getFilesDir(), ui, this.bg);   // "bg" here is the local background colour
        tts = new TextToSpeech(this, status -> {
            ttsOk = status == TextToSpeech.SUCCESS;
            if (ttsOk) ui.post(() -> { if (tts != null) ttsCache.attach(tts); });
            log("движок озвучки: " + (ttsOk ? "готов" : "ошибка " + status));
        });
        web.addJavascriptInterface(new Bridge(), "SlovnikNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(chrome);
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            Uri u = Uri.parse(url);
            if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) openExternal(u);
            else Toast.makeText(this, "Скачать файл отсюда нельзя. Используй «Показать JSON» и скопируй текст.", Toast.LENGTH_LONG).show();
        });

        sensors = getSystemService(SensorManager.class);
        log("запуск: " + (state == null ? "холодный" : "восстановление") + ", сеть: " + networkInfo());
        if (state != null && state.getBoolean("offline", false)) loadOffline(null);
        else open(tabFrom(getIntent()));
    }

    private String urlFor(String tab) {
        return tab == null ? START_URL : START_URL + "#" + tab;
    }

    private static String tabFrom(Intent intent) {
        if (intent == null || intent.getData() == null) return null;
        Uri d = intent.getData();
        if (!"slovnik".equals(d.getScheme())) return null;
        String t = d.getLastPathSegment();
        return t != null && TABS.contains(t) ? t : null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String tab = tabFrom(intent);
        if (tab == null) return;
        if (offline) { loadOffline(tab); return; }
        String target = JSONObject.quote(urlFor(tab));
        web.evaluateJavascript("(function(){location.replace(" + target + ");location.reload();})()", null);
    }

    /* ---------- online / offline ---------- */

    private void open(String tab) {
        if (!isNetworkOk()) { log("сети нет по данным Android → офлайн"); loadOffline(tab); return; }
        loadOnline(tab);
        probeClaude(ok -> {
            log("проверка связи с claude.ai: " + (ok ? "есть" : "нет"));
            if (!ok && !offline && !alive) loadOffline(tab);
        });
    }

    /** Android may report a network (e.g. through a VPN) when there's no internet: ask claude.ai directly. */
    private void probeClaude(Consumer<Boolean> done) {
        bg.execute(() -> {
            boolean ok = false;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL("https://claude.ai/favicon.ico").openConnection();
                c.setRequestMethod("HEAD");
                c.setConnectTimeout(4000);
                c.setReadTimeout(4000);
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                ok = code > 0;
            } catch (Exception e) {
                log("проверка связи: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
            final boolean r = ok;
            ui.post(() -> done.accept(r));
        });
    }

    /**
     * Watchdog for the online page: if Словник hasn't started (no pageAlive) and there is no network,
     * or claude.ai keeps hanging, switch to the offline copy. Sign-in pages are left alone.
     */
    private final Runnable onlineWatch = new Runnable() {
        int checks = 0;
        @Override public void run() {
            if (offline || alive || onAuthPage()) { checks = 0; return; }
            checks++;
            log("сторож: страница не стартовала, проверка " + checks);
            if (!isNetworkOk() || checks >= 4) { checks = 0; log("сторож → офлайн"); loadOffline(null); return; }
            ui.postDelayed(this, 5000);
        }
    };

    /** If the saved offline copy doesn't start, fall back to the copy bundled in the APK. */
    private final Runnable offlineWatch = () -> {
        if (offline && !alive && !offlineFromAsset) { log("сохранённая копия не стартовала → копия из APK"); loadOffline(null, true); }
    };

    private void loadOnline(String tab) {
        offline = false;
        alive = false;
        ui.removeCallbacks(offlineWatch);
        ui.removeCallbacks(onlineWatch);
        log("открываю онлайн: " + urlFor(tab));
        web.loadUrl(urlFor(tab));
        ui.postDelayed(onlineWatch, 6000);
    }

    private void loadOffline(String tab) { loadOffline(tab, false); }

    private void loadOffline(String tab, boolean forceAsset) {
        offline = true;
        alive = false;
        ui.removeCallbacks(onlineWatch);
        ui.removeCallbacks(offlineWatch);
        headerPx = 0;
        applyHeaderShift();
        // Newest copy the online page handed over; the one bundled in the APK covers the first launch and broken copies.
        String page = "";
        if (!forceAsset) synchronized (store) { page = readFile(pageFile()); }
        offlineFromAsset = page.length() < 5000;
        if (offlineFromAsset) {
            try (InputStream in = getAssets().open("slovnik.html")) {
                page = new String(readAll(in), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Toast.makeText(this, "Нет сети, а офлайн-копия не открылась", Toast.LENGTH_LONG).show();
                return;
            }
        }
        log("открываю офлайн: " + (offlineFromAsset ? "копия из APK" : "сохранённая копия") + ", " + page.length() + " символов; копия словаря " + snapFile().length() + " Б");
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
            + "<style>:root{color-scheme:light}body{margin:0;font:14px system-ui,sans-serif}img{max-width:100%}[hidden]{display:none!important}</style>"
            + "</head><body>" + page + "</body></html>";
        web.loadDataWithBaseURL(OFFLINE_BASE + (tab != null ? "#" + tab : ""), html, "text/html", "utf-8", null);
        if (!offlineFromAsset) ui.postDelayed(offlineWatch, 4000);
    }

    private String networkInfo() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return "?";
        Network n = cm.getActiveNetwork();
        if (n == null) return "нет";
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        if (c == null) return "есть, без данных";
        StringBuilder b = new StringBuilder();
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) b.append("wifi ");
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) b.append("мобильная ");
        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) b.append("vpn ");
        b.append(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? "подтверждена" : "не подтверждена");
        return b.toString();
    }

    private boolean isNetworkOk() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return true;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /* ---------- local copy of the dictionary + changes made offline ---------- */

    private File snapFile() { return new File(getFilesDir(), "snapshot.json"); }
    private File pendFile() { return new File(getFilesDir(), "pending.json"); }
    private File pageFile() { return new File(getFilesDir(), "page.html"); }
    private boolean hasSnapshot() { return snapFile().length() > 0; }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String readFile(File f) {
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeFile(File f, String text) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private JSONObject loadPending() {
        String text = readFile(pendFile());
        JSONObject o;
        try { o = text.isEmpty() ? new JSONObject() : new JSONObject(text); }
        catch (JSONException e) { o = new JSONObject(); }
        try { if (o.optJSONObject("words") == null) o.put("words", new JSONObject()); }
        catch (JSONException ignored) { }
        return o;
    }

    /* ---------- hide claude.ai's bar: push the WebView up by its height ---------- */

    private void measureHeader() {
        if (customView != null) return;
        web.evaluateJavascript(MEASURE_JS, value -> {
            int px = 0;
            try {
                float css = Float.parseFloat(value);
                if (css > 0 && css < 200) px = Math.round(css * getResources().getDisplayMetrics().density);
            } catch (NumberFormatException | NullPointerException ignored) { }
            if (Math.abs(px - headerPx) > 1) { headerPx = px; applyHeaderShift(); }
        });
    }

    private void applyHeaderShift() {
        int h = root.getHeight();
        if (h == 0) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) web.getLayoutParams();
        int want = headerPx > 0 ? h + headerPx : FrameLayout.LayoutParams.MATCH_PARENT;
        if (lp.height != want) { lp.height = want; web.setLayoutParams(lp); }
        web.setTranslationY(-headerPx);
    }

    /* ---------- system bars: follow the colours the page draws ---------- */

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int defaultBackground() {
        if (Build.VERSION.SDK_INT >= 31) {
            return getColor(isNight() ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50);
        }
        return isNight() ? 0xFF11141A : 0xFFECEFF3;
    }

    private void setStatusColor(int color) {
        if (color == statusColor) return;
        statusColor = color;
        getWindow().setStatusBarColor(color);
        setLightBars(Color.luminance(color) > 0.5f, true);
    }

    private void setNavColor(int color) {
        if (color == navColor) return;
        navColor = color;
        getWindow().setNavigationBarColor(color);
        setLightBars(Color.luminance(color) > 0.5f, false);
    }

    @SuppressWarnings("deprecation")
    private void setLightBars(boolean light, boolean status) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            int mask = status ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            c.setSystemBarsAppearance(light ? mask : 0, mask);
        } else {
            View decor = w.getDecorView();
            int flag = status ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            int flags = decor.getSystemUiVisibility();
            decor.setSystemUiVisibility(light ? (flags | flag) : (flags & ~flag));
        }
    }

    /**
     * Reads two pixels the page draws: the page background at the left edge halfway down (status bar)
     * and the tab bar at the very bottom (navigation bar). Cheap: two 1×1 copies about once a second.
     */
    private void sampleBars() {
        if (customView != null || web.getWidth() == 0 || web.getHeight() == 0 || !web.isAttachedToWindow()) return;
        int[] loc = new int[2];
        web.getLocationInWindow(loc);
        int inset = Math.max(1, Math.round(3 * getResources().getDisplayMetrics().density));
        int x = loc[0] + inset;
        copyPixel(x, loc[1] + web.getHeight() / 2, this::setStatusColor);
        copyPixel(x, loc[1] + web.getHeight() - inset, this::setNavColor);
    }

    private void copyPixel(int x, int y, IntConsumer onColor) {
        final Bitmap bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(getWindow(), new Rect(x, y, x + 1, y + 1), bmp, result -> {
                if (result == PixelCopy.SUCCESS) onColor.accept(bmp.getPixel(0, 0) | 0xFF000000);
                bmp.recycle();
            }, ui);
        } catch (IllegalArgumentException | IllegalStateException e) {
            bmp.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars(boolean hide) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (hide) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            } else {
                c.show(WindowInsets.Type.systemBars());
            }
        } else {
            w.getDecorView().setSystemUiVisibility(hide
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                : 0);
        }
    }

    /** Exposed to the page as window.SlovnikNative: lets Словник use the system Material You accent. */
    class Bridge {
        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public boolean isOffline() { return offline; }

        @JavascriptInterface
        public void pageAlive() {
            alive = true;
            log("страница стартовала (" + (offline ? "офлайн" : "онлайн") + ")");
            ui.postDelayed(() -> hideSplash(true), 450);
            ui.postDelayed(() -> { if (Notifications.settings(MainActivity.this).on) askNotifPermission(false); }, 2500);
        }

        /** Reminder settings changed in Словник: store them and re-plan alarms right away. */
        @JavascriptInterface
        public void notifSettings(String json) {
            Notifications.saveSettings(MainActivity.this, json);
            bg.execute(() -> Notifications.scheduleAll(MainActivity.this));
            boolean on = true;
            try { on = new JSONObject(json).optBoolean("on", true); } catch (Exception ignored) { }
            if (on) runOnUiThread(() -> askNotifPermission(true));
        }

        @JavascriptInterface
        public boolean notifAllowed() { return Notifications.allowed(MainActivity.this); }

        /** Days the user answered a card right in the notification shade — they count for the streak too. */
        @JavascriptInterface
        public String practiceDays() {
            return new org.json.JSONArray(Notifications.prefs(MainActivity.this).getStringSet("days", new java.util.HashSet<>())).toString();
        }

        @JavascriptInterface
        public void testNotification(String kind) { runOnUiThread(() -> testNotif(kind)); }

        @JavascriptInterface
        public void jsLog(String msg) { MainActivity.log("страница: " + msg); }

        /** Saves a text file to the phone's Downloads folder. */
        @JavascriptInterface
        public boolean saveFile(String name, String content) {
            if (name == null || content == null) return false;
            String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            try {
                ContentResolver r = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                v.put(MediaStore.Downloads.MIME_TYPE, safe.endsWith(".json") ? "application/json" : "text/plain");
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream out = r.openOutputStream(uri)) {
                    if (out == null) return false;
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                }
                v.clear();
                v.put(MediaStore.Downloads.IS_PENDING, 0);
                r.update(uri, v, null, null);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Сохранено в «Загрузки»: " + safe, Toast.LENGTH_LONG).show());
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean ttsReady() { return ttsOk; }

        /** Speaks English through the phone's TTS engine (whatever is set in system settings). */
        @JavascriptInterface
        public void speak(String text, String lang) {
            if (text == null || text.trim().isEmpty()) return;
            final String t = text.length() > 600 ? text.substring(0, 600) : text;
            runOnUiThread(() -> {
                if (tts == null || !ttsOk) {
                    Toast.makeText(MainActivity.this, "Озвучка ещё не готова или не настроена в системе", Toast.LENGTH_SHORT).show();
                    return;
                }
                ttsCache.speak(t, lang);
            });
        }

        @JavascriptInterface
        public void stopSpeaking() {
            runOnUiThread(() -> ttsCache.stop());
        }

        /** Renders these phrases to the speech cache ahead of time (JSON array of strings). */
        @JavascriptInterface
        public void ttsPrefetch(String json, String lang) {
            String[] texts;
            try {
                org.json.JSONArray a = new org.json.JSONArray(json);
                int n = Math.min(a.length(), 80);
                texts = new String[n];
                for (int i = 0; i < n; i++) texts[i] = a.optString(i, null);
            } catch (Exception e) { return; }
            runOnUiThread(() -> { if (ttsOk) ttsCache.prefetch(texts, lang); });
        }

        /** "Voice all words": renders every missing phrase (JSON array) and tracks progress. */
        @JavascriptInterface
        public void ttsPrefetchAll(String json, String lang) {
            String[] texts;
            try {
                org.json.JSONArray a = new org.json.JSONArray(json);
                int n = Math.min(a.length(), 5000);
                texts = new String[n];
                for (int i = 0; i < n; i++) texts[i] = a.optString(i, null);
            } catch (Exception e) { return; }
            runOnUiThread(() -> { if (ttsOk) ttsCache.prefetch(texts, lang, true); });
        }

        /** {"total","done","failed","active"} of the "voice all words" job. */
        @JavascriptInterface
        public String ttsProgress() {
            final String[] out = { "{\"total\":0,\"done\":0,\"failed\":0,\"active\":false}" };
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(() -> { out[0] = ttsCache.progress(); latch.countDown(); });
            try { latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            return out[0];
        }

        @JavascriptInterface
        public void ttsPrefetchCancel() { runOnUiThread(() -> ttsCache.cancelJob()); }

        @JavascriptInterface
        public String ttsCacheInfo() { return ttsCache.info(); }

        @JavascriptInterface
        public void ttsCacheClear() { runOnUiThread(() -> ttsCache.clear()); }

        /** System haptic feedback; respects the phone's "touch feedback" setting. */
        @JavascriptInterface
        public void haptic(String kind) {
            runOnUiThread(() -> {
                int c;
                if ("confirm".equals(kind)) c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY;
                else if ("reject".equals(kind)) c = Build.VERSION.SDK_INT >= 30 ? HapticFeedbackConstants.REJECT : HapticFeedbackConstants.LONG_PRESS;
                else c = HapticFeedbackConstants.CLOCK_TICK;
                web.performHapticFeedback(c);
            });
        }

        @JavascriptInterface
        public boolean online() { return isNetworkOk(); }

        @JavascriptInterface
        public void goOnline() { runOnUiThread(() -> loadOnline(null)); }

        @JavascriptInterface
        public void saveSnapshot(String json) {
            if (json == null || json.isEmpty()) return;
            synchronized (store) { writeFile(snapFile(), json); }
            ui.post(() -> Widget.updateAll(MainActivity.this));
        }

        @JavascriptInterface
        public void savePage(String html) {
            if (html == null || html.length() < 5000) return;
            synchronized (store) {
                if (!html.equals(readFile(pageFile()))) writeFile(pageFile(), html);
            }
        }

        @JavascriptInterface
        public String snapshot() {
            synchronized (store) { return readFile(snapFile()); }
        }

        @JavascriptInterface
        public void pendingPut(String id, String word) {
            if (id == null || id.isEmpty()) return;
            synchronized (store) {
                try {
                    JSONObject p = loadPending();
                    p.getJSONObject("words").put(id, word == null || word.isEmpty() ? JSONObject.NULL : new JSONObject(word));
                    writeFile(pendFile(), p.toString());
                } catch (JSONException ignored) { }
            }
        }

        @JavascriptInterface
        public void pendingMeta(String meta) {
            synchronized (store) {
                try {
                    JSONObject p = loadPending();
                    p.put("meta", new JSONObject(meta));
                    writeFile(pendFile(), p.toString());
                } catch (JSONException | NullPointerException ignored) { }
            }
        }

        @JavascriptInterface
        public String takePending() {
            synchronized (store) { return pendFile().exists() ? loadPending().toString() : ""; }
        }

        @JavascriptInterface
        public void clearPending() {
            synchronized (store) { pendFile().delete(); }
        }

        @JavascriptInterface
        public int pendingCount() {
            synchronized (store) {
                if (!pendFile().exists()) return 0;
                JSONObject p = loadPending();
                JSONObject w = p.optJSONObject("words");
                return (w == null ? 0 : w.length()) + (p.optJSONObject("meta") != null ? 1 : 0);
            }
        }

        @JavascriptInterface
        public String accent() {
            if (Build.VERSION.SDK_INT < 31) return "";
            int c = getColor(android.R.color.system_accent1_500);
            return String.format("#%06X", 0xFFFFFF & c);
        }
    }

    /* ---------- navigation: only the artifact (and sign-in) stays inside ---------- */

    private static boolean isClaude(Uri u) {
        String h = u.getHost();
        return h != null && (h.equals("claude.ai") || h.endsWith(".claude.ai"));
    }

    private static boolean isArtifact(Uri u) {
        String p = u.getPath();
        return isClaude(u) && p != null && p.startsWith(ARTIFACT_PATH);
    }

    private static boolean isAuth(Uri u) {
        String p = u.getPath();
        if (!isClaude(u) || p == null) return false;
        for (String prefix : AUTH_PREFIXES) if (p.startsWith(prefix)) return true;
        return false;
    }

    private static boolean isWeb(Uri u) {
        return "https".equals(u.getScheme()) || "http".equals(u.getScheme());
    }

    private boolean onAuthPage() {
        String cur = web.getUrl();
        return cur != null && isAuth(Uri.parse(cur));
    }

    /** True when the app should keep this main-frame navigation; otherwise it has been handed off. */
    private boolean keepInside(Uri u) {
        if (isWeb(u) && (isArtifact(u) || isAuth(u))) return true;
        // Right after signing in Claude sends you to its home page: come back to Словник instead.
        if (isWeb(u) && isClaude(u) && onAuthPage()) {
            ui.post(() -> web.loadUrl(START_URL));
            return false;
        }
        openExternal(u);
        return false;
    }

    private void openExternal(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Нечем открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            return !keepInside(request.getUrl());
        }

        /** Catches claude.ai's in-page route changes (its own buttons), which never reach shouldOverrideUrlLoading. */
        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            Uri u = Uri.parse(url);
            if (!isWeb(u) || isArtifact(u) || isAuth(u)) return;
            if (isClaude(u)) {
                if (!onAuthPageBefore(view)) openExternal(u);
                view.post(() -> view.loadUrl(START_URL));
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            log("ошибка загрузки" + (request.isForMainFrame() ? " (страница)" : "") + ": " + error.getErrorCode() + " " + error.getDescription() + " — " + shortUrl(request.getUrl().toString()));
            if (request.isForMainFrame() && !offline) ui.post(() -> loadOffline(tabFrom(getIntent())));
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame()) log("HTTP " + response.getStatusCode() + " — " + shortUrl(request.getUrl().toString()));
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            log("загрузка: " + shortUrl(url));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && isAuth(Uri.parse(url))) hideSplash(false);
            log("загружено: " + shortUrl(url));
            CookieManager.getInstance().flush();
        }
    }

    /** The page we came from was a sign-in page (so a jump to claude.ai's home means "signed in", not a link). */
    private boolean onAuthPageBefore(WebView view) {
        android.webkit.WebBackForwardList h = view.copyBackForwardList();
        int i = h.getCurrentIndex() - 1;
        if (i < 0) return false;
        return isAuth(Uri.parse(h.getItemAtIndex(i).getUrl()));
    }

    class ChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR || m.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                String src = m.sourceId() == null ? "" : m.sourceId();
                log("консоль " + m.messageLevel().name().toLowerCase(Locale.ROOT) + ": " + m.message() + " (" + shortUrl(src) + ":" + m.lineNumber() + ")");
            }
            return false;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (pendingFiles != null) pendingFiles.onReceiveValue(null);
            pendingFiles = callback;
            try {
                startActivityForResult(params.createIntent(), FILE_REQUEST);
            } catch (ActivityNotFoundException e) {
                pendingFiles = null;
                return false;
            }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            hideSystemBars(true);
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
            hideSystemBars(false);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_REQUEST) {
            if (pendingFiles != null) {
                pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingFiles = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) { chrome.onHideCustomView(); return; }
        if (web.canGoBack()) {
            String back = web.copyBackForwardList().getItemAtIndex(web.copyBackForwardList().getCurrentIndex() - 1).getUrl();
            if (back != null && isArtifact(Uri.parse(back))) { web.goBack(); return; }
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("offline", offline);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        resumed = true;
        log("возврат в приложение, сеть: " + networkInfo());
        if (offline && isNetworkOk()) probeClaude(ok -> { if (ok && offline) { log("связь вернулась → онлайн"); loadOnline(null); } });
        if (sensors != null) {
            Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc != null) sensors.registerListener(shake, acc, SensorManager.SENSOR_DELAY_UI);
        }
        ui.removeCallbacks(sampler);
        ui.postDelayed(sampler, 150);
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (sensors != null) sensors.unregisterListener(shake);
        ui.removeCallbacks(sampler);
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (ttsCache != null) ttsCache.release();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        bg.shutdownNow();
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }
}
