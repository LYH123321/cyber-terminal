package com.deepseekharness.app.ui;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.deepseekharness.app.HttpShellService;

/** 赛博终端·日常聊天：原生 Activity + 内嵌 HTML，直连云端 API，不依赖酒馆服务器。 */
public class ChatActivity extends Activity {
  public static final String PREFS = "cyber-chat";
  private WebView web;

  public static void open(Activity ctx) {
    ctx.startActivity(new Intent(ctx, ChatActivity.class));
  }

  public static SharedPreferences prefs(Activity ctx) {
    return ctx.getSharedPreferences(PREFS, 0);
  }

  private class Bridge {
    @JavascriptInterface
    public String token() {
      return HttpShellService.currentToken();
    }

    @JavascriptInterface
    public String getPref(String key) {
      return prefs(ChatActivity.this).getString(key, "");
    }

    /** 原生 HTTP：WebView 的 https origin 拿不到 http://127.0.0.1 的桥/本地模型
     * （mixed content 与 CORS 双拦），由原生层代发。GET 默认带桥 token；jsonBody 非空则 POST。 */
    @JavascriptInterface
    public String http(String url, String jsonBody, String authKey) {
      java.net.HttpURLConnection c = null;
      try {
        c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(120000);
        if (jsonBody != null && !jsonBody.isEmpty()) {
          c.setRequestMethod("POST");
          c.setDoOutput(true);
          c.setRequestProperty("Content-Type", "application/json");
          if (authKey != null && !authKey.isEmpty())
            c.setRequestProperty("Authorization", "Bearer " + authKey);
          byte[] body = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          c.setFixedLengthStreamingMode(body.length);
          try (java.io.OutputStream os = c.getOutputStream()) {
            os.write(body);
          }
        } else {
          c.setRequestProperty("X-Token", HttpShellService.currentToken());
        }
        int code = c.getResponseCode();
        java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "[HTTP " + code + "]";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
      } catch (Exception e) {
        return "[ERR] " + e;
      } finally {
        if (c != null) c.disconnect();
      }
    }

    @JavascriptInterface
    public void setPref(String key, String value) {
      prefs(ChatActivity.this).edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public void toast(String text) {
      runOnUiThread(
          () -> android.widget.Toast.makeText(ChatActivity.this, text, android.widget.Toast.LENGTH_SHORT).show());
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    if (com.deepseekharness.app.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
    web = new WebView(new android.content.MutableContextWrapper(this));
    web.setLayoutParams(
        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    android.webkit.WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    web.addJavascriptInterface(new Bridge(), "Android");
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return !"about:blank".equals(url);
          }
        });
    String html = readAsset("chat.html");
    web.loadDataWithBaseURL("https://chat.local/", html, "text/html", "utf-8", "about:blank");
    setContentView(web);
    // 沉浸式，webview 自己画顶栏
    View decor = getWindow().getDecorView();
    decor.setOnSystemUiVisibilityChangeListener(
        v -> hideBars());
    hideBars();
  }

  private void hideBars() {
    View decor = getWindow().getDecorView();
    decor.setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  }

  private String readAsset(String name) {
    try (java.io.InputStream in = getAssets().open(name)) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      return out.toString("UTF-8");
    } catch (Exception e) {
      String m = String.valueOf(e).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
      return "<html><body style='background:#111;color:#f66;font-family:monospace'>chat.html 加载失败: "
          + m
          + "</body></html>";
    }
  }

  @Override
  public void onBackPressed() {
    if (web != null && web.canGoBack()) {
      web.goBack();
      return;
    }
    super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    if (web != null) {
      ((ViewGroup) web.getParent().getParent()).removeAllViews();
      web.destroy();
    }
    super.onDestroy();
  }
}
