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
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Spannable;
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

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "CarGuard";
    private static final int REQ_PERMS = 100;
    private static final int MODE_MANUAL = 5;

    // 五档 + 手动；默认第 1 档 -70dBm（约 5~10 米，给连接认证留时间）
    private static final String[] MODE_NAMES = {
            "推荐（约5-10米）", "远（约4-7米）", "中（约3-5米）",
            "近（约2米）", "很近（约1米）", "手动"
    };
    private static final int[] MODE_ENTER = {-70, -63, -56, -50, -45, -70};
    private static final int[] MODE_TIMES = {2, 2, 2, 2, 2, 2};
    private static final String[] MODE_DISTANCE = {
            "约5-10米", "约4-7米", "约3-5米", "约2米", "约1米", "手动"
    };

    private TextView targetText;
    private TextView statusHeadline;
    private TextView keyStateText;
    private TextView macText;
    private TextView detailsMac;
    private TextView detailsKey;
    private TextView detailsDoor;
    private TextView watchdogText;
    private TextView guardText;
    private TextView connText;
    private TextView doorText;
    private TextView distanceText;
    private TextView distanceSubText;
    private View connCard;
    private View connDot;
    private View connIcon;
    private View btnUnlock;
    private View btnLock;
    private TextView unlockLabel;
    private TextView lockLabel;

    private AlertDialog settingsDialog;
    private AlertDialog loginDialog;
    private TextView loginStatus;

    private AlertDialog logDialog;
    private TextView logText;
    private ScrollView logScroll;
    private CheckBox logAutoRefresh;
    private boolean showDetailLog = false;
    private Button btnLogType;
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
        setTitle(getString(R.string.app_name) + "  v" + BuildConfig.VERSION_NAME);
        applyWindowInsets();

        targetText = findViewById(R.id.targetText);
        statusHeadline = findViewById(R.id.statusHeadline);
        keyStateText = findViewById(R.id.keyStateText);
        macText = findViewById(R.id.macText);
        detailsMac = findViewById(R.id.detailsMac);
        detailsKey = findViewById(R.id.detailsKey);
        detailsDoor = findViewById(R.id.detailsDoor);
        watchdogText = findViewById(R.id.watchdogText);
        guardText = findViewById(R.id.guardText);
        connText = findViewById(R.id.connText);
        doorText = findViewById(R.id.doorText);
        distanceText = findViewById(R.id.distanceText);
        distanceSubText = findViewById(R.id.distanceSubText);
        connCard = findViewById(R.id.connCard);
        connDot = findViewById(R.id.connDot);
        connIcon = findViewById(R.id.connIcon);
        btnUnlock = findViewById(R.id.btnUnlock);
        btnLock = findViewById(R.id.btnLock);
        unlockLabel = findViewById(R.id.unlockLabel);
        lockLabel = findViewById(R.id.lockLabel);

        btnUnlock.setOnClickListener(v -> triggerManualUnlock());
        btnLock.setOnClickListener(v -> triggerManualLock());
        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettingsDialog());

        // 设备详情折叠
        View detailsHeader = findViewById(R.id.detailsHeader);
        View detailsBody = findViewById(R.id.detailsBody);
        View chevron = findViewById(R.id.chevron);
        detailsHeader.setOnClickListener(v -> {
            boolean open = detailsBody.getVisibility() == View.VISIBLE;
            detailsBody.setVisibility(open ? View.GONE : View.VISIBLE);
            chevron.animate().rotation(open ? 0f : 180f).setDuration(200).start();
        });

        // 不再提供默认车辆：MAC / 名称必须由用户在设备列表中选择，或由登录后的钥匙写入
        requestAllPermissions();
        scheduleKeepAlive();
        handler.post(statusRefresh);

        // adb: am start -n com.nixer.carunlocker/.MainActivity -a com.nixer.carunlocker.action.MANUAL_UNLOCK
        String action = getIntent().getAction();
        if (CarService.ACTION_MANUAL_UNLOCK.equals(action)) {
            handler.postDelayed(this::triggerManualUnlock, 300);
        } else if (CarService.ACTION_MANUAL_LOCK.equals(action)) {
            handler.postDelayed(this::triggerManualLock, 300);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent == null) return;
        if (CarService.ACTION_MANUAL_UNLOCK.equals(intent.getAction())) {
            triggerManualUnlock();
        } else if (CarService.ACTION_MANUAL_LOCK.equals(intent.getAction())) {
            triggerManualLock();
        }
    }

    private void triggerManualUnlock() {
        if ("unlocked".equals(AppPrefs.get(this, AppPrefs.K_CAR_DOOR))) {
            Toast.makeText(this, "车门当前已开，无需重复解锁", Toast.LENGTH_SHORT).show();
            return;
        }
        startAction(CarService.ACTION_MANUAL_UNLOCK);
        Toast.makeText(this, "正在连接车辆并解锁…", Toast.LENGTH_LONG).show();
    }

    private void triggerManualLock() {
        if ("locked".equals(AppPrefs.get(this, AppPrefs.K_CAR_DOOR))) {
            Toast.makeText(this, "车门当前已锁，无需重复上锁", Toast.LENGTH_SHORT).show();
            return;
        }
        startAction(CarService.ACTION_MANUAL_LOCK);
        Toast.makeText(this, "正在连接车辆并关锁…", Toast.LENGTH_LONG).show();
    }

    private void startAction(String action) {
        Intent i = new Intent(this, CarService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void showSettingsDialog() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        TextView info = v.findViewById(R.id.tvSettingsInfo);
        Button btnLogin = v.findViewById(R.id.btnLoginSetting);
        Button btnMode = v.findViewById(R.id.btnModeSetting);
        Button btnDistance = v.findViewById(R.id.btnDistanceSetting);
        Button btnRestart = v.findViewById(R.id.btnRestartSetting);
        Button btnDevice = v.findViewById(R.id.btnDeviceSetting);
        Button btnLog = v.findViewById(R.id.btnLogSetting);
        CheckBox cbDetail = v.findViewById(R.id.cbDetailLog);

        info.setText("钥匙: " + keyStatusText() + "\n目标: "
                + (TextUtils.isEmpty(AppPrefs.targetMac(this)) ? "未绑定" : AppPrefs.targetMac(this)));
        btnMode.setText(AppPrefs.powerSave(this) ? "扫描模式：分层（点击切换高灵敏）"
                : "扫描模式：高灵敏（点击切换分层）");
        cbDetail.setChecked(AppPrefs.detailLog(this));

        btnLogin.setOnClickListener(x -> {
            settingsDialog.dismiss();
            showLoginDialog();
        });
        btnMode.setOnClickListener(x -> {
            boolean tiered = !AppPrefs.powerSave(this);
            AppPrefs.setBool(this, AppPrefs.K_POWER_SAVE, tiered);
            btnMode.setText(tiered ? "扫描模式：分层（点击切换高灵敏）"
                    : "扫描模式：高灵敏（点击切换分层）");
            startGuard();
            Toast.makeText(this, tiered ? "已切到分层：待机低功耗，发现车后升档"
                    : "已切到高灵敏：持续低延迟扫描（较费电）", Toast.LENGTH_LONG).show();
        });
        btnDistance.setOnClickListener(x -> {
            settingsDialog.dismiss();
            showDistanceDialog();
        });
        btnRestart.setOnClickListener(x -> {
            startGuard();
            Toast.makeText(this, "守护服务已重启", Toast.LENGTH_SHORT).show();
        });
        btnDevice.setOnClickListener(x -> showDeviceDialog());
        btnLog.setOnClickListener(x -> {
            settingsDialog.dismiss();
            showLogDialog();
        });
        cbDetail.setOnCheckedChangeListener((button, checked) -> {
            AppPrefs.setBool(this, AppPrefs.K_DETAIL_LOG, checked);
            AppLog.event(this, "详细日志已" + (checked ? "开启" : "关闭"));
        });

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(v)
                .setPositiveButton("关闭", null)
                .setOnDismissListener(d -> settingsDialog = null)
                .show();
    }

    private int clampMode(int mode) {
        if (mode < 0) return 0;
        if (mode > MODE_MANUAL) return MODE_MANUAL;
        return mode;
    }

    private String modeName(int mode) {
        return MODE_NAMES[clampMode(mode)];
    }

    private String modeDescription(int mode) {
        int m = clampMode(mode);
        if (m == MODE_MANUAL) return "手动设置进入/离开阈值和确认次数";
        return "进入 ≥" + MODE_ENTER[m] + "dBm，走远 ≤" + (MODE_ENTER[m] - 15)
                + "dBm，连续 " + MODE_TIMES[m] + " 次";
    }

    /** 主页只显示：看门狗、守护服务、车辆连接、车门状态、距离挡位。 */
    private void updateStatus() {
        boolean running = Utils.isServiceRunning(this, CarService.class);
        boolean watchdog = Utils.isServiceRunning(this, WatchdogService.class);
        boolean connected = "1".equals(AppPrefs.get(this, AppPrefs.K_CAR_CONNECTED));
        String door = AppPrefs.get(this, AppPrefs.K_CAR_DOOR);
        String doorValue;
        if ("locked".equals(door)) doorValue = "已锁";
        else if ("unlocked".equals(door)) doorValue = "已开";
        else doorValue = "未知(连接后查询)";

        String mac = AppPrefs.targetMac(this);
        String name = AppPrefs.targetName(this);
        targetText.setText(TextUtils.isEmpty(name) ? "我的车辆" : name);
        String macValue = TextUtils.isEmpty(mac) ? "未绑定" : mac;
        macText.setText(macValue);
        detailsMac.setText("(" + macValue + ")");
        String key = keyStatusText().replace("（", "(").replace("）", ")");
        keyStateText.setText(key);
        detailsKey.setText(key);
        detailsDoor.setText("蓝牙连接后自动查询");

        statusHeadline.setText(running ? "守护中" : "未启动");
        watchdogText.setText(watchdog ? "运行中" : "未活动");
        guardText.setText(running ? "运行中" : "未活动");
        int onColor = Color.parseColor("#4BE79A");
        int offColor = Color.parseColor("#9CA3AF");
        watchdogText.setTextColor(watchdog ? onColor : offColor);
        guardText.setTextColor(running ? onColor : offColor);
        statusHeadline.setTextColor(running ? onColor : offColor);

        connText.setText(connected ? "已连接" : "未活动");
        connText.setTextColor(connected ? onColor : offColor);
        doorText.setText("车门: " + doorValue);
        connCard.setBackgroundResource(connected ? R.drawable.bg_stat_card_active : R.drawable.bg_stat_card);
        connDot.setBackgroundResource(connected ? R.drawable.bg_dot : R.drawable.bg_dot_inactive);
        connIcon.setBackgroundResource(connected ? R.drawable.bg_icon_green : R.drawable.bg_icon_gray);

        int mode = clampMode(AppPrefs.getInt(this, AppPrefs.K_DISTANCE_MODE, 0));
        int enter = AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, MODE_ENTER[mode]);
        int exit = AppPrefs.getInt(this, AppPrefs.K_RSSI_EXIT, MODE_ENTER[mode] - 15);
        distanceText.setText(mode == MODE_MANUAL ? "手动"
                : "约" + MODE_DISTANCE[mode].replace("约", ""));
        distanceSubText.setText((mode == MODE_MANUAL ? "" : "第" + (mode + 1) + "档 · ")
                + enter + "~" + exit + " dBm");
    }

    private void applyWindowInsets() {
        View scroll = findViewById(R.id.mainScroll);
        View content = findViewById(R.id.mainContent);
        final int baseTop = content.getPaddingTop();
        final int baseBottom = content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            content.setPadding(content.getPaddingLeft(), baseTop + bars.top,
                    content.getPaddingRight(), baseBottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(scroll);
    }

    private String keyStatusText() {
        boolean runtime = !TextUtils.isEmpty(AppPrefs.get(this, AppPrefs.K_BLE_KS))
                && !TextUtils.isEmpty(AppPrefs.get(this, AppPrefs.K_BLE_KEY_ID))
                && !TextUtils.isEmpty(AppPrefs.get(this, AppPrefs.K_BLE_KR));
        return runtime ? "已配置（本机下载）" : "未配置钥匙";
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
        label.setText(mode == MODE_MANUAL
                ? "手动：自己填写进入/离开阈值"
                : "挡位 " + (mode + 1) + "/5：" + modeName(mode) + "\n" + modeDescription(mode));
        customBox.setVisibility(mode == MODE_MANUAL ? View.VISIBLE : View.GONE);
    }

    private void showDistanceDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_distance, null);
        Slider slider = v.findViewById(R.id.slider);
        TextView modeLabel = v.findViewById(R.id.modeLabel);
        View customBox = v.findViewById(R.id.customBox);
        EditText etEnter = v.findViewById(R.id.etEnter);
        EditText etTimes = v.findViewById(R.id.etTimes);
        EditText etExit = v.findViewById(R.id.etExit);
        EditText etCooldown = v.findViewById(R.id.etCooldown);

        int mode = clampMode(AppPrefs.getInt(this, AppPrefs.K_DISTANCE_MODE, 0));
        slider.setValue(mode);
        int defEnter = MODE_ENTER[mode];
        etEnter.setText(String.valueOf(AppPrefs.getInt(this, AppPrefs.K_RSSI_ENTER, defEnter)));
        etTimes.setText(String.valueOf(AppPrefs.getInt(this, AppPrefs.K_CONFIRM_TIMES, MODE_TIMES[mode])));
        etExit.setText(String.valueOf(AppPrefs.getInt(this, AppPrefs.K_RSSI_EXIT, defEnter - 15)));
        etCooldown.setText(String.valueOf(AppPrefs.getInt(this, AppPrefs.K_COOLDOWN_SEC, 8)));
        updateDistanceDialogUi(mode, modeLabel, customBox);

        slider.addOnChangeListener((s, value, fromUser) ->
                updateDistanceDialogUi(Math.round(value), modeLabel, customBox));

        new AlertDialog.Builder(this)
                .setTitle("距离设置（五档 + 手动）")
                .setView(v)
                .setPositiveButton("保存并生效", (d, w) -> {
                    int m = clampMode(Math.round(slider.getValue()));
                    int enter;
                    int times;
                    int exitRssi;

                    Integer cd = parseIntOrNull(etCooldown);
                    if (cd == null || cd < 1 || cd > 60) {
                        Toast.makeText(this, "冷却时间应为 1 到 60 秒（建议 5-10）", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (m == MODE_MANUAL) {
                        Integer e = parseIntOrNull(etEnter);
                        Integer t = parseIntOrNull(etTimes);
                        Integer x = parseIntOrNull(etExit);
                        if (e == null || t == null || x == null) {
                            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (e < -100 || e > 0 || x < -100 || x > 0) {
                            Toast.makeText(this, "阈值范围应为 -100 到 0", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (t < 1 || t > 10) {
                            Toast.makeText(this, "连续次数应为 1 到 10", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        enter = e;
                        times = t;
                        exitRssi = x;
                    } else {
                        enter = MODE_ENTER[m];
                        times = MODE_TIMES[m];
                        exitRssi = enter - 15;
                    }

                    AppPrefs.set(this, AppPrefs.K_DISTANCE_MODE, String.valueOf(m));
                    AppPrefs.set(this, AppPrefs.K_RSSI_ENTER, String.valueOf(enter));
                    AppPrefs.set(this, AppPrefs.K_RSSI_EXIT, String.valueOf(exitRssi));
                    AppPrefs.set(this, AppPrefs.K_CONFIRM_TIMES, String.valueOf(times));
                    AppPrefs.set(this, AppPrefs.K_COOLDOWN_SEC, String.valueOf(cd));
                    startGuard();
                    Toast.makeText(this, "已保存：" + (m == MODE_MANUAL ? "手动" : "第" + (m + 1) + "档")
                                    + "  靠近≥" + enter + " / 走远≤" + exitRssi + " dBm",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 日志：简单 / 详细 ----------------

    private String logLabel() {
        return showDetailLog ? "详细日志" : "简单日志";
    }

    private String readLogTail(int maxLines) {
        String raw = AppLog.readTail(this, showDetailLog, maxLines);
        String[] lines = raw.split("\n");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].length() == 0) continue;
            sb.append(lines[i]).append('\n');
            shown++;
        }
        return logLabel() + " 共 " + AppLog.lineCount(this, showDetailLog) + " 行，显示最近 "
                + shown + " 行（最新在上）\n\n" + sb;
    }

    private String readWholeLog() {
        return AppLog.readAll(this, showDetailLog);
    }

    private void clearLog() {
        AppLog.clear(this, showDetailLog);
        Toast.makeText(this, logLabel() + "已清空", Toast.LENGTH_SHORT).show();
    }

    private void copyLogToClipboard() {
        String text = readWholeLog();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(logLabel(), text));
                Toast.makeText(this, logLabel() + "已复制（" + text.length() + " 字符）", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareLog() {
        String text = readWholeLog();
        if (text.length() > 400_000) text = text.substring(text.length() - 400_000);
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "07电子钥匙助手 " + logLabel());
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, "导出日志"));
        } catch (Throwable t) {
            Toast.makeText(this, "导出失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 扫到目标/连接/握手=绿色，失败=红色，其余灰色。 */
    private CharSequence colorizeLog(String text) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        int start = 0;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            int end = Math.min(start + line.length(), sb.length());
            int color;
            if (line.contains("靠近解锁") || line.contains("连接车辆") || line.contains("握手")
                    || line.contains("认证成功") || line.contains("解锁成功") || line.contains("锁车成功")
                    || line.contains("车门状态更新")) {
                color = Color.parseColor("#6EE7B7");
            } else if (line.contains("失败") || line.contains("error") || line.contains("异常")
                    || line.contains("车端返回失败")) {
                color = Color.parseColor("#FCA5A5");
            } else {
                color = Color.parseColor("#9CA3AF");
            }
            if (end > start) sb.setSpan(new ForegroundColorSpan(color), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            start = end + 1;
        }
        return sb;
    }

    private void showLogDialog() {
        if (logDialog != null && logDialog.isShowing()) return;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        btnLogType = new Button(this);
        btnLogType.setAllCaps(false);
        btnLogType.setText(logLabel() + "（点击切换）");
        root.addView(btnLogType);

        logAutoRefresh = new CheckBox(this);
        logAutoRefresh.setText("自动刷新（1.5秒）");
        logAutoRefresh.setChecked(true);
        root.addView(logAutoRefresh);

        LinearLayout logButtons = new LinearLayout(this);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button btnCopyLog = new Button(this);
        btnCopyLog.setText("复制全部");
        btnCopyLog.setAllCaps(false);
        Button btnShareLog = new Button(this);
        btnShareLog.setText("导出/分享");
        btnShareLog.setAllCaps(false);
        Button btnClearLog = new Button(this);
        btnClearLog.setText("清空");
        btnClearLog.setAllCaps(false);
        logButtons.addView(btnCopyLog, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        logButtons.addView(btnShareLog, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        logButtons.addView(btnClearLog, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(logButtons);
        btnCopyLog.setOnClickListener(v -> copyLogToClipboard());
        btnShareLog.setOnClickListener(v -> shareLog());
        btnClearLog.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清空" + logLabel())
                .setMessage("确定清空当前日志？此操作不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, which) -> {
                    clearLog();
                    logText.setText(colorizeLog(readLogTail(300)));
                })
                .show());

        logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextColor(Color.parseColor("#9CA3AF"));
        logText.setTextIsSelectable(true);
        logScroll.addView(logText);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        logText.setText(colorizeLog(readLogTail(300)));

        btnLogType.setOnClickListener(v -> {
            showDetailLog = !showDetailLog;
            btnLogType.setText(logLabel() + "（点击切换）");
            if (showDetailLog && !AppPrefs.detailLog(this)) {
                Toast.makeText(this, "详细日志未开启，可在设置里打开", Toast.LENGTH_SHORT).show();
            }
            logText.setText(colorizeLog(readLogTail(300)));
        });

        logDialog = new AlertDialog.Builder(this)
                .setTitle("日志（最多1000条，超出覆盖最旧）")
                .setView(root)
                .setPositiveButton("关闭", null)
                .setOnDismissListener(d -> {
                    logHandler.removeCallbacks(logRefresh);
                    logDialog = null;
                })
                .show();
        logAutoRefresh.setOnCheckedChangeListener((button, checked) -> {
            logHandler.removeCallbacks(logRefresh);
            if (checked) logHandler.post(logRefresh);
        });
        logHandler.post(logRefresh);
    }

    // ---------------- 登录取钥匙 / 设备选择 ----------------

    /** 在本机完成验证码登录、申请并下载钥匙；验证码不发给任何第三方。 */
    private void showLoginDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_login, null);
        EditText phone = v.findViewById(R.id.etLoginPhone);
        EditText code = v.findViewById(R.id.etLoginCode);
        loginStatus = v.findViewById(R.id.tvLoginStatus);
        phone.setText("");

        if (TextUtils.isEmpty(BuildConfig.VC_APP_KEY) || TextUtils.isEmpty(BuildConfig.SAIC_VIN)) {
            loginStatus.setText("本机 local.properties 缺少登录参数（APP_KEY/APP_SECRET/PKSK_HEX/SAIC_*），无法取钥匙");
        }

        loginDialog = new AlertDialog.Builder(this)
                .setTitle("登录并获取钥匙")
                .setView(v)
                .setPositiveButton("获取钥匙", null)
                .setNegativeButton("取消", null)
                .create();
        loginDialog.setOnShowListener(dialog -> {
            Button confirm = loginDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setOnClickListener(view -> {
                String p = phone.getText().toString().trim();
                String c = code.getText().toString().trim();
                if (TextUtils.isEmpty(p)) {
                    loginStatus.setText("请输入手机号");
                    return;
                }
                if (!c.matches("\\d{6}")) {
                    loginStatus.setText("请输入 6 位短信验证码");
                    return;
                }
                if (TextUtils.isEmpty(BuildConfig.VC_APP_KEY) || TextUtils.isEmpty(BuildConfig.SAIC_VIN)) {
                    loginStatus.setText("本机未配置登录参数，无法取钥匙");
                    return;
                }
                confirm.setEnabled(false);
                loginStatus.setText("正在登录…");
                new KeyFetcher(this, p, c, new KeyFetcher.Callback() {
                    @Override
                    public void onProgress(String msg) {
                        if (loginStatus != null) loginStatus.setText(msg);
                    }

                    @Override
                    public void onDone(boolean success, String detail) {
                        confirm.setEnabled(true);
                        if (loginStatus != null) {
                            loginStatus.setText((success ? "成功：" : "失败：") + detail);
                        }
                        if (!success) return;
                        AppLog.event(MainActivity.this, "钥匙下载成功，已绑定设备 " + AppPrefs.targetMac(MainActivity.this));
                        if (loginDialog != null && loginDialog.isShowing()) loginDialog.dismiss();
                        Toast.makeText(MainActivity.this, "钥匙已获取，正在重启守护服务", Toast.LENGTH_LONG).show();
                        updateStatus();
                        startAction(CarService.ACTION_RELOAD_KEYS);
                    }
                }).start();
            });
        });
        loginDialog.show();
    }

    private void showDeviceDialog() {
        AppLog.detail(this, "showDeviceDialog clicked");
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
            deviceDialog.setCanceledOnTouchOutside(false);
            startPicking();
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            AppLog.error(this, "showDeviceDialog ERROR", t);
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

    private void requestAllPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        List<String> need = new ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);

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
        KeepAliveReceiver.scheduleNext(this);
    }

    private void startGuard() {
        try {
            Intent i = new Intent(this, CarService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            Intent w = new Intent(this, WatchdogService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(w);
            else startService(w);
            KeepAliveReceiver.scheduleNext(this);
            AppPrefs.set(this, AppPrefs.K_SERVICE_START,
                    new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(statusRefresh);
        logHandler.removeCallbacks(logRefresh);
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
