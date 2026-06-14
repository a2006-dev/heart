package com.xinji.heartbeat.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT 远程推送管理器 — 纯连接层，零外部依赖。
 *
 * 解耦设计：
 * - 心率数据通过 HeartRateCallback 接口注入（由外部负责调用）
 * - 蓝牙设备名通过 setBleDeviceName() 设置
 * - 配置持久化在 SharedPreferences
 * - 不依赖任何 Activity/Fragment/Manager
 */
public class MqttManager {
    private static final String TAG = "MqttManager";
    private static volatile MqttManager instance;

    private static final String PREF_NAME = "mqtt_settings";
    private static final String KEY_BROKER = "mqtt_broker";
    private static final String KEY_PORT = "mqtt_port";
    private static final String KEY_TOPIC = "mqtt_topic";
    private static final String KEY_CLIENT_ID = "mqtt_client_id";
    private static final String KEY_DEVICE_TAG = "mqtt_device_tag";

    private static final String DEFAULT_BROKER = "broker-cn.emqx.io";
    private static final int DEFAULT_PORT = 1883;
    private static final int KEEP_ALIVE_SEC = 30;
    private static final int SOCKET_TIMEOUT_MS = 10000;

    private static final String CONN_CODE_PREFIX = "HEARTBEAT#";
    private static final String CONN_CODE_VERSION = "V1#";

    private static volatile String deviceTag = null;

    private final Context context;
    private final SharedPreferences prefs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String brokerHost = DEFAULT_BROKER;
    private volatile int brokerPort = DEFAULT_PORT;
    private volatile String topic = "heart/rate";
    private volatile String clientId = "";

    // 发送统计
    private volatile long publishCount = 0;
    private volatile long lastPublishTime = 0;
    private volatile boolean publishFailed = false;

    // 蓝牙设备名称（由外部通过 setter 设置，保持解耦）
    private volatile String bleDeviceName = "";

    private Socket mqttSocket;
    private OutputStream mqttOut;
    private ExecutorService executor;

    public interface MqttListener {
        void onConnected();
        void onDisconnected();
        void onError(String message);
    }

    /** 心率数据回调接口（由外部注入，完全解耦） */
    public interface HeartRateCallback {
        void onHeartRate(int hr);
    }

    private MqttListener listener;
    private HeartRateCallback hrCallback;

    private final HeartRateCallback publishProxy = hr -> {
        if (running.get()) {
            publishHeartRate(hr);
        }
    };

    private MqttManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadConfig();
    }

    public static MqttManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MqttManager.class) {
                if (instance == null) {
                    instance = new MqttManager(context);
                }
            }
        }
        return instance;
    }

    // ==================== 配置 ====================

    private void loadConfig() {
        brokerHost = prefs.getString(KEY_BROKER, DEFAULT_BROKER);
        brokerPort = prefs.getInt(KEY_PORT, DEFAULT_PORT);
        topic = prefs.getString(KEY_TOPIC, "heart/rate");
        clientId = prefs.getString(KEY_CLIENT_ID, generateClientId());
        // 特征码只在本次会话有效，每次启动重置（持久化存的是上一轮的，不再读取）
        deviceTag = null;
    }

    public void setConfig(String host, String mqttTopic) {
        if (host != null && !host.isEmpty()) brokerHost = host.trim();
        if (mqttTopic != null && !mqttTopic.isEmpty()) topic = mqttTopic.trim();
        prefs.edit()
            .putString(KEY_BROKER, brokerHost)
            .putString(KEY_TOPIC, topic)
            .apply();
    }

    public void setConfig(String host, int port, String mqttTopic) {
        if (host != null && !host.isEmpty()) brokerHost = host.trim();
        if (port > 0) brokerPort = port;
        if (mqttTopic != null && !mqttTopic.isEmpty()) topic = mqttTopic.trim();
        prefs.edit()
            .putString(KEY_BROKER, brokerHost)
            .putInt(KEY_PORT, brokerPort)
            .putString(KEY_TOPIC, topic)
            .apply();
    }

    public void setListener(MqttListener listener) { this.listener = listener; }

    /** 设置心率数据回调（由外部心率源调用，完全解耦） */
    public void setHeartRateCallback(HeartRateCallback callback) {
        this.hrCallback = callback;
    }

    /** 外部心率源收到新数据时调用此方法 */
    public void onHeartRateReceived(int hr) {
        if (running.get()) {
            publishHeartRate(hr);
        }
    }

    /** 设置蓝牙设备名称（由蓝牙连接层调用，保持解耦） */
    public void setBleDeviceName(String name) {
        bleDeviceName = name != null ? name : "";
    }

    // ==================== 状态查询 ====================

    public boolean isRunning() { return running.get(); }
    public String getBrokerHost() { return brokerHost; }
    public int getBrokerPort() { return brokerPort; }
    public String getTopic() { return topic; }

    /** 带设备标识的完整 Topic */
    public String getTaggedTopic() {
        String tag = deviceTag != null ? deviceTag : "--------";
        if (topic.endsWith("/" + tag)) return topic;
        return topic + "/" + tag;
    }

    public static String getDeviceTagStatic() {
        return deviceTag != null ? deviceTag : "--------";
    }

    public boolean hasValidConfig() {
        return brokerHost != null && !brokerHost.isEmpty();
    }

    // ==================== 连接码 ====================

    public String generateConnectionCode() {
        if (!hasValidConfig()) return "";
        return CONN_CODE_PREFIX + CONN_CODE_VERSION
                + getDeviceTagStatic() + "#"
                + brokerHost + ":" + brokerPort + "#"
                + topic;
    }

    /** 生成自定义连接码（不依赖当前配置，由外部传入 Broker/Topic） */
    public static String generateConnectionCode(String host, int port, String mqttTopic) {
        String tag = deviceTag != null ? deviceTag : ("CUST" + UUID.randomUUID().toString().substring(0, 5).toUpperCase());
        String cleanHost = host != null && !host.isEmpty() ? host.trim() : DEFAULT_BROKER;
        int cleanPort = port > 0 ? port : DEFAULT_PORT;
        String cleanTopic = mqttTopic != null && !mqttTopic.isEmpty() ? mqttTopic.trim() : "heart/rate";
        return CONN_CODE_PREFIX + CONN_CODE_VERSION
                + tag + "#"
                + cleanHost + ":" + cleanPort + "#"
                + cleanTopic;
    }

    public static String[] parseConnectionCode(String code) {
        if (code == null || code.isEmpty()) return null;
        String trimmed = code.trim();
        if (!trimmed.startsWith(CONN_CODE_PREFIX)) return null;
        String body = trimmed.substring(CONN_CODE_PREFIX.length());
        String[] parts = body.split("#", 4);
        if (parts.length < 4) return null;
        if (!"V1".equals(parts[0])) return null;

        String tag = parts[1];
        String hostPort = parts[2];
        String topicPart = parts[3];

        int colonIdx = hostPort.lastIndexOf(':');
        if (colonIdx <= 0) return null;
        String host = hostPort.substring(0, colonIdx);
        String portStr = hostPort.substring(colonIdx + 1);
        int port = DEFAULT_PORT;
        try { port = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}

        // Topic 末尾加上设备标识
        String fullTopic = topicPart;
        if (!fullTopic.endsWith("/" + tag)) fullTopic = fullTopic + "/" + tag;

        return new String[]{tag, host, String.valueOf(port), fullTopic};
    }

    // ==================== 生命周期 ====================

    public synchronized boolean start() {
        if (running.get()) return true;
        if (!hasValidConfig()) {
            if (listener != null) listener.onError("未配置 Broker 地址");
            return false;
        }

        // 每次启动生成全新的特征码
        deviceTag = generateDeviceTag();
        // 重新生成 ClientId
        clientId = generateClientId();
        prefs.edit().putString(KEY_CLIENT_ID, clientId).apply();

        // 如果有外部注入的心率回调，代理到发布方法
        // （不再直接依赖 HeartRateManager）

        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MQTT");
            t.setDaemon(true);
            return t;
        });
        executor.execute(this::connectAndLoop);
        return true;
    }

    /** 连通性检测 — 快速测试到 Broker 的 TCP 连接是否可达 */
    public void testConnection(final MqttListener testListener) {
        new Thread(() -> {
            String addr = brokerHost + ":" + brokerPort;
            Log.d(TAG, "测试连接: " + addr);
            Socket s = null;
            try {
                s = new Socket(brokerHost, brokerPort);
                s.setSoTimeout(5000);
                // 能连上 TCP 就算通
                if (testListener != null) testListener.onConnected();
            } catch (IOException e) {
                String msg = "无法连接到 " + addr + ": " + e.getMessage();
                Log.w(TAG, msg);
                if (testListener != null) testListener.onError(msg);
            } finally {
                try { if (s != null) s.close(); } catch (Exception ignored) {}
            }
        }).start();
    }

    public synchronized void stop() {
        try {
            if (!running.getAndSet(false)) return;
            
            // 先置 null listener，防止回调到已销毁的 UI
            listener = null;
            
            // 关 Socket 让阻塞立刻返回
            closeQuietly();
            
            if (executor != null) {
                executor.shutdownNow();
                try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                executor = null;
            }
            Log.d(TAG, "MQTT 已停止");
        } catch (Exception e) {
            Log.e(TAG, "stop 异常", e);
        }
    }

    public synchronized void restart() {
        boolean wasRunning = running.get();
        stop();
        if (wasRunning) start();
    }

    // ==================== MQTT 协议实现 ====================

    private void connectAndLoop() {
        try {
            running.set(true);
            while (running.get()) {
                try {
                    String addr = brokerHost + ":" + brokerPort;
                    Log.d(TAG, "连接 MQTT Broker: " + addr);
                    mqttSocket = new Socket(brokerHost, brokerPort);
                    mqttSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
                    mqttOut = mqttSocket.getOutputStream();
                    sendConnectPacket();

                    byte[] connack = readExactly(4);
                    if (connack == null || connack.length < 4) throw new IOException("CONNACK 失败");
                    if (connack[3] != 0) {
                        closeQuietly();
                        sleepInterruptibly(5000);
                        continue;
                    }

                    Log.d(TAG, "MQTT 已连接");
                    if (listener != null) listener.onConnected();

                    sendSubscribePacket();

                    long lastPing = System.currentTimeMillis();
                    while (running.get()) {
                        long now = System.currentTimeMillis();
                        if (now - lastPing > KEEP_ALIVE_SEC * 750) {
                            sendPingReq();
                            lastPing = now;
                        }
                        try {
                            int available = mqttSocket.getInputStream().available();
                            if (available > 0) {
                                byte[] buf = new byte[available];
                                //noinspection ResultOfMethodCallIgnored
                                mqttSocket.getInputStream().read(buf);
                            }
                        } catch (IOException e) { throw e; }
                        sleepInterruptibly(1000);
                    }
                } catch (Exception e) {
                    if (!running.get()) break;
                    String err = e.getMessage();
                    if (err == null) err = "未知错误";
                    Log.w(TAG, "MQTT 断开: " + err);
                    if (listener != null) listener.onError("MQTT 连接失败: " + err);
                    closeQuietly();
                    sleepInterruptibly(5000);
                }
            }
            closeQuietly();
        } catch (Exception e) {
            Log.e(TAG, "MQTT 连接线程异常", e);
        } finally {
            running.set(false);
        }
    }

    private void sendConnectPacket() throws IOException {
        byte[] idBytes = clientId.getBytes(StandardCharsets.UTF_8);
        int idLen = idBytes.length;
        int remainingLen = 10 + 2 + idLen;
        byte[] packet = new byte[1 + encodedLenLen(remainingLen) + remainingLen];
        int pos = 0;
        packet[pos++] = 0x10;
        pos += encodeLen(packet, pos, remainingLen);
        packet[pos++] = 0x00; packet[pos++] = 0x04;
        packet[pos++] = 'M'; packet[pos++] = 'Q';
        packet[pos++] = 'T'; packet[pos++] = 'T';
        packet[pos++] = 0x04;
        packet[pos++] = 0x02;
        packet[pos++] = 0x00; packet[pos++] = 30;
        packet[pos++] = (byte) (idLen >> 8);
        packet[pos++] = (byte) (idLen & 0xFF);
        System.arraycopy(idBytes, 0, packet, pos, idLen);
        mqttOut.write(packet);
        mqttOut.flush();
    }

    private void sendSubscribePacket() throws IOException {
        String actualTopic = getTaggedTopic();
        byte[] topicBytes = actualTopic.getBytes(StandardCharsets.UTF_8);
        int tLen = topicBytes.length;
        int remainingLen = 2 + 2 + tLen + 1;
        byte[] packet = new byte[1 + encodedLenLen(remainingLen) + remainingLen];
        int pos = 0;
        packet[pos++] = (byte) 0x82;
        pos += encodeLen(packet, pos, remainingLen);
        packet[pos++] = 0x00; packet[pos++] = 0x01;
        packet[pos++] = (byte) (tLen >> 8);
        packet[pos++] = (byte) (tLen & 0xFF);
        System.arraycopy(topicBytes, 0, packet, pos, tLen);
        pos += tLen;
        packet[pos] = 0x00;
        mqttOut.write(packet);
        mqttOut.flush();
    }

    private void publishHeartRate(int hr) {
        if (mqttOut == null || !running.get()) return;
        try {
            String actualTopic = getTaggedTopic();
            // 使用真实的蓝牙设备名称
            String deviceNameStr = bleDeviceName.isEmpty() ? ("心迹-" + (deviceTag != null ? deviceTag : "")) : bleDeviceName;
            String payload = "{\"hr\":" + hr + ",\"device\":\"" + escapeJson(deviceNameStr)
                    + "\",\"connected\":true}";
            byte[] pBytes = payload.getBytes(StandardCharsets.UTF_8);
            byte[] tBytes = actualTopic.getBytes(StandardCharsets.UTF_8);
            int tLen = tBytes.length;
            int remainingLen = 2 + tLen + pBytes.length;
            byte[] packet = new byte[1 + encodedLenLen(remainingLen) + remainingLen];
            int pos = 0;
            packet[pos++] = 0x30;
            pos += encodeLen(packet, pos, remainingLen);
            packet[pos++] = (byte) (tLen >> 8);
            packet[pos++] = (byte) (tLen & 0xFF);
            System.arraycopy(tBytes, 0, packet, pos, tLen);
            pos += tLen;
            System.arraycopy(pBytes, 0, packet, pos, pBytes.length);
            synchronized (this) {
                if (mqttOut != null) { mqttOut.write(packet); mqttOut.flush(); }
            }
            // 发送成功统计
            publishCount++;
            lastPublishTime = System.currentTimeMillis();
            publishFailed = false;
        } catch (IOException e) {
            publishFailed = true;
            Log.w(TAG, "发布心率失败: " + e.getMessage());
        }
    }

    public long getPublishCount() { return publishCount; }
    public long getLastPublishTime() { return lastPublishTime; }
    public boolean isPublishFailed() { return publishFailed; }

    private void sendPingReq() throws IOException {
        byte[] ping = { (byte) 0xC0, 0x00 };
        synchronized (this) { if (mqttOut != null) { mqttOut.write(ping); mqttOut.flush(); } }
    }

    // ==================== 工具 ====================

    private void closeQuietly() {
        try { if (mqttOut != null) { mqttOut.close(); mqttOut = null; } } catch (Exception ignored) {}
        try { if (mqttSocket != null) { mqttSocket.close(); mqttSocket = null; } } catch (Exception ignored) {}
    }

    private byte[] readExactly(int len) throws IOException {
        byte[] buf = new byte[len];
        int read = 0;
        while (read < len) {
            int n = mqttSocket.getInputStream().read(buf, read, len - read);
            if (n < 0) return null;
            read += n;
        }
        return buf;
    }

    private int encodedLenLen(int len) {
        if (len < 128) return 1;
        if (len < 16384) return 2;
        if (len < 2097152) return 3;
        return 4;
    }

    private int encodeLen(byte[] buf, int pos, int len) {
        int start = pos;
        do {
            int digit = len % 128;
            len /= 128;
            if (len > 0) digit |= 0x80;
            buf[pos++] = (byte) digit;
        } while (len > 0);
        return pos - start;
    }

    private void sleepInterruptibly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String generateDeviceTag() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    private String generateClientId() {
        return "heartbeat_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** JSON 转义，防止设备名中的特殊字符破坏 JSON */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}