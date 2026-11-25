package com.nixer.carunlocker;

import android.content.Context;
import android.content.SharedPreferences;

/** 保存用户选择的设备以及运行状态，供界面和服务共享 */
public final class AppPrefs {
    private static final String P = "carguard";
    public static final String K_TARGET_MAC = "target_mac";
    public static final String K_TARGET_NAME = "target_name";
    public static final String K_LAST_SEEN = "last_seen";
    public static final String K_DEVICE_COUNT = "device_count";
    public static final String K_SERVICE_START = "service_start";
    public static final String K_HEARTBEAT = "heartbeat";
    public static final String K_ASKED_OVERLAY = "asked_overlay";
    public static final String K_ASKED_BATTERY = "asked_battery";
    public static final String K_ASKED_BG_LOCATION = "asked_bg_location";

    // 距离判断配置（默认：进入 -60dBm，离开 -75dBm，连续 2 次）
    public static final String K_RSSI_ENTER = "rssi_enter";
    public static final String K_RSSI_EXIT = "rssi_exit";
    public static final String K_CONFIRM_TIMES = "confirm_times";
    public static final String K_CURRENT_RSSI = "current_rssi";
    public static final String K_DISTANCE_MODE = "distance_mode"; // 0很近 1近 2远 3自定义

    public static int getInt(Context c, String key, int def) {
        String v = get(c, key);
        if (v == null || v.length() == 0) return def;
        try { return Integer.parseInt(v); } catch (Throwable t) { return def; }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static String get(Context c, String key) {
        return sp(c).getString(key, "");
    }

    public static void set(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }

    public static String targetMac(Context c) { return get(c, K_TARGET_MAC); }
    public static String targetName(Context c) { return get(c, K_TARGET_NAME); }
}
