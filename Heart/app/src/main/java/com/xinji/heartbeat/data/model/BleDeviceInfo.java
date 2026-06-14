package com.xinji.heartbeat.data.model;
import java.util.ArrayList;
import java.util.List;

public class BleDeviceInfo {
    public String name;
    public String address;
    public int rssi;
    public List<String> serviceUuids = new ArrayList<>();

    public BleDeviceInfo(String name, String address, int rssi) {
        this.name = name != null ? name : "未知设备";
        this.address = address;
        this.rssi = rssi;
    }

    public BleDeviceInfo(String name, String address, int rssi, List<String> serviceUuids) {
        this.name = name != null ? name : "未知设备";
        this.address = address;
        this.rssi = rssi;
        if (serviceUuids != null) this.serviceUuids = serviceUuids;
    }

    public String getFormattedRssi() {
        int bars;
        if (rssi >= -50) bars = 5;
        else if (rssi >= -65) bars = 4;
        else if (rssi >= -80) bars = 3;
        else if (rssi >= -90) bars = 2;
        else if (rssi >= -100) bars = 1;
        else bars = 0;
        return bars + "/5📶";
    }

    public boolean isWearable() {
        if (name == null || name.isEmpty() || "未知设备".equals(name)) return false;
        String n = name.toLowerCase();
        return n.contains("heart") || n.contains("watch") || n.contains("band")
            || n.contains("mi ") || n.contains("xiaomi") || n.contains("小米")
            || n.contains("华为") || n.contains("huawei")
            || n.contains("honor") || n.contains("荣耀")
            || n.contains("三星") || n.contains("samsung")
            || n.contains("oppo") || n.contains("vivo")
            || n.contains("iqoo");
    }

    public int getPriority() {
        if (name == null || name.isEmpty() || "未知设备".equals(name)) return 0;
        String n = name.toLowerCase();
        if (n.contains("heart")) return 100;
        if (n.contains("watch") || n.contains("band")) return 80;
        if (n.contains("mi ") || n.contains("xiaomi") || n.contains("小米")
            || n.contains("华为") || n.contains("huawei")
            || n.contains("honor") || n.contains("荣耀")
            || n.contains("三星") || n.contains("samsung")
            || n.contains("oppo") || n.contains("vivo")
            || n.contains("iqoo")) return 70;
        return 50;
    }

    public String getIcon() {
        if (name == null) return "📡";
        String lower = name.toLowerCase();
        if (lower.contains("heart") || lower.contains("watch") || lower.contains("band")) return "❤️";
        if (lower.contains("mi") || lower.contains("xiaomi") || lower.contains("小米")) return "⌚";
        if (lower.contains("iqoo") || lower.contains("vivo")) return "⌚";
        return "📡";
    }
}
