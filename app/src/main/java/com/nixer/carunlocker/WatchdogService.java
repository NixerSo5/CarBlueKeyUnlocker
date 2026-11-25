package com.nixer.carunlocker;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

/** 看门狗：定期检查主服务是否存活，死了就拉起 */
public class WatchdogService extends Service {
    private static final String TAG = "CarGuard";
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
            handler.postDelayed(this, 5000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(check);
        handler.post(check);
        return START_STICKY;
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
