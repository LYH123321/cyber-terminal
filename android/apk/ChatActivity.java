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
      return "<html><body style='background:#111;color:#f66;font-family:monospace'>chat.html 加载失败: "
          + android.util.Html.escapeHtml(String.valueOf(e))
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
