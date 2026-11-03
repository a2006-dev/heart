package com.xinji.heartbeat.core;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.xinji.heartbeat.MainActivity;

public class HeartRateService extends Service {
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "heart_rate_service";
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_USER_STOPPED = "hr_service_user_stopped";

    private String currentText = "心率监测中";
    private boolean isInGame = false;
    private boolean userStopped = false;

    @Override
    public void onCreate() {
        super.onCreate();
        userStopped = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_USER_STOPPED, false);

        IntentFilter filter = new IntentFilter("com.xinji.heartbeat.UPDATE_NOTIFICATION");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(notifUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(notifUpdateReceiver, filter);
        }
    }

    private void setUserStopped(boolean stopped) {
        userStopped = stopped;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(KEY_USER_STOPPED, stopped).apply();
    }

    private final BroadcastReceiver notifUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            isInGame = intent.getBooleanExtra("in_game", false);
            String text = intent.getStringExtra("text");
            if (text == null) text = "心率监测中";

            if (isInGame) {
                String gameName = intent.getStringExtra("game_name");
                if (gameName != null) {
                    updateNotification("🎮 游戏模式", "记录中：" + gameName);
                } else {
                    updateNotification("🎮 游戏模式", text);
                }
            } else {
                updateNotification("❤️ 心迹", text);
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "com.xinji.heartbeat.STOP_FOREGROUND".equals(intent.getAction())) {
            setUserStopped(true);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        setUserStopped(false);
        initNotification();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (userStopped) return;
        Intent restartIntent = new Intent(this, HeartRateService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(restartIntent);
            else startService(restartIntent);
        } catch (Exception e) {
            android.util.Log.w("HeartRateService", "任务移除后重启服务失败", e);
        }
    }

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "心率监测", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("后台心率监测保活");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
        updateNotification("❤️ 心迹", "心率监测中");
    }

    private void updateNotification(String title, String text) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tapPending = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            // API 24/25 没有 NotificationChannel，使用无 channel 的构造函数
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapPending)
            .setOngoing(true);

        int[] serviceTypes;
        if (Build.VERSION.SDK_INT >= 29) {
            serviceTypes = new int[]{
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                0};
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
                android.util.Log.w("HeartRateService", "前台服务启动失败");
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(notifUpdateReceiver); } catch (Exception ignored) {}
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
