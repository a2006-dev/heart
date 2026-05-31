package com.xinji.heartbeat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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

import java.util.*;

/**
 * 游戏模式检测服务 — 自动识别游戏前台并记录心率。
 * 蓝牙 BLE 连接模块参考了 https://github.com/milirstudio/xinxiu（心宿）
 */
public class GameModeService extends Service {
    private static final String TAG = "GameModeService";
    private UsageStatsManager usageStatsManager;
    private Handler checkHandler;
    private SharedPreferences gamePrefs;
    private SharedPreferences recordPrefs;
    private boolean isGameRunning = false;
    private String currentGamePackage = null;
    private long gameStartTime = 0;
    // 使用 ArrayList 并控制容量，防止 OOM
    private ArrayList<Integer> hrRecord = new ArrayList<>(600);
    private Handler recordHandler;
    private int currentHR = 0;
    // 最多保留 10 分钟数据（600个点 @ 1次/秒），节省内存
    private static final int MAX_HR_RECORDS = 600;
    private int hrRecordCounter = 0;
    private HeartRateManager.HeartRateListener myListener;
    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        gamePrefs = getSharedPreferences("game_mode", Context.MODE_PRIVATE);
        recordPrefs = getSharedPreferences("game_records", Context.MODE_PRIVATE);
        checkHandler = new Handler(Looper.getMainLooper());
        recordHandler = new Handler(Looper.getMainLooper());
        myListener = hr -> {
            currentHR = hr;
            if (isGameRunning && hr > 0) {
                hrRecordCounter++;
                if (hrRecord.size() < MAX_HR_RECORDS || hrRecordCounter % 2 == 0) {
                    hrRecord.add(hr);
                }
            }
        };
        HeartRateManager.getInstance(this).registerListener(myListener);
    }
    private static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "game_mode_service";
    private NotificationManager notificationManager;
    private Notification.Builder notificationBuilder;
    private String currentGameName = "";
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.xinji.heartbeat.STOP_GAME_RECORDING".equals(intent.getAction())) {
            if (isGameRunning) {
                stopGameRecording();
            }
            updateNotification(false, "已停止记录", 0);
            return START_STICKY;
        }
        initNotification();
        startChecking();
        return START_STICKY;
    }
    private void initNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "心迹服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("心率监测后台运行");
        if (notificationManager != null) notificationManager.createNotificationChannel(channel);
        updateNotification(false, "心率监测中", 0);
    }
    private void updateNotification(boolean inGame, String text, int hr) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tapPending = PendingIntent.getActivity(this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(tapPending)
                .setOngoing(true);
        if (inGame) {
            builder.setContentTitle("🎮 游戏模式")
                   .setContentText("记录中：" + currentGameName);
            Intent stopIntent = new Intent(this, GameModeService.class);
            stopIntent.setAction("com.xinji.heartbeat.STOP_GAME_RECORDING");
            PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止记录", stopPending);
        } else {
            builder.setContentTitle("❤️ 心迹")
                   .setContentText(text);
        }
        int[] serviceTypes;
        if (Build.VERSION.SDK_INT >= 34) {
            serviceTypes = new int[]{android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE};
        } else if (Build.VERSION.SDK_INT >= 29) {
            serviceTypes = new int[]{android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, 0};
        } else {
            serviceTypes = new int[]{0};
        }
        boolean started = false;
        for (int type : serviceTypes) {
            try {
                if (type != 0) {
                    startForeground(NOTIFICATION_ID, builder.build(), type);
                } else {
                    startForeground(NOTIFICATION_ID, builder.build());
                }
                started = true;
                break;
            } catch (Exception ignored) {}
        }
        if (!started) {
            try {
                startForeground(NOTIFICATION_ID, builder.build());
            } catch (Exception e) {
                Log.w(TAG, "前台服务启动失败，降级运行");
            }
        }
    }
    private void startChecking() {
        checkHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkForegroundApp();
                checkHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }
    private Set<String> getSelectedPackages() {
        String raw = gamePrefs.getString("selected_games_str", "");
        if (!raw.isEmpty()) {
            String[] parts = raw.split(",");
            Set<String> result = new HashSet<>();
            for (String p : parts) {
                if (!p.isEmpty()) result.add(p);
            }
            return result;
        }
        return new HashSet<>(gamePrefs.getStringSet("selected_games", new HashSet<>()));
    }
    private static final long GAME_EXIT_CONFIRM_MS = 5 * 60 * 1000; // 5分钟
    private long gameLostFocusTime = -1;
    private void checkForegroundApp() {
        if (usageStatsManager == null) return;
        String topPackage = getTopPackageName();
        Set<String> selectedGames = getSelectedPackages();
        boolean isInGame = topPackage != null && selectedGames.contains(topPackage);
        boolean continueInWindow = gamePrefs.getBoolean("game_continue_in_window", true);
        if (isInGame) {
            if (!isGameRunning || !topPackage.equals(currentGamePackage)) {
                startGameRecording(topPackage);
            } else {
                gameLostFocusTime = -1;
            }
        } else {
            if (isGameRunning) {
                if (continueInWindow) {
                    if (isGameReallyExited()) {
                        stopGameRecording();
                    }
                } else {
                    stopGameRecording();
                }
            }
        }
    }
    private boolean isGameReallyExited() {
        if (currentGamePackage == null) return true;
        boolean processAlive = false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<android.app.ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
                if (processes != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                        if (currentGamePackage.equals(process.processName) ||
                            (process.pkgList != null && Arrays.asList(process.pkgList).contains(currentGamePackage))) {
                            processAlive = true;
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        if (processAlive) {
            long now = System.currentTimeMillis();
            if (gameLostFocusTime < 0) {
                gameLostFocusTime = now;
                return false;
            }
            long extendedTimeout = 30 * 60 * 1000L;
            if (now - gameLostFocusTime < extendedTimeout) {
                return false; // 进程还活着，给30分钟宽限
            }
            return true; // 进程活着但超过30分钟没切回来，算了
        }
        try {
            long now = System.currentTimeMillis();
            UsageEvents events = usageStatsManager.queryEvents(now - 10000, now);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.getNextEvent(event)) {
                if (!currentGamePackage.equals(event.getPackageName())) continue;
                int type = event.getEventType();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    type == UsageEvents.Event.MOVE_TO_BACKGROUND ||
                    type == UsageEvents.Event.ACTIVITY_RESUMED ||
                    type == UsageEvents.Event.ACTIVITY_PAUSED ||
                    type == UsageEvents.Event.ACTIVITY_STOPPED ||
                    type == UsageEvents.Event.CONFIGURATION_CHANGE) {
                    gameLostFocusTime = -1;
                    return false;
                }
            }
        } catch (Exception ignored) {}
        long now = System.currentTimeMillis();
        if (gameLostFocusTime < 0) {
            gameLostFocusTime = now;
            return false; // 首次检测到，给缓冲时间
        }
        if (now - gameLostFocusTime < GAME_EXIT_CONFIRM_MS) {
            return false; // 5分钟内可能只是临时被杀（后台回收）
        }
        return true;
    }
    private String topPkgCached = null;
    private long topPkgCacheTime = 0;
    private String getTopPackageName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            long now = System.currentTimeMillis();
            if (topPkgCached != null && now - topPkgCacheTime < 500) {
                return topPkgCached;
            }
            UsageEvents events = usageStatsManager.queryEvents(now - 15000, now);
            String topPackage = null;
            long lastTime = 0;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.getNextEvent(event)) {
                int type = event.getEventType();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.getTimeStamp() > lastTime) {
                        lastTime = event.getTimeStamp();
                        topPackage = event.getPackageName();
                    }
                }
            }
            topPkgCached = topPackage;
            topPkgCacheTime = now;
            return topPackage;
        }
        return null;
    }
    private void startGameRecording(String packageName) {
        if (isGameRunning && packageName.equals(currentGamePackage)) return;
        isGameRunning = true;
        currentGamePackage = packageName;
        gameStartTime = System.currentTimeMillis();
        hrRecord.clear();
        hrRecordCounter = 0;
        try {
            PackageManager pm = getPackageManager();
            currentGameName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            currentGameName = packageName;
        }
        Log.d(TAG, "开始记录游戏：" + currentGameName);
        updateNotification(true, "心率监测中", currentHR);
        notifyMainActivityGameState(true);
    }
    private void stopGameRecording() {
        if (!isGameRunning) return;
        long endTime = System.currentTimeMillis();
        long duration = endTime - gameStartTime;
        Log.d(TAG, "停止记录游戏：" + currentGamePackage + ", 时长：" + duration + "ms");
        if (duration >= 3 * 60 * 1000 && !hrRecord.isEmpty()) {
            saveRecord(currentGamePackage, gameStartTime, endTime, new ArrayList<>(hrRecord));
        }
        isGameRunning = false;
        currentGamePackage = null;
        hrRecord.clear();
        gameLostFocusTime = -1; // 重置退出计时
        updateNotification(false, "心率监测中", 0);
        notifyMainActivityGameState(false);
    }
    private void notifyMainActivityGameState(boolean inGame) {
        try {
            Intent intent = new Intent("com.xinji.heartbeat.GAME_STATE");
            intent.setPackage(getPackageName()); // Android 14+ 显式指定
            intent.putExtra("in_game", inGame);
            sendBroadcast(intent);
        } catch (Exception ignored) {}
    }
    private void saveRecord(String packageName, long startTime, long endTime, List<Integer> hrData) {
        StringBuilder sb = new StringBuilder();
        sb.append(startTime).append("|").append(endTime).append("|");
        for (int i = 0; i < hrData.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(hrData.get(i));
        }
        String recordData = sb.toString();
        if (recordData.length() > 500 * 1024) {
            recordData = recordData.substring(0, 500 * 1024);
        }
        recordPrefs.edit().putString(packageName + "_latest", recordData).apply();
        String historyKey = packageName + "_history";
        String historyRaw = recordPrefs.getString(historyKey, "");
        String[] historyParts = historyRaw.isEmpty() ? new String[0] : historyRaw.split(";;");
        StringBuilder historySb = new StringBuilder();
        int keepCount = Math.min(historyParts.length, 4); // 保留旧的4条 + 当前1条 = 5条
        for (int i = historyParts.length - keepCount; i < historyParts.length; i++) {
            if (historySb.length() > 0) historySb.append(";;");
            historySb.append(historyParts[i]);
        }
        if (historySb.length() > 0) historySb.append(";;");
        historySb.append(startTime).append("|").append(endTime);
        recordPrefs.edit().putString(historyKey, historySb.toString()).apply();
    }
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        checkHandler.removeCallbacksAndMessages(null);
        recordHandler.removeCallbacksAndMessages(null);
        if (myListener != null) {
            HeartRateManager.getInstance(this).removeListener(myListener);
        }
    }
}
