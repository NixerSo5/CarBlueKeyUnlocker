package com.nixer.carunlocker;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.List;
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
    private static final long TARGET_SCAN_STALE_MS = 25_000; // 高灵敏：目标久久不出现：ROM 可能已静默停扫
    private static final long PS_TARGET_STALE_MS = 90_000;   // 分层：低功耗扫描本来就更慢，别频繁重启
    private static final long PS_NO_RESULT_MS = 180_000;     // 分层：过滤后几乎不会有其它设备回调
    private static final long BOOST_MS = 30_000;             // 发现车后临时升到低延迟 + 持锁
    private static final long TARGET_GONE_MS = 10_000;      // 目标消失超过 10 秒视为离开，重置连续计数
    private static final long DK_TRIGGER_COOLDOWN_MS = 90_000; // 自动打开数字钥匙页的最小间隔
    private static final int DEFAULT_NEAR_RSSI = -70;  // 走到车边通常 -50~-65，别让用户罚站
    private static final int DEFAULT_FAR_RSSI = -85;   // 口袋/遮挡会衰减 10dB，太早会误判走远
    private static final int NEAR_CONFIRM = 2;         // 采样 800ms，约 1.6s
    private static final int FAR_CONFIRM = 3;          // 连续 3 次弱信号才算走远，防口袋抖动
    private static final long LOCK_GRACE_MS = 8_000;   // 解锁后 8s 内不自动锁，避免刚开门就锁
    private static final long AUTO_COOLDOWN_MS = 8_000; // 防重复触发，默认 8s（界面可改 5~10s）
    public static final String ACTION_MANUAL_UNLOCK = "com.nixer.carunlocker.action.MANUAL_UNLOCK";
    public static final String ACTION_MANUAL_LOCK = "com.nixer.carunlocker.action.MANUAL_LOCK";
    public static final String ACTION_RESTART_SCAN = "com.nixer.carunlocker.action.RESTART_SCAN";
    public static final String ACTION_RELOAD_KEYS = "com.nixer.carunlocker.action.RELOAD_KEYS";

    private BleDirectUnlocker bleUnlocker;  // 当前正在执行解锁/锁车的会话
    private BleDirectUnlocker bleSession;   // 解锁成功后保活的已认证会话（一键启动 + 走远直接锁车）
    private boolean bleRunning = false;
    private boolean sessionLockInProgress = false;
    private boolean lockNeeded = false;  // 本 App 解锁成功、且还没成功/放弃锁车
    private long unlockedAtMs = 0;
    private long lastAutoUnlock = 0;
    private long lastAutoLock = 0;
    private int awayCount = 0;
    private long farSinceMs = 0;
    private boolean screenOff = false;
    private boolean scanCode1Logged = false;
    private boolean powerSave = true;      // 默认分层，界面可切到高灵敏
    private boolean scanHigh = false;      // 当前是否低延迟扫描
    private long boostUntilMs = 0;         // 分层模式下的高灵敏窗口截止

    /** 锁屏时防止 CPU 进入休眠，保证 BLE 回调和心跳继续跑；每次心跳续 60 秒。 */
    private PowerManager.WakeLock wakeLock;

    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser wakeAdvertiser;
    private AdvertiseCallback wakeAdvCallback;
    private boolean wakeAdvActive;
    private long lastWakeAdvMs;
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

    // 距离判断状态（阈值/冷却可在界面改，服务每次启动读取）
    private int nearRssi = DEFAULT_NEAR_RSSI;
    private int farRssi = DEFAULT_FAR_RSSI;
    private long autoCooldownMs = AUTO_COOLDOWN_MS;
    private int nearCount = 0;
    private long lastSampleTime = 0;
    private long lastResultTime = 0;  // 最近一次收到任意广播的时间，用于识别“假扫描中”
    private long lastTargetTime = 0;  // 最近一次收到目标广播的时间，用于超时重置

    /** 息屏/亮屏立刻续唤醒锁并重启扫描；nubia 锁屏会静默停掉 BLE 扫描。 */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOff = true;
                if (powerSave) {
                    // 分层：低功耗扫描 + 10 秒唤醒窗口，窗口过后让 CPU 睡
                    acquireWakeLock(10_000);
                    log("屏幕熄灭：分层模式，低功耗扫描 + 10s 唤醒窗口");
                    if (!bleRunning) restartScan(); // nubia 息屏会静默停扫，必须重新 start
                } else {
                    acquireWakeLock(60_000);
                    log("屏幕熄灭：高灵敏模式，续唤醒锁并重启低延迟扫描");
                    if (!bleRunning) restartScan();
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                screenOff = false;
                if (powerSave) {
                    // 点亮屏幕通常意味着用户要上车：给 10 秒升档窗口
                    boostUntilMs = SystemClock.elapsedRealtime() + 10_000;
                    acquireWakeLock(10_000);
                    log("屏幕点亮：分层模式，临时升档 10s");
                    if (!bleRunning) restartScan();
                } else {
                    acquireWakeLock(60_000);
                    log("屏幕点亮/解锁：恢复高灵敏扫描");
                    if (!bleRunning) restartScan();
                }
            }
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            boolean boosted = powerSave && now < boostUntilMs;
            if (bleRunning) {
                // 解锁/锁车期间必须持锁；结束后在 onFinished 里按模式释放
                acquireWakeLock(60_000);
            } else if (!powerSave || boosted) {
                // 高灵敏 / 发现车后的短窗口：保持 CPU 运行，BLE 回调才不会停
                acquireWakeLock(powerSave ? BOOST_MS : 60_000);
            } else {
                releaseWakeLock(); // 分层待机：让 CPU 进入深度睡眠
                refreshScanMode(); // 升档窗口到期后降回低功耗
            }
            log("heartbeat, mode=" + (powerSave ? "分层" : "高灵敏")
                    + ", scan=" + (scanHigh ? "低延迟" : "低功耗") + (boosted ? "(升档中)" : "")
                    + ", scanning=" + scanning + ", attempts=" + scanAttempts + ", nearCount=" + nearCount);
            // 系统可能在无回调的情况下静默停掉 BLE 扫描；心跳不能只看布尔标志，要看是否真有广播进来
            long quiet = now - lastResultTime;
            long targetQuiet = now - lastTargetTime;
            long staleLimit = powerSave ? PS_TARGET_STALE_MS : TARGET_SCAN_STALE_MS;
            long quietLimit = powerSave ? PS_NO_RESULT_MS : NO_RESULT_RESTART_MS;
            if (bleRunning) {
                // 解锁/锁车期间暂停扫描是故意的，跳过扫描健康检查
            } else if (!scanning) {
                log("scanning=false，心跳重新拉起扫描");
                if (powerSave) acquireWakeLock(10_000);
                startScan();
            } else if (quiet > quietLimit || targetQuiet > staleLimit) {
                // 息屏/口袋里 ROM 会静默停掉扫描但 scanning 仍是 true；按目标广播超时强制重启
                log("BLE 广播静默（任意 " + (quiet / 1000) + "s / 目标 " + (targetQuiet / 1000) + "s），重启扫描");
                if (powerSave) acquireWakeLock(10_000);
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
        AppLog.auto(this, msg);
    }

    private void logError(String msg, Throwable t) {
        AppLog.error(this, msg, t);
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

            // 钥匙登录后已绑定 MAC：只认这一台车，避免因为名称相同误连其它车辆/蓝牙设备。
            boolean matched = !TextUtils.isEmpty(targetMac)
                    ? targetMac.equalsIgnoreCase(addr)
                    : (!TextUtils.isEmpty(targetName) && name != null && name.equalsIgnoreCase(targetName));
            if (!matched) return;

            // 127 = 扩展广播不提供 RSSI，无效值，不能当成“很近”
            if (rssi >= 0) {
                // 127 = 车在睡眠广播。先发一段存在广播叫醒它，但不连接、不解锁。
                lastTargetTime = SystemClock.elapsedRealtime();
                maybeStartWakeAd();
                return;
            }

            // 实时信号强度写到 prefs，界面显示
            AppPrefs.set(CarService.this, AppPrefs.K_CURRENT_RSSI, rssi + " dBm");
            boostForTarget(); // 分层模式：扫到目标立刻升档，后续连接认证不受低功耗影响

            long now = SystemClock.elapsedRealtime();
            if (nearCount > 0 && now - lastTargetTime > TARGET_GONE_MS) nearCount = 0;
            if (awayCount > 0 && now - lastTargetTime > TARGET_GONE_MS) {
                // 广播消失：还连着车就立刻锁，别等走远了再重连
                awayCount = 0;
                farSinceMs = 0;
                if (lockNeeded && now - unlockedAtMs >= LOCK_GRACE_MS
                        && bleSession != null && bleSession.canLockOnSession()
                        && !sessionLockInProgress && !bleRunning) {
                    lastAutoLock = now;
                    sessionLockInProgress = true;
                    log("目标广播消失，复用保活连接立即锁车");
                    bleSession.lockOnSession();
                }
            }
            lastTargetTime = now;

            if (now - lastSampleTime < SAMPLE_INTERVAL_MS) return; // 采样节流
            lastSampleTime = now;

            if (rssi >= nearRssi) {
                awayCount = 0;
                farSinceMs = 0;
                nearCount++;
                if (nearCount >= NEAR_CONFIRM) {
                    nearCount = 0;
                    if (bleRunning || bleSession != null) return; // 已连接/正在操作，不用重复解锁
                    if (now - lastAutoUnlock < autoCooldownMs) return;
                    lastAutoUnlock = now;
                    String seen = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                    AppPrefs.set(CarService.this, AppPrefs.K_LAST_SEEN, seen + " / " + addr + " / " + rssi + "dBm");
                    log("靠近解锁: " + rssi + "dBm");
                    startBleAction(rssi, name, BleDirectUnlocker.MODE_UNLOCK, false);
                }
            } else if (rssi <= farRssi) {
                nearCount = 0;
                if (bleRunning || sessionLockInProgress) return;
                // 只锁本 App 解锁过的车：车端原本就锁着时不再发上锁指令（真车会返回 01 并反复重试）
                if (!lockNeeded) return;
                if (now - unlockedAtMs < LOCK_GRACE_MS) return; // 刚解锁先别锁，避免门口信号抖动
                if (farSinceMs == 0) farSinceMs = now;
                awayCount++;
                log("目标走远: " + rssi + " dBm, 连续 = " + awayCount + "/" + FAR_CONFIRM);
                if (awayCount < FAR_CONFIRM) return;
                awayCount = 0;
                farSinceMs = 0;
                if (now - lastAutoLock < autoCooldownMs) return;
                lastAutoLock = now;
                if (bleSession != null && bleSession.canLockOnSession()) {
                    // 关键：直接在已认证保活连接上锁，不重新连接、不重新认证
                    sessionLockInProgress = true;
                    log("走远自动锁车：复用已认证连接");
                    bleSession.lockOnSession();
                } else if (rssi >= -88) {
                    // 没有可用的保活连接但信号还能连上：兜底新建连接锁车
                    startBleAction(rssi, name, BleDirectUnlocker.MODE_LOCK, false);
                } else {
                    // 太弱了连不上：停掉存在广播，让车端自己判定钥匙远离并上锁
                    log("信号过弱，停止存在广播，交由车端自动锁车");
                    if (bleUnlocker != null) bleUnlocker.stop();
                    lockNeeded = false; // 已交给车端，不再重复发指令
                }
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("scan failed, code=" + errorCode);
            if (errorCode == 1) { // SCAN_FAILED_ALREADY_STARTED：首次扫描仍在跑，无需重试
                if (!scanCode1Logged) {
                    log("scan already active (code=1), ignored（后续不再重复记录）");
                    scanCode1Logged = true;
                }
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
        powerSave = AppPrefs.powerSave(this);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarGuard:BackgroundScan");
                wakeLock.setReferenceCounted(false);
                acquireWakeLock(powerSave ? 10_000 : 60_000);
            }
        } catch (Throwable t) {
            logError("wakeLock init failed", t);
        }
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
        // 动态注册屏幕广播：nubia 息屏会静默停掉 BLE 扫描，必须立刻重启并续唤醒锁
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenReceiver, filter);
            log("屏幕状态监听已注册");
        } catch (Throwable t) {
            logError("屏幕状态监听注册失败", t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        powerSave = AppPrefs.powerSave(this);
        boostUntilMs = 0;
        acquireWakeLock(powerSave ? 10_000 : 60_000);
        log("onStartCommand, mode=" + (powerSave ? "分层" : "高灵敏"));
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RESTART_SCAN.equals(action)) {
            // 闹钟只检查扫描，不重置距离状态和计数，避免干扰靠近/远离判断
            acquireWakeLock(powerSave ? 15_000 : 60_000);
            log("闹钟保活：检查扫描状态");
            if (!bleRunning && !scanning) startScan();
            return START_STICKY;
        }
        if (ACTION_RELOAD_KEYS.equals(action)) {
            // 短信下载的新钥匙已写入：立刻丢弃旧会话，避免继续用旧钥匙保活/锁车
            acquireWakeLock(powerSave ? 15_000 : 60_000);
            log("钥匙已更新：清理旧 BLE 会话并重新加载目标 MAC");
            if (bleUnlocker != null) { bleUnlocker.stop(); bleUnlocker = null; }
            if (bleSession != null) { bleSession.stop(); bleSession = null; }
            bleRunning = false;
            sessionLockInProgress = false;
            lockNeeded = false;
            AppPrefs.set(this, AppPrefs.K_CAR_CONNECTED, "0");
            AppPrefs.set(this, AppPrefs.K_CAR_DOOR, "");
            unlockedAtMs = 0;
            nearCount = 0;
            awayCount = 0;
            farSinceMs = 0;
            lastAutoUnlock = 0;
            lastAutoLock = 0;
            targetMac = AppPrefs.get(this, AppPrefs.K_BLE_MAC);
            targetName = AppPrefs.get(this, AppPrefs.K_TARGET_NAME);
            if (!TextUtils.isEmpty(targetMac)) {
                AppPrefs.set(this, AppPrefs.K_TARGET_MAC, targetMac);
            }
            AppPrefs.set(this, AppPrefs.K_BLE_STATE, "钥匙已更新，等待操作");
            startScan();
            return START_STICKY;
        }
        targetMac = AppPrefs.targetMac(this);
        targetName = AppPrefs.targetName(this);
        // 兜底：即使服务是被系统/看门狗直接拉起、没进过界面，也能拿到本机配置的车辆 MAC
        if (TextUtils.isEmpty(targetMac)) {
            targetMac = AppPrefs.get(this, AppPrefs.K_BLE_MAC);
            if (!TextUtils.isEmpty(targetMac)) {
                AppPrefs.set(this, AppPrefs.K_TARGET_MAC, targetMac);
            }
        }
        launchPackage = SAIC_PACKAGE;
        launchLabel = "上汽荣威";
        nearRssi = AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, DEFAULT_NEAR_RSSI);
        farRssi = AppPrefs.getInt(this, AppPrefs.K_RSSI_EXIT, DEFAULT_FAR_RSSI);
        // 迁移旧的 12dB 滞回（-70/-82）——口袋里 -84 就会反复锁车，强制至少 15dB
        if (farRssi > nearRssi - 15) farRssi = nearRssi - 15;
        int cooldownSec = AppPrefs.getInt(this, AppPrefs.K_COOLDOWN_SEC, (int) (AUTO_COOLDOWN_MS / 1000));
        if (cooldownSec < 1) cooldownSec = 1;
        autoCooldownMs = cooldownSec * 1000L;
        nearCount = 0;
        awayCount = 0;
        farSinceMs = 0;
        lockNeeded = false; // 服务重启后不再假设车还是本 App 解锁的
        AppPrefs.set(this, AppPrefs.K_CAR_CONNECTED, "0");
        AppPrefs.set(this, AppPrefs.K_CAR_DOOR, ""); // 旧状态可能已失效，连接后由车端查询刷新
        unlockedAtMs = 0;
        lastResultTime = SystemClock.elapsedRealtime();
        lastTargetTime = lastResultTime;
        log("target = " + targetName + " / " + targetMac
                + " | 靠近>=" + nearRssi + "dBm×" + NEAR_CONFIRM
                + " / 走远<=" + farRssi + "dBm×" + FAR_CONFIRM
                + " / 冷却=" + cooldownSec + "s"
                + " / 功耗=" + (powerSave ? "分层" : "高灵敏"));
        startForeground(1, buildNotification());
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
        // v1.5：不再拉活/依赖上汽 App，靠近后由本 App 自己连车解锁
        handler.removeCallbacks(keepAliveCheck);
        handler.removeCallbacks(sdkInitTask);
        handler.removeCallbacks(sdkScanTask);
        startScan();
        // 该 ROM 上第一次 startScan 常常没有回调，300ms 后再直接调一次（不 stop）
        handler.postDelayed(this::startScan, 300);
        if (ACTION_MANUAL_UNLOCK.equals(action)) {
            startBleAction(0, "手动测试", BleDirectUnlocker.MODE_UNLOCK, true);
        } else if (ACTION_MANUAL_LOCK.equals(action)) {
            startBleAction(0, "手动锁车", BleDirectUnlocker.MODE_LOCK, true);
        }
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

    /** 非引用计数 WakeLock：重复 acquire 只刷新到期时间；服务被杀时系统自动释放。 */
    private void acquireWakeLock(long ms) {
        if (wakeLock == null) return;
        try {
            wakeLock.acquire(ms);
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        if (wakeLock == null) return;
        try {
            if (wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
    }

    private boolean highScanNow() {
        return !powerSave || SystemClock.elapsedRealtime() < boostUntilMs;
    }

    /** 分层模式下扫到目标：临时升档 30s，保证连接认证期间不休眠/不降速。 */
    private void boostForTarget() {
        if (!powerSave) return;
        boolean wasHigh = highScanNow();
        boostUntilMs = SystemClock.elapsedRealtime() + BOOST_MS;
        acquireWakeLock(BOOST_MS);
        if (!wasHigh) {
            log("发现目标：分层模式临时升档到低延迟扫描（" + (BOOST_MS / 1000) + "s）");
            refreshScanMode();
        }
    }

    /** 扫描模式与目标不一致时重建扫描；分层待机用低功耗+MAC 过滤，发现车后再升档。 */
    private void refreshScanMode() {
        if (bleRunning || !scanning || scanner == null) return;
        boolean wantHigh = highScanNow();
        if (wantHigh != scanHigh) {
            log("BLE 扫描模式切换：" + (scanHigh ? "低延迟" : "低功耗") + " -> "
                    + (wantHigh ? "低延迟" : "低功耗"));
            restartScan();
        }
    }

    private void startScan() {
        if (bleRunning) {
            log("BLE 解锁中，暂停扫描");
            return;
        }
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
            // 分层待机用低功耗扫描 + 硬件 MAC 过滤；发现车后短时切低延迟
            boolean wantHigh = highScanNow();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(wantHigh ? ScanSettings.SCAN_MODE_LOW_LATENCY
                            : ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            List<ScanFilter> filters = null;
            if (powerSave && !TextUtils.isEmpty(targetMac)) {
                filters = Collections.singletonList(
                        new ScanFilter.Builder().setDeviceAddress(targetMac).build());
            }
            if (scanning && scanner != null) {
                if (scanHigh != wantHigh) {
                    // 模式变了必须 stop 再 start，否则 ROM 会继续用旧模式
                    restartScan();
                    return;
                }
                // 关键：不 stop，直接再 start 一次，ROM 才会开始回调
                log("second startScan call (no stop, ROM workaround)");
                scanner.startScan(filters, settings, scanCallback);
                log("BLE scan re-trigger requested");
                return;
            }
            scanner.startScan(filters, settings, scanCallback);
            scanning = true;
            scanHigh = wantHigh;
            log("BLE scan started OK, mode=" + (wantHigh ? "低延迟" : "低功耗")
                    + (filters == null ? "" : " + MAC 过滤"));
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

    /** 车在睡眠时只发 RSSI=127 的广播；先发一段存在广播叫醒它，不连接、不解锁。 */
    private void maybeStartWakeAd() {
        if (bleRunning) return;
        long now = SystemClock.elapsedRealtime();
        if (wakeAdvActive || now - lastWakeAdvMs < 60_000) return;
        lastWakeAdvMs = now;
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return;
            wakeAdvertiser = adapter.getBluetoothLeAdvertiser();
            if (wakeAdvertiser == null) return;
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder().setIncludeTxPowerLevel(true).build();
            wakeAdvCallback = new AdvertiseCallback() {
                @Override public void onStartSuccess(AdvertiseSettings s) {
                    wakeAdvActive = true;
                    log("唤醒广播已启动（车在睡眠，先叫醒它）");
                }
                @Override public void onStartFailure(int code) {
                    wakeAdvActive = false;
                    log("唤醒广播启动失败 code=" + code);
                }
            };
            wakeAdvertiser.startAdvertising(settings, data, wakeAdvCallback);
            handler.postDelayed(this::stopWakeAd, 12_000);
        } catch (Throwable t) {
            log("唤醒广播异常: " + t);
        }
    }

    private void stopWakeAd() {
        AdvertiseCallback cb = wakeAdvCallback;
        wakeAdvCallback = null;
        try {
            if (wakeAdvertiser != null && cb != null) wakeAdvertiser.stopAdvertising(cb);
        } catch (Throwable ignored) {}
        wakeAdvertiser = null;
        wakeAdvActive = false;
    }

    /** 靠近解锁 / 远离锁车：自己连车、自己认证（不经过上汽 App）。 */
    private void startBleAction(int rssi, String name, final int mode, boolean manual) {
        final String actionName = mode == BleDirectUnlocker.MODE_LOCK ? "锁车" : "解锁";
        if (bleRunning) {
            log("BLE: 正在操作中，跳过本次触发");
            return;
        }
        // 新建连接前才断开旧保活连接；顺序必须在冷却判断之后，避免白断
        if (bleSession != null) {
            log("BLE: 断开旧保活连接，为本次" + actionName + "腾出会话");
            bleSession.stop();
            bleSession = null;
        }
        stopWakeAd();
        bleRunning = true;
        if (powerSave) {
            // 连接/认证期间保持 60s 高灵敏窗口，避免分层策略把正在握手的车断开
            boostUntilMs = SystemClock.elapsedRealtime() + 60_000;
        }
        acquireWakeLock(60_000);
        AppPrefs.set(this, AppPrefs.K_BLE_STATE, actionName + "中…");
        if (isAppRunning(SAIC_PACKAGE)) {
            log("BLE 警告：上汽荣威 App 进程仍在运行，可能占用车端认证会话！请强行停止它");
        }
        // 操作期间暂停扫描，减少射频竞争，把时间片让给存在广播
        try {
            if (scanner != null && scanning) scanner.stopScan(scanCallback);
        } catch (Throwable ignored) {}
        scanning = false;
        log("BLE: 开始" + (manual ? "手动" : "自动") + actionName + "（" + rssi + "dBm / " + name + "）");
        final int[] resultMode = {mode};
        bleUnlocker = new BleDirectUnlocker(this, targetMac, mode, new BleDirectUnlocker.Callback() {
            @Override
            public void log(String msg) {
                CarService.this.log("[BLE] " + msg);
            }

            @Override
            public void onDoorState(boolean locked) {
                // 车端状态查询/执行结果：主页据此显示“已锁/已开”，并避免重复发同一指令
                AppPrefs.set(CarService.this, AppPrefs.K_CAR_DOOR, locked ? "locked" : "unlocked");
                log("车门状态更新: " + (locked ? "已锁" : "已开"));
            }

            @Override
            public void onFinished(boolean success, String detail) {
                bleRunning = false;
                if (sessionLockInProgress) {
                    resultMode[0] = BleDirectUnlocker.MODE_LOCK; // 保活会话上执行的是锁车
                    sessionLockInProgress = false;
                    bleUnlocker = null;
                    bleSession = null;
                    AppPrefs.set(CarService.this, AppPrefs.K_CAR_CONNECTED, "0");
                } else if (success && resultMode[0] == BleDirectUnlocker.MODE_UNLOCK) {
                    bleSession = bleUnlocker; // 解锁成功后保留会话：一键启动 + 走远直接锁车
                    lockNeeded = true;       // 只有本 App 解锁过的车才需要自动上锁
                    unlockedAtMs = SystemClock.elapsedRealtime();
                    AppPrefs.set(CarService.this, AppPrefs.K_CAR_CONNECTED, "1");
                } else {
                    // 保活连接断开时也会走这里，主页立刻显示“未连接”
                    if (bleSession != null && resultMode[0] == BleDirectUnlocker.MODE_UNLOCK) {
                        AppPrefs.set(CarService.this, AppPrefs.K_CAR_CONNECTED, "0");
                    }
                    bleUnlocker = null;
                }
                String done = resultMode[0] == BleDirectUnlocker.MODE_LOCK ? "锁车" : "解锁";
                if (resultMode[0] == BleDirectUnlocker.MODE_LOCK && success) {
                    // 锁车后清零状态：下次靠近可以立刻自动解锁，不再被旧冷却卡住
                    AppPrefs.set(CarService.this, AppPrefs.K_CAR_CONNECTED, "0");
                    lockNeeded = false;
                    lastAutoUnlock = 0;
                    lastAutoLock = 0;
                    nearCount = 0;
                    awayCount = 0;
                    farSinceMs = 0;
                }
                AppPrefs.set(CarService.this, AppPrefs.K_BLE_STATE,
                        (success ? done + "成功" : done + "失败") + " · " + detail);
                log("BLE: " + (success ? done + "成功" : done + "失败") + " :: " + detail);
                if (powerSave && !highScanNow()) releaseWakeLock();
                startScan();
            }

            @Override
            public void onKeepAliveClosed() {
                // 解锁已经成功，这里只结束保活会话，不能把结果改写成失败
                bleRunning = false;
                bleUnlocker = null;
                bleSession = null;
                lockNeeded = false;
                AppPrefs.set(CarService.this, AppPrefs.K_CAR_CONNECTED, "0");
                log("BLE: 解锁已完成，保活连接结束（车辆可能自动上锁）");
                startScan();
            }
        });
        final BleDirectUnlocker unlocker = bleUnlocker;
        handler.postDelayed(() -> {
            if (unlocker != null && bleRunning) unlocker.start();
        }, 450);
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
        stopWakeAd();
        if (scanner != null && scanning) {
            try { scanner.stopScan(scanCallback); } catch (Throwable ignored) {}
        }
        handler.removeCallbacks(heartbeat);
        handler.removeCallbacks(keepAliveCheck);
        handler.removeCallbacks(sdkInitTask);
        handler.removeCallbacks(sdkScanTask);
        try { unregisterReceiver(screenReceiver); } catch (Throwable ignored) {}
        if (bleUnlocker != null) {
            bleUnlocker.stop();
            bleUnlocker = null;
        }
        if (bleSession != null) {
            bleSession.stop();
            bleSession = null;
        }
        if (daemonBound) {
            try { unbindService(daemonConnection); } catch (Throwable ignored) {}
            daemonBound = false;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Throwable ignored) {}
            wakeLock = null;
        }
        super.onDestroy();
    }

    /** 用户从最近任务划掉时，立刻排一次闹钟把自己拉回来。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            KeepAliveReceiver.scheduleNext(this);
            Intent i = new Intent(this, CarService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable ignored) {
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
