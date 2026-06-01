package com.xinji.heartbeat.core;

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
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * 游戏模式检测服务 — 自动识别游戏前台并记录心率。
 *
 * 改进：
 * - 修复 HR 记录去重逻辑：改为环形缓冲区，不限时记录
 * - 修复记录末尾多余逗号的问题
 * - 退出确认时间从 5 分钟改为 2 分钟
 * - 改进前台检测精度
 */
public class GameModeService extends Service {
    private static final String TAG = "GameModeService";

    private UsageStatsManager usageStatsManager;
    private CheckHandler checkHandler;
    private SharedPreferences gamePrefs;
    private SharedPreferences recordPrefs;

    private boolean isGameRunning = false;
    private String currentGamePackage = null;
    private String currentGameName = "";
    private long gameStartTime = 0;

    // 【修复】静态内部类 Handler + WeakReference，防止匿名内部类隐式持有 Service 引用
    private static class CheckHandler extends Handler {
        final WeakReference<GameModeService> serviceRef;
        CheckHandler(GameModeService service) {
            super(Looper.getMainLooper());
            this.serviceRef = new WeakReference<>(service);
        }
    }

    // 环形缓冲区：最多 30 分钟（1800 点 @ 1次/秒）
    private static final int MAX_HR_RECORDS = 1800;
    private final int[] hrBuffer = new int[MAX_HR_RECORDS];
    private int hrBufferCount = 0;
    private int hrBufferIndex = 0;

    private int currentHR = 0;
    private HeartRateManager.HeartRateListener myListener;

    // 退出缓冲：2分钟
    private static final long GAME_EXIT_TIMEOUT_MS = 2 * 60 * 1000L;
    private long gameLostFocusTime = -1;

    private static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "game_mode_service";

    @Override
    public void onCreate() {
        super.onCreate();
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        gamePrefs = getSharedPreferences("game_mode", Context.MODE_PRIVATE);
        recordPrefs = getSharedPreferences("game_records", Context.MODE_PRIVATE);
        // 【修复】使用静态内部类 CheckHandler
        checkHandler = new CheckHandler(this);

        myListener = hr -> {
            currentHR = hr;
            if (isGameRunning && hr > 0) {
                hrBuffer[hrBufferIndex] = hr;
                hrBufferIndex = (hrBufferIndex + 1) % MAX_HR_RECORDS;
                if (hrBufferCount < MAX_HR_RECORDS) {
                    hrBufferCount++;
                }
            }
        };
        HeartRateManager.getInstance(this).registerListener(myListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.xinji.heartbeat.STOP_GAME_RECORDING".equals(intent.getAction())) {
            if (isGameRunning) stopGameRecording();
            updateNotification(false, "已停止记录");
            return START_STICKY;
        }
        initNotification();
        startChecking();
        return START_STICKY;
    }

    private void initNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "心迹服务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("心率监测后台运行");
        if (nm != null) nm.createNotificationChannel(channel);
        updateNotification(false, "心率监测中");
    }

    private void updateNotification(boolean inGame, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent tapIntent = new Intent(this, com.xinji.heartbeat.MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tapPending = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(tapPending)
                .setOngoing(true);

        if (inGame) {
            builder.setContentTitle("🎮 游戏模式")
                   .setContentText("记录中：" + currentGameName);
            Intent stopIntent = new Intent(this, GameModeService.class);
            stopIntent.setAction("com.xinji.heartbeat.STOP_GAME_RECORDING");
            PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止记录", stopPending);
        } else {
            builder.setContentTitle("❤️ 心迹")
                   .setContentText(text);
        }

        int[] serviceTypes;
        if (Build.VERSION.SDK_INT >= 34) {
            serviceTypes = new int[]{
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE};
        } else if (Build.VERSION.SDK_INT >= 29) {
            serviceTypes = new int[]{ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, 0};
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
            try { startForeground(NOTIFICATION_ID, builder.build()); } catch (Exception e) {
                Log.w(TAG, "前台服务启动失败，降级运行");
            }
        }
    }

    private void startChecking() {
        // 【修复】通过 WeakReference 引用 Service，防止泄漏
        if (checkHandler == null) return;
        final WeakReference<GameModeService> ref = checkHandler.serviceRef;
        checkHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                GameModeService service = ref.get();
                if (service == null) return;
                service.checkForegroundApp();
                service.checkHandler.postDelayed(this, 1000);
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

    /**
     * 判断游戏是否真的退出了。
     * 2分钟缓冲，避免短暂切出误中断。
     */
    private boolean isGameReallyExited() {
        if (currentGamePackage == null) return true;
        long now = System.currentTimeMillis();

        if (gameLostFocusTime < 0) {
            gameLostFocusTime = now;
            return false;
        }

        // 检查游戏进程是否还在
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
            // 进程还在，说明只是切到后台/小窗
            if (now - gameLostFocusTime < GAME_EXIT_TIMEOUT_MS) {
                return false;
            }
            return true; // 超过2分钟没回来
        }

        // 进程不在，查 UsageEvents 确认
        try {
            UsageEvents events = usageStatsManager.queryEvents(now - 10000, now);
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.getNextEvent(event)) {
                if (!currentGamePackage.equals(event.getPackageName())) continue;
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    gameLostFocusTime = -1;
                    return false;
                }
            }
        } catch (Exception ignored) {}

        if (now - gameLostFocusTime < GAME_EXIT_TIMEOUT_MS) {
            return false;
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
        hrBufferCount = 0;
        hrBufferIndex = 0;
        gameLostFocusTime = -1;
        try {
            PackageManager pm = getPackageManager();
            currentGameName = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            currentGameName = packageName;
        }
        Log.d(TAG, "开始记录游戏：" + currentGameName);
        updateNotification(true, "心率监测中");
        notifyMainActivityGameState(true);
    }

    private void stopGameRecording() {
        if (!isGameRunning) return;
        long endTime = System.currentTimeMillis();
        long duration = endTime - gameStartTime;
        Log.d(TAG, "停止记录游戏：" + currentGamePackage + ", 时长：" + duration + "ms");

        if (duration >= 3 * 60 * 1000 && hrBufferCount > 0) {
            saveRecord(currentGamePackage, gameStartTime, endTime);
        }

        isGameRunning = false;
        currentGamePackage = null;
        currentGameName = "";
        hrBufferCount = 0;
        hrBufferIndex = 0;
        gameLostFocusTime = -1;
        updateNotification(false, "心率监测中");
        notifyMainActivityGameState(false);
    }

    /**
     * 保存记录 — 使用 StringBuilder，末尾无多余逗号。
     */
    private void saveRecord(String packageName, long startTime, long endTime) {
        StringBuilder sb = new StringBuilder();
        sb.append(startTime).append("|").append(endTime).append("|");

        // 按顺序从环形缓冲区读取
        int count = hrBufferCount;
        int startIdx = hrBufferIndex - count;
        if (startIdx < 0) startIdx += MAX_HR_RECORDS;

        for (int i = 0; i < count; i++) {
            int idx = (startIdx + i) % MAX_HR_RECORDS;
            if (i > 0) sb.append(",");
            sb.append(hrBuffer[idx]);
        }

        String recordData = sb.toString();
        // 【修复】按最后一个完整心率数值截断，避免截断在数字中间
        if (recordData.length() > 500 * 1024) {
            int cutPos = recordData.lastIndexOf(",", 500 * 1024);
            if (cutPos > 0) {
                recordData = recordData.substring(0, cutPos);
            } else {
                recordData = recordData.substring(0, 500 * 1024);
            }
        }

        // 保存最新记录
        recordPrefs.edit().putString(packageName + "_latest", recordData).apply();

        // 保存历史记录（最多 5 条）
        String historyKey = packageName + "_history";
        String historyRaw = recordPrefs.getString(historyKey, "");
        String[] historyParts = historyRaw.isEmpty() ? new String[0] : historyRaw.split(";;");
        StringBuilder historySb = new StringBuilder();
        int keepCount = Math.min(historyParts.length, 4);
        for (int i = historyParts.length - keepCount; i < historyParts.length; i++) {
            if (historySb.length() > 0) historySb.append(";;");
            historySb.append(historyParts[i]);
        }
        if (historySb.length() > 0) historySb.append(";;");
        historySb.append(startTime).append("|").append(endTime);

        recordPrefs.edit().putString(historyKey, historySb.toString()).apply();
    }

    private void notifyMainActivityGameState(boolean inGame) {
        try {
            Intent intent = new Intent("com.xinji.heartbeat.GAME_STATE");
            intent.setPackage(getPackageName());
            intent.putExtra("in_game", inGame);
            sendBroadcast(intent);
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        checkHandler.removeCallbacksAndMessages(null);
        if (myListener != null) {
            HeartRateManager.getInstance(this).removeListener(myListener);
        }
    }
}