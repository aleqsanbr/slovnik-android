package app.slovnik;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
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
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyHeaderShift());

        int bg = defaultBackground();
        root.setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        setStatusColor(bg);
        setNavColor(bg);

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

        web.addJavascriptInterface(new Bridge(), "SlovnikNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(chrome);
        web.setDownloadListener((url, ua, disposition, mime, length) -> {
            Uri u = Uri.parse(url);
            if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) openExternal(u);
            else Toast.makeText(this, "Скачать файл отсюда нельзя. Используй «Показать JSON» и скопируй текст.", Toast.LENGTH_LONG).show();
        });

        if (state != null) web.restoreState(state);
        if (state == null || web.getUrl() == null) web.loadUrl(urlFor(tabFrom(getIntent())));
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
        String target = JSONObject.quote(urlFor(tab));
        web.evaluateJavascript("(function(){location.replace(" + target + ");location.reload();})()", null);
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
        public void onPageFinished(WebView view, String url) {
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
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        resumed = true;
        ui.removeCallbacks(sampler);
        ui.postDelayed(sampler, 150);
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(sampler);
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }
}
