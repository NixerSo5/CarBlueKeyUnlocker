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

    // 距离判断配置：进入/离开阈值、连续次数、重复触发冷却（秒）
    public static final String K_RSSI_ENTER = "rssi_enter";
    public static final String K_RSSI_EXIT = "rssi_exit";
    public static final String K_CONFIRM_TIMES = "confirm_times";
    public static final String K_COOLDOWN_SEC = "cooldown_sec";
    public static final String K_CURRENT_RSSI = "current_rssi";
    public static final String K_TARGET_ALIVE = "target_alive";
    public static final String K_DISTANCE_MODE = "distance_mode"; // 0~4=五档 5=手动
    public static final String K_POWER_SAVE = "power_save";       // 1=分层（默认）/ 0=高灵敏（当前状态常开）

    // 日志与车辆状态
    public static final String K_DETAIL_LOG = "detail_log";       // 1=开启详细日志（默认关）
    public static final String K_CAR_CONNECTED = "car_connected"; // 1=BLE 会话已连接
    public static final String K_CAR_DOOR = "car_door";           // locked / unlocked / 空=未知

    // 通用版：用户选择要拉起的应用
    public static final String K_LAUNCH_PACKAGE = "launch_package";
    public static final String K_LAUNCH_LABEL = "launch_label";

    // 靠近车辆时自动打开上汽“数字钥匙”页（需要无障碍权限）
    public static final String K_AUTO_OPEN_DK = "auto_open_dk";         // 默认 1=开
    public static final String K_WAKE_SCREEN = "wake_screen";           // 默认 0=不主动点亮屏幕
    public static final String K_ACC_STATE = "acc_state";               // 无障碍流程状态（用于界面显示）
    public static final String K_BLE_STATE = "ble_state";               // 最近一次 BLE 直连解锁状态

    // 短信登录后下载到本机的钥匙（只保存在设备本地，不随安装包分发）
    public static final String K_BLE_KS = "runtime_ble_ks";
    public static final String K_BLE_KEY_ID = "runtime_ble_key_id";
    public static final String K_BLE_KR = "runtime_ble_kr";
    public static final String K_BLE_MAC = "runtime_ble_mac";
    public static final String K_KEY_SOURCE = "key_source";

    // 登录时自动生成并持久化的设备标识（可在 local.properties 覆盖）
    public static final String K_SAIC_UUID = "saic_uuid";
    public static final String K_SAIC_DEVICE_ID = "saic_device_id";

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

    public static boolean getBool(Context c, String key, boolean def) {
        String v = get(c, key);
        if (v == null || v.length() == 0) return def;
        return "1".equals(v);
    }

    public static void setBool(Context c, String key, boolean value) {
        set(c, key, value ? "1" : "0");
    }

    /** 默认分层：低功耗待机 + 发现车/操作时升到低延迟；关闭即高灵敏常开。 */
    public static boolean powerSave(Context c) { return getBool(c, K_POWER_SAVE, true); }

    public static boolean detailLog(Context c) { return getBool(c, K_DETAIL_LOG, false); }

    public static String targetMac(Context c) { return get(c, K_TARGET_MAC); }
    public static String targetName(Context c) { return get(c, K_TARGET_NAME); }
}
