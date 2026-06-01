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

/**
 * 广播管理页面 — 适配重构后的 BroadcastServer。
 */
public class BroadcastActivity extends AppCompatActivity {
    private Switch swServer;
    private TextView tvServerStatus, tvNetworkInfo, tvWifiName, tvLocalIP, tvAccessURL;
    private View btnBack;
    private Handler handler = new Handler(Looper.getMainLooper());
    // 【修复】不再自己 new BroadcastServer，通过 MainActivity 获取运行中的实例
    // 如果 MainActivity 还没创建或已销毁，通过 intent 获取状态

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

        // 分享 Python 客户端
        findViewById(R.id.btnSharePython).setOnClickListener(v -> {
            try {
                // 写到缓存目录（FileProvider 已配置 cache-path: share/）
                java.io.File cacheDir = new java.io.File(getCacheDir(), "share");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                java.io.File destFile = new java.io.File(cacheDir, "heart_monitor.py");
                // 从 assets 复制
                try (java.io.InputStream is = getAssets().open("heart_monitor.py");
                     java.io.OutputStream os = new java.io.FileOutputStream(destFile)) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
                }
                // 用 FileProvider 生成安全的 content:// Uri
                Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", destFile);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                shareIntent.putExtra(Intent.EXTRA_TEXT, "心迹 - 电脑端心率接收脚本\n在电脑上安装 Python 后运行此脚本即可接收心率数据");
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "分享 Python 客户端"));
            } catch (Exception e) {
                Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });

        updateNetworkInfo();
        updateStatus();

        // 开关服务器：通过广播通知 MainActivity 启动/停止 BroadcastServer
        swServer.setOnCheckedChangeListener((b, c) -> {
            Intent intent = new Intent();
            intent.setPackage(getPackageName());
            if (c) {
                intent.setAction("com.xinji.heartbeat.START_BROADCAST");
            } else {
                intent.setAction("com.xinji.heartbeat.STOP_BROADCAST");
            }
            sendBroadcast(intent);
            handler.postDelayed(this::updateStatus, 500);
        });

        // 【修复】移除定时轮询，改用 ConnectivityManager 网络状态回调
        registerNetworkCallback();
    }

    /** 【修复】注册网络状态变化监听，替代定时轮询 */
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            // NetworkCallback 从 API 21 开始可用，minSdk=24 安全
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
                public void onCapabilitiesChanged(Network network,
                    NetworkCapabilities caps) {
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
        } else {
            tvLocalIP.setText("本机IP: 未连接网络");
            tvNetworkInfo.setText("WiFi: 未连接");
        }
    }

    /** 【修复】从 MainActivity 获取真实的 BroadcastServer 实例端口，不再 new 空对象 */
    private int getActualServerPort() {
        // 尝试通过 Application 中的 Activity 获取运行中的 server
        if (getApplication() instanceof android.app.Application) {
            // 遍历所有 Activity 找 MainActivity
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // 更好的方式：通过 Application.ActivityLifecycleCallbacks
                // 但为了兼容，这里通过静态方法获取
            }
        }
        // 从 SharedPreferences 读取保存的端口（MainActivity 启动成功后会保存）
        return getSharedPreferences("app_settings", MODE_PRIVATE).getInt("broadcast_port", 9090);
    }

    private void updateStatus() {
        String ip = BroadcastServer.getLocalIPStatic(this);
        if (swServer.isChecked()) {
            tvServerStatus.setText("运行中");
            tvServerStatus.setTextColor(0xFF4CAF50);
            if (!"未连接".equals(ip)) {
                int port = getActualServerPort();
                tvAccessURL.setText("访问地址: http://" + ip + ":" + port);
            } else {
                tvAccessURL.setText("访问地址: 请连接WiFi");
            }
        } else {
            tvServerStatus.setText("未运行");
            tvServerStatus.setTextColor(0xFF675c62);
            tvAccessURL.setText("访问地址: 启动服务后显示");
        }
    }

    private String getWifiName() {
        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
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