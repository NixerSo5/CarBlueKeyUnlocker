package com.nixer.carunlocker;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.slider.Slider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "CarGuard";
    private static final int REQ_PERMS = 100;
    private static final String SAIC_PACKAGE = "com.saicmotor.tocapp"; // 上汽荣威专用分支
    private static final String SAIC_LABEL = "上汽荣威";

    // 4 挡：非常远 / 远 / 近 / 自定义。默认非常远，给目标 App 留出启动+连接蓝牙的时间
    private static final String[] MODE_NAMES = {"非常远（约5-10米·推荐）", "远（约3-5米）", "近（约2米）", "自定义"};
    private static final int[] MODE_ENTER = {-75, -68, -60, -75};
    private static final int[] MODE_TIMES = {2, 2, 2, 2};

    private TextView statusText;
    private TextView targetText;
    private TextView processText;
    private Button btnSelect;
    private Button btnStart;
    private Button btnDistance;
    private Button btnLog;
    private Button btnAccess;
    private CheckBox cbWakeScreen;

    private AlertDialog logDialog;
    private TextView logText;
    private ScrollView logScroll;
    private CheckBox logAutoRefresh;
    private final Handler logHandler = new Handler();
    private final Runnable logRefresh = new Runnable() {
        @Override
        public void run() {
            if (logDialog == null || !logDialog.isShowing() || logText == null) return;
            int y = logScroll != null ? logScroll.getScrollY() : 0;
            logText.setText(colorizeLog(readLogTail(300)));
            if (logScroll != null) logScroll.post(() -> logScroll.scrollTo(0, y));
            logHandler.postDelayed(this, 1500);
        }
    };

    private AlertDialog deviceDialog;
    private ListView dialogDeviceList;
    private TextView scanStatus;

    private final Handler handler = new Handler();
    private BluetoothLeScanner scanner;
    private boolean picking = false;

    private final LinkedHashMap<String, String> devices = new LinkedHashMap<>();
    private final List<String> deviceKeys = new ArrayList<>();

    private final Runnable statusRefresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        targetText = findViewById(R.id.targetText);
        statusText = findViewById(R.id.statusText);
        processText = findViewById(R.id.processText);
        btnSelect = findViewById(R.id.btnSelect);
        btnStart = findViewById(R.id.btnStart);
        btnDistance = findViewById(R.id.btnDistance);
        btnLog = findViewById(R.id.btnLog);
        btnAccess = findViewById(R.id.btnAccess);
        cbWakeScreen = findViewById(R.id.cbWakeScreen);

        btnSelect.setOnClickListener(v -> showDeviceDialog());
        btnStart.setOnClickListener(v -> {
            startGuard();
            if (!SaicKeyAccessibilityService.isEnabled(this)) {
                Toast.makeText(this,
                        "提示：靠近车辆自动解锁需要开启无障碍权限（点“无障碍”按钮）",
                        Toast.LENGTH_LONG).show();
            }
        });
        btnDistance.setOnClickListener(v -> showDistanceDialog());
        btnLog.setOnClickListener(v -> showLogDialog());
        btnAccess.setOnClickListener(v -> openAccessibilitySettings());
        // 长按：立即手动测试一次“打开数字钥匙页”流程（不用等车）
        btnAccess.setOnLongClickListener(v -> {
            boolean ok = SaicKeyAccessibilityService.trigger(this, "手动测试");
            Toast.makeText(this, ok ? "已触发：正在打开数字钥匙页" : "无障碍未开启，无法触发",
                    Toast.LENGTH_LONG).show();
            return true;
        });
        // 靠近车辆时自动打开上汽数字钥匙页：默认开启（需要无障碍权限）
        if (AppPrefs.get(this, AppPrefs.K_AUTO_OPEN_DK).length() == 0) {
            AppPrefs.setBool(this, AppPrefs.K_AUTO_OPEN_DK, true);
        }
        cbWakeScreen.setChecked(AppPrefs.getBool(this, AppPrefs.K_WAKE_SCREEN, false));
        cbWakeScreen.setOnCheckedChangeListener((button, checked) ->
                AppPrefs.setBool(this, AppPrefs.K_WAKE_SCREEN, checked));

        requestAllPermissions();
        scheduleKeepAlive();
        handler.post(statusRefresh);
    }

    private int clampMode(int mode) {
        if (mode < 0) return 0;
        if (mode > 3) return 3;
        return mode;
    }

    private String modeName(int mode) {
        return MODE_NAMES[clampMode(mode)];
    }

    private String modeDescription(int mode) {
        switch (clampMode(mode)) {
            case 0: return "提前触发，给 App 留出启动和连接时间（≥ -75dBm，连续2次）";
            case 1: return "接近车辆时触发（≥ -68dBm，连续2次）";
            case 2: return "走到车旁边才触发（≥ -60dBm，连续2次）";
            default: return "手动设置进入/离开阈值和确认次数";
        }
    }

    private void appendStatusLine(SpannableStringBuilder sb, boolean ok) {
        int start = sb.length();
        sb.append(ok ? "● 运行中" : "○ 未运行");
        sb.setSpan(new ForegroundColorSpan(ok ? Color.parseColor("#4CAF50") : Color.parseColor("#999999")),
                start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void updateStatus() {
        boolean running = Utils.isServiceRunning(this, CarService.class);
        String targetMac = AppPrefs.targetMac(this);
        String targetName = AppPrefs.targetName(this);

        String target = TextUtils.isEmpty(targetMac)
                ? "目标设备: 未选择（请点上方按钮选择）"
                : "目标设备: " + (TextUtils.isEmpty(targetName) ? targetMac : targetName) + "\nMAC: " + targetMac;
        targetText.setText(target);

        String count = AppPrefs.get(this, AppPrefs.K_DEVICE_COUNT);
        String lastSeen = AppPrefs.get(this, AppPrefs.K_LAST_SEEN);
        String started = AppPrefs.get(this, AppPrefs.K_SERVICE_START);
        String rssi = AppPrefs.get(this, AppPrefs.K_CURRENT_RSSI);
        int mode = clampMode(AppPrefs.getInt(this, AppPrefs.K_DISTANCE_MODE, 0));
        int enter = AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, MODE_ENTER[mode]);
        int times = AppPrefs.getInt(this, AppPrefs.K_CONFIRM_TIMES, MODE_TIMES[mode]);

        String launchPkg = SAIC_PACKAGE;
        String launchLabel = SAIC_LABEL;
        String launch = TextUtils.isEmpty(launchLabel) ? launchPkg : launchLabel;
        boolean launchRunning = "1".equals(AppPrefs.get(this, AppPrefs.K_TARGET_ALIVE));

        SpannableStringBuilder pb = new SpannableStringBuilder();
        pb.append("目标App进程: ");
        if (TextUtils.isEmpty(launchPkg)) {
            int s = pb.length();
            pb.append("未选择");
            pb.setSpan(new ForegroundColorSpan(Color.parseColor("#999999")), s, pb.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            appendStatusLine(pb, launchRunning);
            pb.append(launchRunning ? "  (" : "  (未检测到进程)(").append(launch).append(")");
        }
        processText.setText(pb);

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("拉起应用: ").append(launch).append("\n");
        sb.append("守护服务: ");
        appendStatusLine(sb, running);
        sb.append("\n已发现设备数: ").append(TextUtils.isEmpty(count) ? "0" : count);
        sb.append("\n当前信号强度: ").append(TextUtils.isEmpty(rssi) ? "--" : rssi);
        sb.append("\n距离挡位: ").append(String.valueOf(mode + 1)).append("/4  ").append(modeName(mode));
        sb.append("\n距离判断: >=").append(String.valueOf(enter))
                .append("dBm 触发, 连续").append(String.valueOf(times))
                .append("次（信号消失10s重置）");
        sb.append("\n最近一次发现目标: ").append(TextUtils.isEmpty(lastSeen) ? "暂无" : lastSeen);
        sb.append("\n服务启动时间: ").append(TextUtils.isEmpty(started) ? "暂无" : started);
        boolean accOn = SaicKeyAccessibilityService.isEnabled(this);
        String accState = AppPrefs.get(this, AppPrefs.K_ACC_STATE);
        sb.append("\n数字钥匙自动打开: ").append(accOn ? "无障碍已开启" : "未开启（点下方按钮）");
        if (!TextUtils.isEmpty(accState)) sb.append("\n无障碍状态: ").append(accState);
        sb.append("\n看门狗: ");
        appendStatusLine(sb, Utils.isServiceRunning(this, WatchdogService.class));
        statusText.setText(sb);

        btnStart.setText(running ? "重启守护服务" : "启动守护服务");
        if (btnAccess != null) {
            btnAccess.setText(accOn
                    ? "无障碍：已开启（点击管理）"
                    : "无障碍：未开启（点击开启）");
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this,
                    "请找到“07电子钥匙助手”并开启无障碍权限", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开无障碍设置: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean isProcessRunning(String pkg) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                if (p.pkgList == null) continue;
                for (String n : p.pkgList) if (pkg.equals(n)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private Integer parseIntOrNull(EditText et) {
        try {
            return Integer.parseInt(et.getText().toString().trim());
        } catch (Throwable t) {
            return null;
        }
    }

    private void updateDistanceDialogUi(int mode, TextView label, View customBox) {
        mode = clampMode(mode);
        label.setText("挡位 " + (mode + 1) + "/4：" + modeName(mode) + "\n" + modeDescription(mode));
        customBox.setVisibility(mode == 3 ? View.VISIBLE : View.GONE);
    }

    private void mainLog(String msg) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "carguard.log");
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + " " + msg + "\n";
            FileOutputStream out = new FileOutputStream(f, true);
            out.write(line.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {}
    }

    /** 右上角“扫描日志”：读取 carguard.log 最近若干行，最新在最上面。 */
    private String readLogTail(int maxLines) {
        File dir = getExternalFilesDir(null);
        if (dir == null) return "无法获取日志目录";
        File f = new File(dir, "carguard.log");
        if (!f.exists() || !f.canRead()) return "暂无日志文件，服务启动后会自动生成";
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
            int from = Math.max(0, lines.size() - maxLines);
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(lines.size()).append(" 行，显示最近 ")
                    .append(Math.min(maxLines, lines.size())).append(" 行（最新在上）\n\n");
            for (int i = lines.size() - 1; i >= from; i--) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "读取日志失败: " + t.getMessage();
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 扫到目标/触发=绿色，失败/错误=红色，其余灰色。 */
    private CharSequence colorizeLog(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int start = 0;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            int end = Math.min(start + line.length(), sb.length());
            int color;
            if (line.contains("目标信号") || line.contains("TARGET NEAR")
                    || line.contains("launched ") || line.contains("目标广播消失")
                    || line.contains("目标进程仍在运行") || line.contains("刚刚拉起过")
                    || line.contains("KEEPALIVE") || line.contains("started daemon silently")
                    || line.contains("bound daemon silently") || line.contains("target daemon connected")) {
                color = Color.parseColor("#2E7D32"); // 绿色：扫到目标
            } else if (line.contains("failed") || line.contains("error")
                    || line.contains("ERROR") || line.contains("异常") || line.contains("被系统拦截")) {
                color = Color.parseColor("#C62828"); // 红色：失败/错误
            } else {
                color = Color.rgb(55, 71, 79);
            }
            if (end > start) {
                sb.setSpan(new ForegroundColorSpan(color), start, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = end + 1;
        }
        return sb;
    }

    private void showLogDialog() {
        if (logDialog != null && logDialog.isShowing()) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        logAutoRefresh = new CheckBox(this);
        logAutoRefresh.setText("自动刷新（1.5秒）");
        logAutoRefresh.setChecked(true);
        root.addView(logAutoRefresh, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextColor(Color.rgb(55, 71, 79));
        logText.setTextIsSelectable(true); // 可勾选文字复制
        logScroll.addView(logText);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        logText.setText(colorizeLog(readLogTail(300)));

        logDialog = new AlertDialog.Builder(this)
                .setTitle("扫描日志（最新在最上方）")
                .setView(root)
                .setNeutralButton("立即刷新", null)
                .setPositiveButton("关闭", null)
                .setOnDismissListener(d -> {
                    logHandler.removeCallbacks(logRefresh);
                    logDialog = null;
                })
                .show();
        logDialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> logText.setText(colorizeLog(readLogTail(300))));
        logAutoRefresh.setOnCheckedChangeListener((button, checked) -> {
            logHandler.removeCallbacks(logRefresh);
            if (checked) logHandler.post(logRefresh);
        });
        logHandler.post(logRefresh);
    }

    private void showDeviceDialog() {
        mainLog("showDeviceDialog clicked");
        try {
            if (deviceDialog != null && deviceDialog.isShowing()) return;

            View v = LayoutInflater.from(this).inflate(R.layout.dialog_device, null);
            scanStatus = v.findViewById(R.id.scanStatus);
            dialogDeviceList = v.findViewById(R.id.dialogDeviceList);
            dialogDeviceList.setAdapter(new DeviceAdapter());
            dialogDeviceList.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= deviceKeys.size()) return;
                String mac = deviceKeys.get(position);
                String name = devices.get(mac);
                AppPrefs.set(this, AppPrefs.K_TARGET_MAC, mac);
                AppPrefs.set(this, AppPrefs.K_TARGET_NAME, name == null ? "" : name);
                if (deviceDialog != null) deviceDialog.dismiss();
                Toast.makeText(this, "已选择: " + name + "\n" + mac, Toast.LENGTH_LONG).show();
                startGuard();
            });

            deviceDialog = new AlertDialog.Builder(this)
                    .setTitle("选择蓝牙设备")
                    .setView(v)
                    .setNegativeButton("取消", null)
                    .setOnDismissListener(d -> {
                        stopPicking();
                        deviceDialog = null;
                        dialogDeviceList = null;
                        scanStatus = null;
                    })
                    .show();
            deviceDialog.setCanceledOnTouchOutside(false); // 防止打开瞬间被同一次点击误关
            mainLog("device dialog shown=" + deviceDialog.isShowing());

            startPicking();
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            mainLog("showDeviceDialog ERROR: " + sw.toString());
        }
    }

    private void showDistanceDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_distance, null);
        Slider slider = v.findViewById(R.id.slider);
        TextView modeLabel = v.findViewById(R.id.modeLabel);
        View customBox = v.findViewById(R.id.customBox);
        EditText etEnter = v.findViewById(R.id.etEnter);
        EditText etTimes = v.findViewById(R.id.etTimes);

        int mode = clampMode(AppPrefs.getInt(this, AppPrefs.K_DISTANCE_MODE, 0));
        slider.setValue(mode);
        etEnter.setText(String.valueOf(AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, MODE_ENTER[mode])));
        etTimes.setText(String.valueOf(AppPrefs.getInt(this, AppPrefs.K_CONFIRM_TIMES, MODE_TIMES[mode])));
        updateDistanceDialogUi(mode, modeLabel, customBox);

        slider.addOnChangeListener((s, value, fromUser) ->
                updateDistanceDialogUi(Math.round(value), modeLabel, customBox));

        new AlertDialog.Builder(this)
                .setTitle("距离灵敏度")
                .setView(v)
                .setPositiveButton("保存并生效", (d, w) -> {
                    int m = clampMode(Math.round(slider.getValue()));
                    int enter;
                    int times;

                    if (m == 3) {
                        Integer e = parseIntOrNull(etEnter);
                        Integer t = parseIntOrNull(etTimes);
                        if (e == null || t == null) {
                            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (e < -100 || e > 0) {
                            Toast.makeText(this, "进入阈值范围应为 -100 到 0（建议 -75 左右）", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (t < 1 || t > 10) {
                            Toast.makeText(this, "连续次数应为 1 到 10", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        enter = e;
                        times = t;
                    } else {
                        enter = MODE_ENTER[m];
                        times = MODE_TIMES[m];
                    }

                    AppPrefs.set(this, AppPrefs.K_DISTANCE_MODE, String.valueOf(m));
                    AppPrefs.set(this, AppPrefs.K_RSSI_ENTER, String.valueOf(enter));
                    AppPrefs.set(this, AppPrefs.K_CONFIRM_TIMES, String.valueOf(times));
                    startGuard();
                    Toast.makeText(this,
                            "已保存：" + modeName(m) + "  进入≥" + enter + " / 连续" + times + "次（信号消失10s重置）",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestAllPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> need = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }

        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
                && "".equals(AppPrefs.get(this, AppPrefs.K_ASKED_BG_LOCATION))) {
            AppPrefs.set(this, AppPrefs.K_ASKED_BG_LOCATION, "1");
            try {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_PERMS + 1);
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)
                && "".equals(AppPrefs.get(this, AppPrefs.K_ASKED_OVERLAY))) {
            AppPrefs.set(this, AppPrefs.K_ASKED_OVERLAY, "1");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 23
                && "".equals(AppPrefs.get(this, AppPrefs.K_ASKED_BATTERY))) {
            AppPrefs.set(this, AppPrefs.K_ASKED_BATTERY, "1");
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        startGuard();
    }

    private void scheduleKeepAlive() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(this, KeepAliveReceiver.class);
            int flag = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flag |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, flag);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 60_000, 60_000, pi);
        } catch (Throwable ignored) {}
    }

    private void startGuard() {
        try {
            Intent i = new Intent(this, CarService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            startService(new Intent(this, WatchdogService.class));
            AppPrefs.set(this, AppPrefs.K_SERVICE_START,
                    new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startPicking() {
        picking = true;
        devices.clear();
        deviceKeys.clear();
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter != null) {
                for (BluetoothDevice d : adapter.getBondedDevices()) {
                    addDevice(d.getAddress(), d.getName());
                }
            }
        } catch (Throwable ignored) {}

        if (scanStatus != null) scanStatus.setText("正在扫描附近蓝牙设备…（20秒，点设备即选中）");
        if (dialogDeviceList != null && dialogDeviceList.getAdapter() != null) {
            ((BaseAdapter) dialogDeviceList.getAdapter()).notifyDataSetChanged();
        }

        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            scanner = adapter != null ? adapter.getBluetoothLeScanner() : null;
            if (scanner != null) {
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
                scanner.startScan(null, settings, pickCallback);
            }
        } catch (Throwable ignored) {}

        handler.postDelayed(() -> {
            if (picking) stopPicking();
        }, 20_000);
    }

    private final ScanCallback pickCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String mac = result.getDevice().getAddress();
            String name = result.getDevice().getName();
            if (name == null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            addDevice(mac, name);
        }
    };

    private void addDevice(String mac, String name) {
        if (mac == null) return;
        if (!devices.containsKey(mac)) deviceKeys.add(mac);
        devices.put(mac, TextUtils.isEmpty(name) ? "(未知设备)" : name);
        runOnUiThread(() -> {
            if (dialogDeviceList != null && dialogDeviceList.getAdapter() != null) {
                ((BaseAdapter) dialogDeviceList.getAdapter()).notifyDataSetChanged();
            }
        });
    }

    private void stopPicking() {
        picking = false;
        if (scanner != null) {
            try { scanner.stopScan(pickCallback); } catch (Throwable ignored) {}
            scanner = null;
        }
        if (scanStatus != null) scanStatus.setText("扫描已停止，点上方列表中的设备即可选中");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusRefresh);
        stopPicking();
        super.onDestroy();
    }

    private class DeviceAdapter extends BaseAdapter {
        @Override public int getCount() { return deviceKeys.size(); }
        @Override public Object getItem(int i) { return deviceKeys.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View view, ViewGroup parent) {
            TextView tv = view instanceof TextView ? (TextView) view : new TextView(MainActivity.this);
            String mac = deviceKeys.get(i);
            tv.setText(devices.get(mac) + "\n" + mac);
            tv.setTextSize(15);
            tv.setTextColor(getResources().getColor(R.color.text_primary));
            tv.setPadding(12, 16, 12, 16);
            return tv;
        }
    }
}

