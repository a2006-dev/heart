package com.xinji.heartbeat;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.io.*;
import java.net.*;
import java.util.*;
public class BroadcastActivity extends AppCompatActivity {
    private Switch swServer;
    private TextView tvServerStatus, tvNetworkInfo, tvWifiName, tvLocalIP, tvAccessURL, tvQRCode, tvLanStatus, tvDeviceList, tvScanTarget;
    private View btnSharePython, btnScanConnect, btnBack;
    private Handler handler = new Handler(Looper.getMainLooper());
    private String pushTargetIP = null;
    private int pushTargetPort = 9091;
    private boolean isPushing = false;
    private Thread pushThread;
    private int lastPushHR = 0;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast);
        swServer = findViewById(R.id.swServer);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        tvNetworkInfo = findViewById(R.id.tvNetworkInfo);
        tvWifiName = findViewById(R.id.tvWifiName);
        tvLocalIP = findViewById(R.id.tvLocalIP);
        tvAccessURL = findViewById(R.id.tvAccessURL);
        tvQRCode = findViewById(R.id.tvQRCode);
        tvLanStatus = findViewById(R.id.tvLanStatus);
        tvDeviceList = findViewById(R.id.tvDeviceList);
        tvScanTarget = findViewById(R.id.tvScanTarget);
        btnSharePython = findViewById(R.id.btnSharePython);
        btnScanConnect = findViewById(R.id.btnScanConnect);
        btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        updateNetworkInfo();
        updateStatus();
        swServer.setOnCheckedChangeListener((b, c) -> {
            Intent intent = new Intent();
            intent.setPackage(getPackageName()); // 显式指定包名，兼容 Android 14+
            if (c) {
                intent.setAction("com.xinji.heartbeat.START_BROADCAST");
            } else {
                intent.setAction("com.xinji.heartbeat.STOP_BROADCAST");
            }
            sendBroadcast(intent);
            handler.postDelayed(this::updateStatus, 500);
        });
        btnSharePython.setOnClickListener(v -> sharePythonScript());
        btnScanConnect.setOnClickListener(v -> startScan());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateNetworkInfo();
                if (swServer.isChecked()) {
                    updateStatus();
                    scanNetworkDevices();
                }
                handler.postDelayed(this, 3000);
            }
        }, 1000);
        if (swServer.isChecked()) {
            scanNetworkDevices();
        }
    }
    private void updateNetworkInfo() {
        String wifiName = getWifiName();
        String ip = HeartRateBroadcastServer.getLocalIP(this);
        String type = HeartRateBroadcastServer.getNetworkType(this);
        if (wifiName != null) {
            tvWifiName.setText("WiFi: " + wifiName);
        } else {
            tvWifiName.setText("WiFi: 未连接");
        }
        if (!"未连接".equals(ip)) {
            tvLocalIP.setText("本机IP: " + ip + " (" + type + ")");
            tvNetworkInfo.setText("已连接 · " + type);
        } else {
            tvLocalIP.setText("本机IP: 未连接网络");
            tvNetworkInfo.setText("WiFi: 未连接");
        }
    }
    private void updateStatus() {
        String ip = HeartRateBroadcastServer.getLocalIP(this);
        if (swServer.isChecked()) {
            tvServerStatus.setText("运行中");
            tvServerStatus.setTextColor(0xFF4CAF50);
            if (!"未连接".equals(ip)) {
                tvAccessURL.setText("访问地址: http://" + ip + ":8080");
                tvQRCode.setText("电脑浏览器打开上方地址可查看心率页面");
            } else {
                tvAccessURL.setText("访问地址: 请连接WiFi");
                tvQRCode.setText("");
            }
        } else {
            tvServerStatus.setText("未运行");
            tvServerStatus.setTextColor(0xFF675c62);
            tvAccessURL.setText("访问地址: 启动服务后显示");
            tvQRCode.setText("");
        }
    }
    private String getWifiName() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null) {
                        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                        if (!"<unknown ssid>".equals(ssid)) {
                            return ssid;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
    private void scanNetworkDevices() {
        String localIP = HeartRateBroadcastServer.getLocalIP(this);
        if ("未连接".equals(localIP)) {
            tvDeviceList.setText("未检测到网络连接");
            tvLanStatus.setText("未连接网络");
            return;
        }
        String[] parts = localIP.split("\\.");
        if (parts.length != 4) return;
        String subnet = parts[0] + "." + parts[1] + "." + parts[2] + ".";
        String myIP = parts[3];
        tvLanStatus.setText("扫描中...");
        tvDeviceList.setText("正在扫描同网段设备...");
        new Thread(() -> {
            List<String> foundDevices = new ArrayList<>();
            boolean hasReachableDevice = false;
            for (int i = 1; i <= 254; i++) {
                if (String.valueOf(i).equals(myIP)) continue;
                String target = subnet + i;
                try {
                    InetAddress addr = InetAddress.getByName(target);
                    if (addr.isReachable(150)) {
                        hasReachableDevice = true;
                        String hostName = addr.getCanonicalHostName();
                        String portTest = testReachablePorts(target);
                        if (hostName != null && !hostName.equals(target)) {
                            foundDevices.add(target + " (" + hostName + ")" + portTest);
                        } else {
                            foundDevices.add(target + portTest);
                        }
                    }
                } catch (Exception ignored) {}
            }
            String finalStatus;
            if (hasReachableDevice) {
                finalStatus = "✅ 可互访 · 发现 " + foundDevices.size() + " 台设备";
            } else {
                try {
                    ServerSocket testSocket = new ServerSocket(0);
                    int testPort = testSocket.getLocalPort();
                    testSocket.close();
                    finalStatus = "⚠️ 未发现其他设备 · 网络正常";
                } catch (Exception e) {
                    finalStatus = "⚠️ 网络异常 · 可能无法局域网互访";
                }
            }
            String finalStatus2 = finalStatus;
            runOnUiThread(() -> {
                tvLanStatus.setText(finalStatus2);
                if (foundDevices.isEmpty()) {
                    tvDeviceList.setText("未发现其他在线设备\n● 请确保电脑/其他设备已连接同一WiFi\n● 部分路由器开启了「AP隔离」，需在路由器设置中关闭");
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (String d : foundDevices) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append("● ").append(d);
                    }
                    tvDeviceList.setText(sb.toString());
                }
            });
        }).start();
    }
    private String testReachablePorts(String ip) {
        int[] ports = {80, 8080, 445, 139, 22, 3389};
        String reachable = "";
        for (int port : ports) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), 100);
                reachable += " [端口" + port + "开放]";
            } catch (Exception ignored) {}
        }
        return reachable;
    }
    private void startScan() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, 
                new String[]{android.Manifest.permission.CAMERA}, 1001);
            return;
        }
        new IntentIntegrator(this)
            .setCaptureActivity(ScanActivity.class)  // 使用自定义竖屏 Activity
            .setDesiredBarcodeFormats("QR_CODE")
            .setPrompt("扫描电脑屏幕上的二维码")
            .setCameraId(0)
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .setOrientationLocked(true)
            .initiateScan();
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startScan();
        } else {
            Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show();
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            handleScanResult(result.getContents());
        }
    }
    private void handleScanResult(String scanContent) {
        String content = scanContent.trim();
        if (content.contains("://")) {
            try {
                java.net.URI uri = new java.net.URI(content);
                pushTargetIP = uri.getHost();
                if (uri.getPort() > 0) pushTargetPort = uri.getPort();
            } catch (Exception e) {
                parseIPPort(content);
            }
        } else {
            parseIPPort(content);
        }
        if (pushTargetIP != null) {
            tvScanTarget.setText("已连接: " + pushTargetIP + ":" + pushTargetPort);
            startPushing();
            Toast.makeText(this, "已连接电脑 " + pushTargetIP, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "二维码格式无效，请扫描电脑上的心迹二维码", Toast.LENGTH_LONG).show();
        }
    }
    private void parseIPPort(String content) {
        String[] parts = content.split(":");
        if (parts.length >= 1) {
            String ipPart = parts[0].trim();
            if (ipPart.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                pushTargetIP = ipPart;
            }
        }
        if (parts.length >= 2) {
            try {
                pushTargetPort = Integer.parseInt(parts[1].trim());
            } catch (Exception ignored) {}
        }
    }
    private void startPushing() {
        if (isPushing) return;
        isPushing = true;
        pushThread = new Thread(() -> {
            while (isPushing && pushTargetIP != null) {
                try {
                    pushHR(lastPushHR);
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            }
        }, "HR-Push-Thread");
        pushThread.setDaemon(true);
        pushThread.start();
        HeartRateManager.getInstance(this).registerListener(hr -> {
            lastPushHR = hr;
            if (isPushing && pushTargetIP != null) {
                pushHR(hr);
            }
        });
    }
    private void pushHR(int hr) {
        if (pushTargetIP == null) return;
        try {
            String netType = HeartRateBroadcastServer.getNetworkType(this);
            String connectType = "局域网";
            if (netType.contains("USB")) connectType = "有线";
            String json = "{\"hr\":" + hr + ",\"device\":\"心迹" + 
                "\",\"connected\":" + (hr > 0) + ",\"connect_type\":\"" + connectType + "\"}";
            URL url = new URL("http://" + pushTargetIP + ":" + pushTargetPort + "/api/hr");
            HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
            httpConn.setRequestMethod("POST");
            httpConn.setRequestProperty("Content-Type", "application/json");
            httpConn.setDoOutput(true);
            httpConn.setConnectTimeout(500);
            httpConn.setReadTimeout(500);
            OutputStream os = httpConn.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.flush();
            os.close();
            httpConn.getResponseCode();
            httpConn.disconnect();
        } catch (Exception ignored) {}
    }
    private void stopPushing() {
        isPushing = false;
        if (pushThread != null) {
            pushThread.interrupt();
            pushThread = null;
        }
        HeartRateManager.getInstance(this).removeListener(hr -> {});
        pushTargetIP = null;
    }
    private void sharePythonScript() {
        try {
            InputStream is = getAssets().open("heart_monitor.py");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            is.close();
            String pythonCode = sb.toString();
            File scriptDir = new File(getCacheDir(), "share");
            if (!scriptDir.exists()) scriptDir.mkdirs();
            File scriptFile = new File(scriptDir, "heart_monitor.py");
            FileOutputStream fos = new FileOutputStream(scriptFile);
            fos.write(pythonCode.getBytes("UTF-8"));
            fos.close();
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            Uri fileUri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", scriptFile);
            shareIntent.setType("application/octet-stream");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "心迹 - 电脑心率悬浮窗客户端");
            shareIntent.putExtra(Intent.EXTRA_TEXT,
                "📡 心迹 · 电脑心率悬浮窗 v2.0\n\n" +
                "1. 把附件 heart_monitor.py 保存到电脑\n" +
                "2. 电脑上运行: python heart_monitor.py\n" +
                "3. 脚本显示二维码页面\n" +
                "4. 手机APP「扫码连接」扫电脑二维码\n" +
                "5. 心率自动推送到电脑显示悬浮窗！\n\n" +
                "右键悬浮窗可自定义颜色、透明度、大小等"
            );
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "分享 Python 脚本"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPushing();
        handler.removeCallbacksAndMessages(null);
    }
}
