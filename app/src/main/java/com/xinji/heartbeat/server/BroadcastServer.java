package com.xinji.heartbeat.server;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import android.content.res.AssetManager;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class BroadcastServer {
    private static final String TAG = "BroadcastServer";
    private static final int DEFAULT_PORT = 9090;
    private static final int PORT_RETRY_COUNT = 10;
    private static final long SSE_HEARTBEAT_INTERVAL_MS = 15000;
    private static final long ACCEPT_RETRY_DELAY_MS = 3000;

    private ServerSocket serverSocket;
    private volatile int actualPort = -1;
    private volatile boolean running = false;
    private volatile boolean shouldRestart = false;
    private volatile int currentHR = 0;
    private volatile String deviceName = "未连接";
    private volatile boolean connected = false;
    private final Context context;

    private ExecutorService threadPool;
    private ScheduledExecutorService heartbeatScheduler;

    private final List<SseClient> sseClients = Collections.synchronizedList(new ArrayList<>());

    private volatile String cachedHtml = null;

    private ServerListener listener;

    public interface ServerListener {
        void onServerStarted(String ip, int port);
        void onServerStopped();
        void onError(String message);
    }

    private static class SseClient {
        final Socket socket;
        final PrintWriter writer;
        volatile long lastHeartbeat;

        SseClient(Socket socket, PrintWriter writer) {
            this.socket = socket;
            this.writer = writer;
            this.lastHeartbeat = System.currentTimeMillis();
        }
    }

    public BroadcastServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setListener(ServerListener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        start(DEFAULT_PORT);
    }

    public synchronized void start(int preferredPort) {
        if (running) return;

        threadPool = new ThreadPoolExecutor(
            2, 8,
            30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16),
            r -> {
                Thread t = new Thread(r, "HR-Server-Pool");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()
        );

        int port = preferredPort;
        for (int i = 0; i < PORT_RETRY_COUNT; i++) {
            try {
                serverSocket = new ServerSocket(port, 10, InetAddress.getByName("0.0.0.0"));
                actualPort = port;
                running = true;
                Log.d(TAG, "广播服务器启动于端口 " + port);
                break;
            } catch (IOException e) {
                port++;
            }
        }

        if (!running) {
            String msg = "无法启动服务器：端口 " + preferredPort + "-" + (preferredPort + PORT_RETRY_COUNT - 1) + " 均被占用";
            Log.e(TAG, msg);
            if (listener != null) listener.onError(msg);
            threadPool.shutdown();
            return;
        }

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HR-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(this::checkSseClients, SSE_HEARTBEAT_INTERVAL_MS, SSE_HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);

        threadPool.execute(this::acceptLoop);

        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putInt("broadcast_port", actualPort).apply();

        String ip = getLocalIP();
        if (listener != null) {
            listener.onServerStarted(ip, actualPort);
        }
    }

    public synchronized void stop() {
        running = false;

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
            heartbeatScheduler = null;
        }

        synchronized (sseClients) {
            for (SseClient client : sseClients) {
                try { client.writer.close(); } catch (Exception ignored) {}
                try { client.socket.close(); } catch (Exception ignored) {}
            }
            sseClients.clear();
        }

        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
        serverSocket = null;

        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }

        actualPort = -1;
        Log.d(TAG, "广播服务器已停止");

        if (listener != null) listener.onServerStopped();
    }

    public boolean isRunning() { return running; }
    public int getPort() { return actualPort; }

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

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                threadPool.execute(() -> handleClient(client));
            } catch (SocketException e) {
                if (!running) break;
                Log.w(TAG, "ServerSocket异常，3秒后重试", e);
                shouldRestart = true;
                try { Thread.sleep(ACCEPT_RETRY_DELAY_MS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

                synchronized (this) {
                    if (running) {
                        try {
                            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
                        } catch (Exception ignored) {}
                        int port = actualPort > 0 ? actualPort : DEFAULT_PORT;
                        try {
                            serverSocket = new ServerSocket(port, 10, InetAddress.getByName("0.0.0.0"));
                            shouldRestart = false;
                            Log.d(TAG, "ServerSocket 已恢复");
                        } catch (IOException e2) {
                            Log.e(TAG, "ServerSocket 恢复失败", e2);
                        }
                    }
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "接受连接异常", e);
                }
            }
        }
    }

    private void handleClient(Socket client) {
        final boolean[] isSse = {false};
        try {
            client.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) { safeClose(client); return; }

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
                        isSse[0] = true;
                        handleSSE(client, out);
                        return;
                    } else {
                        serveHtml(client, out);
                    }
                } else if (path.equals("/api/hr")) {
                    serveJson(client, out);
                } else if (path.equals("/api/ip")) {
                    serveIPJson(client, out);
                } else if (path.equals("/api/profiles")) {
                    serveProfilesJson(client, out);
                } else {
                    send404(client, out);
                }
            } else {
                send404(client, out);
            }

            safeClose(client);
        } catch (Exception e) {
            if (!isSse[0]) safeClose(client);
        }
    }

    private void handleSSE(Socket client, OutputStream out) throws IOException {
        PrintWriter pw = new PrintWriter(out, true);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: text/event-stream\r\n");
        pw.print("Cache-Control: no-cache\r\n");
        pw.print("Connection: keep-alive\r\n");
        pw.print("Access-Control-Allow-Origin: *\r\n");
        pw.print("X-Accel-Buffering: no\r\n");
        pw.print("\r\n");
        pw.flush();

        SseClient sseClient = new SseClient(client, pw);
        sseClients.add(sseClient);
        sendSSEData(pw);

        pw.print(": connected\n\n");
        pw.flush();

        try {
            client.setSoTimeout(30000);
            BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()));
            while (running && !Thread.currentThread().isInterrupted()) {
                String s = r.readLine();
                if (s == null) break;
                sseClient.lastHeartbeat = System.currentTimeMillis();
            }
        } catch (SocketTimeoutException e) {
        } catch (Exception ignored) {
        } finally {
            sseClients.remove(sseClient);
            try { pw.close(); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void checkSseClients() {
        long now = System.currentTimeMillis();
        synchronized (sseClients) {
            Iterator<SseClient> it = sseClients.iterator();
            while (it.hasNext()) {
                SseClient client = it.next();
                if (now - client.lastHeartbeat > SSE_HEARTBEAT_INTERVAL_MS * 3) {
                    try { client.writer.close(); } catch (Exception ignored) {}
                    try { client.socket.close(); } catch (Exception ignored) {}
                    it.remove();
                    Log.d(TAG, "清理僵尸 SSE 连接");
                }
            }
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
            Iterator<SseClient> it = sseClients.iterator();
            while (it.hasNext()) {
                SseClient client = it.next();
                try {
                    sendSSEData(client.writer);
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
        String html = buildHtmlPage();
        byte[] htmlBytes = html.getBytes("UTF-8");
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
        String json = "{\"ip\":\"" + getLocalIP() + "\",\"port\":" + actualPort + "}";
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

    private void serveProfilesJson(Socket client, OutputStream out) throws IOException {
        String json = "[]";
        try {
            json = com.xinji.heartbeat.core.DeviceProfileManager.getInstance(context).getProfilesJson();
        } catch (Exception ignored) {}
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

    private String buildHtmlPage() {
        if (cachedHtml != null) return cachedHtml;
        try {
            AssetManager am = context.getAssets();
            InputStream is = am.open("broadcast_overlay.html");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            cachedHtml = sb.toString();
            Log.d(TAG, "广播页面已从 assets/broadcast_overlay.html 加载（" + cachedHtml.length() + " 字节）");
            return cachedHtml;
        } catch (IOException e) {
            Log.e(TAG, "读取 broadcast_overlay.html 失败，使用默认页面", e);
            cachedHtml = "<!DOCTYPE html><html><body><h1>心迹 - 心率广播</h1><p>页面加载失败，请检查 assets</p></body></html>";
            return cachedHtml;
        }
    }

    public String getLocalIP() {
        String status = getNetworkStatus();
        if (status.startsWith("USB:") || status.startsWith("WiFi:")) {
            return status.substring(4);
        }
        return "未连接";
    }

    public String getNetworkType() {
        String status = getNetworkStatus();
        if (status.startsWith("USB:")) return "USB网络共享";
        if (status.startsWith("WiFi:")) return "WiFi";
        return "未连接";
    }

    public boolean canStartBroadcast() {
        String status = getNetworkStatus();
        return status.startsWith("USB:") || status.startsWith("WiFi:");
    }

    public String getNetworkStatus() {
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

            String fallbackIp = null;
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("0.") || ip.startsWith("127.") || "0.0.0.0".equals(ip)) continue;
                    String name = ni.getName().toLowerCase();

                    if (fallbackIp == null && !ip.startsWith("169.254.")) {
                        fallbackIp = "WiFi:" + ip;
                    }

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
                    return isUsb ? "USB:" + ip : "WiFi:" + ip;
                }
            }
            if (fallbackIp != null) return fallbackIp;
        } catch (Exception e) {
            Log.e(TAG, "检测网络状态失败", e);
        }
        return "未连接";
    }

    public static String getLocalIPStatic(Context context) {
        BroadcastServer tmp = new BroadcastServer(context);
        return tmp.getLocalIP();
    }

    public static String getNetworkTypeStatic(Context context) {
        BroadcastServer tmp = new BroadcastServer(context);
        return tmp.getNetworkType();
    }

    public static boolean canStartBroadcastStatic(Context context) {
        BroadcastServer tmp = new BroadcastServer(context);
        return tmp.canStartBroadcast();
    }

    private void safeClose(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Exception ignored) {}
    }
}
