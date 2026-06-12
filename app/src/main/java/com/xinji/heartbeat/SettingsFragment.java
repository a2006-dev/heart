package com.xinji.heartbeat;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.xinji.heartbeat.app.HeartEventBus;
import com.xinji.heartbeat.app.HeartServiceLocator;
import com.xinji.heartbeat.app.PreferencesManager;
import com.xinji.heartbeat.bluetooth.BleManager;
import com.xinji.heartbeat.widget.FloatWindowManager;

public class SettingsFragment extends Fragment {
    private HeartServiceLocator serviceLocator;
    private PreferencesManager prefs;
    private Switch swAutoConnect, swFloatMemory, swFloatVisible, swFloatLocked;
    private TextView tvFloatStyleName, tvBroadcastStatus;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);
        serviceLocator = HeartServiceLocator.from(requireContext());
        prefs = PreferencesManager.from(requireContext());

        swAutoConnect = v.findViewById(R.id.swAutoConnect);
        swFloatMemory = v.findViewById(R.id.swFloatMemory);
        swFloatVisible = v.findViewById(R.id.swFloatVisible);
        swFloatLocked = v.findViewById(R.id.swFloatLocked);
        tvFloatStyleName = v.findViewById(R.id.tvFloatStyleName);
        tvBroadcastStatus = v.findViewById(R.id.tvBroadcastStatus);

        swAutoConnect.setChecked(prefs.getAutoConnect());
        swFloatMemory.setChecked(prefs.getFloatMemory());
        swFloatVisible.setChecked(prefs.getFloatVisible());
        swFloatLocked.setChecked(prefs.getFloatLocked());
        updateStyleName();
        updateBroadcastStatus();

        swAutoConnect.setOnCheckedChangeListener((b, c) -> {
            prefs.setAutoConnect(c);
            BleManager ble = serviceLocator.getBleManager();
            if (ble != null) {
                ble.setAutoConnectEnabled(c);
                if (c) ble.startAutoScan();
            }
        });

        swFloatMemory.setOnCheckedChangeListener((b, c) -> {
            prefs.setFloatMemory(c);
            FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
            if (fwm != null) fwm.setMemoryEnabled(c);
        });

        swFloatVisible.setOnCheckedChangeListener((b, c) -> {
            FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
            if (fwm != null) {
                if (c) fwm.show();
                else fwm.hideByUser();
            }
        });

        swFloatLocked.setOnCheckedChangeListener((b, c) -> {
            FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
            if (fwm != null) fwm.setLocked(c);
        });

        v.findViewById(R.id.btnFloatStyle).setOnClickListener(view -> showStylePicker());
        v.findViewById(R.id.btnHelp).setOnClickListener(this::showHelp);
        v.findViewById(R.id.btnContact).setOnClickListener(this::contactAuthor);
        v.findViewById(R.id.btnBroadcast).setOnClickListener(this::openBroadcastPage);
        v.findViewById(R.id.btnGitHub).setOnClickListener(v2 -> openGitHub());
        v.findViewById(R.id.btnCheckUpdate).setOnClickListener(v2 -> checkUpdate());

        Switch swHide = v.findViewById(R.id.swHideRecents);
        swHide.setChecked(RecentsUtils.isHideFromRecents(requireContext()));
        swHide.setOnCheckedChangeListener((b, c) -> {
            if (c) {
                new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("⚠️ 隐藏最近任务")
                    .setMessage("开启后APP将从最近任务列表中隐藏，"
                        + "部分手机（尤其是小米、华为、OPPO等）可能会因此杀掉APP进程导致心率监测中断。\n\n"
                        + "如遇到心率掉线，请关闭此功能。")
                    .setPositiveButton("知道了", (d, w) -> {
                        RecentsUtils.setHideFromRecents(getActivity(), true);
                        try {
                            Intent keepAlive = new Intent(getActivity(), com.xinji.heartbeat.core.HeartRateService.class);
                            keepAlive.setAction("com.xinji.heartbeat.KEEP_ALIVE");
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                getActivity().startForegroundService(keepAlive);
                            } else {
                                getActivity().startService(keepAlive);
                            }
                        } catch (Exception ignored) {}
                    })
                    .setNegativeButton("取消", (d, w) -> swHide.setChecked(false))
                    .show();
            } else {
                RecentsUtils.setHideFromRecents(getActivity(), false);
            }
        });

        return v;
    }

    void syncFloatSwitch(boolean visible) {
        if (swFloatVisible != null) swFloatVisible.setChecked(visible);
    }

    void updateBroadcastStatus() {
        if (tvBroadcastStatus == null) return;
        var server = serviceLocator.getBroadcastServer();
        if (server != null && server.isRunning()) {
            String ip = server.getLocalIP();
            String type = server.getNetworkType();
            if (!"未连接".equals(ip)) {
                tvBroadcastStatus.setText("已启动 · " + type + ":" + ip);
            } else {
                tvBroadcastStatus.setText("⚠️ 已启动，但未检测到网络");
            }
        } else {
            tvBroadcastStatus.setText("未启动 · 点此管理");
        }
    }

    private void openBroadcastPage(View v) {
        startActivity(new Intent(getActivity(), BroadcastActivity.class));
    }

    private void updateStyleName() {
        if (tvFloatStyleName == null) return;
        int style = prefs.getFloatStyle();
        tvFloatStyleName.setText(FloatWindowManager.STYLE_NAMES[style]);
    }

    private void showStylePicker() {
        int current = prefs.getFloatStyle();
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("悬浮窗样式")
            .setSingleChoiceItems(FloatWindowManager.STYLE_NAMES, current, (dialog, which) -> {
                prefs.setFloatStyle(which);
                updateStyleName();
                FloatWindowManager fwm = serviceLocator.getFloatWindowManager();
                if (fwm != null) fwm.recreate();
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void showHelp(View v) {
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("📖 使用帮助")
            .setMessage("1. 开启蓝牙并扫描连接心率设备\n2. 连接成功后主页显示心率\n3. 悬浮窗可在其他应用上显示心率\n4. 自动连接会记忆上次设备并自动重连\n5. 悬浮窗记忆会保存位置和显示状态\n6. 心率广播：将心率推送到电脑显示\n\n问题反馈：QQ 3544399875")
            .setPositiveButton("知道了", null)
            .show();
    }

    private void contactAuthor(View v) {
        try {
            String url = "mqqwpa://im/chat?chat_type=wpa&uin=3544399875";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "请先安装 QQ", Toast.LENGTH_SHORT).show();
        }
    }

    private void openGitHub() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/a2006-dev/heart")));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "打开链接失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void checkUpdate() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/a2006-dev/heart/releases/latest")));
        } catch (Exception e) {
            Toast.makeText(requireContext(), "打开链接失败", Toast.LENGTH_SHORT).show();
        }
    }
}
