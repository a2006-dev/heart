package com.xinji.heartbeat.common.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用配置管理中心 — 统一管理所有 SharedPreferences
 * 
 * 职责：
 * - 隐藏 SharedPreferences 细节
 * - 提供类型安全的 getter/setter
 * - 支持默认值
 * - 支持事务式更新
 * 
 * 使用示例：
 * AppConfig config = AppConfig.getInstance(context);
 * config.setAutoConnectEnabled(true);
 * boolean enabled = config.isAutoConnectEnabled();
 */
public class AppConfig {
    private static volatile AppConfig instance;
    private final SharedPreferences appSettings;   // app_settings
    private final SharedPreferences blePrefs;      // ble_prefs
    private final SharedPreferences floatPos;      // float_pos

    private AppConfig(Context context) {
        Context appContext = context.getApplicationContext();
        this.appSettings = appContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        this.blePrefs = appContext.getSharedPreferences("ble_prefs", Context.MODE_PRIVATE);
        this.floatPos = appContext.getSharedPreferences("float_pos", Context.MODE_PRIVATE);
    }

    public static AppConfig getInstance(Context context) {
        if (instance == null) {
            synchronized (AppConfig.class) {
                if (instance == null) {
                    instance = new AppConfig(context);
                }
            }
        }
        return instance;
    }

    // ===================== 蓝牙配置 =====================

    public boolean isAutoConnectEnabled() {
        return appSettings.getBoolean("auto_connect", false);
    }

    public void setAutoConnectEnabled(boolean enabled) {
        appSettings.edit().putBoolean("auto_connect", enabled).apply();
    }

    public String getLastDeviceName() {
        return blePrefs.getString("last_device_name", "");
    }

    public void setLastDeviceName(String name) {
        blePrefs.edit().putString("last_device_name", name != null ? name : "").apply();
    }

    public String getLastDeviceAddress() {
        return blePrefs.getString("last_device_address", "");
    }

    public void setLastDeviceAddress(String address) {
        blePrefs.edit().putString("last_device_address", address != null ? address : "").apply();
    }

    // ===================== 悬浮窗配置 =====================

    public boolean isFloatMemoryEnabled() {
        return appSettings.getBoolean("float_memory", true);
    }

    public void setFloatMemoryEnabled(boolean enabled) {
        appSettings.edit().putBoolean("float_memory", enabled).apply();
    }

    public boolean isFloatLocked() {
        return appSettings.getBoolean("float_locked", false);
    }

    public void setFloatLocked(boolean locked) {
        appSettings.edit().putBoolean("float_locked", locked).apply();
    }

    public boolean isFloatVisible() {
        return appSettings.getBoolean("float_visible", false);
    }

    public void setFloatVisible(boolean visible) {
        appSettings.edit().putBoolean("float_visible", visible).apply();
    }

    public int getFloatStyle() {
        return appSettings.getInt("float_style", 0);
    }

    public void setFloatStyle(int style) {
        appSettings.edit().putInt("float_style", style).apply();
    }

    public int getFloatStyleBeforeGame() {
        return appSettings.getInt("float_style_before_game", 0);
    }

    public void setFloatStyleBeforeGame(int style) {
        appSettings.edit().putInt("float_style_before_game", style).apply();
    }

    public int getFloatX() {
        return floatPos.getInt("float_x", -1);
    }

    public void setFloatX(int x) {
        floatPos.edit().putInt("float_x", x).apply();
    }

    public int getFloatY() {
        return floatPos.getInt("float_y", -1);
    }

    public void setFloatY(int y) {
        floatPos.edit().putInt("float_y", y).apply();
    }

    // ===================== 广播服务器配置 =====================

    public boolean isBroadcastEnabled() {
        return appSettings.getBoolean("broadcast_enabled", false);
    }

    public void setBroadcastEnabled(boolean enabled) {
        appSettings.edit().putBoolean("broadcast_enabled", enabled).apply();
    }

    public int getBroadcastPort() {
        return appSettings.getInt("broadcast_port", 9090);
    }

    public void setBroadcastPort(int port) {
        appSettings.edit().putInt("broadcast_port", port).apply();
    }

    // ===================== 事务性操作 =====================

    /**
     * 批量更新配置
     */
    public void beginTransaction() {
        // 可用于支持事务，当前不实现
    }

    public void commit() {
        // 可用于支持事务，当前不实现
    }
}
