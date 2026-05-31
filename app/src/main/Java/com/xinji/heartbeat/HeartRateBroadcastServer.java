package com.xinji.heartbeat;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
public class HeartRateBroadcastServer {
    private static final String TAG = "HRBroadcast";
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = false;
    private volatile int currentHR = 0;
    private volatile String deviceName = "未连接";
    private volatile boolean connected = false;
    private final int port;
    private final Context context;
    private final List<PrintWriter> sseClients = Collections.synchronizedList(new ArrayList<>());
    public HeartRateBroadcastServer(int port, Context context) {
        this.port = port;
        this.context = context.getApplicationContext();
    }
    public static String getNetworkStatus(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Network activeNetwork = cm.getActiveNetwork();
                    if (activeNetwork == null) return "未连接";
                } else {
                    NetworkInfo activeInfo = cm.getActiveNetworkInfo();
                    if (activeInfo == null || !activeInfo.isConnected()) return "未连接";
                }
            }
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip == null || ip.startsWith("0.") || ip.startsWith("127.") || "0.0.0.0".equals(ip)) continue;
                        String name = ni.getName().toLowerCase();
                        boolean isLocalIp = false;
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            isLocalIp = true;
                        } else if (ip.startsWith("172.")) {
                            try {
                                int second = Integer.parseInt(ip.split("\\.")[1]);
                                isLocalIp = second >= 16 && second <= 31;
                            } catch (Exception ignored) {}
                        }
                        if (!isLocalIp) continue;
                        boolean isUsb = name.contains("usb") || name.contains("rndis") 
                                    || name.contains("eth") || name.contains("ccmni");
                        if (isUsb) return "USB:" + ip;
                        return "WiFi:" + ip;
                    }
                }
            }
            Enumeration<NetworkInterface> nis2 = NetworkInterface.getNetworkInterfaces();
            while (nis2.hasMoreElements()) {
                NetworkInterface ni = nis2.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip == null || ip.startsWith("127.") || "0.0.0.0".equals(ip)) continue;
                        return "WiFi:" + ip;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "检测网络状态失败", e);
        }
        return "未连接";
    }
    public static String getLocalIP(Context context) {
        String status = getNetworkStatus(context);
        if (status.startsWith("USB:") || status.startsWith("WiFi:")) {
            return status.substring(4); // 去掉"USB:"或"WiFi:"前缀
        }
        return "未连接";
    }
    public static boolean canStartBroadcast(Context context) {
        String status = getNetworkStatus(context);
        return status.startsWith("USB:") || status.startsWith("WiFi:");
    }
    public static String getNetworkType(Context context) {
        String status = getNetworkStatus(context);
        if (status.startsWith("USB:")) return "USB网络共享";
        if (status.startsWith("WiFi:")) return "WiFi";
        return "未连接";
    }
    public void start() {
        if (running) return;
        running = true;
        serverThread = new Thread(this::runServer, "HR-Broadcast-Server");
        serverThread.setDaemon(true);
        serverThread.start();
        Log.d(TAG, "广播服务器启动于端口 " + port);
    }
    public void stop() {
        running = false;
        synchronized (sseClients) {
            for (PrintWriter pw : sseClients) {
                try { pw.close(); } catch (Exception ignored) {}
            }
            sseClients.clear();
        }
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        serverThread = null;
        Log.d(TAG, "广播服务器已停止");
    }
    public boolean isRunning() { return running; }
    public int getPort() { return port; }
    public void updateHR(int hr) {
        currentHR = hr;
        broadcastSSE();
    }
    public void updateDevice(String name) {
        if (name != null) deviceName = name;
    }
    public void setConnected(boolean c) {
        connected = c;
        broadcastSSE();
    }
    private void runServer() {
        try {
            serverSocket = new ServerSocket(port, 10, InetAddress.getByName("0.0.0.0"));
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client), "HR-Client-" + System.currentTimeMillis()).start();
                } catch (Exception e) {
                    if (running) Log.w(TAG, "接受连接异常", e);
                }
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "服务器启动失败", e);
        }
    }
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) { client.close(); return; }
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(":");
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(),
                            line.substring(idx + 1).trim());
                }
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";
            OutputStream out = client.getOutputStream();
            String accept = headers.getOrDefault("accept", "");
            if ("GET".equals(method)) {
                if (path.equals("/") || path.equals("/index.html")) {
                    if (accept.contains("text/event-stream")) {
                        handleSSE(client, out);
                    } else {
                        serveHtml(client, out);
                    }
                } else if (path.equals("/api/hr")) {
                    serveJson(client, out);
                } else if (path.equals("/api/ip")) {
                    serveIPJson(client, out);
                } else {
                    send404(client, out);
                }
            } else {
                send404(client, out);
            }
            client.close();
        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
        }
    }
    private void handleSSE(Socket client, OutputStream out) throws IOException {
        PrintWriter pw = new PrintWriter(out);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: text/event-stream\r\n");
        pw.print("Cache-Control: no-cache\r\n");
        pw.print("Connection: keep-alive\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("\r\n");
        pw.flush();
        sseClients.add(pw);
        sendSSEData(pw);
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()));
            while (running) {
                String s = r.readLine();
                if (s == null) break;
            }
        } catch (Exception ignored) {}
        finally {
            sseClients.remove(pw);
            try { pw.close(); } catch (Exception ignored) {}
        }
    }
    private void sendSSEData(PrintWriter pw) {
        try {
            pw.print("data: ");
            pw.print(buildJSON());
            pw.print("\n\n");
            pw.flush();
        } catch (Exception ignored) {}
    }
    private void broadcastSSE() {
        synchronized (sseClients) {
            Iterator<PrintWriter> it = sseClients.iterator();
            while (it.hasNext()) {
                PrintWriter pw = it.next();
                try {
                    sendSSEData(pw);
                } catch (Exception e) {
                    it.remove();
                }
            }
        }
    }
    private String buildJSON() {
        return "{\"hr\":" + currentHR +
                ",\"device\":\"" + escapeJson(deviceName) +
                "\",\"connected\":" + connected + "}";
    }
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
    private void serveHtml(Socket client, OutputStream out) throws IOException {
        byte[] htmlBytes = getHtmlContent().getBytes("UTF-8");
        PrintWriter pw = new PrintWriter(out);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: text/html; charset=utf-8\r\n");
        pw.print("Content-Length: " + htmlBytes.length + "\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
        out.write(htmlBytes);
        out.flush();
    }
    private void serveJson(Socket client, OutputStream out) throws IOException {
        String json = buildJSON();
        byte[] data = json.getBytes("UTF-8");
        PrintWriter pw = new PrintWriter(out);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: application/json; charset=utf-8\r\n");
        pw.print("Content-Length: " + data.length + "\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
        out.write(data);
        out.flush();
    }
    private void serveIPJson(Socket client, OutputStream out) throws IOException {
        String json = "{\"ip\":\"" + getLocalIP(context) + "\",\"port\":" + port + "}";
        byte[] data = json.getBytes("UTF-8");
        PrintWriter pw = new PrintWriter(out);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: application/json; charset=utf-8\r\n");
        pw.print("Content-Length: " + data.length + "\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("Connection: close\r\n");
        pw.print("\r\n");
        pw.flush();
        out.write(data);
        out.flush();
    }
    private void send404(Socket client, OutputStream out) throws IOException {
        String body = "<h1>404 Not Found</h1>";
        byte[] data = body.getBytes("UTF-8");
        PrintWriter pw = new PrintWriter(out);
        pw.print("HTTP/1.1 404 Not Found\r\n");
        pw.print("Content-Type: text/html\r\n");
        pw.print("Content-Length: " + data.length + "\r\n");
        pw.print("\r\n");
        pw.flush();
        out.write(data);
        out.flush();
    }
    private String getHtmlContent() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "<title>心迹 - 电脑悬浮窗</title>\n" +
                "<style>\n" +
                "  * { margin:0; padding:0; box-sizing:border-box; user-select:none; }\n" +
                "  body {\n" +
                "    width:100vw; height:100vh; overflow:hidden;\n" +
                "    font-family: 'Inter', -apple-system, 'Segoe UI', sans-serif;\n" +
                "    background: transparent !important;\n" +
                "    display:flex; align-items:center; justify-content:center;\n" +
                "  }\n" +
                "  \n" +
                "  .float-window {\n" +
                "    position:fixed;\n" +
                "    top:20px; right:20px;\n" +
                "    cursor:grab;\n" +
                "    z-index:9999;\n" +
                "    display:flex; flex-direction:column; align-items:center;\n" +
                "    gap:4px; padding:10px 16px;\n" +
                "    background: rgba(12,12,16,0.7);\n" +
                "    backdrop-filter: blur(16px);\n" +
                "    border-radius: 16px;\n" +
                "    border: 1px solid rgba(255,93,124,0.25);\n" +
                "    box-shadow: 0 4px 20px rgba(0,0,0,0.5);\n" +
                "    transition: box-shadow 0.2s;\n" +
                "  }\n" +
                "  .float-window:hover {\n" +
                "    box-shadow: 0 6px 28px rgba(0,0,0,0.6);\n" +
                "  }\n" +
                "  .float-window:active { cursor:grabbing; }\n" +
                "  \n" +
                "  .float-window.transparent-mode {\n" +
                "    background: transparent !important;\n" +
                "    backdrop-filter: none !important;\n" +
                "    border: none !important;\n" +
                "    box-shadow: none !important;\n" +
                "    padding: 0 !important;\n" +
                "    gap: 0 !important;\n" +
                "  }\n" +
                "  .hr-row {\n" +
                "    display:flex; align-items:baseline; gap:4px;\n" +
                "  }\n" +
                "  .hr-number {\n" +
                "    font-size: 2.6rem; font-weight:700;\n" +
                "    font-family: 'Inter', 'SF Mono', monospace;\n" +
                "    color: #ff5d7c;\n" +
                "    text-shadow: 0 0 14px rgba(255,60,100,0.5);\n" +
                "    line-height:1; letter-spacing:-1px;\n" +
                "  }\n" +
                "  .hr-unit {\n" +
                "    font-size: 0.8rem; color:#ffb0bd; font-weight:500;\n" +
                "  }\n" +
                "  .device-bar {\n" +
                "    display:flex; align-items:center; gap:6px;\n" +
                "    margin-top:1px;\n" +
                "  }\n" +
                "  .pulse-dot {\n" +
                "    width:6px; height:6px; border-radius:50%;\n" +
                "    background:#f44b6e;\n" +
                "    box-shadow: 0 0 6px #ff3e64;\n" +
                "    transition: transform 0.1s cubic-bezier(0.2,1.3,0.8,1);\n" +
                "  }\n" +
                "  .pulse-dot.beat { transform: scale(1.8); }\n" +
                "  .device-name {\n" +
                "    font-size:0.6rem; color:#998088;\n" +
                "  }\n" +
                "  .disconnected .hr-number { color:#665c62; text-shadow:none; }\n" +
                "  .disconnected .pulse-dot { background:#444; box-shadow:none; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class=\"float-window\" id=\"floatWindow\">\n" +
                "  <div class=\"hr-row\">\n" +
                "    <span class=\"hr-number\" id=\"hrValue\">--</span>\n" +
                "    <span class=\"hr-unit\">BPM</span>\n" +
                "  </div>\n" +
                "  <div class=\"device-bar\">\n" +
                "    <div class=\"pulse-dot\" id=\"pulseDot\"></div>\n" +
                "    <span class=\"device-name\" id=\"deviceName\">等待连接...</span>\n" +
                "  </div>\n" +
                "</div>\n" +
                "<script>\n" +
                "  // URL 参数：?transparent=1 透明模式\n" +
                "  if (location.search.includes('transparent=1') || location.search.includes('transparent=true')) {\n" +
                "    document.getElementById('floatWindow').classList.add('transparent-mode');\n" +
                "  }\n" +
                "  // 拖拽功能 — 在电脑上可拖动悬浮窗位置\n" +
                "  var floatWin = document.getElementById('floatWindow');\n" +
                "  var isDragging = false, startX, startY, origX, origY;\n" +
                "  floatWin.addEventListener('mousedown', function(e) {\n" +
                "    isDragging = true;\n" +
                "    startX = e.clientX; startY = e.clientY;\n" +
                "    origX = floatWin.offsetLeft; origY = floatWin.offsetTop;\n" +
                "    e.preventDefault();\n" +
                "  });\n" +
                "  document.addEventListener('mousemove', function(e) {\n" +
                "    if (!isDragging) return;\n" +
                "    floatWin.style.left = (origX + e.clientX - startX) + 'px';\n" +
                "    floatWin.style.top = (origY + e.clientY - startY) + 'px';\n" +
                "    floatWin.style.right = 'auto';\n" +
                "  });\n" +
                "  document.addEventListener('mouseup', function() { isDragging = false; });\n" +
                "  // SSE 接收心率数据\n" +
                "  var evtSource;\n" +
                "  function connectSSE() {\n" +
                "    evtSource = new EventSource('/');\n" +
                "    evtSource.onmessage = function(e) {\n" +
                "      try { updateDisplay(JSON.parse(e.data)); } catch(err) {}\n" +
                "    };\n" +
                "    evtSource.onerror = function() {\n" +
                "      evtSource.close();\n" +
                "      setTimeout(connectSSE, 2000);\n" +
                "    };\n" +
                "  }\n" +
                "  function startPolling() {\n" +
                "    setInterval(function() {\n" +
                "      fetch('/api/hr').then(function(r){ return r.json(); }).then(updateDisplay).catch(function(){});\n" +
                "    }, 1000);\n" +
                "  }\n" +
                "  function updateDisplay(data) {\n" +
                "    var el = document.getElementById('hrValue');\n" +
                "    var win = document.getElementById('floatWindow');\n" +
                "    if (data.connected && data.hr > 30 && data.hr < 220) {\n" +
                "      el.innerText = data.hr;\n" +
                "      win.classList.remove('disconnected');\n" +
                "      var dot = document.getElementById('pulseDot');\n" +
                "      dot.classList.remove('beat');\n" +
                "      void dot.offsetWidth;\n" +
                "      dot.classList.add('beat');\n" +
                "      setTimeout(function(){ dot.classList.remove('beat'); }, 200);\n" +
                "    } else {\n" +
                "      el.innerText = '--';\n" +
                "      win.classList.add('disconnected');\n" +
                "    }\n" +
                "    document.getElementById('deviceName').innerText = data.device || '等待连接...';\n" +
                "  }\n" +
                "  if (window.EventSource) {\n" +
                "    connectSSE();\n" +
                "    setTimeout(function() {\n" +
                "      if (document.getElementById('hrValue').innerText === '--') {\n" +
                "        if (evtSource) evtSource.close();\n" +
                "        startPolling();\n" +
                "      }\n" +
                "    }, 4000);\n" +
                "  } else { startPolling(); }\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>";
    }
}
