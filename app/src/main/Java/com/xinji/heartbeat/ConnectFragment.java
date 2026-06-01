package com.xinji.heartbeat;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.xinji.heartbeat.bluetooth.BleManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备连接页面 — 使用 BleManager 进行扫描和连接。
 */
public class ConnectFragment extends Fragment {
    private MainActivity activity;
    private List<BleManager.ScanDeviceInfo> deviceList = new ArrayList<>();
    private Button btnScan, btnDisconnect;
    private TextView tvDevice, tvStatus;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isScanning = false;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_connect, container, false);
        activity = (MainActivity) getActivity();

        btnScan = v.findViewById(R.id.btnScan);
        btnDisconnect = v.findViewById(R.id.btnDisconnect);
        tvDevice = v.findViewById(R.id.tvDevice);
        tvStatus = v.findViewById(R.id.tvStatus);

        btnScan.setOnClickListener(view -> toggleScan());
        btnDisconnect.setOnClickListener(view -> {
            if (activity != null) activity.disconnectDevice();
        });

        // 初始状态
        updateStatus("⚡ 未连接", "待机");

        return v;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (isScanning && activity != null) {
            activity.getBleManager().stopManualScan();
            isScanning = false;
        }
    }

    // ==================== 扫描 ====================

    private void toggleScan() {
        if (activity == null) return;
        BleManager ble = activity.getBleManager();

        if (isScanning) {
            ble.stopManualScan();
            isScanning = false;
            btnScan.setText("🔍 扫描设备");
            showDevicePicker(); // 停止扫描后弹出设备列表
            return;
        }

        if (!ble.isAvailable()) {
            Toast.makeText(activity, "请开启蓝牙", Toast.LENGTH_SHORT).show();
            return;
        }

        deviceList.clear();
        updateStatus("⚡ 未连接", "正在扫描...");

        ble.startManualScan();
        isScanning = true;
        btnScan.setText("⏹ 停止扫描");
    }

    /** 停止扫描后弹出设备列表弹窗 */
    private void showDevicePicker() {
        if (deviceList.isEmpty()) {
            Toast.makeText(activity, "未发现设备，请确保手表已开启并靠近手机", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BleManager.ScanDeviceInfo d = deviceList.get(i);
            names[i] = d.name + "  (" + d.address + ")  RSSI:" + d.rssi;
        }

        new AlertDialog.Builder(requireContext())
            .setTitle("选择设备")
            .setItems(names, (dialog, which) -> {
                BleManager.ScanDeviceInfo selected = deviceList.get(which);
                if (activity != null) {
                    activity.getBleManager().connectToDevice(selected.address, selected.name);
                }
            })
            .setNegativeButton("取消", null)
            .show();
    }

    // ==================== 状态更新 ====================

    public void onScanResult(List<BleManager.ScanDeviceInfo> devices) {
        handler.post(() -> {
            deviceList.clear();
            deviceList.addAll(devices);
            if (!deviceList.isEmpty()) {
                updateStatus("📡 发现 " + deviceList.size() + " 个设备", "点击「停止扫描」后连接");
            }
        });
    }

    public void onScanStopped() {
        handler.post(() -> {
            isScanning = false;
            btnScan.setText("🔍 扫描设备");
        });
    }

    public void updateStatus(String device, String status) {
        handler.post(() -> {
            if (tvDevice != null) tvDevice.setText(device);
            if (tvStatus != null) tvStatus.setText(status);
        });
    }
}