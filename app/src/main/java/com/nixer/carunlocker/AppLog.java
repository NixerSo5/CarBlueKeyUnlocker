package com.nixer.carunlocker;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 两段日志：
 * - 简单日志 carguard.log：只记关键事件（连接、握手、认证、解锁/锁车等），始终记录。
 * - 详细日志 carguard_detail.log：记录全部内容，只有开关打开时才写。
 * 两者都最多保留 3000 行，超过后覆盖最旧的行。
 */
public final class AppLog {
    private static final String TAG = "CarGuard";
    public static final String SIMPLE_FILE = "carguard.log";
    public static final String DETAIL_FILE = "carguard_detail.log";
    private static final int MAX_LINES = 3000;

    /** 出现在这些关键词里的消息属于“主要事件”，进简单日志。 */
    private static final String[] EVENT_KEYS = {
            "onCreate", "onStartCommand", "屏幕熄灭", "屏幕点亮", "扫描模式切换", "发现目标",
            "靠近解锁", "目标走远", "目标广播消失", "走远自动锁车", "信号过弱",
            "钥匙已更新", "钥匙已获取", "守护服务", "开始直接", "存在广播",
            "连接车辆", "连接状态 status", "服务发现", "开启通知", "收到车端握手请求",
            "收到 80CA", "认证结果", "认证成功", "认证失败", "s2 解密成功", "step2 已发送",
            "发送车辆状态查询", "收到车辆状态响应", "车辆锁状态", "车辆状态",
            "发送 RKE", "RKE 挑战", "解锁成功", "锁车成功", "解锁失败", "锁车失败",
            "车端返回失败", "保活连接断开", "启动保活结束", "BLE: 开始",
            "提前连接", "预连接", "已靠近", "扫描采样", "连接耗时", "认证耗时",
            "GATT", "唤醒广播", "解锁请求", "取消预连接", "等待靠近", "连接尝试"
    };

    private AppLog() {}

    public static boolean detailEnabled(Context c) {
        return c != null && AppPrefs.detailLog(c);
    }

    /** 自动分流：关键事件进简单日志；开关打开时所有内容都进详细日志。 */
    public static void auto(Context c, String msg) {
        Log.d(TAG, msg);
        if (c == null) return;
        if (isEvent(msg)) write(c, SIMPLE_FILE, msg);
        if (detailEnabled(c)) write(c, DETAIL_FILE, msg);
    }

    /** 强制作为关键事件记录（简单日志 + 详细日志）。 */
    public static void event(Context c, String msg) {
        Log.d(TAG, msg);
        if (c == null) return;
        write(c, SIMPLE_FILE, msg);
        if (detailEnabled(c)) write(c, DETAIL_FILE, msg);
    }

    /** 只写详细日志；开关关闭时完全不落盘。 */
    public static void detail(Context c, String msg) {
        if (detailEnabled(c)) write(c, DETAIL_FILE, msg);
    }

    public static void error(Context c, String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) t.printStackTrace(new PrintWriter(sw));
        event(c, msg + (t == null ? "" : " :: " + sw.toString()));
    }

    private static boolean isEvent(String msg) {
        if (msg == null) return false;
        for (String k : EVENT_KEYS) if (msg.contains(k)) return true;
        return false;
    }

    private static File file(Context c, boolean detail) {
        File dir = c.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, detail ? DETAIL_FILE : SIMPLE_FILE);
    }

    private static synchronized void write(Context c, String name, String msg) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        File f = new File(dir, name);
        try {
            List<String> lines = new ArrayList<>();
            if (f.exists()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                String s;
                while ((s = r.readLine()) != null) lines.add(s);
                r.close();
            }
            lines.add(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date()) + " " + msg);
            while (lines.size() > MAX_LINES) lines.remove(0);
            FileOutputStream out = new FileOutputStream(f, false);
            for (String s : lines) out.write((s + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    public static int lineCount(Context c, boolean detail) {
        File f = file(c, detail);
        if (f == null || !f.exists()) return 0;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            int n = 0;
            while (r.readLine() != null) n++;
            return n;
        } catch (Throwable t) {
            return 0;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    /** 从文件末尾取最多 maxLines 行，按时间正序返回（旧在上，新在下）。 */
    public static String readTail(Context c, boolean detail, int maxLines) {
        File f = file(c, detail);
        if (f == null || !f.exists()) return "";
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            ArrayDeque<String> tail = new ArrayDeque<>();
            String s;
            while ((s = r.readLine()) != null) {
                if (tail.size() >= maxLines) tail.pollFirst();
                tail.add(s);
            }
            StringBuilder sb = new StringBuilder();
            for (String line : tail) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return "读取日志失败: " + t.getMessage();
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    public static String readAll(Context c, boolean detail) {
        return readTail(c, detail, Integer.MAX_VALUE);
    }

    public static void clear(Context c, boolean detail) {
        File f = file(c, detail);
        if (f == null) return;
        try {
            new FileOutputStream(f, false).close();
        } catch (Throwable ignored) {
        }
    }
}
