package com.nixer.carunlocker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/** 定时被 AlarmManager 唤醒：拉起主服务和看门狗，并安排下一次唤醒（Doze 下也可触发）。 */
public class KeepAliveReceiver extends BroadcastReceiver {
    public static final long INTERVAL_MS = 60_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        Intent car = new Intent(context, CarService.class);
        car.setAction(CarService.ACTION_RESTART_SCAN);
        startForeground(context, car);
        startForeground(context, WatchdogService.class);
        scheduleNext(context);
    }

    private static void startForeground(Context context, Class<?> cls) {
        startForeground(context, new Intent(context, cls));
    }

    private static void startForeground(Context context, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Throwable ignored) {
            // 后台启动被 ROM 拦住时，下一次闹钟会再试
        }
    }

    /** 每次触发后重新排一次，避免 setRepeating 在 Doze / 国产 ROM 上被冻结。 */
    public static void scheduleNext(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(context, KeepAliveReceiver.class);
            int flag = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flag |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, flag);
            long at = SystemClock.elapsedRealtime() + INTERVAL_MS;
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (Throwable ignored) {
        }
    }
}
