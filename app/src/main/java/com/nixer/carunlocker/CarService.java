package com.nixer.carunlocker;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CarService extends Service {
    private static final String TAG = "CarGuard";
    private static final String SAIC_PACKAGE = "com.saicmotor.tocapp"; // 上汽荣威专用分支：固定拉起目标
    private static final String CHANNEL_ID = "car_guard_silent"; // 新通道：最低优先级，避免横幅打扰
    private static final long RELAUNCH_GUARD_MS = 3_000; // 只防同一瞬间重复启动，不阻碍保活
    private static final long KEEPALIVE_INTERVAL_MS = 30_000; // 独立保活轮询；BLE 只是检测手段
    // 目标 App 中已导出的入口服务：无声拉起进程，App 自己会启动 DigitalKeyService
    private static final String SILENT_DAEMON_CLASS = "com.uaes.key.core.daemon.huawei.BleICCEService";
    // 已导出的主进程服务：onStartCommand 会调 DigitalKeySDKDelegate.initService()，用于静默完成 SDK 初始化
    private static final String SDK_INIT_SERVICE = "com.uaes.key.core.daemon.honor.HonorPullLiveService1";
    private static final long SDK_INIT_DELAY_MS = 500; // 给守护绑定/Application 初始化留一点时间
    private static final long SDK_SCAN_FOLLOWUP_MS = 4_000; // 等 DigitalKeyService 绑定完成后再触发 BLE 扫描
    private static final long SAMPLE_INTERVAL_MS = 800; // 采样间隔，避免同一秒多次回调误判
    private static final long NO_RESULT_RESTART_MS = 60_000; // 长时间收不到任何广播则重启扫描
    private static final long TARGET_GONE_MS = 10_000;      // 目标消失超过 10 秒视为离开，重置连续计数
    private static final long DK_TRIGGER_COOLDOWN_MS = 90_000; // 自动打开数字钥匙页的最小间隔

    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private long lastLaunch = 0;
    private final Handler handler = new Handler();
    private int scanAttempts = 0;
    private int foundCount = 0;
    private String targetMac = "";
    private String targetName = "";
    private String launchPackage = "";
    private String launchLabel = "";
    private boolean daemonBound = false;
    private boolean targetAlive = false; // 由守护服务绑定回调维护，作为进程存活判据
    private boolean sdkInitScheduled = false;
    private long lastDkTrigger = 0;      // 上次请求打开数字钥匙页的时间
    private final ServiceConnection daemonConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) { onDaemonConnected(name); }
        @Override public void onServiceDisconnected(ComponentName name) { onDaemonDisconnected(name); }
    };

    private void onDaemonConnected(ComponentName name) {
        daemonBound = true;
        targetAlive = true;
        AppPrefs.set(this, AppPrefs.K_TARGET_ALIVE, "1");
        log("target daemon connected -> process alive: " + (name == null ? "" : name.getClassName()));
    }

    private void onDaemonDisconnected(ComponentName name) {
        daemonBound = false;
        targetAlive = false;
        // 目标进程可能已重启，允许下次拉起时重新初始化 SDK
        sdkInitScheduled = false;
        handler.removeCallbacks(sdkInitTask);
        handler.removeCallbacks(sdkScanTask);
        AppPrefs.set(this, AppPrefs.K_TARGET_ALIVE, "0");
        log("target daemon disconnected -> process not alive: " + (name == null ? "" : name.getClassName()));
    }

    // 距离判断状态
    private int enterRssi = -75;   // 强于此值 = 进入范围（默认很早就触发，给目标 App 连接时间）
    private int confirmTimes = 2;  // 连续几次采样都在范围内才触发
    private int nearCount = 0;
    private long lastSampleTime = 0;
    private long lastResultTime = 0;  // 最近一次收到任意广播的时间，用于识别“假扫描中”
    private long lastTargetTime = 0;  // 最近一次收到目标广播的时间，用于超时重置

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            log("heartbeat, scanning=" + scanning + ", attempts=" + scanAttempts + ", nearCount=" + nearCount);
            // 系统可能在无回调的情况下静默停掉 BLE 扫描；心跳不能只看布尔标志，要看是否真有广播进来
            long quiet = SystemClock.elapsedRealtime() - lastResultTime;
            if (scanning && quiet > NO_RESULT_RESTART_MS) {
                log("no BLE result for " + (quiet / 1000) + "s, restarting scan");
                restartScan();
            }
            AppPrefs.set(CarService.this, AppPrefs.K_HEARTBEAT,
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
            AppPrefs.set(CarService.this, AppPrefs.K_DEVICE_COUNT, String.valueOf(foundCount));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, buildNotification());
            handler.postDelayed(this, 15_000);
        }
    };

    /** 不依赖 BLE：只要目标进程死了就拉起，BLE 命中只是提前触发。 */
    private final Runnable keepAliveCheck = new Runnable() {
        @Override
        public void run() {
            if (!TextUtils.isEmpty(launchPackage)) {
                if (!targetAlive) {
                    log("KEEPALIVE: 目标守护未连接，准备拉起");
                    launchSelectedApp("KEEPALIVE");
                } else {
                    log("KEEPALIVE: 目标守护已连接，目标进程正常运行");
                }
            }
            handler.postDelayed(this, KEEPALIVE_INTERVAL_MS);
        }
    };

    /** 一次性静默初始化上汽 SDK：启动导出服务，使其调用 DigitalKeySDKDelegate.initService()。 */
    private final Runnable sdkInitTask = new Runnable() {
        @Override
        public void run() {
            triggerSdkInit("SDK INIT");
            handler.removeCallbacks(sdkScanTask);
            handler.postDelayed(sdkScanTask, SDK_SCAN_FOLLOWUP_MS);
        }
    };

    /** init() 内 bindService 是异步的；稍后再触发一次，确保 mService 已就绪并启动 BLE 扫描。 */
    private final Runnable sdkScanTask = new Runnable() {
        @Override
        public void run() {
            triggerSdkInit("SDK SCAN FOLLOWUP");
        }
    };

    private void scheduleSdkInit() {
        if (sdkInitScheduled) return;
        sdkInitScheduled = true;
        handler.removeCallbacks(sdkInitTask);
        handler.postDelayed(sdkInitTask, SDK_INIT_DELAY_MS);
    }

    private void triggerSdkInit(String reason) {
        try {
            Intent svc = new Intent().setComponent(new ComponentName(SAIC_PACKAGE, SDK_INIT_SERVICE));
            String mac = targetMac;
            if (TextUtils.isEmpty(mac)) {
                // mac 只要求非空；有真实车辆 MAC 时优先用真实的
                mac = "00:00:00:00:00:00";
            }
            svc.putExtra("BleParamsOrigin", "CarGuard");
            svc.putExtra("keyId", "carguard");
            svc.putExtra("mac", mac);
            startService(svc);
            log(reason + " -> started SAIC SDK init service silently: " + SDK_INIT_SERVICE + " mac=" + mac);
        } catch (Throwable t) {
            logError(reason + " -> start SAIC SDK init service failed", t);
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "carguard.log");
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " " + msg + "\n";
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    private void logError(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log(msg + " :: " + sw.toString());
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String addr = result.getDevice().getAddress();
            String name = result.getDevice().getName();
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            int rssi = result.getRssi();
            foundCount++;
            lastResultTime = SystemClock.elapsedRealtime();

            boolean byMac = !TextUtils.isEmpty(targetMac) && targetMac.equalsIgnoreCase(addr);
            boolean byName = !TextUtils.isEmpty(targetName) && name != null
                    && name.equalsIgnoreCase(targetName);
            if (!byMac && !byName) return;

            // 实时信号强度写到 prefs，界面显示
            AppPrefs.set(CarService.this, AppPrefs.K_CURRENT_RSSI, rssi + " dBm");

            long now = SystemClock.elapsedRealtime();
            // 不做“弱信号走远”判断：离开后本来就收不到广播，用超时判定更可靠
            if (nearCount > 0 && now - lastTargetTime > TARGET_GONE_MS) {
                log("目标广播消失超过 " + (TARGET_GONE_MS / 1000) + "s，重置连续计数");
                nearCount = 0;
            }
            lastTargetTime = now;

            if (now - lastSampleTime < SAMPLE_INTERVAL_MS) return; // 采样节流
            lastSampleTime = now;

            if (rssi >= enterRssi) {
                nearCount++;
                log("目标信号: " + rssi + " dBm, 连续 = " + nearCount + "/" + confirmTimes);
                if (nearCount >= confirmTimes) {
                    nearCount = 0; // 触发后清零，靠冷却时间防重复
                    String seen = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                    AppPrefs.set(CarService.this, AppPrefs.K_LAST_SEEN, seen + " / " + addr + " / " + rssi + "dBm");
                    launchSelectedApp("TARGET NEAR(" + rssi + "dBm / " + name + ")");
                    maybeTriggerDigitalKey(rssi, name);
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("scan failed, code=" + errorCode);
            if (errorCode == 1) { // SCAN_FAILED_ALREADY_STARTED：首次扫描仍在跑，无需重试
                log("scan already active (code=1), ignored");
                scanning = true;
                return;
            }
            scanning = false;
            handler.postDelayed(CarService.this::startScan, 5000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        log("onCreate");
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "07电子钥匙助手守护（静默）", NotificationManager.IMPORTANCE_MIN);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
                nm.deleteNotificationChannel("car_guard"); // 清掉会弹横幅的旧通道
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand");
        targetMac = AppPrefs.targetMac(this);
        targetName = AppPrefs.targetName(this);
        launchPackage = SAIC_PACKAGE;
        launchLabel = "上汽荣威";
        enterRssi = AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, -75);
        confirmTimes = AppPrefs.getInt(this, AppPrefs.K_CONFIRM_TIMES, 2);
        if (confirmTimes < 1) confirmTimes = 1;
        nearCount = 0;
        lastResultTime = SystemClock.elapsedRealtime();
        lastTargetTime = lastResultTime;
        log("target = " + targetName + " / " + targetMac + " | launch = " + launchLabel + " / " + launchPackage
                + " | enter=" + enterRssi + " confirm=" + confirmTimes + " (离开按超时 " + (TARGET_GONE_MS / 1000) + "s 判定)");
        startForeground(1, buildNotification());
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
        handler.removeCallbacks(keepAliveCheck);
        handler.post(keepAliveCheck);
        startScan();
        // 该 ROM 上第一次 startScan 常常没有回调，300ms 后再直接调一次（不 stop）
        handler.postDelayed(this::startScan, 300);
        // 静默完成上汽数字钥匙 SDK 初始化；不弹任何上汽 Activity
        scheduleSdkInit();
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        int flag = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flag);
        String t = TextUtils.isEmpty(targetName) ? "未选择设备" : targetName;
        String rssi = AppPrefs.get(this, AppPrefs.K_CURRENT_RSSI);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT < 26) {
            b.setPriority(Notification.PRIORITY_MIN)
                    .setSound(null)
                    .setVibrate(new long[0]);
        }
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("07电子钥匙助手 · 守护中")
                .setContentText("目标: " + t + " | 信号: " + (TextUtils.isEmpty(rssi) ? "--" : rssi))
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build();
    }

    private void startScan() {
        scanAttempts++;
        log("startScan attempt #" + scanAttempts);
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null) {
                log("adapter null, retry in 5s");
                handler.postDelayed(this::startScan, 5000);
                return;
            }
            log("adapter enabled=" + adapter.isEnabled());
            if (!adapter.isEnabled()) {
                handler.postDelayed(this::startScan, 5000);
                return;
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                log("BLE scanner null, retry in 5s");
                handler.postDelayed(this::startScan, 5000);
                return;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            if (scanning && scanner != null) {
                // 关键：不 stop，直接再 start 一次，ROM 才会开始回调
                log("second startScan call (no stop, ROM workaround)");
                scanner.startScan(null, settings, scanCallback);
                log("BLE scan re-trigger requested");
                return;
            }
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            log("BLE scan started OK");
        } catch (Throwable t) {
            logError("startScan error", t);
            handler.postDelayed(this::startScan, 5000);
        }
    }

    /** 系统可能在无回调的情况下静默停掉扫描；停掉旧扫描后重新来一次，避免心跳假活。 */
    private void restartScan() {
        log("restartScan: stop old scan and start fresh");
        try {
            if (scanner != null) {
                try { scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
            }
        } finally {
            scanning = false;
            startScan();
        }
    }

    private void launchSelectedApp(String trigger) {
        if (TextUtils.isEmpty(launchPackage)) {
            log(trigger + " -> 未选择拉起应用，跳过");
            return;
        }
        // 绑定成功后目标进程肯定存活；后台也算运行，不需要切到前台
        if (targetAlive) {
            log(trigger + " -> 目标进程仍在运行，跳过拉起: " + launchPackage);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < RELAUNCH_GUARD_MS) {
            log(trigger + " -> 刚刚拉起过，3秒内不重复启动: " + launchPackage);
            return;
        }
        lastLaunch = now;
        // 进程刚被拉起时 SDK 尚未初始化，补一次静默初始化触发（已触发过则忽略）
        scheduleSdkInit();
        // 首选：启动已导出的守护服务，无声拉起进程；目标 App 自己会开 DigitalKeyService，不打扰当前界面
        try {
            Intent svc = new Intent().setComponent(new ComponentName(launchPackage, SILENT_DAEMON_CLASS));
            if (daemonBound) {
                try { unbindService(daemonConnection); } catch (Throwable ignored) {}
                daemonBound = false;
            }
            boolean bound = bindService(svc, daemonConnection, Context.BIND_AUTO_CREATE);
            if (bound) {
                log(trigger + " -> bound daemon silently: " + SILENT_DAEMON_CLASS);
                return;
            }
            startService(svc);
            log(trigger + " -> started daemon silently: " + SILENT_DAEMON_CLASS);
            return;
        } catch (Throwable t) {
            // 绝不弹界面兜底；被 ROM 拦截时提示用户开一次“自启动/关联启动”即可
            logError(trigger + " -> 被系统拦截，未拉起（请在系统设置给本 App 开启自启动/关联启动）", t);
        }
    }

    /**
     * 靠近车辆时，通过无障碍服务把上汽 App 打开到“数字钥匙”页。
     * 手机屏幕亮着并已解锁时最可靠；锁屏时系统会挡住点击，可开启“点亮屏幕”尝试。
     */
    private void maybeTriggerDigitalKey(int rssi, String name) {
        if (!AppPrefs.getBool(this, AppPrefs.K_AUTO_OPEN_DK, true)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastDkTrigger < DK_TRIGGER_COOLDOWN_MS) {
            log("DK: 冷却中，跳过自动打开数字钥匙页");
            return;
        }
        if (!SaicKeyAccessibilityService.isEnabled(this)) {
            log("DK: 无障碍未开启，跳过（请在 App 内开启无障碍权限）");
            return;
        }
        lastDkTrigger = now;
        if (AppPrefs.getBool(this, AppPrefs.K_WAKE_SCREEN, false)) {
            wakeScreenIfNeeded();
        }
        boolean ok = SaicKeyAccessibilityService.trigger(
                this, "TARGET NEAR(" + rssi + "dBm / " + name + ")");
        log(ok ? "DK: 已请求无障碍打开数字钥匙页"
                : "DK: 无障碍服务未就绪，请求失败");
    }

    /** 可选：靠近时短暂点亮屏幕，方便无障碍读取界面（锁屏时仍需用户解锁）。 */
    private void wakeScreenIfNeeded() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isInteractive()) return;
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "CarGuard:DKWake");
            wl.acquire(8000);
            log("DK: 已点亮屏幕 8 秒");
        } catch (Throwable t) {
            logError("DK: 点亮屏幕失败", t);
        }
    }

    /** 目标应用进程是否存活；后台也算运行，不需要切到前台。 */
    private boolean isAppRunning(String pkg) {
        if (TextUtils.isEmpty(pkg)) return false;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            java.util.List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list == null) return false;
            for (ActivityManager.RunningAppProcessInfo info : list) {
                if (info == null || info.pkgList == null) continue;
                for (String p : info.pkgList) {
                    if (pkg.equals(p)) return true;
                }
            }
        } catch (Throwable t) {
            logError("isAppRunning error", t);
        }
        return false;
    }

    @Override
    public void onDestroy() {
        log("onDestroy");
        if (scanner != null && scanning) {
            try { scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
        }
        handler.removeCallbacks(heartbeat);
        handler.removeCallbacks(keepAliveCheck);
        handler.removeCallbacks(sdkInitTask);
        handler.removeCallbacks(sdkScanTask);
        if (daemonBound) {
            try { unbindService(daemonConnection); } catch (Throwable ignored) {}
            daemonBound = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
