package app.slovnik;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A thin full-screen shell around the Словник artifact on claude.ai.
 * The claude.ai page is loaded as-is (so sign-in, storage and Claude calls keep working),
 * and a small injected script stretches the artifact's frame over claude.ai's own header.
 */
public class MainActivity extends Activity {

    static final String START_URL = "https://claude.ai/artifact/L2DuyM3QrRJHowUuoqp6mh";
    static final Set<String> TABS = new HashSet<>(Arrays.asList("review", "words", "add", "more"));
    static final int FILE_REQUEST = 41;

    /** Stretches the biggest iframe (the artifact) over the claude.ai page, except while a claude.ai dialog is open. */
    static final String IMMERSIVE_JS =
        "(function(){" +
        "if(window.__slv){window.__slv();return;}" +
        "var st=document.createElement('style');(document.head||document.documentElement).appendChild(st);" +
        "var CSS='iframe[data-slv]{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;" +
        "width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;z-index:2147483000!important;" +
        "border:0!important;border-radius:0!important;margin:0!important;transform:none!important}html,body{overflow:hidden!important}';" +
        "function dialogOpen(){var d=document.querySelectorAll('[role=dialog],[role=alertdialog]');" +
        "for(var i=0;i<d.length;i++){var r=d[i].getBoundingClientRect();if(r.width>0&&r.height>0)return true;}return false;}" +
        "function apply(){" +
        " var on=location.pathname.indexOf('/artifact/')===0&&!dialogOpen();" +
        " if(!on){if(st.textContent)st.textContent='';return;}" +
        " var cur=document.querySelector('iframe[data-slv]');" +
        " if(!cur){var best=null,ba=0,f=document.querySelectorAll('iframe');" +
        "  for(var i=0;i<f.length;i++){var r=f[i].getBoundingClientRect(),a=r.width*r.height;if(a>ba){ba=a;best=f[i];}}" +
        "  if(!best||ba<40000){if(st.textContent)st.textContent='';return;}best.setAttribute('data-slv','1');}" +
        " if(st.textContent!==CSS)st.textContent=CSS;}" +
        "window.__slv=apply;apply();" +
        "new MutationObserver(function(){clearTimeout(window.__slvT);window.__slvT=setTimeout(apply,80);})" +
        ".observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['role','data-state','open','style','class']});" +
        "})();";

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private ValueCallback<Uri[]> pendingFiles;
    private final ChromeClient chrome = new ChromeClient();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        int bg = defaultBackground();
        root.setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        applyBars(bg);

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

    /* ---------- system bars ---------- */

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private int defaultBackground() {
        if (Build.VERSION.SDK_INT >= 31) {
            return getColor(isNight() ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50);
        }
        return isNight() ? 0xFF11141A : 0xFFECEFF3;
    }

    @SuppressWarnings("deprecation")
    private void applyBars(int color) {
        Window w = getWindow();
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color);
        boolean light = Color.luminance(color) > 0.5f;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            View decor = w.getDecorView();
            int flags = decor.getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            decor.setSystemUiVisibility(light ? (flags | mask) : (flags & ~mask));
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
            View decor = w.getDecorView();
            decor.setSystemUiVisibility(hide
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                : 0);
        }
    }

    private static final Pattern RGB = Pattern.compile("rgba?\\(\\s*(\\d+)[ ,]+(\\d+)[ ,]+(\\d+)");

    static Integer parseCssColor(String css) {
        if (css == null) return null;
        css = css.trim();
        try {
            if (css.startsWith("#") && css.length() == 7) return 0xFF000000 | Integer.parseInt(css.substring(1), 16);
            Matcher m = RGB.matcher(css);
            if (m.find()) {
                return Color.rgb(Math.min(255, Integer.parseInt(m.group(1))),
                                 Math.min(255, Integer.parseInt(m.group(2))),
                                 Math.min(255, Integer.parseInt(m.group(3))));
            }
        } catch (NumberFormatException ignored) { }
        return null;
    }

    /** Exposed to the page (all frames) as window.SlovnikNative. Only harmless, display-related calls. */
    class Bridge {
        @JavascriptInterface
        public boolean isApp() { return true; }

        @JavascriptInterface
        public String accent() {
            if (Build.VERSION.SDK_INT < 31) return "";
            int c = getColor(android.R.color.system_accent1_500);
            return String.format("#%06X", 0xFFFFFF & c);
        }

        @JavascriptInterface
        public void setBarColor(String css) {
            final Integer c = parseCssColor(css);
            if (c == null) return;
            runOnUiThread(() -> {
                if (customView != null) return;
                applyBars(c);
                root.setBackgroundColor(c);
            });
        }
    }

    /* ---------- navigation ---------- */

    private static boolean isInternal(String host) {
        if (host == null) return false;
        return host.equals("claude.ai") || host.endsWith(".claude.ai")
            || host.equals("anthropic.com") || host.endsWith(".anthropic.com")
            || host.endsWith(".claudeusercontent.com")
            || host.equals("challenges.cloudflare.com");
    }

    private void openExternal(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Нечем открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            Uri u = request.getUrl();
            String scheme = u.getScheme();
            if (("https".equals(scheme) || "http".equals(scheme)) && isInternal(u.getHost())) return false;
            openExternal(u);
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(IMMERSIVE_JS, null);
            CookieManager.getInstance().flush();
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            view.evaluateJavascript(IMMERSIVE_JS, null);
        }
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
        if (web.canGoBack()) { web.goBack(); return; }
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
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        root.removeAllViews();
        web.destroy();
        super.onDestroy();
    }
}
