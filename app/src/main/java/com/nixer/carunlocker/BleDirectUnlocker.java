package com.nixer.carunlocker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 直接连车、完成 ICCE 认证并发送 RKE 解锁，完全不经过上汽 App。
 *
 * 流程：存在广播唤醒 -> 连接 GATT -> 开通知 -> 握手 -> step1 -> step2 -> RKE 挑战 -> 解锁。
 */
public final class BleDirectUnlocker {
    public interface Callback {
        void log(String msg);
        void onFinished(boolean unlocked, String detail);
        /** 车端状态查询/执行结果，供主页显示车门状态并避免重复指令。 */
        default void onDoorState(boolean locked) {}
        /** 解锁成功后的保活连接结束（正常到期或车端断开），不改变解锁结果。 */
        default void onKeepAliveClosed() {}
        /** 预连接已认证，等待靠近后执行解锁。 */
        default void onReady() {}
        /** 预连接被取消/超时，未执行任何解锁。 */
        default void onPrepareClosed() {}
    }

    private static final UUID SVC = UUID.fromString("0000FFF0-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE = UUID.fromString("0000FFFD-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String HANDSHAKE = "5A000B0000020800010001020201B436";

    private static final int RKE_TYPE_DOOR = 1;
    /** 与官方 ControlCmdType 一致：CAR_DOOR_UNLOCK=0 / CAR_DOOR_LOCK=1。 */
    public static final int MODE_UNLOCK = 0;
    public static final int MODE_LOCK = 1;

    private static final long WAKE_DELAY_MS = 1_000;
    private static final long OVERALL_TIMEOUT_MS = 35_000;
    /** 连上后车端这么久还不发 0208，就断开重连：车只在“新连接”上发起握手。 */
    private static final long SILENT_RECONNECT_MS = 1_500;
    /** 解锁成功后保持 GATT，覆盖上车一键启动（车端要求数字钥匙持续在线）。 */
    private static final long KEEP_ALIVE_MS = 5 * 60_000;
    /** 查询车辆锁状态的等待时间；超时或无法解析就按兜底直接发 RKE。 */
    private static final long STATUS_QUERY_WAIT_MS = 1200;
    /** 预连接认证成功后等待用户靠近的最长时间。 */
    private static final long PREPARE_TIMEOUT_MS = 120_000;

    private final Context ctx;
    private final String mac;
    private int mode; // lockOnSession() 会把保活会话切到锁车模式
    private final Callback cb;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothGatt gatt;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback wakeCallback;
    private int wakeRetry = 0;
    private BluetoothGattCharacteristic writeChar;
    private final List<BluetoothGattCharacteristic> notifyQueue = new ArrayList<>();
    private int notifyIndex = 0;

    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private boolean writing = false;
    private int mtu = 23;

    private static final class Challenge {
        final byte[] vrnd;
        final byte[] prnd;
        final byte[] k2;
        Challenge(byte[] vrnd, byte[] prnd, byte[] k2) {
            this.vrnd = vrnd;
            this.prnd = prnd;
            this.k2 = k2;
        }
    }

    private byte[] vrnd;
    private byte[] prnd;
    private byte[] k2;
    private final List<Challenge> challenges = new ArrayList<>();
    private final Runnable handshakeTask = this::scheduleHandshake;

    private boolean step1Sent;
    private int step1Count;
    private boolean step2Sent;
    private boolean authResultSeen;
    private boolean rkeSent;
    private boolean connected;
    private boolean rxSeen;
    private int handshakeTries;
    private int connectAttempts = 0;
    private int authRetries = 0;

    private long connectedAtMs = 0;
    private long startedAtMs = 0;
    private long readyAtMs = 0;
    /** cycleConnection() 主动断开时置位，避免 onConnectionStateChange 再排一次重连。 */
    private boolean reconnecting = false;
    private int writeFailCount = 0;

    private boolean finished;
    /** 解锁成功后不 cleanup，保活待启动；此标志防止重复 finish。 */
    private boolean sessionDone;

    private boolean statusQuerySent;
    private boolean statusResponded;
    private Boolean statusLocked; // true=已锁 / false=已开 / null=查询不到
    private int statusFrameCount;
    private boolean keepAliveActive;
    private final Runnable keepAliveExpiry = this::expireKeepAlive;

    /** 预连接：先完成认证并保持连接，等用户靠近再发 RKE。 */
    private boolean prepareOnly;
    private boolean readyForAction;
    private final Runnable overallTimeout = () -> {
        if (!finished && !sessionDone && !readyForAction) {
            finish(false, (OVERALL_TIMEOUT_MS / 1000) + "s 内车未进入钥匙会话。请触摸门把手或按遥控钥匙唤醒车辆；车醒后本 App 会自动完成认证" + actionName() + "，无需上汽 App，也不需要网络。");
        }
    };
    private final Runnable prepareTimeout = this::onPrepareTimeout;
    private void onPrepareTimeout() {
        if (finished || !prepareOnly) return;
        log("预连接等待靠近超时，断开连接");
        prepareOnly = false;
        readyForAction = false;
        finished = true;
        cleanup();
        cb.onPrepareClosed();
    }

    public BleDirectUnlocker(Context ctx, String mac, int mode, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.mac = normalizeMac(mac);
        this.mode = mode == MODE_LOCK ? MODE_LOCK : MODE_UNLOCK;
        this.cb = cb;
    }

    private String actionName() {
        return mode == MODE_LOCK ? "锁车" : "解锁";
    }

    public void start() {
        if (!hasKeys()) {
            finish(false, "未配置钥匙，请先在设置中登录并获取钥匙");
            return;
        }
        if (TextUtils.isEmpty(mac)) {
            finish(false, "未选择车辆 MAC");
            return;
        }
        authRetries = 0;
        startedAtMs = SystemClock.elapsedRealtime();
        log("开始直接" + (prepareOnly ? "预连接" : actionName()) + "，目标 " + mac);
        if (prepareOnly) {
            log("提前连接车辆并完成认证，等待靠近后再解锁");
            handler.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        } else {
            log("存在广播已启动，立即连接车辆并持续握手");
        }
        handler.postDelayed(overallTimeout, OVERALL_TIMEOUT_MS);
        startWakeAdvertising();
        handler.postDelayed(this::connect, WAKE_DELAY_MS);
    }

    public void setPrepareOnly(boolean prepareOnly) {
        this.prepareOnly = prepareOnly;
    }

    public boolean isReadyForAction() {
        return !finished && readyForAction && connected && gatt != null && writeChar != null
                && k2 != null && !reconnecting && !writing;
    }

    /** 用户已靠近：在预连接会话上立即执行解锁。 */
    public boolean requestUnlock() {
        if (finished || !readyForAction) return false;
        prepareOnly = false;
        readyForAction = false;
        handler.removeCallbacks(prepareTimeout);
        mode = MODE_UNLOCK;
        statusQuerySent = false;
        statusResponded = false;
        statusLocked = null;
        rkeSent = false;
        log("已靠近，使用预连接立即发送解锁（就绪后 " + (SystemClock.elapsedRealtime() - readyAtMs) + "ms / 启动后 " + (SystemClock.elapsedRealtime() - startedAtMs) + "ms）");
        queryLockStatus();
        return true;
    }

    /** 取消预连接（用户没有靠近/走远），不产生解锁失败提示。 */
    public void cancelPrepare() {
        if (!prepareOnly) return;
        prepareOnly = false;
        readyForAction = false;
        if (!finished) {
            finished = true;
            cleanup();
        }
        cb.onPrepareClosed();
    }

    public void stop() {
        if (prepareOnly) {
            cancelPrepare();
            return;
        }
        if (!finished) {
            finished = true;
            cleanup();
        }
    }

    private boolean hasKeys() {
        return !TextUtils.isEmpty(AppPrefs.get(ctx, AppPrefs.K_BLE_KS))
                && !TextUtils.isEmpty(AppPrefs.get(ctx, AppPrefs.K_BLE_KEY_ID))
                && !TextUtils.isEmpty(AppPrefs.get(ctx, AppPrefs.K_BLE_KR));
    }

    private void log(String msg) {
        cb.log(msg);
    }

    private void finish(boolean success, String detail) {
        if (finished || sessionDone) return;
        sessionDone = true;
        if (success) cb.onDoorState(mode == MODE_LOCK);
        if (success && mode == MODE_UNLOCK) {
            // 解锁成功后不断开：车端一键启动要求数字钥匙持续在线。
            log("执行完成，启动后 " + (SystemClock.elapsedRealtime() - startedAtMs) + "ms");
            log("解锁成功，保持 BLE 连接 " + (KEEP_ALIVE_MS / 60_000) + " 分钟供一键启动使用");
            keepAliveActive = true;
            handler.removeCallbacks(keepAliveExpiry);
            handler.postDelayed(keepAliveExpiry, KEEP_ALIVE_MS);
            cb.onFinished(success, detail);
            return;
        }
        finished = true;
        cleanup();
        cb.onFinished(success, detail);
    }

    private void expireKeepAlive() {
        if (finished || !keepAliveActive) return;
        log("启动保活结束，断开 BLE 连接");
        keepAliveActive = false;
        finished = true;
        cleanup();
        cb.onKeepAliveClosed();
    }

    /**
     * 走远自动锁车：直接在已经认证的保活连接上发 RKE 上锁。
     * 不重新连接，也不等 60s 冷却——车边信号没断就能立刻锁。
     */
    /** 保活会话是否仍可用于直接锁车；正在重连/重新认证时不能复用，否则会打断握手。 */
    public boolean canLockOnSession() {
        return !finished && keepAliveActive && connected && gatt != null && writeChar != null
                && k2 != null && authResultSeen && !reconnecting && !writing;
    }

    public void lockOnSession() {
        if (!canLockOnSession()) {
            cb.onFinished(false, "保活会话不可用或正在重连，无法锁车");
            return;
        }
        log("走远自动锁车：复用已认证连接发送上锁指令");
        keepAliveActive = false;
        handler.removeCallbacks(keepAliveExpiry);
        mode = MODE_LOCK;
        finished = false;
        sessionDone = false;
        authRetries = 0;
        statusQuerySent = false;
        statusResponded = false;
        statusLocked = null;
        rkeSent = false;
        queryLockStatus();
    }

    /** 只重置 GATT 和会话状态，保留整体超时计时器，用于“连上但车端不说话”时重连。 */
    private void resetGattOnly() {
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Throwable ignored) {}
            try { gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
        txQueue.clear();
        writing = false;
        writeFailCount = 0;
        notifyQueue.clear();
        notifyIndex = 0;
        writeChar = null;
        connected = false;
        vrnd = null; prnd = null; k2 = null;
        challenges.clear();
        step1Sent = false; step1Count = 0; step2Sent = false;
        authResultSeen = false; rkeSent = false;
        rxSeen = false;
        handshakeTries = 0;
    }

    private void scheduleHandshake() {
        if (finished || !connected || gatt == null || writeChar == null) return;
        if (rxSeen || step1Sent) return;
        handshakeTries++;
        if (handshakeTries == 1 || handshakeTries % 5 == 0) {
            log("等待车端唤醒，持续握手（第 " + handshakeTries + " 次）");
        }
        enqueue(hexToBytes(HANDSHAKE));
        // 车不会在一条已经存在的连接上补发 0208；必须定期断线重连制造新会话。
        if (SystemClock.elapsedRealtime() - connectedAtMs >= SILENT_RECONNECT_MS) {
            handler.removeCallbacks(handshakeTask);
            handler.postDelayed(this::cycleConnection, 300);
            return;
        }
        handler.postDelayed(handshakeTask, 1000);
    }

    /** 车端沉默超过 5s：主动断开并重连，让车有机会在新连接上发 0208。 */
    private void cycleConnection() {
        if (finished || rxSeen || step1Sent) return;
        reconnecting = true;
        log("车端未进入钥匙会话，主动断开重连（重新触发握手）");
        resetGattOnly();
        handler.postDelayed(() -> {
            reconnecting = false;
            connect();
        }, 400);
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        stopAdvertising();
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Throwable ignored) {}
            try { gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
        txQueue.clear();
        writing = false;
    }

    // ---------------- 唤醒广播 ----------------

    private void startWakeAdvertising() {
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                log("蓝牙未开启");
                return;
            }
            // 关键：先停掉旧广播（必须用同一个 callback），否则会 code=2 广播实例已满
            if (wakeCallback != null) stopAdvertising();
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                log("设备不支持 BLE 广播，直接尝试连接");
                return;
            }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder().setIncludeTxPowerLevel(true).build();
            if (wakeCallback == null) {
                wakeCallback = new AdvertiseCallback() {
                    @Override public void onStartSuccess(AdvertiseSettings s) {
                        wakeRetry = 0;
                        log("存在广播已启动（唤醒车辆）");
                    }
                    @Override public void onStartFailure(int code) {
                        log("存在广播启动失败 code=" + code);
                        if (code == 2 && wakeRetry < 3 && !finished) {
                            wakeRetry++;
                            stopAdvertising();
                            final long delay = 900L * wakeRetry;
                            handler.postDelayed(BleDirectUnlocker.this::startWakeAdvertising, delay);
                        }
                    }
                };
            }
            advertiser.startAdvertising(settings, data, wakeCallback);
        } catch (Throwable t) {
            log("广播异常: " + t);
        }
    }

    private void stopAdvertising() {
        AdvertiseCallback cb = wakeCallback;
        wakeCallback = null;
        try {
            if (advertiser != null && cb != null) advertiser.stopAdvertising(cb);
        } catch (Throwable ignored) {
        }
        advertiser = null;
    }

    // ---------------- 连接 ----------------

    private void connect() {
        if (finished) return;
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                finish(false, "蓝牙未开启");
                return;
            }
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            connectAttempts++;
            log("连接车辆 " + mac + "（第 " + connectAttempts + " 次，启动后 " + (SystemClock.elapsedRealtime() - startedAtMs) + "ms）");
            if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(ctx, false, gattCb);
            }
            if (gatt == null) {
                log("connectGatt 返回 null");
                retryConnect();
            }
        } catch (Throwable t) {
            log("连接异常: " + t);
            retryConnect();
        }
    }

    /** 认证失败后不直接放弃：断线重连，在整体超时窗口内多试几次。 */
    private void retryAuth(String reason) {
        if (finished) return;
        authRetries++;
        if (authRetries > 5) {
            finish(false, reason + "，重试 5 次仍失败");
            return;
        }
        log(reason + "，重连重试（第 " + authRetries + " 次）");
        handler.removeCallbacks(handshakeTask);
        resetGattOnly();
        connectAttempts = 0;
        handler.postDelayed(this::connect, 1200);
    }

    private void retryConnect() {
        if (finished) return;
        // 车未广播时不要只试 3 次就放弃；在整个整体超时窗口内持续给车醒来连接的机会。
        long delay = 5000L;
        log("连接未成功，" + (delay / 1000) + "s 后重试（第 " + (connectAttempts + 1) + " 次）");
        handler.postDelayed(this::connect, delay);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            log("连接状态 status=" + status + " state=" + newState
                    + "（启动后 " + (SystemClock.elapsedRealtime() - startedAtMs) + "ms）");
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                connected = true;
                connectedAtMs = SystemClock.elapsedRealtime();
                g.requestMtu(517);
                g.discoverServices();
            } else {
                if (reconnecting) return; // cycleConnection() 自己会重连，忽略这条断开回调
                connected = false;
                handler.removeCallbacks(handshakeTask);
                // 必须在 resetGattOnly() 之前记录：它会把 step1Sent 清掉
                boolean hadAuth = step1Sent || step2Sent || authResultSeen;
                resetGattOnly();
                if (keepAliveActive) {
                    // 一键启动保活期间被车端断开：解锁已经完成，不能把结果改成失败
                    log("保活连接断开（解锁已完成，不影响已执行的开锁）");
                    keepAliveActive = false;
                    handler.removeCallbacks(keepAliveExpiry);
                    finished = true;
                    cleanup();
                    cb.onKeepAliveClosed();
                    return;
                }
                if (!hadAuth) {
                    retryConnect();
                } else {
                    finish(false, "车端断开（status=" + status + "）");
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            mtu = status == BluetoothGatt.GATT_SUCCESS ? newMtu : 23;
            log("MTU=" + mtu);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            log("服务发现 status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finish(false, "服务发现失败");
                return;
            }
            BluetoothGattService svc = g.getService(SVC);
            if (svc == null) {
                finish(false, "未找到 FFF0 服务");
                return;
            }
            writeChar = svc.getCharacteristic(WRITE);
            if (writeChar == null) {
                finish(false, "未找到写入特征 FFFD");
                return;
            }
            notifyQueue.clear();
            notifyIndex = 0;
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                            || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        notifyQueue.add(c);
                    }
                }
            }
            enableNext(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            log("开启通知 status=" + status + " " + d.getCharacteristic().getUuid());
            enableNext(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            writing = false;
            writeFailCount = 0;
            pump();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null || v.length < 7 || v[0] != 0x5A) return;
            rxSeen = true;
            int len = ((v[1] & 0xFF) << 8) | (v[2] & 0xFF);
            if (v.length < 5 + len) {
                log("帧长度异常 declared=" + len + " actual=" + v.length);
                return;
            }
            int flags = v[3] & 0xFF;
            byte[] payload = Arrays.copyOfRange(v, 5, 5 + len - 2);
            // 保活时车端每秒发一条 0302 03 状态帧：只记第一条和每 10 条，避免刷屏
            boolean statusFrame = payload.length >= 3 && (payload[0] & 0xFF) == 0x03
                    && (payload[1] & 0xFF) == 0x02 && (payload[2] & 0xFF) == 0x03;
            if (statusFrame) statusFrameCount++;
            if (!statusFrame || statusFrameCount == 1 || statusFrameCount % 10 == 0) {
                log("RX flags=" + Integer.toHexString(flags) + " " + hex(payload));
            }
            if ((flags & 0x08) != 0) {
                handleEncrypted(payload);
            } else {
                handlePlain(payload);
            }
        }
    };

    private void enableNext(BluetoothGatt g) {
        while (notifyIndex < notifyQueue.size()) {
            BluetoothGattCharacteristic c = notifyQueue.get(notifyIndex++);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD);
            if (d == null) continue;
            try { g.setCharacteristicNotification(c, true); } catch (Throwable ignored) {}
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (g.writeDescriptor(d)) {
                return;
            }
        }
        log("通知已开启，准备握手");
        ensureBond(g);
        handler.postDelayed(handshakeTask, 600);
    }

    /** 官方 App 在通知开启后会主动 createBond()；已配对时无副作用。 */
    private void ensureBond(BluetoothGatt g) {
        try {
            BluetoothDevice dev = g.getDevice();
            if (dev == null) return;
            int state = dev.getBondState();
            log("车端配对状态=" + state + (state == BluetoothDevice.BOND_BONDED ? "（已配对）" : "，发起配对"));
            if (state != BluetoothDevice.BOND_BONDED && state != BluetoothDevice.BOND_BONDING) {
                boolean ok = dev.createBond();
                log("createBond 返回=" + ok);
            }
        } catch (Throwable t) {
            log("配对异常: " + t);
        }
    }

    // ---------------- 协议处理 ----------------

    private void handlePlain(byte[] payload) {
        // 车端 msg=02 cmd=01 测距请求：先响应，再按官方做法重启存在广播
        if (payload.length >= 4 && payload[0] == 0x02 && payload[1] == 0x01) {
            log("收到车端测距请求 0201，响应并重启存在广播");
            enqueue(frame(0x00, 0, hexToBytes("0201000100010100")));
            stopAdvertising();
            startWakeAdvertising();
            return;
        }
        if (payload.length >= 4 && payload[0] == 0x02 && payload[1] == 0x08) {
            log("收到车端握手请求 0208，立即响应");
            enqueue(frame(0x00, 0, hexToBytes("020800010001020201")));
            return;
        }
        int i = indexOf(payload, new byte[]{(byte) 0x9F, 0x37});
        if (i >= 0 && i + 2 < payload.length) {
            int l = payload[i + 2] & 0xFF;
            if (l > 0 && i + 3 + l <= payload.length) {
                byte[] challenge = Arrays.copyOfRange(payload, i + 3, i + 3 + l);
                step1Count++;
                log("收到 80CA vrnd=" + hex(challenge) + "，发送 step1（第 " + step1Count + " 次）");
                try {
                    // 官方对每个 80CA 都会重新生成 prnd 并回 step1
                    byte[] body = buildStep1(challenge);
                    log("step1 body=" + hex(body));
                    sendFragmented(body, 0x00);
                    step1Sent = true;
                } catch (Throwable t) {
                    log("step1 构造失败: " + t);
                    finish(false, "step1 构造失败");
                }
            }
            return;
        }

        if (step1Sent && !step2Sent && payload.length > 8
                && payload[0] == 0x01 && payload[1] == 0x01
                && payload[4] == (byte) 0x80 && payload[5] == (byte) 0x80) {
            try {
                handleStep2(payload);
            } catch (Throwable t) {
                log("step2 处理失败: " + t);
                retryAuth("step2 失败: " + t.getMessage());
            }
            return;
        }

        if (payload.length >= 3 && payload[0] == 0x03 && payload[1] == 0x02
                && payload[2] == 0x03) {
            sendHeartbeatAck();
        }
        if (payload.length >= 2 && payload[0] == 0x03 && payload[1] == 0x02) {
            handleAuthResult(payload);
        }
    }

    /** 车端 0302 03 心跳：官方 App 会回 03 02 000100 030100（明文），不回会断链。 */
    private void sendHeartbeatAck() {
        enqueue(frame(0x00, 0, hexToBytes("0302000100030100")));
    }

    /** 0302 认证结果：00 成功才发 RKE，其它码直接判失败。 */
    private void handleAuthResult(byte[] payload) {
        if (authResultSeen) return;
        Tlv t = readTlv(payload, 2);
        if (t == null || t.tag != 0x02) {
            // 0302 里还混有车辆状态/心跳帧（tag=0x03 等），不是认证结果，静默忽略
            return;
        }
        if (t.value.length < 1) {
            log("认证结果格式异常: " + hex(payload));
            return;
        }
        authResultSeen = true;
        int code = t.value[0] & 0xFF;
        String name;
        switch (code) {
            case 0x00: name = "成功"; break;
            case 0x01: name = "认证超时"; break;
            case 0x02: name = "认证失败"; break;
            case 0x03: name = "版本协商失败"; break;
            case 0x08: name = "认证超时断开"; break;
            case 0x09: name = "白名单认证失败"; break;
            case 0x18: name = "连接数超限"; break;
            default: name = "未知"; break;
        }
        log("认证结果 code=" + code + "（" + name + "）");
        // 官方 VEHICLE_APDU_EXECUTION_RESULT 应答：000100 + TLV(02,00)
        enqueue(frame(0x00, 0, concat(hexToBytes("000100"), hexToBytes("020100"))));
        if (code == 0x00) {
            if (prepareOnly) {
                readyForAction = true;
                readyAtMs = SystemClock.elapsedRealtime();
                handler.removeCallbacks(overallTimeout);
                log("预连接认证成功，保持连接等待靠近（连接后 " + (readyAtMs - connectedAtMs) + "ms / 启动后 " + (readyAtMs - startedAtMs) + "ms）");
                cb.onReady();
                return;
            }
            log("认证成功，先查询车辆锁状态（连接后 " + (SystemClock.elapsedRealtime() - connectedAtMs) + "ms）");
            queryLockStatus();
        } else {
            retryAuth("认证失败 code=" + code + "（" + name + "）");
        }
    }

    private void handleStep2(byte[] payload) throws Exception {
        Tlv t = readTlv(payload, 2);
        if (t == null) throw new IllegalStateException("no outer tlv");
        byte[] outer = t.value;
        if (outer.length < 7 || outer[0] != (byte) 0x80 || outer[1] != (byte) 0x80) {
            throw new IllegalStateException("outer not 8080");
        }
        Tlv w = readTlv(outer, 5);
        if (w == null || w.tag != 0x77) throw new IllegalStateException("no 77 tlv");
        byte[] s2 = w.value;

        // 车端可能连发多个 80CA，8080 对应的不一定是最后一组 prnd/k2。
        // 逐组尝试解密，选能解出合法 9F36/9F0A 且 9F3E 匹配的那一组。
        Challenge matched = null;
        byte[] plain = null;
        for (int i = challenges.size() - 1; i >= 0; i--) {
            Challenge c = challenges.get(i);
            try {
                byte[] p = unpad80(aesDecrypt(c.k2, concat(c.vrnd, c.prnd), s2));
                byte[] atc = findTlv(p, 0x9F36);
                byte[] auth = findTlv(p, 0x9F0A);
                byte[] carPrnd = findTlv(p, 0x9F3E);
                if (auth != null && atc != null
                        && Arrays.equals(atc, hexToBytes("00000001"))
                        && (carPrnd == null || Arrays.equals(carPrnd, c.prnd))) {
                    matched = c;
                    plain = p;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (matched == null) throw new IllegalStateException("no 9F0A");
        vrnd = matched.vrnd;
        prnd = matched.prnd;
        k2 = matched.k2;
        log("s2 解密成功（挑战 " + (challenges.indexOf(matched) + 1) + "/" + challenges.size() + "） " + hex(plain));

        byte[] authParam = findTlv(plain, 0x9F0A);
        if (authParam == null) throw new IllegalStateException("no 9F0A");

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(concat(tlv(0x9F0A, authParam), tlv(0x9F37, vrnd)));
        byte[] s3 = aesEncrypt(k2, concat(vrnd, prnd), pad80(digest));
        log("s3=" + hex(s3));

        byte[] inner = concat(tlv(0x77, s3), hexToBytes("9000"));
        byte[] body = concat(new byte[]{0x01, 0x01},
                hexToBytes("000100"), new byte[]{0x01}, lenBytes(inner.length), inner);
        enqueue(frame(0x00, 0, body));
        step2Sent = true;
        log("step2 已发送，等待车端 0302 认证结果");
    }

    private void handleEncrypted(byte[] payload) {
        if (k2 == null || payload.length == 0 || payload.length % 16 != 0) return;
        try {
            byte[] plain = unpad80(aesDecrypt(k2, concat(vrnd, prnd), payload));
            if (plain.length < 2) return;
            int msg = plain[0] & 0xFF;
            int cmd = plain[1] & 0xFF;
            log("解密 msg=" + msg + " cmd=" + cmd + " " + hex(plain));
            if (msg == 2 && cmd == 4) {
                byte[] rest = Arrays.copyOfRange(plain, 2, plain.length);
                Tlv status = readTlv(rest, 0);
                if (status != null && status.tag == 0 && status.value.length == 1
                        && status.value[0] != 0) {
                    finish(false, "车端拒绝 RKE 挑战，code=" + hex(status.value));
                    return;
                }
                byte[] rnd = findTlv(rest, 0x01);
                if (rnd != null && rnd.length > 0) {
                    log("RKE 挑战 rnd=" + hex(rnd));
                    sendRkeCommand(rnd);
                } else {
                    finish(false, "RKE 挑战解析失败");
                }
            } else if (msg == 3 && cmd == 2) {
                if (plain.length >= 3 && (plain[2] & 0xFF) == 0x03) sendHeartbeatAck();
                handleAuthResult(plain);
            } else if ((msg == 2 || msg == 3) && cmd == 5) {
                // 真车查询应答是 msg=03 cmd=05，不能只接 msg=02，否则状态查询永远超时兜底。
                handleCarInfoStatus(plain);
            } else if (msg == 2 && cmd == 3) {
                byte[] r = findTlv(Arrays.copyOfRange(plain, 2, plain.length), 0x01);
                if (r != null && r.length > 0 && r[0] == 0) {
                    finish(true, actionName() + "成功");
                } else {
                    finish(false, "车端返回失败: " + (r == null ? "无结果" : hex(r)));
                }
            }
        } catch (Throwable t) {
            log("加密帧解密异常: " + t);
        }
    }

    private final Runnable statusQueryFallthrough = this::proceedAfterStatus;

    /** 先问车：当前是锁着还是开着。格式取官方 CommandCenter.a(2, "")：03 05 + TLV(1,TLV(2,空))。 */
    private void queryLockStatus() {
        if (statusQuerySent || k2 == null || finished) return;
        statusQuerySent = true;
        try {
            byte[] body = concat(new byte[]{0x03, 0x05}, hexToBytes("01020200"));
            byte[] enc = aesEncrypt(k2, concat(vrnd, prnd), pad80(body));
            log("发送车辆状态查询（锁状态）");
            enqueue(frame(0x18, 0, enc));
            handler.removeCallbacks(statusQueryFallthrough);
            handler.postDelayed(statusQueryFallthrough, STATUS_QUERY_WAIT_MS);
        } catch (Throwable t) {
            log("状态查询构造失败，兜底直接执行: " + t);
            proceedAfterStatus();
        }
    }

    /** 状态查询返回/超时后决定：状态已符合就直接完成，否则发 RKE（兜底）。 */
    private void proceedAfterStatus() {
        if (finished || rkeSent) return;
        handler.removeCallbacks(statusQueryFallthrough);
        if (statusResponded && statusLocked != null) {
            boolean wantLocked = mode == MODE_LOCK;
            if (statusLocked == wantLocked) {
                log("车辆状态已是" + (wantLocked ? "锁车" : "解锁") + "，跳过 RKE");
                finish(true, actionName() + "成功（状态已是目标）");
                return;
            }
            log("车辆状态与目标不同，发 RKE " + actionName());
        } else {
            log("未查询到车辆状态，兜底发 RKE " + actionName());
        }
        sendRkeChallenge();
    }

    private void handleCarInfoStatus(byte[] plain) {
        statusResponded = true;
        log("收到车辆状态响应 " + hex(plain));
        int p = 2;
        while (p + 2 <= plain.length) {
            int tag = plain[p] & 0xFF;
            int q = p + 1;
            if ((tag & 0x1F) == 0x1F) {
                if (q >= plain.length) break;
                tag = (tag << 8) | (plain[q++] & 0xFF);
            }
            if (q >= plain.length) break;
            int len = plain[q++] & 0xFF;
            if (len == 0x81) {
                if (q >= plain.length) break;
                len = plain[q++] & 0xFF;
            }
            if (q + len > plain.length) break;
            // 真车应答 03 05 00 01 02：tag 可能是 0x00（TLV）或 0x02（官方文档）。
            // 真车日志里 0x02=已锁：此时发 RKE 上锁必然返回 01（车端拒绝重复锁）。
            if ((tag == 0x00 || tag == 0x02) && len > 0) {
                int v = plain[q] & 0xFF;
                if (v == 0x02) {
                    statusLocked = true;
                    log("车辆锁状态=已锁（原始值 02，跳过重复上锁）");
                } else if (v == 0x01) {
                    statusLocked = false;
                    log("车辆锁状态=已开（原始值 01）");
                } else {
                    statusLocked = null;
                    log("车辆锁状态值未知（原始值 " + hex(Arrays.copyOfRange(plain, q, q + len)) + "），兜底执行");
                }
                if (statusLocked != null) cb.onDoorState(statusLocked);
            }
            p = q + len;
        }
        proceedAfterStatus();
    }

    private void sendRkeChallenge() {
        if (k2 == null || rkeSent) return;
        try {
            // 注意：挑战类型是单字节 0x01（对应 f.a(byte)），不是 01 00
            byte[] body = concat(new byte[]{0x02, 0x04},
                    tlv(0x01, new byte[]{(byte) RKE_TYPE_DOOR}));
            byte[] enc = aesEncrypt(k2, concat(vrnd, prnd), pad80(body));
            rkeSent = true;
            log("发送 RKE 挑战请求（启动后 " + (SystemClock.elapsedRealtime() - startedAtMs) + "ms）");
            enqueue(frame(0x18, 0, enc));
        } catch (Throwable t) {
            finish(false, "RKE 挑战构造失败");
        }
    }

    private void sendRkeCommand(byte[] rnd) {
        try {
            // 官方 step2 = tlv(0x01, <00 解锁 | 01 上锁> + hex(rnd))，rnd 必须原样，不能补零到 8 字节
            byte[] value = concat(new byte[]{(byte) mode}, rnd);
            byte[] body = concat(new byte[]{0x02, 0x03}, tlv(0x01, value));
            byte[] enc = aesEncrypt(k2, concat(vrnd, prnd), pad80(body));
            log("发送 RKE " + actionName() + "指令（启动后 " + (SystemClock.elapsedRealtime() - startedAtMs) + "ms）");
            enqueue(frame(0x1C, 0, enc));
        } catch (Throwable t) {
            finish(false, "RKE 指令构造失败");
        }
    }

    // ---------------- 发送队列 / 分片 ----------------

    private void enqueue(byte[] data) {
        if (data == null || finished) return;
        txQueue.add(data);
        pump();
    }

    private void pump() {
        if (writing || gatt == null || writeChar == null) return;
        byte[] f = txQueue.poll();
        if (f == null) return;
        writing = true;
        try {
            writeChar.setValue(f);
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!gatt.writeCharacteristic(writeChar)) {
                writing = false;
                // 通知 descriptor 写入未完成时 BLE 栈会拒绝并发写；短延时重试，不能丢帧。
                writeFailCount++;
                if (writeFailCount <= 4) {
                    log("writeCharacteristic 返回 false，" + 150 + "ms 后重试（第 " + writeFailCount + " 次）");
                    handler.postDelayed(() -> { if (!finished) pump(); }, 150);
                } else {
                    log("writeCharacteristic 连续失败，丢弃本帧");
                    writeFailCount = 0;
                }
            }
        } catch (Throwable t) {
            writing = false;
            log("写入异常: " + t);
        }
    }

    private void sendFragmented(byte[] body, int baseFlags) {
        final int chunk = 144;
        if (body.length <= chunk) {
            enqueue(frame(baseFlags, 0, body));
            return;
        }
        int idx = 0;
        for (int p = 0; p < body.length; p += chunk) {
            int end = Math.min(body.length, p + chunk);
            byte[] part = Arrays.copyOfRange(body, p, end);
            int flags = idx == 0 ? (baseFlags | 0x01) : (baseFlags | 0x03);
            enqueue(frame(flags, idx, part));
            idx++;
        }
    }

    // ---------------- 加密/帧 ----------------

    private byte[] buildStep1(byte[] vrnd) throws Exception {
        byte[] ks = hexToBytes(AppPrefs.get(ctx, AppPrefs.K_BLE_KS));
        byte[] keyId = hexToBytes(AppPrefs.get(ctx, AppPrefs.K_BLE_KEY_ID));
        byte[] kr = hexToBytes(AppPrefs.get(ctx, AppPrefs.K_BLE_KR));
        byte[] prnd = new byte[8];
        new SecureRandom().nextBytes(prnd);

        byte[] k2 = sessionKey(ks, vrnd, prnd);
        byte[] s1 = aesEncrypt(k2, concat(vrnd, prnd),
                pad80(concat(tlv(0x9F36, hexToBytes("00000001")), tlv(0x9F37, vrnd))));

        byte[] inner = concat(
                tlv(0x5A, new byte[8]),
                tlv(0x9F3B, keyId),
                tlv(0x9F3E, prnd),
                tlv(0x9F05, kr),
                tlv(0x73, s1));
        byte[] withStatus = concat(tlv(0x77, inner), hexToBytes("9000"));

        this.vrnd = vrnd;
        this.prnd = prnd;
        this.k2 = k2;
        challenges.add(new Challenge(vrnd, prnd, k2));

        return concat(new byte[]{0x01, 0x01}, hexToBytes("000100"),
                new byte[]{0x01}, lenBytes(withStatus.length), withStatus);
    }

    private static byte[] sessionKey(byte[] ks, byte[] vrnd, byte[] prnd) throws Exception {
        byte[] enc = aesEncrypt(ks, new byte[16], pad80(concat(vrnd, prnd)));
        return Arrays.copyOfRange(enc, enc.length - 16, enc.length);
    }

    private static byte[] tlv(int tag, byte[] value) {
        byte[] tagB = tag > 0xFF
                ? new byte[]{(byte) (tag >> 8), (byte) tag}
                : new byte[]{(byte) tag};
        return concat(tagB, lenBytes(value.length), value);
    }

    private static byte[] lenBytes(int len) {
        return len > 127 ? new byte[]{(byte) 0x81, (byte) len} : new byte[]{(byte) len};
    }

    private static final class Tlv {
        int tag;
        byte[] value;
    }

    private static Tlv readTlv(byte[] b, int off) {
        if (off + 2 > b.length) return null;
        int tag = b[off] & 0xFF;
        int p = off + 1;
        if ((tag & 0x1F) == 0x1F) {
            tag = (tag << 8) | (b[p] & 0xFF);
            p++;
        }
        if (p >= b.length) return null;
        int len = b[p++] & 0xFF;
        if (len == 0x81) {
            if (p >= b.length) return null;
            len = b[p++] & 0xFF;
        } else if (len == 0x82) {
            if (p + 1 >= b.length) return null;
            len = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            p += 2;
        }
        if (p + len > b.length) return null;
        Tlv t = new Tlv();
        t.tag = tag;
        t.value = Arrays.copyOfRange(b, p, p + len);
        return t;
    }

    private static byte[] findTlv(byte[] b, int tag) {
        // 先按 TLV 边界顺序遍历：逐字节扫描会把 value 里的字节误认成 tag
        // （例如 00 01 00 01 04 D0 49 06 0F，i=1 会把 01 误读成 tag/len=0）
        byte[] seq = findTlvSeq(b, tag);
        if (seq != null) return seq;
        for (int i = 0; i + 2 < b.length; i++) {
            Tlv t = readTlv(b, i);
            if (t != null && t.tag == tag) return t.value;
        }
        return null;
    }

    private static byte[] findTlvSeq(byte[] b, int tag) {
        int i = 0;
        while (i + 2 <= b.length) {
            int t = b[i] & 0xFF;
            int p = i + 1;
            if ((t & 0x1F) == 0x1F) {
                if (p >= b.length) return null;
                t = (t << 8) | (b[p++] & 0xFF);
            }
            if (p >= b.length) return null;
            int len = b[p++] & 0xFF;
            if (len == 0x81) {
                if (p >= b.length) return null;
                len = b[p++] & 0xFF;
            } else if (len == 0x82) {
                if (p + 1 >= b.length) return null;
                len = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
                p += 2;
            }
            if (p + len > b.length) return null;
            if (t == tag) return Arrays.copyOfRange(b, p, p + len);
            i = p + len;
        }
        return null;
    }

    private static byte[] frame(int flags, int index, byte[] body) {
        int len = body.length + 2;
        byte[] head = new byte[]{0x5A, (byte) (len >> 8), (byte) len, (byte) flags, (byte) index};
        byte[] full = concat(head, body);
        int crc = crc16(full);
        return concat(full, new byte[]{(byte) (crc >> 8), (byte) crc});
    }

    private static int crc16(byte[] data) {
        int crc = 0xFFFF;
        for (byte b : data) {
            for (int i = 0; i < 8; i++) {
                boolean bit = ((b >> (7 - i)) & 1) == 1;
                boolean c15 = ((crc >> 15) & 1) == 1;
                crc = (crc << 1) & 0xFFFF;
                if (bit ^ c15) crc ^= 0x1021;
            }
        }
        return crc & 0xFFFF;
    }

    private static byte[] pad80(byte[] data) {
        int n = data.length + 1;
        int padded = ((n + 15) / 16) * 16;
        byte[] out = Arrays.copyOf(data, padded);
        out[data.length] = (byte) 0x80;
        return out;
    }

    private static byte[] unpad80(byte[] data) {
        for (int i = data.length - 1; i >= 0; i--) {
            if (data[i] == (byte) 0x80) return Arrays.copyOf(data, i);
        }
        return data;
    }

    private static byte[] aesEncrypt(byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    private static byte[] aesDecrypt(byte[] key, byte[] iv, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return c.doFinal(data);
    }

    private static byte[] concat(byte[]... arrays) {
        int n = 0;
        for (byte[] a : arrays) if (a != null) n += a.length;
        byte[] out = new byte[n];
        int p = 0;
        for (byte[] a : arrays) {
            if (a == null) continue;
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }

    private static int indexOf(byte[] data, byte[] pat) {
        outer:
        for (int i = 0; i + pat.length <= data.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] hexToBytes(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    private static String normalizeMac(String raw) {
        if (raw == null) return "";
        String s = raw.replace(":", "").replace("-", "").replace(" ", "").toUpperCase();
        if (s.length() != 12) return raw;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) sb.append(':');
            sb.append(s, i, i + 2);
        }
        return sb.toString();
    }

}
