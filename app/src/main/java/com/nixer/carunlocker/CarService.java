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
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
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
    private static final String SAIC_PACKAGE = "com.saicmotor.tocapp";
    private static final String CHANNEL_ID = "car_guard";
    private static final long COOLDOWN_MS = 90_000;
    private static final long SAMPLE_INTERVAL_MS = 800; // 采样间隔，避免同一秒多次回调误判

    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private long lastLaunch = 0;
    private final Handler handler = new Handler();
    private int scanAttempts = 0;
    private int foundCount = 0;
    private String targetMac = "";
    private String targetName = "";

    // 距离判断状态
    private int enterRssi = -60;   // 强于此值 = 进入近距离
    private int exitRssi = -75;    // 弱于此值 = 走远
    private int confirmTimes = 2;  // 连续几次采样都在近处才触发
    private int nearCount = 0;
    private long lastSampleTime = 0;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            log("heartbeat, scanning=" + scanning + ", attempts=" + scanAttempts + ", nearCount=" + nearCount);
            AppPrefs.set(CarService.this, AppPrefs.K_HEARTBEAT,
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
            AppPrefs.set(CarService.this, AppPrefs.K_DEVICE_COUNT, String.valueOf(foundCount));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, buildNotification());
            handler.postDelayed(this, 15_000);
        }
    };

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

            boolean byMac = !TextUtils.isEmpty(targetMac) && targetMac.equalsIgnoreCase(addr);
            boolean byName = !TextUtils.isEmpty(targetName) && name != null
                    && name.equalsIgnoreCase(targetName);
            if (!byMac && !byName) return;

            // 实时信号强度写到 prefs，界面显示
            AppPrefs.set(CarService.this, AppPrefs.K_CURRENT_RSSI, rssi + " dBm");

            long now = SystemClock.elapsedRealtime();
            if (now - lastSampleTime < SAMPLE_INTERVAL_MS) return; // 采样节流
            lastSampleTime = now;

            if (rssi >= enterRssi) {
                nearCount++;
                log("目标信号强: " + rssi + " dBm, 连续近 = " + nearCount + "/" + confirmTimes);
                if (nearCount >= confirmTimes) {
                    nearCount = 0; // 触发后清零，靠冷却时间防重复
                    String seen = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                    AppPrefs.set(CarService.this, AppPrefs.K_LAST_SEEN, seen + " / " + addr + " / " + rssi + "dBm");
                    log("TARGET NEAR -> launching SAIC (" + addr + " / " + name + " / " + rssi + "dBm)");
                    launchSaic();
                }
            } else if (rssi < exitRssi) {
                if (nearCount > 0) log("目标信号弱: " + rssi + " dBm, 重置连续计数");
                nearCount = 0; // 走远，重置（迟滞：enter 与 exit 之间保持不变）
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("scan failed, code=" + errorCode);
            if (errorCode == 1) {
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
                    CHANNEL_ID, "07电子钥匙助手守护", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand");
        targetMac = AppPrefs.targetMac(this);
        targetName = AppPrefs.targetName(this);
        enterRssi = AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, -60);
        exitRssi = AppPrefs.getInt(this, AppPrefs.K_RSSI_EXIT, -75);
        confirmTimes = AppPrefs.getInt(this, AppPrefs.K_CONFIRM_TIMES, 2);
        if (confirmTimes < 1) confirmTimes = 1;
        nearCount = 0;
        log("target = " + targetName + " / " + targetMac + " | enter=" + enterRssi + " exit=" + exitRssi + " confirm=" + confirmTimes);
        startForeground(1, buildNotification());
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
        startScan();
        // 该 ROM 上第一次 startScan 常常没有回调，300ms 后再直接调一次（不 stop）
        handler.postDelayed(this::startScan, 300);
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
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("07电子钥匙助手 · 守护中")
                .setContentText("目标: " + t + " | 信号: " + (TextUtils.isEmpty(rssi) ? "--" : rssi))
                .setContentIntent(pi)
                .setOngoing(true)
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
                log("second startScan call (no stop)");
                scanner.startScan(null, settings, scanCallback);
                log("BLE scan restarted OK");
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

    private void launchSaic() {
        // 目标应用已在运行（含后台）就不再拉起，避免反复把它切到前台打扰用户
        if (isAppRunning(SAIC_PACKAGE)) {
            log("目标应用已在运行，跳过拉起: " + SAIC_PACKAGE);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < COOLDOWN_MS) return;
        lastLaunch = now;
        Intent i = getPackageManager().getLaunchIntentForPackage(SAIC_PACKAGE);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            log("SAIC launched");
            // 让目标应用退到后台：把自己 App 的任务切回前台
            handler.postDelayed(this::bringOurTaskToFront, 300);
        } else {
            log("SAIC package not found");
        }
    }

    /** 目标应用进程是否存活（无特殊权限，进程刚被杀或从未启动则返回 false）。 */
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

    /** 把自己 App 的任务切回前台，目标应用随之退到后台继续运行。 */
    private void bringOurTaskToFront() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            java.util.List<ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks == null || tasks.isEmpty()) {
                log("bringOurTaskToFront: no own task");
                return;
            }
            for (ActivityManager.AppTask task : tasks) {
                try {
                    task.moveToFront();
                    log("bringOurTaskToFront: moved own task to front -> target backgrounded");
                    return;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            logError("bringOurTaskToFront error", t);
        }
    }

    @Override
    public void onDestroy() {
        log("onDestroy");
        if (scanner != null && scanning) {
            try { scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
        }
        handler.removeCallbacks(heartbeat);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
