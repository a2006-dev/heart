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
    private ExecutorService sseExecutor;
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
        final Object writeLock = new Object();

        SseClient(Socket socket, PrintWriter writer) {
            this.socket = socket;
            this.writer = writer;
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

        sseExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HR-SSE");
            t.setDaemon(true);
            return t;
        });

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

        if (sseExecutor != null) {
            sseExecutor.shutdownNow();
            sseExecutor = null;
        }

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
            int queryIndex = path.indexOf('?');
            if (queryIndex >= 0) path = path.substring(0, queryIndex);
            OutputStream out = client.getOutputStream();
            String accept = headers.getOrDefault("accept", "");

            if ("GET".equals(method)) {
                if (path.equals("/") || path.equals("/index.html") || path.equals("/api/sse")) {
                    if (path.equals("/api/sse") || accept.contains("text/event-stream")) {
                        isSse[0] = true;
                        ExecutorService executor = sseExecutor;
                        if (executor != null) executor.execute(() -> handleSSE(client, out));
                        else safeClose(client);
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

    private void handleSSE(Socket client, OutputStream out) {
        SseClient sseClient = null;
        try {
            client.setSoTimeout(0);
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8), false);
            pw.print("HTTP/1.1 200 OK\r\n");
            pw.print("Content-Type: text/event-stream; charset=utf-8\r\n");
            pw.print("Cache-Control: no-cache, no-store\r\n");
            pw.print("Connection: keep-alive\r\n");
            pw.print("Access-Control-Allow-Origin: *\r\n");
            pw.print("X-Accel-Buffering: no\r\n");
            pw.print("\r\n");
            pw.flush();

            sseClient = new SseClient(client, pw);
            synchronized (sseClients) { sseClients.add(sseClient); }
            sendSSEData(sseClient);
            sendSSEHeartbeat(sseClient);


            while (running && !client.isClosed() && !Thread.currentThread().isInterrupted()
                    && isSseClientPresent(sseClient)) {
                try { Thread.sleep(1000); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } catch (Exception e) {
            Log.d(TAG, "SSE 连接结束: " + e.getMessage());
        } finally {
            if (sseClient != null) removeSseClient(sseClient);
            safeClose(client);
        }
    }

    private boolean isSseClientPresent(SseClient client) {
        synchronized (sseClients) { return sseClients.contains(client); }
    }

    private void checkSseClients() {
        synchronized (sseClients) {
            for (SseClient client : new ArrayList<>(sseClients)) {
                if (!sendSSEHeartbeat(client)) {
                    removeSseClient(client);
                }
            }
        }
    }

    private boolean sendSSEHeartbeat(SseClient client) {
        synchronized (client.writeLock) {
            if (client.socket.isClosed()) return false;
            client.writer.print(": heartbeat\n\n");
            client.writer.flush();
            return !client.writer.checkError();
        }
    }

    private boolean sendSSEData(SseClient client) {
        synchronized (client.writeLock) {
            if (client.socket.isClosed()) return false;
            client.writer.print("data: ");
            client.writer.print(buildJSON());
            client.writer.print("\n\n");
            client.writer.flush();
            return !client.writer.checkError();
        }
    }

    private void broadcastSSE() {
        synchronized (sseClients) {
            for (SseClient client : new ArrayList<>(sseClients)) {
                if (!sendSSEData(client)) removeSseClient(client);
            }
        }
    }

    private void removeSseClient(SseClient client) {
        synchronized (sseClients) { sseClients.remove(client); }
        synchronized (client.writeLock) {
            try { client.writer.close(); } catch (Exception ignored) {}
            safeClose(client.socket);
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


    private static String classifyInterface(String name) {
        if (name == null || name.isEmpty()) return "UNKNOWN";
        String n = name.toLowerCase(java.util.Locale.ROOT);


        if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp")
                || n.startsWith("wwan") || n.startsWith("seth")) {
            return "CELLULAR";
        }


        if (n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm")) {
            return "USB";
        }


        if (matchesPrefixWithIndex(n, "wlan") || matchesPrefixWithIndex(n, "wifi")
                || matchesPrefixWithIndex(n, "swlan") || matchesPrefixWithIndex(n, "p2p")
                || matchesPrefixWithIndex(n, "ap")) {
            return "WIFI";
        }


        if (matchesPrefixWithIndex(n, "eth")) {
            return "ETHERNET";
        }

        return "UNKNOWN";
    }


    private static boolean matchesPrefixWithIndex(String n, String prefix) {
        if (!n.startsWith(prefix)) return false;
        if (n.length() == prefix.length()) return true;
        char next = n.charAt(prefix.length());

        return Character.isDigit(next) || next == '-' || next == '_';
    }


    public String getNetworkStatus() {
        try {
            String fallbackIp = null;
            String fallbackType = null;

            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni == null) continue;
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception ignored) {
                    continue;
                }

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs != null && addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address)) continue;


                    if (addr.isLoopbackAddress()) continue;
                    if (addr.isAnyLocalAddress()) continue;
                    if (addr.isMulticastAddress()) continue;
                    if (addr.isLinkLocalAddress()) continue;

                    String ip = addr.getHostAddress();
                    if (ip == null || ip.isEmpty()) continue;

                    if (ip.startsWith("0.")) continue;

                    String name = ni.getName() != null ? ni.getName().toLowerCase() : "";
                    String type = classifyInterface(name);


                    if ("CELLULAR".equals(type)) continue;

                    if ("USB".equals(type)) {

                        return "USB:" + ip;
                    }
                    if ("WIFI".equals(type) || "ETHERNET".equals(type)) {
                        return "WiFi:" + ip;
                    }


                    if (fallbackIp == null) {
                        fallbackIp = ip;
                        fallbackType = type;
                    }
                }
            }


            if (fallbackIp != null) {
                return ("USB".equals(fallbackType) ? "USB:" : "WiFi:") + fallbackIp;
            }
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
