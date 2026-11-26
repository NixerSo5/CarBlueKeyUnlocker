package com.nixer.carunlocker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 无障碍服务：靠近车辆后，自动把上汽 App 打开到“数字钥匙”页。
 *
 * 只做三件事：
 *   1. 启动上汽 App（导出入口，正常启动）；
 *   2. 在首页找到文字为“数字钥匙”的入口并点击；
 *   3. 等待页面自动连接车辆（不退出页面，避免连接被断开）。
 *
 * 不会点击隐私协议、登录、支付、分享等任何按钮；只识别“跳过”广告和“数字钥匙”。
 */
public class SaicKeyAccessibilityService extends AccessibilityService {
    public static final String SAIC_PACKAGE = "com.saicmotor.tocapp";
    private static final String LAUNCHER_CLASS = "com.saicmotor.common.activity.WelcomeActivityDefault";
    private static final String DK_TEXT = "数字钥匙";
    private static final String ID_DK_STATE = SAIC_PACKAGE + ":id/tv_digitalkey_state";
    private static final String ID_HOME_LIST = SAIC_PACKAGE + ":id/rv_smart_component";

    private static final long FIRST_WAIT_MS = 2500;      // 给上汽 App 启动/闪屏留时间
    private static final long RETRY_MS = 1000;           // 找不到入口时的重试间隔
    private static final int MAX_FIND_ATTEMPTS = 25;     // 最多找 25 秒
    private static final long AFTER_CLICK_WAIT_MS = 3000; // 点击后等页面加载
    private static final long REPORT_WAIT_MS = 12000;    // 等蓝牙连接/解锁

    private static volatile SaicKeyAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean flowRunning = false;
    private boolean dkClicked = false;
    private boolean keyguardLogged = false;
    private int attempts = 0;
    private String reason = "";

    public static boolean isEnabled(Context c) {
        if (instance != null) return true;
        if (c == null) return false;
        try {
            AccessibilityManager am = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null && am.isEnabled()) {
                List<AccessibilityServiceInfo> list =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (list != null) {
                    for (AccessibilityServiceInfo info : list) {
                        if (info == null || info.getResolveInfo() == null
                                || info.getResolveInfo().serviceInfo == null) continue;
                        if (c.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 部分 ROM 上 getEnabledAccessibilityServiceList 返回不全，直接读系统设置兜底
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null) {
                String pkg = c.getPackageName().toLowerCase();
                String lower = enabled.toLowerCase();
                if (lower.contains(pkg + "/") || lower.contains(pkg + ".")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 由 CarService 调用：请求执行一次“打开数字钥匙页”流程。 */
    public static boolean trigger(Context c, String why) {
        SaicKeyAccessibilityService s = instance;
        if (s == null) return false;
        final String r = why == null ? "" : why;
        s.handler.post(() -> s.startFlow(r));
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
                setServiceInfo(info);
            }
        } catch (Throwable ignored) {
        }
        LogUtil.log(this, "ACC: 无障碍服务已连接");
        AppPrefs.set(this, AppPrefs.K_ACC_STATE, "无障碍已就绪");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 主动轮询，不依赖事件。
    }

    @Override
    public void onInterrupt() {
        finishFlow("被系统打断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
            LogUtil.log(this, "ACC: 无障碍服务已断开");
            AppPrefs.set(this, AppPrefs.K_ACC_STATE, "无障碍已关闭");
        }
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void startFlow(String why) {
        if (flowRunning) {
            LogUtil.log(this, "ACC: 流程进行中，忽略本次触发: " + why);
            return;
        }
        flowRunning = true;
        dkClicked = false;
        keyguardLogged = false;
        attempts = 0;
        reason = why;
        AppPrefs.set(this, AppPrefs.K_ACC_STATE, "正在打开上汽App");
        LogUtil.log(this, "ACC: 开始流程 " + why);
        launchSaicApp();
        handler.postDelayed(this::stepFind, FIRST_WAIT_MS);
    }

    private void launchSaicApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(SAIC_PACKAGE);
            if (i == null) {
                i = new Intent();
                i.setComponent(new ComponentName(SAIC_PACKAGE, LAUNCHER_CLASS));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            LogUtil.log(this, "ACC: 已启动上汽App");
        } catch (Throwable t) {
            LogUtil.log(this, "ACC: 启动上汽App失败: " + t);
            AppPrefs.set(this, AppPrefs.K_ACC_STATE, "启动上汽App失败（请给后台弹出界面权限）");
            finishFlow("启动失败");
        }
    }

    private void stepFind() {
        if (!flowRunning) return;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) root = firstWindowRoot();
            if (root != null) {
                String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
                if ("com.android.systemui".equals(pkg)) {
                    // 锁屏界面在顶层：无障碍读不到上汽界面，无法点击
                    if (!keyguardLogged) {
                        keyguardLogged = true;
                        LogUtil.log(this, "ACC: 屏幕处于锁屏/系统界面，暂时无法点击（解锁后会自动继续尝试）");
                        AppPrefs.set(this, AppPrefs.K_ACC_STATE, "锁屏中，等待解锁");
                    }
                    attempts++;
                    if (attempts > 12) {
                        LogUtil.log(this, "ACC: 锁屏未解锁，放弃本次自动点击");
                        AppPrefs.set(this, AppPrefs.K_ACC_STATE, "锁屏未解锁，请手动打开数字钥匙页");
                        finishFlow("锁屏");
                        return;
                    }
                    handler.postDelayed(this::stepFind, RETRY_MS);
                    return;
                }
                if (isOnDkPage(root)) {
                    onDkPageReady("已在数字钥匙页");
                    return;
                }
                if (!dkClicked) {
                    AccessibilityNodeInfo skip = findClickableByText(root, "跳过");
                    if (skip != null) {
                        logClickNode(skip, "跳过广告");
                        handler.postDelayed(this::stepFind, 1500);
                        return;
                    }
                    AccessibilityNodeInfo node = findByText(root, DK_TEXT);
                    if (node != null) {
                        if (clickNode(node, DK_TEXT)) {
                            dkClicked = true;
                            AppPrefs.set(this, AppPrefs.K_ACC_STATE, "已点击数字钥匙");
                            handler.postDelayed(this::stepFind, AFTER_CLICK_WAIT_MS);
                            return;
                        }
                    } else {
                        // 入口可能在列表下方，尝试滚动一次
                        AccessibilityNodeInfo list = findByViewId(root, ID_HOME_LIST);
                        if (list != null) {
                            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogUtil.log(this, "ACC: 查找入口异常: " + t);
        }

        attempts++;
        if (attempts > MAX_FIND_ATTEMPTS) {
            LogUtil.log(this, "ACC: 未找到“数字钥匙”入口，放弃（尝试 " + attempts + " 次）");
            AppPrefs.set(this, AppPrefs.K_ACC_STATE, "未找到数字钥匙入口");
            finishFlow("未找到入口");
            return;
        }
        handler.postDelayed(this::stepFind, RETRY_MS);
    }

    private void onDkPageReady(String how) {
        AppPrefs.set(this, AppPrefs.K_ACC_STATE, "数字钥匙页已打开");
        LogUtil.log(this, "ACC: 数字钥匙页已打开（" + how + "），等待蓝牙连接");
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::stepReport, REPORT_WAIT_MS);
    }

    /** 只读取页面状态写日志，方便真车调试；不退出页面，避免断开蓝牙连接。 */
    private void stepReport() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String state = "";
            if (root != null) {
                AccessibilityNodeInfo n = findByViewId(root, ID_DK_STATE);
                if (n != null && n.getText() != null) state = n.getText().toString();
            }
            if (TextUtils.isEmpty(state)) {
                LogUtil.log(this, "ACC: 等待结束（未读到钥匙状态）");
                AppPrefs.set(this, AppPrefs.K_ACC_STATE, "已打开数字钥匙页");
            } else {
                LogUtil.log(this, "ACC: 数字钥匙状态 = " + state);
                AppPrefs.set(this, AppPrefs.K_ACC_STATE, "数字钥匙状态: " + state);
            }
        } catch (Throwable t) {
            LogUtil.log(this, "ACC: 读取状态异常: " + t);
        }
        finishFlow("完成");
    }

    private void finishFlow(String why) {
        flowRunning = false;
        dkClicked = false;
        handler.removeCallbacksAndMessages(null);
        LogUtil.log(this, "ACC: 流程结束（" + why + "）");
    }

    // ---------------- 无障碍树工具 ----------------

    private AccessibilityNodeInfo firstWindowRoot() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return null;
            for (AccessibilityWindowInfo w : windows) {
                if (w == null) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) return r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isOnDkPage(AccessibilityNodeInfo root) {
        if (findByViewId(root, ID_DK_STATE) != null) return true;
        return findByText(root, "手机钥匙") != null && findByText(root, "近车自动解锁") != null;
    }

    private AccessibilityNodeInfo findByViewId(AccessibilityNodeInfo root, String viewId) {
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
            if (list != null && !list.isEmpty()) return list.get(0);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo root, String text) {
        try {
            List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText(text);
            if (list == null) return null;
            for (AccessibilityNodeInfo n : list) {
                if (n == null || n.getText() == null) continue;
                if (text.contentEquals(n.getText())) return n;
            }
            for (AccessibilityNodeInfo n : list) {
                if (n != null && n.getText() != null) return n;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableByText(AccessibilityNodeInfo root, String text) {
        AccessibilityNodeInfo n = findByText(root, text);
        if (n == null) return null;
        return clickableAncestor(n);
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node, String label) {
        AccessibilityNodeInfo target = clickableAncestor(node);
        boolean ok = false;
        if (target != null) {
            ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        if (!ok) ok = tapCenter(node);
        LogUtil.log(this, "ACC: 点击“" + label + "” " + (ok ? "成功" : "失败"));
        return ok;
    }

    private void logClickNode(AccessibilityNodeInfo node, String label) {
        clickNode(node, label);
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        try {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) return false;
            Path p = new Path();
            p.moveTo(r.centerX(), r.centerY());
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                    .build();
            return dispatchGesture(g, null, null);
        } catch (Throwable t) {
            return false;
        }
    }
}
