package com.xinji.heartbeat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.xinji.heartbeat.bluetooth.BleManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 设备连接页面 — 上半实时设备列表 + ⋮ 查看服务UUID/筛选模式
 * 下半可滚动日志框
 */
public class ConnectFragment extends Fragment {
    private MainActivity activity;
    private List<BleManager.ScanDeviceInfo> deviceList = new ArrayList<>();
    private Button btnScan, btnDisconnect, btnClearLog;
    private TextView tvDevice, tvStatus, tvScanCount, tvLog, tvBroadSearchHint;
    private ScrollView logScrollView;
    private androidx.appcompat.widget.SwitchCompat swBroadSearch;
    private RecyclerView rvDevices;
    private DeviceAdapter deviceAdapter;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;
    private boolean broadSearch = false;
    private static final int MAX_LOG_LINES = 300;
    private final List<LogEntry> logEntries = new ArrayList<>();

    private static class LogEntry {
        long time;
        String text;
        int color;
        LogEntry(long time, String text, int color) {
            this.time = time;
            this.text = text;
            this.color = color;
        }
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_connect, container, false);
        activity = (MainActivity) getActivity();

        tvDevice = v.findViewById(R.id.tvDevice);
        tvStatus = v.findViewById(R.id.tvStatus);
        btnScan = v.findViewById(R.id.btnScan);
        btnDisconnect = v.findViewById(R.id.btnDisconnect);
        tvScanCount = v.findViewById(R.id.tvScanCount);
        swBroadSearch = v.findViewById(R.id.swBroadSearch);
        tvBroadSearchHint = v.findViewById(R.id.tvBroadSearchHint);

        rvDevices = v.findViewById(R.id.rvDevices);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        deviceAdapter = new DeviceAdapter();
        rvDevices.setAdapter(deviceAdapter);

        tvLog = v.findViewById(R.id.tvLog);
        logScrollView = (ScrollView) v.findViewById(R.id.logScrollView);
        btnClearLog = v.findViewById(R.id.btnClearLog);

        btnScan.setOnClickListener(view -> toggleScan());
        btnDisconnect.setOnClickListener(view -> {
            if (activity != null) activity.disconnectDevice();
        });
        btnClearLog.setOnClickListener(view -> {
            logEntries.clear();
            updateLogDisplay();
        });

        swBroadSearch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            broadSearch = isChecked;
            deviceAdapter.notifyDataSetChanged();
            appendLog(isChecked ? "🔓 广义搜索模式 — 已显示 ⋮ 可查看各设备广播服务" : "🔒 标准模式 — 仅显示心率设备", 0xFFcbadbb);
            if (isChecked) {
                tvBroadSearchHint.setVisibility(View.VISIBLE);
                tvBroadSearchHint.setText("点击 ⋮ 查看服务UUID，或使用「筛选模式」自动匹配");
            } else {
                tvBroadSearchHint.setVisibility(View.GONE);
            }
            if (isScanning && activity != null) {
                activity.getBleManager().stopManualScan();
                isScanning = false;
                deviceList.clear();
                deviceAdapter.notifyDataSetChanged();
                btnScan.postDelayed(() -> {
                    if (activity != null && activity.getBleManager().isAvailable()) {
                        startScan();
                    }
                }, 200);
            }
        });

        // 初始化：同步当前连接状态
        syncConnectionStatus();

        appendLog("📱 心迹连接页面已加载", 0xFF675c62);
        appendLog("💡 点击「开始搜索」扫描附近设备", 0xFF675c62);
        appendLog("💡 开启「广义搜索」可查看所有设备，点击 ⋮ 选择「筛选模式」自动匹配心率服务", 0xFF675c62);
        return v;
    }

    /** 从 BleManager 同步当前连接状态（用于页面切换回来时恢复） */
    private void syncConnectionStatus() {
        if (activity == null) return;
        BleManager ble = activity.getBleManager();
        if (ble.isConnected()) {
            String name = ble.getCurrentDeviceName();
            if (name != null) {
                updateStatus(name, "已连接");
            } else {
                updateStatus("⚡ 已连接", "同步中");
            }
        } else {
            String savedName = ble.getLastDeviceName();
            if (!savedName.isEmpty() && ble.isAutoConnectEnabled()) {
                updateStatus("⚡ " + savedName, "自动重连中...");
            } else {
                updateStatus("⚡ 未连接", "待机");
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次页面回到前台时同步状态
        syncConnectionStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (isScanning && activity != null) {
            activity.getBleManager().stopManualScan();
            isScanning = false;
        }
        handler.removeCallbacksAndMessages(null);
    }

    // ==================== 日志系统 ====================

    private void appendLog(String text, int color) {
        logEntries.add(new LogEntry(System.currentTimeMillis(), text, color));
        if (logEntries.size() > MAX_LOG_LINES) {
            logEntries.remove(0);
        }
        updateLogDisplay();
    }

    private void updateLogDisplay() {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < logEntries.size(); i++) {
            LogEntry entry = logEntries.get(i);
            String timeStr = sdf.format(new Date(entry.time));
            String line = "[" + timeStr + "] " + entry.text + "\n";
            int start = ssb.length();
            ssb.append(line);
            ssb.setSpan(new ForegroundColorSpan(entry.color), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvLog.setText(ssb);
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    // ==================== 权限检查 ====================

    private void requestBluetoothPermission() {
        if (activity == null) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("需要蓝牙权限")
            .setMessage("扫描和连接心率设备需要蓝牙权限")
            .setPositiveButton("去设置", (d, w) -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void requestLocationPermission() {
        if (activity == null) return;
        new AlertDialog.Builder(requireContext())
            .setTitle("需要位置权限")
            .setMessage("蓝牙扫描需要位置权限才能发现心率设备")
            .setPositiveButton("去设置", (d, w) -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + activity.getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private boolean checkBlePermissions() {
        if (activity == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestBluetoothPermission();
                return false;
            }
        }
        return true;
    }

    // ==================== 扫描控制 ====================

    private void toggleScan() {
        if (activity == null) return;
        if (!checkBlePermissions()) return;
        BleManager ble = activity.getBleManager();

        if (isScanning) {
            ble.stopManualScan();
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
            tvScanCount.setText("");
            appendLog("⏹ 扫描已停止", 0xFF675c62);
            return;
        }

        if (!ble.isAvailable()) {
            Toast.makeText(activity, "请开启蓝牙", Toast.LENGTH_SHORT).show();
            appendLog("❌ 蓝牙未开启", 0xFFff5d7c);
            return;
        }

        startScan();
    }

    private void startScan() {
        if (activity == null) return;
        BleManager ble = activity.getBleManager();

        deviceList.clear();
        deviceAdapter.notifyDataSetChanged();
        updateStatus("⚡ 未连接", "正在扫描...");
        appendLog("🔍 开始扫描" + (broadSearch ? "（广义模式）" : "（心率设备）"), 0xFFcbadbb);

        ble.setBroadScan(broadSearch);
        ble.startManualScan();
        isScanning = true;
        btnScan.setText("⏹ 停止扫描");

        handler.removeCallbacks(scanTimeoutRunnable);
        handler.postDelayed(scanTimeoutRunnable, 15000);
    }

    private final Runnable scanTimeoutRunnable = () -> {
        if (isScanning && activity != null) {
            activity.getBleManager().stopManualScan();
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
            appendLog("⏱ 扫描超时（15秒），已自动停止", 0xFF675c62);
        }
    };

    // ==================== 设备列表 Adapter ====================

    class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvDevName, tvDevAddress, tvDevRssi, tvDeviceIcon, btnServiceDetail;
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvDevName = itemView.findViewById(R.id.tvDevName);
                tvDevAddress = itemView.findViewById(R.id.tvDevAddress);
                tvDevRssi = itemView.findViewById(R.id.tvDevRssi);
                tvDeviceIcon = itemView.findViewById(R.id.tvDeviceIcon);
                btnServiceDetail = itemView.findViewById(R.id.btnServiceDetail);
            }
        }

        @NonNull @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(requireContext()).inflate(R.layout.item_ble_device, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
            BleManager.ScanDeviceInfo dev = deviceList.get(pos);
            String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";
            h.tvDevName.setText(name);
            h.tvDevAddress.setText(dev.address);
            h.tvDevRssi.setText(formatRssi(dev.rssi));

            String lowerName = (dev.name != null ? dev.name : "").toLowerCase(Locale.ROOT);
            if (lowerName.contains("heart") || lowerName.contains("watch") || lowerName.contains("band")) {
                h.tvDeviceIcon.setText("❤️");
            } else if (lowerName.contains("mi") || lowerName.contains("xiaomi") || lowerName.contains("小米")) {
                h.tvDeviceIcon.setText("⌚");
            } else if (lowerName.contains("iqoo") || lowerName.contains("vivo")) {
                h.tvDeviceIcon.setText("⌚");
            } else {
                h.tvDeviceIcon.setText("📡");
            }

            if (broadSearch) {
                h.btnServiceDetail.setVisibility(View.VISIBLE);
                h.btnServiceDetail.setOnClickListener(v -> showServiceMenu(dev));
            } else {
                h.btnServiceDetail.setVisibility(View.GONE);
                h.btnServiceDetail.setOnClickListener(null);
            }

            h.itemView.setOnClickListener(v -> {
                if (activity == null) return;
                if (isScanning) {
                    Toast.makeText(activity, "请先停止扫描再连接", Toast.LENGTH_SHORT).show();
                    appendLog("⚠️ 请先点击「停止扫描」再连接", 0xFFffaa33);
                    return;
                }
                appendLog("🔗 正在连接: " + name + " (" + dev.address + ")", 0xFFcbadbb);
                activity.getBleManager().connectToDevice(dev.address, dev.name);
                updateStatus("📡 " + name, "连接中...");
            });
        }

        @Override
        public int getItemCount() { return deviceList.size(); }
    }

    // ==================== ⋮ 菜单 ====================

    private void showServiceMenu(BleManager.ScanDeviceInfo dev) {
        if (getContext() == null) return;
        String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";

        new AlertDialog.Builder(requireContext())
            .setTitle(name)
            .setItems(new String[]{"📋 查看广播服务 UUID", "🔍 筛选模式（自动匹配心率特征）"}, (dialog, which) -> {
                if (which == 0) showServiceUuidDialog(dev);
                else startFilterMode(dev);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showServiceUuidDialog(BleManager.ScanDeviceInfo dev) {
        if (getContext() == null) return;
        String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";
        List<String> uuids = dev.serviceUuids;

        StringBuilder sb = new StringBuilder();
        sb.append("📡 ").append(name).append("\n");
        sb.append("📍 ").append(dev.address).append("\n");
        sb.append("📶 ").append(formatRssi(dev.rssi)).append("\n\n");

        if (uuids == null || uuids.isEmpty()) {
            sb.append("该设备广播中未声明 Service UUID\n");
            sb.append("（可能需要在连接后通过服务发现获取）");
        } else {
            sb.append("广播的 Service UUID（共 ").append(uuids.size()).append(" 个）:\n\n");
            for (int i = 0; i < uuids.size(); i++) {
                String uuid = uuids.get(i);
                String tag = "";
                if (uuid.contains("180d")) tag = " ← 心率服务(标准)";
                else if (uuid.contains("180a")) tag = " ← 设备信息";
                else if (uuid.contains("180f")) tag = " ← 电池服务";
                else if (uuid.contains("fee0")) tag = " ← 小米/华米私有";
                else if (uuid.contains("fee1")) tag = " ← 小米/华米私有";
                sb.append("  ").append(i + 1).append(". ").append(uuid).append(tag).append("\n");
            }
            sb.append("\n💡 小米手环通常使用 fee0/fee1 等私有服务");
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("📋 服务 UUID 详情")
            .setMessage(sb.toString())
            .setPositiveButton("知道了", null)
            .setNegativeButton("连接此设备", (d, w) -> {
                if (activity != null) {
                    if (isScanning) {
                        activity.getBleManager().stopManualScan();
                        isScanning = false;
                        btnScan.setText("🔍 开始搜索");
                    }
                    appendLog("🔗 正在连接: " + name, 0xFFcbadbb);
                    activity.getBleManager().connectToDevice(dev.address, dev.name);
                    updateStatus("📡 " + name, "连接中...");
                }
            })
            .show();
    }

    // ==================== 筛选模式 ====================

    private void startFilterMode(BleManager.ScanDeviceInfo dev) {
        if (activity == null) return;
        String name = dev.name != null && !dev.name.isEmpty() ? dev.name : "未知设备";

        appendLog("🔍 [筛选模式] 开始分析 " + name + " ...", 0xFF4CAF50);
        appendLog("🔍 [筛选模式] 正在连接并发现所有服务...", 0xFFcbadbb);

        if (isScanning) {
            activity.getBleManager().stopManualScan();
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
        }

        activity.getBleManager().connectAndFilterServices(dev.address, dev.name,
            new BleManager.FilterCallback() {
                @Override
                public void onServiceDiscovered(String serviceUuid, String charUuid) {
                    appendLog("🔍 [筛选] 发现服务: " + serviceUuid + " / 特征: " + charUuid, 0xFFcbadbb);
                }

                @Override
                public void onHeartRateCharFound(String serviceUuid, String charUuid) {
                    appendLog("✅ [筛选] 匹配到心率特征! 服务=" + serviceUuid + " 特征=" + charUuid, 0xFF4CAF50);
                }

                @Override
                public void onSubscribed(String serviceUuid, String charUuid) {
                    appendLog("✅ [筛选] 已订阅通知: " + charUuid + " → 等待心率数据...", 0xFF4CAF50);
                    // ★ 重要：更新连接状态到 UI，保存设备信息
                    updateStatus(name, "已连接（筛选模式）");
                    if (activity != null) {
                        activity.getBleManager().saveLastDevice(dev.name, dev.address);
                        // 通知主页面和悬浮窗更新
                        if (activity.homeFragment != null) {
                            activity.homeFragment.updateDevice(name);
                        }
                    }
                }

                @Override
                public void onFilterComplete(int totalServices, int totalChars, int matched) {
                    appendLog("🔍 [筛选] 扫描完成: " + totalServices + " 个服务, "
                        + totalChars + " 个特征, 匹配到 " + matched + " 个心率相关特征", 0xFFcbadbb);
                    if (matched == 0) {
                        appendLog("⚠️ [筛选] 未找到匹配的心率特征 — 可以查看「广播服务UUID」手动选择", 0xFFffaa33);
                    }
                }

                @Override
                public void onError(String msg) {
                    appendLog("❌ [筛选] " + msg, 0xFFff5d7c);
                }

                @Override
                public void onHeartRateData(int hr) {
                    appendLog("❤️ 心率数据: " + hr + " BPM", 0xFFff5d7c);
                }
            });
    }

    // ==================== 信号强度格式化 ====================

    public static String formatRssi(int rssi) {
        int bars;
        if (rssi >= -50) bars = 5;
        else if (rssi >= -65) bars = 4;
        else if (rssi >= -80) bars = 3;
        else if (rssi >= -90) bars = 2;
        else if (rssi >= -100) bars = 1;
        else bars = 0;
        return bars + "/5📶";
    }

    // ==================== 回调 ====================

    public void onScanResult(List<BleManager.ScanDeviceInfo> devices) {
        handler.post(() -> {
            deviceList.clear();
            deviceList.addAll(devices);
            // 智能排序：watch/band/heart/小米等穿戴设备排前面，未知设备排后面
            sortDeviceList();
            deviceAdapter.notifyDataSetChanged();
            tvScanCount.setText("共 " + devices.size() + " 个");
        });
    }

    /** 穿戴设备优先排序 */
    private void sortDeviceList() {
        deviceList.sort((a, b) -> {
            int pa = getDevicePriority(a);
            int pb = getDevicePriority(b);
            if (pa != pb) return Integer.compare(pb, pa); // 优先级高的在前
            // 同优先级按信号强度降序
            return Integer.compare(b.rssi, a.rssi);
        });
    }

    /** 根据设备名判断优先级，越高越靠前 */
    private int getDevicePriority(BleManager.ScanDeviceInfo dev) {
        if (dev.name == null || dev.name.isEmpty() || "未知设备".equals(dev.name)) return 0;
        String n = dev.name.toLowerCase(Locale.ROOT);
        // 最优先：名称含 heart（心率设备）
        if (n.contains("heart")) return 100;
        // 其次：watch / band（手表/手环）
        if (n.contains("watch") || n.contains("band")) return 80;
        // 小米/华米/华为等常见品牌手环
        if (n.contains("mi ") || n.contains("xiaomi") || n.contains("小米")
            || n.contains("华为") || n.contains("huawei")
            || n.contains("honor") || n.contains("荣耀")
            || n.contains("三星") || n.contains("samsung")
            || n.contains("oppo") || n.contains("vivo")
            || n.contains("iqoo")) return 70;
        // 有具体名称的设备（不是未知）
        return 50;
    }

    public void onScanStopped() {
        handler.post(() -> {
            isScanning = false;
            btnScan.setText("🔍 开始搜索");
            if (deviceList.isEmpty()) {
                appendLog("📭 未发现任何设备", 0xFF675c62);
            } else {
                appendLog("✅ 扫描完成，共发现 " + deviceList.size() + " 个设备", 0xFFcbadbb);
            }
        });
    }

    public void updateStatus(String device, String status) {
        handler.post(() -> {
            if (tvDevice != null) tvDevice.setText(device);
            if (tvStatus != null) tvStatus.setText(status);
        });
    }

    public void appendLogExternal(String text, int color) {
        handler.post(() -> appendLog(text, color));
    }
}