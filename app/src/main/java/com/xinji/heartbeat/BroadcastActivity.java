package com.xinji.heartbeat;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.xinji.heartbeat.server.BroadcastServer;

public class BroadcastActivity extends AppCompatActivity {
    private Switch swServer;
    private TextView tvServerStatus, tvNetworkInfo, tvWifiName, tvLocalIP, tvAccessURL;
    private View btnBack;
    private Handler handler = new Handler(Looper.getMainLooper());

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
        btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        findViewById(R.id.btnSharePython).setOnClickListener(v -> sharePythonClient());

        tvAccessURL.setOnClickListener(v -> copyAccessUrl());

        updateNetworkInfo();
        updateStatus();

        swServer.setOnCheckedChangeListener((b, c) -> {
            Intent intent = new Intent();
            intent.setPackage(getPackageName());
            intent.setAction(c ? "com.xinji.heartbeat.START_BROADCAST"
                              : "com.xinji.heartbeat.STOP_BROADCAST");
            sendBroadcast(intent);
            handler.postDelayed(this::updateStatus, 500);
        });

        registerNetworkCallback();
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    runOnUiThread(() -> { updateNetworkInfo(); updateStatus(); });
                }
                @Override
                public void onLost(Network network) {
                    runOnUiThread(() -> { updateNetworkInfo(); updateStatus(); });
                }
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    runOnUiThread(() -> { updateNetworkInfo(); updateStatus(); });
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback);
            } else {
                cm.registerNetworkCallback(new NetworkRequest.Builder().build(), callback);
            }
        } catch (Exception ignored) {}
    }

    private void updateNetworkInfo() {
        String wifiName = getWifiName();
        String ip = BroadcastServer.getLocalIPStatic(this);
        String type = BroadcastServer.getNetworkTypeStatic(this);

        tvWifiName.setText("WiFi: " + (wifiName != null ? wifiName : "未连接"));
        if (!"未连接".equals(ip)) {
            tvLocalIP.setText("本机IP: " + ip + " (" + type + ")");
            tvNetworkInfo.setText("已连接 · " + type);
            // 电脑端会自动扫描本网段，告知用户无需手动输入
            if (swServer.isChecked()) {
                tvAccessURL.setText("http://" + ip + ":" + getActualServerPort());
            }
        } else {
            tvLocalIP.setText("本机IP: 未连接网络");
            tvNetworkInfo.setText("WiFi: 未连接 · 电脑端需连接同一网络");
        }
    }

    private int getActualServerPort() {
        return getSharedPreferences("app_settings", MODE_PRIVATE).getInt("broadcast_port", 9090);
    }

    private void updateStatus() {
        String ip = BroadcastServer.getLocalIPStatic(this);
        boolean running = swServer.isChecked();

        if (running) {
            tvServerStatus.setText("运行中");
            tvServerStatus.setTextColor(0xFF4CAF50);
            if (!"未连接".equals(ip)) {
                int port = getActualServerPort();
                String url = "http://" + ip + ":" + port;
                tvAccessURL.setText(url);
                tvAccessURL.setVisibility(View.VISIBLE);
            } else {
                tvAccessURL.setText("⚠️ 请连接WiFi后启动");
                tvAccessURL.setVisibility(View.VISIBLE);
            }
        } else {
            tvServerStatus.setText("未运行");
            tvServerStatus.setTextColor(0xFF675c62);
            tvAccessURL.setText("💡 开启开关启动广播服务");
            tvAccessURL.setVisibility(View.VISIBLE);
        }
    }

    private void copyAccessUrl() {
        String url = tvAccessURL.getText().toString();
        if (url.startsWith("http://") || url.startsWith("💡") || url.startsWith("⚠️")) {
            if (url.startsWith("http://")) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("心迹访问地址", url);
                cm.setPrimaryClip(clip);
                Toast.makeText(this, "已复制访问地址: " + url, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void sharePythonClient() {
        try {
            java.io.File cacheDir = new java.io.File(getCacheDir(), "share");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            java.io.File destFile = new java.io.File(cacheDir, "heart_monitor.py");
            try (java.io.InputStream is = getAssets().open("heart_monitor.py");
                 java.io.OutputStream os = new java.io.FileOutputStream(destFile)) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
            }
            Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", destFile);
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_TEXT, "❤️ 心迹 - 电脑端心率接收脚本\n\n在电脑上安装 Python 后运行此脚本即可接收心率数据\n\n访问地址: " + tvAccessURL.getText().toString());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "分享 Python 客户端"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String getWifiName() {
        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager)
                getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null) {
                        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                        if (!"<unknown ssid>".equals(ssid)) return ssid;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
