package com.xinji.heartbeat.app;
import android.content.Context;
import android.content.SharedPreferences;

public class PreferencesManager {
    private static final String PREFS_APP = "app_settings";
    private static final String PREFS_BLE = "ble_prefs";
    private static final String PREFS_GAME = "game_mode";
    private static final String PREFS_GAME_RECORDS = "game_records";
    private static final String PREFS_FLOAT_POS = "float_pos";

    private final SharedPreferences appPrefs;
    private final SharedPreferences blePrefs;
    private final SharedPreferences gamePrefs;
    private final SharedPreferences gameRecordPrefs;
    private final SharedPreferences floatPosPrefs;

    private static PreferencesManager instance;

    private PreferencesManager(Context context) {
        Context app = context.getApplicationContext();
        appPrefs = app.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE);
        blePrefs = app.getSharedPreferences(PREFS_BLE, Context.MODE_PRIVATE);
        gamePrefs = app.getSharedPreferences(PREFS_GAME, Context.MODE_PRIVATE);
        gameRecordPrefs = app.getSharedPreferences(PREFS_GAME_RECORDS, Context.MODE_PRIVATE);
        floatPosPrefs = app.getSharedPreferences(PREFS_FLOAT_POS, Context.MODE_PRIVATE);
    }

    public static synchronized PreferencesManager init(Context context) {
        if (instance == null) {
            instance = new PreferencesManager(context);
        }
        return instance;
    }

    public static PreferencesManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("PreferencesManager 尚未初始化");
        }
        return instance;
    }

    public static PreferencesManager from(Context context) {
        if (instance == null) init(context);
        return instance;
    }

    public boolean getAutoConnect() { return appPrefs.getBoolean("auto_connect", false); }
    public void setAutoConnect(boolean v) { appPrefs.edit().putBoolean("auto_connect", v).apply(); }

    public boolean getFloatMemory() { return appPrefs.getBoolean("float_memory", true); }
    public void setFloatMemory(boolean v) { appPrefs.edit().putBoolean("float_memory", v).apply(); }

    public boolean getFloatVisible() { return appPrefs.getBoolean("float_visible", false); }
    public void setFloatVisible(boolean v) { appPrefs.edit().putBoolean("float_visible", v).apply(); }

    public boolean getFloatLocked() { return appPrefs.getBoolean("float_locked", false); }
    public void setFloatLocked(boolean v) { appPrefs.edit().putBoolean("float_locked", v).apply(); }

    public int getFloatStyle() { return appPrefs.getInt("float_style", 0); }
    public void setFloatStyle(int v) { appPrefs.edit().putInt("float_style", v).apply(); }

    public int getFloatStyleBeforeGame() { return appPrefs.getInt("float_style_before_game", 0); }
    public void setFloatStyleBeforeGame(int v) { appPrefs.edit().putInt("float_style_before_game", v).apply(); }

    public boolean getBroadcastEnabled() { return appPrefs.getBoolean("broadcast_enabled", false); }
    public void setBroadcastEnabled(boolean v) { appPrefs.edit().putBoolean("broadcast_enabled", v).apply(); }

    public int getBroadcastPort() { return appPrefs.getInt("broadcast_port", 9090); }
    public void setBroadcastPort(int v) { appPrefs.edit().putInt("broadcast_port", v).apply(); }

    public boolean isOnboardingDone() { return appPrefs.getBoolean("onboarding_done", false); }
    public void setOnboardingDone(boolean v) { appPrefs.edit().putBoolean("onboarding_done", v).apply(); }

    public boolean isHideFromRecents() { return appPrefs.getBoolean("hide_from_recents", false); }
    public void setHideFromRecents(boolean v) { appPrefs.edit().putBoolean("hide_from_recents", v).apply(); }

    public String getLastDeviceName() { return blePrefs.getString("last_device_name", ""); }
    public void setLastDeviceName(String v) { blePrefs.edit().putString("last_device_name", v != null ? v : "").apply(); }

    public String getLastDeviceAddress() { return blePrefs.getString("last_device_address", ""); }
    public void setLastDeviceAddress(String v) { blePrefs.edit().putString("last_device_address", v != null ? v : "").apply(); }

    public void saveLastDevice(String name, String address) {
        blePrefs.edit()
            .putString("last_device_name", name != null ? name : "")
            .putString("last_device_address", address != null ? address : "")
            .apply();
    }

    public int getFloatX(int def) { return floatPosPrefs.getInt("float_x", def); }
    public void setFloatX(int v) { floatPosPrefs.edit().putInt("float_x", v).apply(); }

    public int getFloatY(int def) { return floatPosPrefs.getInt("float_y", def); }
    public void setFloatY(int v) { floatPosPrefs.edit().putInt("float_y", v).apply(); }

    public void saveFloatPos(int x, int y) {
        floatPosPrefs.edit().putInt("float_x", x).putInt("float_y", y).apply();
    }

    public SharedPreferences getGamePrefs() { return gamePrefs; }
    public SharedPreferences getGameRecordPrefs() { return gameRecordPrefs; }

    public String getGameRecord(String key) { return gameRecordPrefs.getString(key, ""); }
    public void setGameRecord(String key, String v) { gameRecordPrefs.edit().putString(key, v).apply(); }

    public java.util.Map<String, ?> getAllGameRecords() { return gameRecordPrefs.getAll(); }
    public java.util.Map<String, ?> getAllManualRecords() { return gamePrefs.getAll(); }

    public SharedPreferences getAppPrefs() { return appPrefs; }

    public void saveManualRecord(long startTime, long endTime, String data) {
        String key = "record_" + startTime;
        gamePrefs.edit().putString(key, startTime + "|" + endTime + "|" + data).apply();
    }
}
