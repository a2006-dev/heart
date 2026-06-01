package com.xinji.heartbeat.core;

import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.*;

public class GameModeService extends Service {
    private static final String TAG = "GameModeService";
    private UsageStatsManager usageStatsManager;
    private CheckHandler checkHandler;
    private SharedPreferences gamePrefs, recordPrefs;
    private boolean isGameRunning = false;
    private String currentGamePackage = null, currentGameName = "";
    private long gameStartTime = 0;

    private static class CheckHandler extends Handler {
        final WeakReference<GameModeService> serviceRef;
        CheckHandler(GameModeService s) { super(Looper.getMainLooper()); this.serviceRef = new WeakReference<>(s); }
    }

    private static final int MAX_HR_RECORDS = 1800;
    private final int[] hrBuffer = new int[MAX_HR_RECORDS];
    private int hrBufferCount = 0, hrBufferIndex = 0;
    private int currentHR = 0;
    private HeartRateManager.HeartRateListener myListener;
    private static final long GAME_EXIT_TIMEOUT_MS = 2 * 60 * 1000L;
    private long gameLostFocusTime = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        gamePrefs = getSharedPreferences("game_mode", Context.MODE_PRIVATE);
        recordPrefs = getSharedPreferences("game_records", Context.MODE_PRIVATE);
        checkHandler = new CheckHandler(this);
        myListener = hr -> {
            currentHR = hr;
            if (isGameRunning && hr > 0) {
                hrBuffer[hrBufferIndex] = hr;
                hrBufferIndex = (hrBufferIndex + 1) % MAX_HR_RECORDS;
                if (hrBufferCount < MAX_HR_RECORDS) hrBufferCount++;
            }
        };
        HeartRateManager.getInstance(this).registerListener(myListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.xinji.heartbeat.STOP_GAME_RECORDING".equals(intent.getAction())) {
            if (isGameRunning) stopGameRecording();
            return START_STICKY;
        }
        startChecking();
        return START_STICKY;
    }

    /** 发送广播让 HeartRateService 切换常驻通知 */
    private void updateNotif(boolean inGame, String text) {
        Intent i = new Intent("com.xinji.heartbeat.UPDATE_NOTIFICATION");
        i.setPackage(getPackageName());
        i.putExtra("in_game", inGame);
        i.putExtra("text", text != null ? text : "");
        if (inGame && currentGameName != null) i.putExtra("game_name", currentGameName);
        sendBroadcast(i);
    }

    private void startChecking() {
        if (checkHandler == null) return;
        WeakReference<GameModeService> ref = checkHandler.serviceRef;
        checkHandler.postDelayed(new Runnable() {
            @Override public void run() {
                GameModeService s = ref.get();
                if (s == null) return;
                s.checkForegroundApp();
                s.checkHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private Set<String> getSelectedPackages() {
        String raw = gamePrefs.getString("selected_games_str", "");
        if (!raw.isEmpty()) {
            String[] parts = raw.split(",");
            Set<String> r = new HashSet<>();
            for (String p : parts) if (!p.isEmpty()) r.add(p);
            return r;
        }
        return new HashSet<>(gamePrefs.getStringSet("selected_games", new HashSet<>()));
    }

    private void checkForegroundApp() {
        if (usageStatsManager == null) return;
        String top = getTopPackageName();
        Set<String> games = getSelectedPackages();
        boolean inGame = top != null && games.contains(top);
        boolean cont = gamePrefs.getBoolean("game_continue_in_window", true);
        if (inGame) {
            if (!isGameRunning || !top.equals(currentGamePackage)) startGameRecording(top);
            else gameLostFocusTime = -1;
        } else if (isGameRunning) {
            if (cont) { if (isGameReallyExited()) stopGameRecording(); }
            else stopGameRecording();
        }
    }

    private boolean isGameReallyExited() {
        if (currentGamePackage == null) return true;
        long now = System.currentTimeMillis();
        if (gameLostFocusTime < 0) { gameLostFocusTime = now; return false; }

        // 检查进程是否还在运行
        boolean alive = false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<android.app.ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
                if (procs != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo p : procs) {
                        if (currentGamePackage.equals(p.processName) ||
                            (p.pkgList != null && Arrays.asList(p.pkgList).contains(currentGamePackage))) {
                            alive = true; break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (alive) {
            // 进程还在，说明只是切到后台/小窗，给2分钟缓冲
            if (now - gameLostFocusTime < GAME_EXIT_TIMEOUT_MS) return false;
            return true;
        }

        // 进程不在，查 UsageEvents 确认
        try {
            UsageEvents events = usageStatsManager.queryEvents(now - 10000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.getNextEvent(e)) {
                if (!currentGamePackage.equals(e.getPackageName())) continue;
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    gameLostFocusTime = -1;
                    return false;
                }
            }
        } catch (Exception ignored) {}

        if (now - gameLostFocusTime < GAME_EXIT_TIMEOUT_MS) return false;
        return true;
    }

    private String topPkgCached = null;
    private long topPkgCacheTime = 0;

    private String getTopPackageName() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null;
        long now = System.currentTimeMillis();
        if (topPkgCached != null && now - topPkgCacheTime < 500) return topPkgCached;
        UsageEvents events = usageStatsManager.queryEvents(now - 15000, now);
        String top = null; long last = 0;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.getNextEvent(e)) {
            int t = e.getEventType();
            if ((t == UsageEvents.Event.MOVE_TO_FOREGROUND || t == UsageEvents.Event.ACTIVITY_RESUMED) && e.getTimeStamp() > last) {
                last = e.getTimeStamp(); top = e.getPackageName();
            }
        }
        topPkgCached = top; topPkgCacheTime = now;
        return top;
    }

    private void startGameRecording(String pkg) {
        if (isGameRunning && pkg.equals(currentGamePackage)) return;
        isGameRunning = true; currentGamePackage = pkg; gameStartTime = System.currentTimeMillis();
        hrBufferCount = 0; hrBufferIndex = 0; gameLostFocusTime = -1;
        try { currentGameName = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { currentGameName = pkg; }
        Log.d(TAG, "开始记录游戏：" + currentGameName);
        updateNotif(true, "记录中：" + currentGameName);
        notifyGameState(true);
    }

    private void stopGameRecording() {
        if (!isGameRunning) return;
        long end = System.currentTimeMillis(), dur = end - gameStartTime;
        if (dur >= 3 * 60 * 1000 && hrBufferCount > 0) saveRecord(currentGamePackage, gameStartTime, end);
        isGameRunning = false; currentGamePackage = null; currentGameName = "";
        hrBufferCount = 0; hrBufferIndex = 0; gameLostFocusTime = -1;
        updateNotif(false, "心率监测中");
        notifyGameState(false);
    }

    private void saveRecord(String pkg, long start, long end) {
        StringBuilder sb = new StringBuilder();
        sb.append(start).append("|").append(end).append("|");
        int count = hrBufferCount, si = hrBufferIndex - count;
        if (si < 0) si += MAX_HR_RECORDS;
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append(hrBuffer[(si + i) % MAX_HR_RECORDS]);
        }
        String data = sb.toString();
        if (data.length() > 500 * 1024) { int p = data.lastIndexOf(",", 500 * 1024); if (p > 0) data = data.substring(0, p); else data = data.substring(0, 500 * 1024); }
        recordPrefs.edit().putString(pkg + "_latest", data).apply();
        String hk = pkg + "_history", hr = recordPrefs.getString(hk, "");
        String[] parts = hr.isEmpty() ? new String[0] : hr.split(";;");
        StringBuilder hsb = new StringBuilder();
        int k = Math.min(parts.length, 4);
        for (int i = parts.length - k; i < parts.length; i++) { if (hsb.length() > 0) hsb.append(";;"); hsb.append(parts[i]); }
        if (hsb.length() > 0) hsb.append(";;");
        hsb.append(start).append("|").append(end);
        recordPrefs.edit().putString(hk, hsb.toString()).apply();
    }

    private void notifyGameState(boolean inGame) {
        try {
            Intent i = new Intent("com.xinji.heartbeat.GAME_STATE");
            i.setPackage(getPackageName()); i.putExtra("in_game", inGame);
            sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        checkHandler.removeCallbacksAndMessages(null);
        if (myListener != null) HeartRateManager.getInstance(this).removeListener(myListener);
        updateNotif(false, "心率监测中");
    }
}