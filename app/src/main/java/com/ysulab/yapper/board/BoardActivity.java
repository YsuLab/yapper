package com.ysulab.yapper.board;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import com.ysulab.yapper.settings.Settings;
import java.io.OutputStream;

/**
 * Tablet always-on display: the Today board, full screen, inside Yapper. The page is the hub's board
 * (copied into the APK at build time), loaded from the app itself so it opens even when the hub is
 * down. It reads the hub URL and this device's token from Yapper's settings. While the tablet is
 * charging the screen stays on; the page's own screen care (pixel shift, dimming, night black) protects
 * the AMOLED panel. On battery the normal screen timeout applies.
 */
public final class BoardActivity extends Activity {

    static final String ORIGIN = "https://" + WebViewAssetLoader.DEFAULT_DOMAIN;
    static final String PAGE = ORIGIN + "/assets/board/index.html";

    private WebView web;
    private Settings settings;

    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (plugged != 0) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        setShowWhenLocked(true);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0C0908);
        setContentView(web);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                // The board never navigates away; nothing else opens inside this window.
                return !r.getUrl().toString().startsWith(ORIGIN + "/assets/board/");
            }
        });
        web.addJavascriptInterface(new Bridge(), "YapperBoard");
        if (state == null) {
            web.loadUrl(PAGE);
        } else {
            web.restoreState(state);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(power, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        immersive();
        web.onResume();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(power);
        web.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            immersive();
        }
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private void immersive() {
        getWindow().setDecorFitsSystemWindows(false);
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** What the page may ask the app. Only the board page from the APK is loaded in this WebView. */
    final class Bridge {
        @JavascriptInterface
        public String hubUrl() {
            return settings.hubUrl();
        }

        @JavascriptInterface
        public String token() {
            return settings.hubToken();
        }

        @JavascriptInterface
        public void setToken(String token) {
            settings.saveHub(settings.hubUrl(), token == null ? "" : token);
        }

        /** Saves a drawing page as a PNG in Pictures/Yapper. */
        @JavascriptInterface
        public boolean savePng(String name, String base64) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name.replaceAll("[^A-Za-z0-9._-]", "_"));
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Yapper");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) {
                    return false;
                }
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        return false;
                    }
                    out.write(Base64.decode(base64, Base64.DEFAULT));
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
