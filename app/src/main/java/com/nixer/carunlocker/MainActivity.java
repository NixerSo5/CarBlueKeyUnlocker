package com.nixer.carunlocker;

import android.Manifest;
import android.app.Activity;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.slider.Slider;

import java.io.File;
import java.io.FileOutputStream;
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

    // 4 挡：很近 / 近 / 远 / 自定义
    private static final String[] MODE_NAMES = {"很近（约1米）", "近（约2米·推荐）", "远（约3-5米）", "自定义"};
    private static final int[] MODE_ENTER = {-50, -60, -68, -60};
    private static final int[] MODE_EXIT = {-65, -75, -80, -75};
    private static final int[] MODE_TIMES = {3, 2, 2, 2};

    private TextView statusText;
    private TextView targetText;
    private Button btnSelect;
    private Button btnSelectApp;
    private Button btnStart;
    private Button btnDistance;

    private AlertDialog deviceDialog;
    private ListView dialogDeviceList;
    private TextView scanStatus;

    private AlertDialog appDialog;
    private ListView dialogAppList;
    private EditText etAppFilter;

    private final Handler handler = new Handler();
    private BluetoothLeScanner scanner;
    private boolean picking = false;

    private final LinkedHashMap<String, String> devices = new LinkedHashMap<>();
    private final List<String> deviceKeys = new ArrayList<>();

    // 通用版：可拉起的应用列表
    private final LinkedHashMap<String, String> appLabels = new LinkedHashMap<>(); // 包名 -> 显示名
    private final List<String> appPackages = new ArrayList<>();
    private final List<String> appVisible = new ArrayList<>();

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
        btnSelect = findViewById(R.id.btnSelect);
        btnSelectApp = findViewById(R.id.btnSelectApp);
        btnStart = findViewById(R.id.btnStart);
        btnDistance = findViewById(R.id.btnDistance);

        btnSelect.setOnClickListener(v -> showDeviceDialog());
        btnSelectApp.setOnClickListener(v -> showAppDialog());
        btnStart.setOnClickListener(v -> startGuard());
        btnDistance.setOnClickListener(v -> showDistanceDialog());

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
            case 0: return "走到约1米内才触发（≥ -50dBm，连续3次）";
            case 1: return "走到车旁边触发，推荐（≥ -60dBm，连续2次）";
            case 2: return "接近车辆时提前触发（≥ -68dBm，连续2次）";
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
        int mode = clampMode(AppPrefs.getInt(this, AppPrefs.K_DISTANCE_MODE, 1));
        int enter = AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, MODE_ENTER[mode]);
        int exit = AppPrefs.getInt(this, AppPrefs.K_RSSI_EXIT, MODE_EXIT[mode]);
        int times = AppPrefs.getInt(this, AppPrefs.K_CONFIRM_TIMES, MODE_TIMES[mode]);

        String launchPkg = AppPrefs.get(this, AppPrefs.K_LAUNCH_PACKAGE);
        String launchLabel = AppPrefs.get(this, AppPrefs.K_LAUNCH_LABEL);
        String launch = TextUtils.isEmpty(launchPkg) ? "未选择"
                : (TextUtils.isEmpty(launchLabel) ? launchPkg : launchLabel);

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("拉起应用: ").append(launch).append("\n");
        sb.append("守护服务: ");
        appendStatusLine(sb, running);
        sb.append("\n已发现设备数: ").append(TextUtils.isEmpty(count) ? "0" : count);
        sb.append("\n当前信号强度: ").append(TextUtils.isEmpty(rssi) ? "--" : rssi);
        sb.append("\n距离挡位: ").append(String.valueOf(mode + 1)).append("/4  ").append(modeName(mode));
        sb.append("\n距离判断: >=").append(String.valueOf(enter))
                .append("dBm 触发, <=").append(String.valueOf(exit))
                .append("dBm 走远, 连续").append(String.valueOf(times)).append("次");
        sb.append("\n最近一次发现目标: ").append(TextUtils.isEmpty(lastSeen) ? "暂无" : lastSeen);
        sb.append("\n服务启动时间: ").append(TextUtils.isEmpty(started) ? "暂无" : started);
        sb.append("\n看门狗: ");
        appendStatusLine(sb, Utils.isServiceRunning(this, WatchdogService.class));
        statusText.setText(sb);

        btnStart.setText(running ? "重启守护服务" : "启动守护服务");
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

    private void showAppDialog() {
        mainLog("showAppDialog clicked");
        try {
            if (appDialog != null && appDialog.isShowing()) return;
            loadInstalledApps();

            View v = LayoutInflater.from(this).inflate(R.layout.dialog_app, null);
            etAppFilter = v.findViewById(R.id.etAppFilter);
            dialogAppList = v.findViewById(R.id.dialogAppList);
            dialogAppList.setAdapter(new AppAdapter());
            dialogAppList.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= appVisible.size()) return;
                String pkg = appVisible.get(position);
                String label = appLabels.get(pkg);
                AppPrefs.set(this, AppPrefs.K_LAUNCH_PACKAGE, pkg);
                AppPrefs.set(this, AppPrefs.K_LAUNCH_LABEL, label == null ? pkg : label);
                if (appDialog != null) appDialog.dismiss();
                Toast.makeText(this, "已选择拉起: " + label + "\n" + pkg, Toast.LENGTH_LONG).show();
                startGuard();
            });
            etAppFilter.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    filterApps(s == null ? "" : s.toString());
                }
            });

            appDialog = new AlertDialog.Builder(this)
                    .setTitle("选择要拉起的应用")
                    .setView(v)
                    .setNegativeButton("取消", null)
                    .setOnDismissListener(d -> {
                        appDialog = null;
                        dialogAppList = null;
                        etAppFilter = null;
                    })
                    .show();
            appDialog.setCanceledOnTouchOutside(false);
            mainLog("app dialog shown=" + appDialog.isShowing() + ", apps=" + appPackages.size());
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            mainLog("showAppDialog ERROR: " + sw.toString());
        }
    }

    private void loadInstalledApps() {
        appLabels.clear();
        appPackages.clear();
        appVisible.clear();
        try {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
            for (ResolveInfo ri : apps) {
                String pkg = ri.activityInfo.packageName;
                if (TextUtils.isEmpty(pkg) || appLabels.containsKey(pkg)) continue;
                CharSequence l = ri.loadLabel(pm);
                appLabels.put(pkg, l == null ? pkg : l.toString());
                appPackages.add(pkg);
            }
            Collections.sort(appPackages, (a, b) -> {
                String la = appLabels.get(a);
                String lb = appLabels.get(b);
                return (la == null ? a : la).compareToIgnoreCase(lb == null ? b : lb);
            });
            appVisible.addAll(appPackages);
        } catch (Throwable t) {
            mainLog("loadInstalledApps ERROR: " + t.getMessage());
        }
    }

    private void filterApps(String query) {
        appVisible.clear();
        String key = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        for (String pkg : appPackages) {
            String label = appLabels.get(pkg);
            if (key.isEmpty()
                    || (label != null && label.toLowerCase(Locale.getDefault()).contains(key))
                    || pkg.toLowerCase(Locale.getDefault()).contains(key)) {
                appVisible.add(pkg);
            }
        }
        if (dialogAppList != null && dialogAppList.getAdapter() != null) {
            ((BaseAdapter) dialogAppList.getAdapter()).notifyDataSetChanged();
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return appVisible.size(); }
        @Override public Object getItem(int i) { return appVisible.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View view, ViewGroup parent) {
            TextView tv = view instanceof TextView ? (TextView) view : new TextView(MainActivity.this);
            String pkg = appVisible.get(i);
            String label = appLabels.get(pkg);
            tv.setText((label == null ? pkg : label) + "\n" + pkg);
            tv.setTextSize(15);
            tv.setTextColor(getResources().getColor(R.color.text_primary));
            tv.setPadding(12, 16, 12, 16);
            return tv;
        }
    }

    private void showDistanceDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_distance, null);
        Slider slider = v.findViewById(R.id.slider);
        TextView modeLabel = v.findViewById(R.id.modeLabel);
        View customBox = v.findViewById(R.id.customBox);
        EditText etEnter = v.findViewById(R.id.etEnter);
        EditText etExit = v.findViewById(R.id.etExit);
        EditText etTimes = v.findViewById(R.id.etTimes);

        int mode = clampMode(AppPrefs.getInt(this, AppPrefs.K_DISTANCE_MODE, 1));
        slider.setValue(mode);
        etEnter.setText(String.valueOf(AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, MODE_ENTER[mode])));
        etExit.setText(String.valueOf(AppPrefs.getInt(this, AppPrefs.K_RSSI_EXIT, MODE_EXIT[mode])));
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
                    int exit;
                    int times;

                    if (m == 3) {
                        Integer e = parseIntOrNull(etEnter);
                        Integer x = parseIntOrNull(etExit);
                        Integer t = parseIntOrNull(etTimes);
                        if (e == null || x == null || t == null) {
                            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (e < -100 || e > 0 || x < -100 || x > 0) {
                            Toast.makeText(this, "阈值范围应为 -100 到 0", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (x > e) {
                            Toast.makeText(this, "离开阈值必须小于/等于进入阈值（更负）", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (t < 1 || t > 10) {
                            Toast.makeText(this, "连续次数应为 1 到 10", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        enter = e;
                        exit = x;
                        times = t;
                    } else {
                        enter = MODE_ENTER[m];
                        exit = MODE_EXIT[m];
                        times = MODE_TIMES[m];
                    }

                    AppPrefs.set(this, AppPrefs.K_DISTANCE_MODE, String.valueOf(m));
                    AppPrefs.set(this, AppPrefs.K_RSSI_ENTER, String.valueOf(enter));
                    AppPrefs.set(this, AppPrefs.K_RSSI_EXIT, String.valueOf(exit));
                    AppPrefs.set(this, AppPrefs.K_CONFIRM_TIMES, String.valueOf(times));
                    startGuard();
                    Toast.makeText(this,
                            "已保存：" + modeName(m) + "  进入≥" + enter + " / 离开≤" + exit + " / 连续" + times + "次",
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
