package com.nixer.carunlocker;

import android.app.ActivityManager;
import android.content.Context;

import java.util.List;

public final class Utils {
    public static boolean isServiceRunning(Context c, Class<?> cls) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningServiceInfo> list = am.getRunningServices(Integer.MAX_VALUE);
        if (list == null) return false;
        for (ActivityManager.RunningServiceInfo info : list) {
            if (cls.getName().equals(info.service.getClassName())) return true;
        }
        return false;
    }
}
