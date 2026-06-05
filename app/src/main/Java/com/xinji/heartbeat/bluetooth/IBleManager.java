package com.xinji.heartbeat.bluetooth;

/**
 * 蓝牙管理器接口 — 定义所有蓝牙相关操作
 * 
 * 目的：
 * - 模块解耦
 * - 便于测试和依赖注入
 * - 支持多实现（可以有 MockBleManager）
 */
public interface IBleManager {
    // ===================== 状态查询 =====================
    boolean isAvailable();
    boolean isConnected();
    String getCurrentDeviceName();
    String getCurrentDeviceAddress();
    boolean hasRequiredPermissions();
    boolean isAutoConnectEnabled();

    // ===================== 权限 =====================
    void requestPermissions();

    // ===================== 扫描 =====================
    void setBroadScan(boolean enabled);
    void startManualScan();
    void stopManualScan();
    void startAutoScan();
    void stopAutoScan();
    void stopAllScan();

    // ===================== 连接 =====================
    void connectToDevice(String address, String name);
    void connectAndFilterServices(String address, String name, BleManager.FilterCallback callback);
    void disconnect();
    void setAutoConnectEnabled(boolean enabled);

    // ===================== 持久化 =====================
    void saveLastDevice(String name, String address);
    String getLastDeviceName();
    String getLastDeviceAddress();

    // ===================== 生命周期 =====================
    void destroy();
}
