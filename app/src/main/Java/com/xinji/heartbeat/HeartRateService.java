package com.xinji.heartbeat;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
public class HeartRateService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("heart_service", "心迹服务", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("后台运行服务");
            channel.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, "heart_service")
                    .setContentTitle("❤️ 心迹")
                    .setContentText("心率监测运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setOngoing(true)
                    .build();
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                } else {
                    startForeground(1, notification);
                }
            } catch (Exception e) {
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(1, notification, 0);
                    } else {
                        startForeground(1, notification);
                    }
                } catch (Exception ignored) {}
            }
        }
        return START_STICKY;
    }
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
