package com.xinji.heartbeat.common.event;

import java.util.List;

/**
 * 蓝牙相关的事件定义
 */
public class BleEvents {
    /**
     * 扫描结果更新
     */
    public static class ScanResultEvent extends Event {
        public List<?> devices; // List<BleManager.ScanDeviceInfo>
        
        public ScanResultEvent(List<?> devices) {
            this.devices = devices;
        }
    }

    /**
     * 扫描失败
     */
    public static class ScanFailedEvent extends Event {
        public int errorCode;
        
        public ScanFailedEvent(int errorCode) {
            this.errorCode = errorCode;
        }
    }

    /**
     * 扫描停止
     */
    public static class ScanStoppedEvent extends Event {}

    /**
     * 正在连接
     */
    public static class ConnectingEvent extends Event {
        public String deviceName;
        
        public ConnectingEvent(String deviceName) {
            this.deviceName = deviceName;
        }
    }

    /**
     * 连接成功
     */
    public static class ConnectedEvent extends Event {
        public String deviceName;
        public String deviceAddress;
        
        public ConnectedEvent(String deviceName, String deviceAddress) {
            this.deviceName = deviceName;
            this.deviceAddress = deviceAddress;
        }
    }

    /**
     * 断开连接
     */
    public static class DisconnectedEvent extends Event {
        public String deviceName;
        
        public DisconnectedEvent(String deviceName) {
            this.deviceName = deviceName;
        }
    }

    /**
     * 心率更新
     */
    public static class HeartRateUpdateEvent extends Event {
        public int heartRate;
        
        public HeartRateUpdateEvent(int heartRate) {
            this.heartRate = heartRate;
        }
    }

    /**
     * 连接失败
     */
    public static class ConnectionFailedEvent extends Event {
        public String reason;
        
        public ConnectionFailedEvent(String reason) {
            this.reason = reason;
        }
    }

    /**
     * 蓝牙不可用
     */
    public static class BleNotAvailableEvent extends Event {
        public String reason;
        
        public BleNotAvailableEvent(String reason) {
            this.reason = reason;
        }
    }
}
