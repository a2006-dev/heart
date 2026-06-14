package com.xinji.heartbeat;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.xinji.heartbeat.app.HeartServiceLocator;
import com.xinji.heartbeat.app.PreferencesManager;
import com.xinji.heartbeat.bluetooth.BleManager;
import com.xinji.heartbeat.mqtt.MqttManager;
import com.xinji.heartbeat.widget.FloatWindowManager;

public class SettingsFragment extends Fragment {
    private HeartServiceLocator serviceLocator;
    private PreferencesManager prefs;
    private Switch swAutoConnect, swFloatMemory, swFloatVisible, swFloatLocked;
    private TextView tvFloatStyleName, tvBroadcastStatus;

    // MQTT
    private MqttManager mqttManager;
    private Switch swMqttEnabled;
    private TextView tvMqttStatus, tvMqttTag, tvMqttCode;
    private EditText etMqttBroker, etMqttPort, etMqttTopic;
    private View mqttConfigBody, mqttCodeSection;
    private Button btnCopyCode, btnShareCode, btnMqttTest;
    private final Handler handler = new Handler(Looper.getMainLooper());

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

        // MQTT 初始化
        mqttManager = serviceLocator.getMqttManager();
        swMqttEnabled = v.findViewById(R.id.swMqttEnabled);
        tvMqttStatus = v.findViewById(R.id.tvMqttStatus);
        tvMqttTag = v.findViewById(R.id.tvMqttTag);
        tvMqttCode = v.findViewById(R.id.tvMqttCode);
        etMqttBroker = v.findViewById(R.id.etMqttBroker);
        etMqttPort = v.findViewById(R.id.etMqttPort);
        etMqttTopic = v.findViewById(R.id.etMqttTopic);
        mqttConfigBody = v.findViewById(R.id.mqttConfigBody);
        mqttCodeSection = v.findViewById(R.id.mqttCodeSection);
        btnCopyCode = v.findViewById(R.id.btnCopyCode);
        btnShareCode = v.findViewById(R.id.btnShareCode);
        btnMqttTest = v.findViewById(R.id.btnMqttTest);
        Button btnMqttHelp = v.findViewById(R.id.btnMqttHelp);

        tvMqttTag.setText(MqttManager.getDeviceTagStatic());
        etMqttBroker.setText(mqttManager.getBrokerHost());
        etMqttPort.setText(String.valueOf(mqttManager.getBrokerPort()));
        etMqttTopic.setText(mqttManager.getTopic());
        if (mqttManager.isRunning()) {
            swMqttEnabled.setChecked(true);
            mqttConfigBody.setVisibility(View.VISIBLE);
            showConnCode();
        }
        updateMqttStatus();

        swMqttEnabled.setOnCheckedChangeListener((b, c) -> {
            if (c) {
                mqttConfigBody.setVisibility(View.VISIBLE);
                saveMqttConfig();
                if (!mqttManager.start()) { swMqttEnabled.setChecked(false); return; }
                showConnCode();
            } else {
                try {
                    mqttManager.stop();
                } catch (Exception ignored) {}
                mqttConfigBody.setVisibility(View.GONE);
                mqttCodeSection.setVisibility(View.GONE);
            }
            updateMqttStatus();
        });

        View.OnFocusChangeListener onBlur = (v2, hasFocus) -> {
            if (!hasFocus && swMqttEnabled.isChecked()) {
                saveMqttConfig();
                mqttManager.restart();
                showConnCode();
                updateMqttStatus();
            }
        };
        etMqttBroker.setOnFocusChangeListener(onBlur);
        etMqttPort.setOnFocusChangeListener(onBlur);
        etMqttTopic.setOnFocusChangeListener(onBlur);

        btnCopyCode.setOnClickListener(v2 -> {
            String code = tvMqttCode.getText().toString();
            if (TextUtils.isEmpty(code)) return;
            ((ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("心跳连接码", code));
            Toast.makeText(getActivity(), "📋 已复制", Toast.LENGTH_SHORT).show();
        });

        btnShareCode.setOnClickListener(v2 -> {
            String code = tvMqttCode.getText().toString();
            if (TextUtils.isEmpty(code)) return;
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT,
                    "❤️ 心迹 · MQTT 连接码\n\n" + code + "\n\n电脑端粘贴此码自动连接");
            startActivity(Intent.createChooser(share, "分享连接码"));
        });

        btnMqttTest.setOnClickListener(v2 -> {
            saveMqttConfig();
            tvMqttStatus.setText("⏳ 测试连接中...");
            mqttManager.testConnection(new MqttManager.MqttListener() {
                @Override public void onConnected() {
                    handler.post(() -> tvMqttStatus.setText("✅ Broker 连接正常"));
                }
                @Override public void onDisconnected() {}
                @Override public void onError(String msg) {
                    handler.post(() -> tvMqttStatus.setText("❌ " + msg));
                }
            });
        });

        btnMqttHelp.setOnClickListener(v2 -> showMqttHelp());

        mqttManager.setListener(new MqttManager.MqttListener() {
            @Override public void onConnected() { handler.post(SettingsFragment.this::updateMqttStatus); }
            @Override public void onDisconnected() { handler.post(SettingsFragment.this::updateMqttStatus); }
            @Override public void onError(String msg) { handler.post(() -> tvMqttStatus.setText("⚠️ " + msg)); }
        });

        // 定时刷新发送统计
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mqttManager.isRunning()) updateMqttStatus();
                handler.postDelayed(this, 2000);
            }
        }, 2000);

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

    // ==================== MQTT 辅助 ====================

    private void saveMqttConfig() {
        String host = etMqttBroker.getText().toString().trim();
        String portStr = etMqttPort.getText().toString().trim();
        String t = etMqttTopic.getText().toString().trim();
        if (TextUtils.isEmpty(host)) host = "broker-cn.emqx.io";
        if (TextUtils.isEmpty(portStr)) portStr = "1883";
        if (TextUtils.isEmpty(t)) t = "heart/rate";
        int port = 1883;
        try { port = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}
        mqttManager.setConfig(host, port, t);
    }

    private void showConnCode() {
        String code = mqttManager.generateConnectionCode();
        if (!TextUtils.isEmpty(code)) {
            tvMqttCode.setText(code);
            mqttCodeSection.setVisibility(View.VISIBLE);
        }
    }

    private void showMqttHelp() {
        String[] options = {"🌐 公共免费 Broker", "🏗️ 自建 / 云平台 Broker"};
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("📖 MQTT Broker 选择指南")
            .setItems(options, (dialog, which) -> {
                if (which == 0) showPublicBrokerHelp();
                else showSelfBuiltBrokerHelp();
            })
            .setPositiveButton("关闭", null)
            .show();
    }

    private void showPublicBrokerHelp() {
        String text = "🌐 公共免费 Broker（无需注册，即开即用）\n\n"
            + "心迹默认使用 EMQX 国内公共节点，完全免费：\n\n"
            + "■ broker-cn.emqx.io:1883（国内，延迟低 ✅）\n"
            + "■ broker.emqx.io:1883（全球集群）\n"
            + "■ test.mosquitto.org:1883（Eclipse 基金会）\n\n"
            + "⚠️ 注意：\n"
            + "• 公共 Broker 所有消息公开可见，请勿传输敏感数据\n"
            + "• 适合测试、个人使用、直播分享\n\n"
            + "🔍 连不上怎么办？\n"
            + "• 点「测试连接」按钮检测 Broker 是否可达\n"
            + "• 如果测试成功但仍收不到数据 → 可能是防火墙拦截\n"
            + "• 用同目录下的 mqtt.html 诊断：\n"
            + "  浏览器打开 mqtt.html，粘贴连接码\n"
            + "  如果能收到 → 说明 exe/py 有问题，重新下载\n"
            + "  如果收不到 → 检查防火墙或更换 Broker\n\n"
            + "💡 提示：\n"
            + "直接在下方 Broker 输入框填写地址即可使用\n"
            + "公共 Broker 无需用户名密码";
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("🌐 公共免费 Broker")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .show();
    }

    private void showSelfBuiltBrokerHelp() {
        String text = "🏗️ 自建 Broker 或使用云平台\n\n"
            + "如果你需要私有部署、更高性能或生产环境：\n\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "一、Docker 自建 EMQX（推荐）\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "有 Docker 环境的话一行命令：\n"
            + "  docker run -d --name emqx \\\n"
            + "    -p 1883:1883 -p 8083:8083 \\\n"
            + "    -p 18083:18083 \\\n"
            + "    emqx/emqx:latest\n\n"
            + "• 管理后台：http://服务器IP:18083\n"
            + "• 默认账号：admin / public\n"
            + "• 数据不出局域网，完全私有\n\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "二、Windows 直接安装 EMQX\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "1. 下载 https://www.emqx.com/downloads\n"
            + "2. 解压，进入 bin 目录运行 emqx start\n"
            + "3. 管理后台 http://localhost:18083\n\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "三、阿里云微消息队列 MQTT\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "国内正式使用首选，延迟最低：\n"
            + "• 5元/月起（100万条消息/月）\n"
            + "• 地址：https://www.aliyun.com/product/mq\n"
            + "• 创建实例 → 获取接入点 → 创建 Topic\n"
            + "• 将接入点填入下方 Broker 框\n\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "四、华为云 IoTDA / EMQX Cloud\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "• 华为云：100万条/月免费\n"
            + "  https://www.huaweicloud.com/product/iothub.html\n"
            + "• EMQX Cloud：Serverless 免费额度\n"
            + "  https://www.emqx.com/cloud\n\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "📌 配置方式：\n"
            + "━━━━━━━━━━━━━━━━━━━━━━\n"
            + "1. 将 Broker 地址填入上方「Broker」框\n"
            + "2. 填入对应的端口号\n"
            + "3. 填入 Topic 名称（如 heart/rate）\n"
            + "4. 点击「测试连接」验证\n"
            + "5. 开启开关即可使用\n\n"
            + "💡 如果以上方案都不熟悉，直接用默认公共 Broker\n"
            + "🤖 详细配置可问 AI 或联系作者";
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle("🏗️ 自建 / 云平台 Broker")
            .setMessage(text)
            .setPositiveButton("知道了", null)
            .setNegativeButton("不懂就问 AI", (d, w) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.baidu.com/s?wd=MQTT+Broker+%E9%85%8D%E7%BD%AE%E6%95%99%E7%A8%8B")));
                } catch (Exception ignored) {}
            })
            .show();
    }

    private void updateMqttStatus() {
        if (mqttManager.isRunning()) {
            String hc = "";
            long count = mqttManager.getPublishCount();
            long lastTime = mqttManager.getLastPublishTime();
            if (count > 0) {
                long ago = (System.currentTimeMillis() - lastTime) / 1000;
                hc = " · 已发送" + count + "条";
                if (ago > 10) hc += " · 最后发送" + ago + "秒前";
            } else if (mqttManager.isPublishFailed()) {
                hc = " · ⚠️ 发送失败";
            }
            tvMqttStatus.setText("✅ 运行中" + hc);
        } else {
            tvMqttStatus.setText("⏹ 未启动");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
        mqttManager.setListener(null);
    }
}
