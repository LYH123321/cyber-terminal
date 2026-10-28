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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 赛博终端·视频陪看：本地 HTTP 服务（3990）供页面与视频流，页面同源加载，无 mixed-content。 */
public class VideoActivity extends Activity {
  public static final int PORT = 3990;
  private static final String VIDEO_DIR = "/sdcard/CyberVideos";
  private WebView web;
  private Thread serverThread;
  private volatile boolean serving = false;

  public static void open(Activity ctx) {
    ctx.startActivity(new Intent(ctx, VideoActivity.class));
  }

  public static SharedPreferences prefs(Activity ctx) {
    return ctx.getSharedPreferences(ChatActivity.PREFS, 0); // 复用日常聊天的 API 配置与人设
  }

  private class Bridge {
    @JavascriptInterface
    public String getPref(String key) {
      return prefs(VideoActivity.this).getString(key, "");
    }

    /** 原生 POST（JSON）：绕开 WebView 对 127.0.0.1:8001 本地模型服务的 CORS 限制。 */
    @JavascriptInterface
    public String httpPost(String url, String jsonBody, String authKey) {
      java.net.HttpURLConnection c = null;
      try {
        c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(120000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (authKey != null && !authKey.isEmpty())
          c.setRequestProperty("Authorization", "Bearer " + authKey);
        byte[] body = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
          os.write(body);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "{\"error\":\"HTTP " + code + "\"}";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
      } catch (Exception e) {
        return "{\"error\":\"" + e + "\"}";
      } finally {
        if (c != null) c.disconnect();
      }
    }

    @JavascriptInterface
    public void toast(String text) {
      runOnUiThread(
          () -> android.widget.Toast.makeText(VideoActivity.this, text, android.widget.Toast.LENGTH_SHORT).show());
    }
  }

  // ---------- 本地静态/文件服务器 ----------
  private void startServer() {
    try {
      final ServerSocket ss = new ServerSocket(PORT);
      serving = true;
      serverThread =
          new Thread(
              () -> {
                while (serving) {
                  try {
                    Socket c = ss.accept();
                    handle(c);
                  } catch (Exception ignored) {
                  }
                }
                try { ss.close(); } catch (Exception ignored) {}
              });
      serverThread.start();
    } catch (Exception e) {
      serving = false;
    }
  }

  private void handle(Socket c) {
    new Thread(() -> {
      try {
        c.setSoTimeout(10000);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        String req = "";
        for (int i = 0; i < 4096; i++) {
          b = in.read();
          if (b < 0) return;
          head.write(b);
          int n = head.size();
          if (n >= 4 && head.toByteArray()[n-1]=='\n' && head.toByteArray()[n-2]=='\r'
              && head.toByteArray()[n-3]=='\n' && head.toByteArray()[n-4]=='\r') {
            req = new String(head.toByteArray(), StandardCharsets.UTF_8);
            break;
          }
        }
        String[] first = req.split(" ")[0] + " " + (req.split(" ").length>1?req.split(" ")[1]:"");
        String method = first.split(" ")[0];
        String target = first.contains(" ") ? first.substring(first.indexOf(' ')+1) : "/";
        // 收集 Range 头
        String range = null;
        for (String line : req.split("\r\n")) {
          if (line.toLowerCase().startsWith("range:")) range = line.substring(6).trim();
        }
        if (!"GET".equals(method)) { respond(c, 405, "text/plain", "METHOD".getBytes()); return; }
        String path = target;
        int q = path.indexOf('?');
        String query = q >= 0 ? path.substring(q + 1) : "";
        path = q >= 0 ? path.substring(0, q) : path;

        if (path.equals("/") || path.equals("/index.html")) {
          byte[] html = readAsset("video.html");
          if (html == null) { respond(c, 500, "text/plain", "video.html missing".getBytes()); return; }
          respond(c, 200, "text/html; charset=utf-8", html);
        } else if (path.equals("/list")) {
          JSONArray arr = new JSONArray();
          File dir = new File(VIDEO_DIR);
          File[] files = dir.listFiles();
          if (files != null) {
            List<File> vids = new ArrayList<>();
            for (File f : files) {
              String n = f.getName().toLowerCase();
              if (f.isFile() && (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov")))
                vids.add(f);
            }
            java.util.Collections.sort(vids, (a, z) -> a.getName().compareToIgnoreCase(z.getName()));
            for (File f : vids) {
              JSONObject o = new JSONObject();
              o.put("name", f.getName());
              o.put("size", f.length());
              String base = f.getName().replaceAll("\\.[^.]+$", "");
              String sub = null;
              for (String ext : new String[]{".srt", ".ass", ".zh.srt", ".sc.srt"}) {
                File sf = new File(dir, base + ext);
                if (sf.isFile()) { sub = sf.getName(); break; }
              }
              if (sub == null) sub = "";
              o.put("sub", sub);
              arr.put(o);
            }
          }
          respond(c, 200, "application/json", arr.toString().getBytes(StandardCharsets.UTF_8));
        } else if (path.equals("/f")) {
          String name = param(query, "name");
          if (name.isEmpty()) { respond(c, 400, "text/plain", "need name".getBytes()); return; }
          File f = new File(VIDEO_DIR, name);
          if (!f.isFile()) { respond(c, 404, "text/plain", "not found".getBytes()); return; }
          streamFile(c, f, range, mimeOf(name));
        } else if (path.equals("/sub")) {
          String name = param(query, "name");
          File f = new File(VIDEO_DIR, name);
          if (!f.isFile()) { respond(c, 404, "text/plain", "not found".getBytes()); return; }
          byte[] buf = new byte[(int) Math.min(f.length(), 4 * 1024 * 1024)];
          try (FileInputStream fi = new FileInputStream(f)) {
            int n = fi.read(buf);
            respond(c, 200, "text/plain; charset=utf-8", java.util.Arrays.copyOf(buf, Math.max(n, 0)));
          }
        } else {
          respond(c, 404, "text/plain", "404".getBytes());
        }
      } catch (Exception ignored) {
      } finally {
        try { c.close(); } catch (Exception ignored) {}
      }
    }).start();
  }

  private static String mimeOf(String name) {
    String n = name.toLowerCase();
    if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
    if (n.endsWith(".webm")) return "video/webm";
    if (n.endsWith(".mkv")) return "video/x-matroska";
    if (n.endsWith(".mov")) return "video/quicktime";
    return "application/octet-stream";
  }

  private static String param(String query, String key) {
    for (String p : query.split("&")) {
      int i = p.indexOf('=');
      if (i > 0 && p.substring(0, i).equals(key))
        return URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8);
    }
    return "";
  }

  private void respond(Socket c, int code, String type, byte[] body) throws Exception {
    OutputStream out = c.getOutputStream();
    String h = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
        + "\r\nConnection: close\r\n\r\n";
    out.write(h.getBytes(StandardCharsets.UTF_8));
    out.write(body);
    out.flush();
  }

  private void streamFile(Socket c, File f, String range, String mime) throws Exception {
    OutputStream out = c.getOutputStream();
    long total = f.length();
    long start = 0, end = total - 1;
    int code = 200;
    String status = "200 OK";
    if (range != null && range.startsWith("bytes=")) {
      String spec = range.substring(6);
      int dash = spec.indexOf('-');
      try {
        if (dash > 0) {
          start = Long.parseLong(spec.substring(0, dash));
          if (dash + 1 < spec.length()) end = Long.parseLong(spec.substring(dash + 1));
        } else {
          start = total - Long.parseLong(spec.substring(dash + 1));
        }
      } catch (NumberFormatException ignored) {}
      if (start > end || end >= total) end = total - 1;
      code = 206; status = "206 Partial Content";
    }
    String h = "HTTP/1.1 " + status + "\r\nContent-Type: " + mime + "\r\nContent-Length: " + (end - start + 1)
        + "\r\nAccept-Ranges: bytes\r\nContent-Range: bytes " + start + "-" + end + "/" + total
        + "\r\nConnection: close\r\n\r\n";
    out.write(h.getBytes(StandardCharsets.UTF_8));
    try (FileInputStream fi = new FileInputStream(f)) {
      long skip = fi.skip(start);
      byte[] buf = new byte[64 * 1024];
      long sent = 0;
      while (sent < end - start + 1) {
        int want = (int) Math.min(buf.length, end - start + 1 - sent);
        int n = fi.read(buf, 0, want);
        if (n <= 0) break;
        out.write(buf, 0, n);
        sent += n;
      }
    }
    out.flush();
    c.close(); // 端口由代码 404 分支外统一关闭
  }

  private byte[] readAsset(String name) {
    try (InputStream in = getAssets().open(name)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      return out.toByteArray();
    } catch (Exception e) {
      return null;
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    new File(VIDEO_DIR).mkdirs();
    startServer();
    if (com.deepseekharness.app.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
    web = new WebView(new android.content.MutableContextWrapper(this));
    web.setLayoutParams(
        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    android.webkit.WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    web.addJavascriptInterface(new Bridge(), "Android");
    web.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return true;
          }
        });
    // 等服务器就绪再加载
    web.postDelayed(() -> web.loadUrl("http://127.0.0.1:" + PORT + "/"), 300);
    setContentView(web);
    View decor = getWindow().getDecorView();
    decor.setOnSystemUiVisibilityChangeListener(v -> hideBars());
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

  @Override
  protected void onDestroy() {
    serving = false;
    if (serverThread != null) serverThread.interrupt();
    if (web != null) {
      ((ViewGroup) web.getParent()).removeAllViews();
      web.destroy();
    }
    super.onDestroy();
  }
}
