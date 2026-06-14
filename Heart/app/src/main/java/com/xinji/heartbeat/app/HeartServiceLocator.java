package com.xinji.heartbeat.app;
import android.app.Activity;
import android.content.Context;

import com.xinji.heartbeat.bluetooth.BleManager;
import com.xinji.heartbeat.core.DeviceProfileManager;
import com.xinji.heartbeat.core.HeartRateManager;
import com.xinji.heartbeat.mqtt.MqttManager;
import com.xinji.heartbeat.server.BroadcastServer;
import com.xinji.heartbeat.widget.FloatWindowManager;

public class HeartServiceLocator {
    private static volatile HeartServiceLocator instance;
    private final Context appContext;

    private volatile BleManager bleManager;
    private volatile BroadcastServer broadcastServer;
    private volatile HeartRateManager heartRateManager;
    private volatile DeviceProfileManager deviceProfileManager;

    private volatile MqttManager mqttManager;

    private volatile FloatWindowManager floatWindowManager;
    private volatile Activity activityRef;

    public boolean autoConnectEnabled = false;
    public boolean floatMemoryEnabled = true;
    public boolean broadcastEnabled = false;

    
    private int bleErrorCount = 0;
    private int serverErrorCount = 0;

    private HeartServiceLocator(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static synchronized HeartServiceLocator init(Context context) {
        if (instance == null) {
            instance = new HeartServiceLocator(context);
        }
        return instance;
    }

    public static HeartServiceLocator getInstance() {
        if (instance == null) {
            throw new IllegalStateException("HeartServiceLocator 未初始化");
        }
        return instance;
    }

    public static HeartServiceLocator from(Context context) {
        if (instance == null) init(context);
        return instance;
    }

    public void setActivity(Activity activity) {
        this.activityRef = activity;
        
        if (floatWindowManager != null && activity != null) {
            try {
                floatWindowManager = new FloatWindowManager(activity);
                floatWindowManager.setMemoryEnabled(floatMemoryEnabled);
            } catch (Exception ignored) {}
        }
    }

    public BleManager getBleManager() {
        try {
            if (bleManager == null) {
                synchronized (this) {
                    if (bleManager == null) {
                        bleManager = new BleManager(appContext);
                        bleManager.setAutoConnectEnabled(autoConnectEnabled);
                    }
                }
            }
            bleErrorCount = 0;
            return bleManager;
        } catch (Exception e) {
            bleErrorCount++;
            return null;
        }
    }

    public FloatWindowManager getFloatWindowManager() {
        try {
            if (floatWindowManager == null && activityRef != null && !activityRef.isFinishing()) {
                floatWindowManager = new FloatWindowManager(activityRef);
                floatWindowManager.setMemoryEnabled(floatMemoryEnabled);
            }
            return floatWindowManager;
        } catch (Exception e) {
            return null;
        }
    }

    public BroadcastServer getBroadcastServer() {
        try {
            if (broadcastServer == null) {
                synchronized (this) {
                    if (broadcastServer == null) {
                        broadcastServer = new BroadcastServer(appContext);
                    }
                }
            }
            serverErrorCount = 0;
            return broadcastServer;
        } catch (Exception e) {
            serverErrorCount++;
            return null;
        }
    }

    public MqttManager getMqttManager() {
        if (mqttManager == null) {
            synchronized (this) {
                if (mqttManager == null) {
                    mqttManager = MqttManager.getInstance(appContext);
                }
            }
        }
        return mqttManager;
    }

    public HeartRateManager getHeartRateManager() {
        if (heartRateManager == null) {
            heartRateManager = HeartRateManager.getInstance(appContext);
        }
        return heartRateManager;
    }

    public DeviceProfileManager getDeviceProfileManager() {
        if (deviceProfileManager == null) {
            deviceProfileManager = DeviceProfileManager.getInstance(appContext);
        }
        return deviceProfileManager;
    }

    public Context getContext() { return appContext; }
    public int getBleErrorCount() { return bleErrorCount; }
    public int getServerErrorCount() { return serverErrorCount; }
}
