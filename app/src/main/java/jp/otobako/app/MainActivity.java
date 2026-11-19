package jp.otobako.app;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private WebView wv;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xFF14121C);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        wv = new WebView(this);
        setContentView(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        wv.addJavascriptInterface(new Bridge(), "AndroidBridge");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost()) || !r.isForMainFrame()) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                return true;
            }
        });
        wv.loadUrl("https://" + HOST + "/assets/index.html");
    }

    @Override
    protected void onPause() {
        super.onPause();
        wv.evaluateJavascript("window.appPause&&appPause()", null);
    }

    @Override
    public void onBackPressed() {
        wv.evaluateJavascript("window.andBack?andBack():false", r -> {
            if (!"true".equals(r)) finish();
        });
    }

    private String audioPerm() {
        return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        wv.evaluateJavascript("window.onPerm&&onPerm()", null);
    }

    class Bridge {
        @JavascriptInterface
        public void setBrightness(final float v) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = v;
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public boolean hasAudioPerm() {
            return checkSelfPermission(audioPerm()) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestAudioPerm() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33)
                    requestPermissions(new String[]{audioPerm(), Manifest.permission.POST_NOTIFICATIONS}, 7);
                else requestPermissions(new String[]{audioPerm()}, 7);
            });
        }

        @JavascriptInterface
        public String listAudio() {
            JSONArray a = new JSONArray();
            String[] cols = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION};
            try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                    MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.TITLE + " ASC")) {
                while (c != null && c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    String ar = c.getString(2);
                    o.put("id", c.getLong(0)).put("t", c.getString(1)).put("a", ar == null || "<unknown>".equals(ar) ? "" : ar).put("d", c.getLong(3));
                    a.put(o);
                }
            } catch (Exception e) { }
            return a.toString();
        }

        @JavascriptInterface
        public String counts() {
            JSONObject o = new JSONObject();
            try {
                for (Map.Entry<String, ?> e : getSharedPreferences("counts", MODE_PRIVATE).getAll().entrySet()) o.put(e.getKey(), e.getValue());
            } catch (Exception x) { }
            return o.toString();
        }

        @JavascriptInterface
        public void lplay(String ids, int index) {
            try {
                JSONArray a = new JSONArray(ids);
                long[] q = new long[a.length()];
                for (int i = 0; i < q.length; i++) q[i] = a.getLong(i);
                MusicService.queue = q;
                MusicService.idx = index;
                runOnUiThread(() -> startForegroundService(new Intent(MainActivity.this, MusicService.class).setAction("PLAY")));
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public void lcmd(final String c, final int v) {
            runOnUiThread(() -> {
                MusicService m = MusicService.inst;
                if (m == null) return;
                switch (c) {
                    case "toggle": m.toggle(); break;
                    case "pause": m.pause(); break;
                    case "next": m.next(); break;
                    case "prev": m.prev(); break;
                    case "seek": m.seek(v); break;
                    case "stop": m.stopAll(); break;
                }
            });
        }

        @JavascriptInterface
        public String lstate() {
            MusicService m = MusicService.inst;
            return m == null ? "{}" : m.state();
        }
    }
}
