package com.xinji.heartbeat.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class MqttManager {
    private static final String TAG = "MqttManager";
    private static volatile MqttManager instance;

    private static final String PREF_NAME = "mqtt_settings";
    private static final String KEY_BROKER = "mqtt_broker";
    private static final String KEY_PORT = "mqtt_port";
    private static final String KEY_TOPIC = "mqtt_topic";
    private static final String KEY_CLIENT_ID = "mqtt_client_id";

    private static final String DEFAULT_BROKER = "broker-cn.emqx.io";
    private static final int DEFAULT_PORT = 1883;
    private static final int KEEP_ALIVE_SEC = 30;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final long INITIAL_RECONNECT_MS = 2000;
    private static final long MAX_RECONNECT_MS = 60000;

    private static final String CONN_CODE_PREFIX = "HEARTBEAT#";
    private static final String CONN_CODE_VERSION = "V1#";
    private static volatile String deviceTag;

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile String brokerHost = DEFAULT_BROKER;
    private volatile int brokerPort = DEFAULT_PORT;
    private volatile String topic = "heart/rate";
    private volatile String clientId = "";
    private volatile String bleDeviceName = "";

    private volatile long publishCount;
    private volatile long lastPublishTime;
    private volatile boolean publishFailed;
    private volatile boolean connected;

    private Socket mqttSocket;
    private OutputStream mqttOut;
    private InputStream mqttIn;
    private ExecutorService executor;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile long lastInboundTime;
    private volatile long lastPingTime;
    private final Object ioLock = new Object();

    private volatile MqttListener listener;
    private volatile HeartRateCallback hrCallback;

    public interface MqttListener {
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    public interface HeartRateCallback {
        void onHeartRate(int hr);
    }

    private MqttManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadConfig();
    }

    public static MqttManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MqttManager.class) {
                if (instance == null) instance = new MqttManager(context);
            }
        }
        return instance;
    }

    private void loadConfig() {
        brokerHost = prefs.getString(KEY_BROKER, DEFAULT_BROKER);
        brokerPort = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        topic = prefs.getString(KEY_TOPIC, "heart/rate");
        clientId = prefs.getString(KEY_CLIENT_ID, generateClientId());
        deviceTag = null;
    }

    public synchronized void setConfig(String host, String mqttTopic) {
        if (host != null && !host.trim().isEmpty()) brokerHost = host.trim();
        if (mqttTopic != null && !mqttTopic.trim().isEmpty()) topic = mqttTopic.trim();
        saveConfig();
    }

    public synchronized void setConfig(String host, int port, String mqttTopic) {
        if (host != null && !host.trim().isEmpty()) brokerHost = host.trim();
        if (port > 0 && port <= 65535) brokerPort = port;
        if (mqttTopic != null && !mqttTopic.trim().isEmpty()) topic = mqttTopic.trim();
        saveConfig();
    }

    private void saveConfig() {
        prefs.edit().putString(KEY_BROKER, brokerHost).putInt(KEY_PORT, brokerPort)
                .putString(KEY_TOPIC, topic).apply();
    }

    public void setListener(MqttListener listener) { this.listener = listener; }
    public void setHeartRateCallback(HeartRateCallback callback) { this.hrCallback = callback; }

    public void onHeartRateReceived(int hr) {
        if (running.get() && hr > 20 && hr < 250) publishHeartRate(hr);
        HeartRateCallback callback = hrCallback;
        if (callback != null) callback.onHeartRate(hr);
    }

    public void setBleDeviceName(String name) { bleDeviceName = name != null ? name : ""; }

    public boolean isRunning() { return running.get(); }
    public boolean isConnected() { return connected; }
    public String getBrokerHost() { return brokerHost; }
    public int getBrokerPort() { return brokerPort; }
    public String getTopic() { return topic; }
    public long getPublishCount() { return publishCount; }
    public long getLastPublishTime() { return lastPublishTime; }
    public boolean isPublishFailed() { return publishFailed; }

    public String getTaggedTopic() {
        String tag = deviceTag;
        if (tag == null || tag.isEmpty()) return topic;
        return topic.endsWith("/" + tag) ? topic : topic + "/" + tag;
    }

    public static String getDeviceTagStatic() { return deviceTag != null ? deviceTag : ""; }
    public boolean hasValidConfig() {
        return brokerHost != null && !brokerHost.isEmpty() && brokerPort > 0 && brokerPort <= 65535;
    }

    public String generateConnectionCode() {
        if (!hasValidConfig()) return "";
        return CONN_CODE_PREFIX + CONN_CODE_VERSION + getDeviceTagStatic() + "#"
                + brokerHost + ":" + brokerPort + "#" + topic;
    }

    public static String generateConnectionCode(String host, int port, String mqttTopic) {
        String tag = deviceTag != null ? deviceTag : ("CUST" + UUID.randomUUID().toString().substring(0, 5).toUpperCase());
        String cleanHost = host != null && !host.trim().isEmpty() ? host.trim() : DEFAULT_BROKER;
        int cleanPort = port > 0 && port <= 65535 ? port : DEFAULT_PORT;
        String cleanTopic = mqttTopic != null && !mqttTopic.trim().isEmpty() ? mqttTopic.trim() : "heart/rate";
        return CONN_CODE_PREFIX + CONN_CODE_VERSION + tag + "#" + cleanHost + ":" + cleanPort + "#" + cleanTopic;
    }


    public static String[] parseConnectionCode(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        String trimmed = code.trim();
        if (!trimmed.startsWith(CONN_CODE_PREFIX)) return null;
        String[] parts = trimmed.substring(CONN_CODE_PREFIX.length()).split("#", 4);
        if (parts.length != 4 || !"V1".equals(parts[0])) return null;

        String tag = parts[1].trim();
        String hostPort = parts[2].trim();
        String topicPart = parts[3].trim();
        if (tag.isEmpty() || topicPart.isEmpty()) return null;

        String host;
        String portStr;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0 || close + 1 >= hostPort.length() || hostPort.charAt(close + 1) != ':') return null;
            host = hostPort.substring(1, close);
            portStr = hostPort.substring(close + 2);
        } else {
            int colon = hostPort.lastIndexOf(':');
            if (colon <= 0) return null;
            host = hostPort.substring(0, colon);
            portStr = hostPort.substring(colon + 1);
        }

        int port;
        try { port = Integer.parseInt(portStr); }
        catch (NumberFormatException e) { return null; }
        if (host.isEmpty() || port < 1 || port > 65535) return null;

        String fullTopic = topicPart.endsWith("/" + tag) ? topicPart : topicPart + "/" + tag;
        return new String[]{tag, host, String.valueOf(port), fullTopic};
    }

    public synchronized boolean start() {
        if (running.get()) return true;
        if (!hasValidConfig()) {
            postError("未配置有效的 Broker 地址或端口");
            return false;
        }
        deviceTag = generateDeviceTag();
        clientId = generateClientId();
        prefs.edit().putString(KEY_CLIENT_ID, clientId).apply();
        running.set(true);
        connected = false;

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Heart-MQTT");
            t.setDaemon(true);
            return t;
        });
        executor.execute(this::connectAndLoop);
        return true;
    }

    public void testConnection(final MqttListener testListener) {
        ExecutorService testExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MQTT-Test");
            t.setDaemon(true);
            return t;
        });
        testExecutor.execute(() -> {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(brokerHost, brokerPort), CONNECT_TIMEOUT_MS);
                postConnected(testListener);
            } catch (IOException e) {
                postError(testListener, "无法连接到 " + brokerHost + ":" + brokerPort + ": " + safeMessage(e));
            } finally {
                testExecutor.shutdown();
            }
        });
    }

    public synchronized void stop() {
        boolean wasRunning = running.getAndSet(false);
        boolean wasConnected = connected;
        stopHeartbeatMonitor();
        closeQuietly();
        connected = false;

        final ExecutorService oldExecutor = executor;
        executor = null;
        if (oldExecutor != null) {


            oldExecutor.shutdownNow();
        }
        if (wasRunning && wasConnected) postDisconnected();
    }

    public synchronized void restart() {
        boolean wasRunning = running.get();
        stop();
        if (wasRunning) start();
    }

    private void connectAndLoop() {
        long retryDelay = INITIAL_RECONNECT_MS;
        while (running.get()) {
            try {
                openConnection();
                retryDelay = INITIAL_RECONNECT_MS;
                runConnectedLoop();
            } catch (Exception e) {
                if (!running.get()) break;
                Log.w(TAG, "MQTT 连接断开: " + safeMessage(e));
                if (connected) {
                    connected = false;
                    postDisconnected();
                }
                closeQuietly();
                postError("MQTT 连接失败: " + safeMessage(e));
                sleepInterruptibly(retryDelay);
                retryDelay = Math.min(MAX_RECONNECT_MS, retryDelay * 2);
            }
        }
        connected = false;
        closeQuietly();
    }

    private void openConnection() throws IOException {
        Log.d(TAG, "连接 MQTT Broker: " + brokerHost + ":" + brokerPort);
        Socket socket = new Socket();
        socket.setKeepAlive(true);
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(brokerHost, brokerPort), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(0);
        synchronized (ioLock) {
            mqttSocket = socket;
            mqttOut = socket.getOutputStream();
            mqttIn = socket.getInputStream();
        }

        sendConnectPacket();
        byte[] connack = readPacket();
        if (connack == null || (connack[0] & 0xF0) != 0x20 || connack.length < 4) {
            throw new IOException("CONNACK 格式错误");
        }
        int returnCode = connack[3] & 0xFF;
        if (returnCode != 0) throw new IOException("Broker 拒绝连接，返回码 " + returnCode);

        sendSubscribePacket();
        connected = true;
        publishFailed = false;
        postConnected(null);
        Log.d(TAG, "MQTT 已连接，Topic=" + getTaggedTopic());
    }

    private void runConnectedLoop() throws IOException {
        lastInboundTime = System.currentTimeMillis();
        lastPingTime = 0;
        startHeartbeatMonitor();
        try {
            while (running.get() && connected) {
                byte[] packet = readPacket();
                if (packet == null) throw new EOFException("Broker 已关闭连接");
                lastInboundTime = System.currentTimeMillis();
                int type = packet[0] & 0xF0;
                if (type == 0xD0) {
                    lastPingTime = 0;
                }

            }
        } finally {
            stopHeartbeatMonitor();
        }
    }

    private synchronized void startHeartbeatMonitor() {
        stopHeartbeatMonitor();
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Heart-MQTT-Heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!running.get() || !connected) return;
            long now = System.currentTimeMillis();
            try {
                if (lastPingTime > 0 && now - lastPingTime > KEEP_ALIVE_SEC * 1000L) {
                    Log.w(TAG, "MQTT PINGRESP 超时，主动重连");
                    closeQuietly();
                    return;
                }
                if (now - lastInboundTime >= KEEP_ALIVE_SEC * 500L) {
                    sendPingReq();
                    lastPingTime = now;
                }
            } catch (IOException e) {
                closeQuietly();
            }
        }, KEEP_ALIVE_SEC * 500L, KEEP_ALIVE_SEC * 500L, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopHeartbeatMonitor() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
    }

    private void sendConnectPacket() throws IOException {
        byte[] idBytes = clientId.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeU16(body, 4); body.write("MQTT".getBytes(StandardCharsets.UTF_8), 0, 4);
        body.write(0x04);
        body.write(0x02);
        writeU16(body, KEEP_ALIVE_SEC);
        writeU16(body, idBytes.length); body.write(idBytes, 0, idBytes.length);
        writePacket(0x10, body.toByteArray());
    }

    private void sendSubscribePacket() throws IOException {
        byte[] topicBytes = getTaggedTopic().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeU16(body, 1);
        writeU16(body, topicBytes.length); body.write(topicBytes, 0, topicBytes.length);
        body.write(0x00);
        writePacket(0x82, body.toByteArray());
    }

    private void publishHeartRate(int hr) {
        if (!connected || hr <= 20 || hr >= 250) return;
        try {
            String device = bleDeviceName.isEmpty() ? "心迹-" + getDeviceTagStatic() : bleDeviceName;
            String payload = "{\"hr\":" + hr + ",\"device\":\"" + escapeJson(device) + "\",\"connected\":true}";
            byte[] topicBytes = getTaggedTopic().getBytes(StandardCharsets.UTF_8);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeU16(body, topicBytes.length); body.write(topicBytes, 0, topicBytes.length);
            body.write(payloadBytes, 0, payloadBytes.length);
            writePacket(0x30, body.toByteArray());
            publishCount++;
            lastPublishTime = System.currentTimeMillis();
            publishFailed = false;
        } catch (IOException e) {
            publishFailed = true;
            Log.w(TAG, "发布心率失败: " + safeMessage(e));
            closeQuietly();
        }
    }

    private void sendPingReq() throws IOException { writePacket(0xC0, new byte[0]); }

    private void writePacket(int header, byte[] body) throws IOException {
        synchronized (ioLock) {
            if (mqttOut == null) throw new IOException("MQTT 未连接");
            mqttOut.write(header);
            mqttOut.write(encodeRemainingLen(body.length));
            mqttOut.write(body);
            mqttOut.flush();
        }
    }


    private byte[] readPacket() throws IOException {
        InputStream in = mqttIn;
        if (in == null) throw new IOException("输入流不可用");
        int header = in.read();
        if (header < 0) return null;
        int remaining = readRemainingLen(in);
        if (remaining < 0 || remaining > 1024 * 1024) throw new IOException("MQTT 包长度非法");
        byte[] packet = new byte[1 + remainingLenBytes(remaining) + remaining];
        packet[0] = (byte) header;
        byte[] len = encodeRemainingLen(remaining);
        System.arraycopy(len, 0, packet, 1, len.length);
        readFully(in, packet, 1 + len.length, remaining);
        return packet;
    }

    private int readRemainingLen(InputStream in) throws IOException {
        int multiplier = 1, value = 0;
        for (int i = 0; i < 4; i++) {
            int b = in.read();
            if (b < 0) throw new EOFException("MQTT 包被截断");
            value += (b & 0x7F) * multiplier;
            if ((b & 0x80) == 0) return value;
            multiplier *= 128;
        }
        throw new IOException("MQTT 剩余长度编码非法");
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) throw new EOFException("MQTT 包数据不足");
            read += n;
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static byte[] encodeRemainingLen(int len) {
        if (len < 0 || len > 268435455) throw new IllegalArgumentException("MQTT 剩余长度超限");
        ByteArrayOutputStream out = new ByteArrayOutputStream(4);
        do {
            int digit = len % 128;
            len /= 128;
            if (len > 0) digit |= 0x80;
            out.write(digit);
        } while (len > 0);
        return out.toByteArray();
    }

    private static int remainingLenBytes(int len) {
        if (len < 128) return 1;
        if (len < 16384) return 2;
        if (len < 2097152) return 3;
        return 4;
    }

    private synchronized void closeQuietly() {
        synchronized (ioLock) {
            try { if (mqttOut != null) mqttOut.close(); } catch (Exception ignored) {}
            try { if (mqttIn != null) mqttIn.close(); } catch (Exception ignored) {}
            try { if (mqttSocket != null) mqttSocket.close(); } catch (Exception ignored) {}
            mqttOut = null;
            mqttIn = null;
            mqttSocket = null;
        }
    }

    private void postConnected(MqttListener explicit) {
        MqttListener target = explicit != null ? explicit : listener;
        if (target != null) mainHandler.post(() -> target.onConnected());
    }

    private void postDisconnected() {
        MqttListener target = listener;
        if (target != null) mainHandler.post(target::onDisconnected);
    }

    private void postError(String message) { postError(listener, message); }
    private void postError(MqttListener target, String message) {
        if (target != null) mainHandler.post(() -> target.onError(message));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private void sleepInterruptibly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String generateDeviceTag() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    private String generateClientId() { return "heartbeat_" + UUID.randomUUID().toString().substring(0, 8); }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
