package com.nixer.carunlocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        KeepAliveReceiver.scheduleNext(context);
        Intent i = new Intent(context, CarService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
        Intent w = new Intent(context, WatchdogService.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(w);
        } else {
            context.startService(w);
        }
    }
}
