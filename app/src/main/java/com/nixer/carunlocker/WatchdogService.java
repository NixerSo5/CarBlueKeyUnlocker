package com.nixer.carunlocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

/** 看门狗：低优先级前台服务，定期确认主服务活着；被杀后由 AlarmManager 再拉起。 */
public class WatchdogService extends Service {
    private static final String TAG = "CarGuard";
    private static final String CHANNEL_ID = "car_guard_silent";
    private static final int NOTIFY_ID = 2;
    private static final long CHECK_INTERVAL_MS = 5_000;

    private final Handler handler = new Handler();
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            try {
                if (!Utils.isServiceRunning(WatchdogService.this, CarService.class)) {
                    Log.d(TAG, "watchdog: restarting CarService");
                    Intent i = new Intent(WatchdogService.this, CarService.class);
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(i);
                    } else {
                        startService(i);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "watchdog error", t);
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFY_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFY_ID, buildNotification());
        handler.removeCallbacks(check);
        handler.post(check);
        return START_STICKY;
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "07电子钥匙助手守护（静默）", NotificationManager.IMPORTANCE_MIN);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        int flag = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flag);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_MIN)
                    .setSound(null)
                    .setVibrate(new long[0]);
        }
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("07电子钥匙助手 · 看门狗")
                .setContentText("后台保活中")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(check);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
