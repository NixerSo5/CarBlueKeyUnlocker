package com.nixer.carunlocker;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 统一日志：Logcat + 外部私有目录 carguard.log，界面“扫描日志”可查看。 */
public final class LogUtil {
    private static final String TAG = "CarGuard";

    private LogUtil() {}

    public static void log(Context c, String msg) {
        Log.d(TAG, msg);
        if (c == null) return;
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "carguard.log");
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " " + msg + "\n";
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }
}
