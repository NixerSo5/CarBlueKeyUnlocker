package com.nixer.carunlocker;

import android.content.Context;

/** 兼容旧调用：统一走 AppLog 的简单/详细分流。 */
public final class LogUtil {
    private LogUtil() {}

    public static void log(Context c, String msg) {
        AppLog.auto(c, msg);
    }
}
