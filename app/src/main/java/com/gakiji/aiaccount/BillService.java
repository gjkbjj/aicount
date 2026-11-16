package com.gakiji.aiaccount;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;

public final class BillService extends AccessibilityService {
    static final String CHANGED = "com.gakiji.aiaccount.LEDGER_CHANGED";
    static final String CHANNEL = "booked";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler completion = new Handler(Looper.getMainLooper());
    private long scanStart;
    private String lastFingerprint = "";
    private int lastWindow = -1;
    private long lastObserved;
    private final java.util.concurrent.ExecutorService writer =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean processing;
    private boolean destroyed;

    private PageInspector inspector;
    private boolean inspecting;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener inspectorSetting =
            (prefs, key) -> { if (PageInspector.ENABLED.equals(key)) refreshInspector(); };
    private final android.content.BroadcastReceiver inspectorRefresh = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshInspector(); }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (inspector == null) {
            inspector = new PageInspector(this);
            Cloud.prefs(this).registerOnSharedPreferenceChangeListener(inspectorSetting);
            registerReceiver(inspectorRefresh, new android.content.IntentFilter(PageInspector.REFRESH),
                    Context.RECEIVER_NOT_EXPORTED);
        }
        refreshInspector();
    }

    /** 悬浮窗被系统回收并补回后，重新对齐调试模式的服务配置。 */
    void syncOverlay() {
        if (!inspecting) refreshInspector();
    }

    private void refreshInspector() {
        if (inspector == null) return;
        boolean enabled = inspector.refresh();
        var info = getServiceInfo();
        if (info == null || (enabled == inspecting && enabled == (info.packageNames == null))) return;
        inspecting = enabled;
        int flags = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.packageNames = enabled ? null : new String[]{Bill.WECHAT, Bill.ALIPAY};
        if (enabled) info.flags |= flags;
        else info.flags &= ~flags;
        setServiceInfo(info);
    }

    private void closeInspector() {
        if (inspector == null) return;
        inspector.dispose();
        inspector = null;
        Cloud.prefs(this).unregisterOnSharedPreferenceChangeListener(inspectorSetting);
        unregisterReceiver(inspectorRefresh);
        inspecting = false;
    }

    static void changed(Context context) {
        context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));
    }

    static boolean allowed(Context context, String source) {
        var preferences = Cloud.prefs(context);
        return preferences.getBoolean("capture", false)
                && preferences.getBoolean(source, true)
                && (source.equals(Bill.WECHAT) || source.equals(Bill.ALIPAY));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (inspecting && !android.provider.Settings.canDrawOverlays(this)) refreshInspector();
        if (inspector != null) inspector.observe(event);
        if (event.getPackageName() == null) return;
        String source = event.getPackageName().toString();
        if (!allowed(this, source)) {
            onInterrupt();
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (scanStart == 0) scanStart = now;
        handler.removeCallbacksAndMessages(null);
        // A noisy window still gets a scan once per bounded coalescing interval.
        handler.postDelayed(() -> scan(source, 0), Math.max(0, Math.min(220, scanStart + 800 - now)));
    }

    static boolean hasTree(CharSequence className, boolean visible, boolean hasBounds, int children) {
        return className != null && className.length() > 0 && (visible && hasBounds || children > 0);
    }

    static boolean hasTree(AccessibilityNodeInfo root) {
        if (root == null) return false;
        var bounds = new android.graphics.Rect();
        root.getBoundsInScreen(bounds);
        return hasTree(root.getClassName(), root.isVisibleToUser(), !bounds.isEmpty(), root.getChildCount());
    }

    AccessibilityNodeInfo activePage(String source) {
        var root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && source != null
                && !source.contentEquals(root.getPackageName())) return null;
        if (hasTree(root)) return root;
        for (var window : getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive()) continue;
            var candidate = window.getRoot();
            if (hasTree(candidate) && candidate.getPackageName() != null
                    && (source == null || source.contentEquals(candidate.getPackageName()))) return candidate;
        }
        return root;
    }

    private void scan(String source, int attempt) {
        scanStart = 0;
        if (destroyed || !allowed(this, source) || getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        boolean alipay = source.equals(Bill.ALIPAY);
        AccessibilityNodeInfo root = activePage(source);
        if (!hasTree(root) || root.getPackageName() == null || !source.contentEquals(root.getPackageName())) {
            diagnostic(source, "收到页面事件，当前窗口未提供可读布局", 0);
            if (attempt < 2) handler.postDelayed(() -> scan(source, attempt + 1), 250L << attempt);
            return;
        }
        int scannedWindow = root.getWindowId();
        var fields = new ArrayList<String>();
        var positioned = new ArrayList<ScreenText>();
        int[] budget = {alipay ? 1400 : PLAIN_NODES};
        int[] depthLimit = {alipay ? 50 : PLAIN_DEPTH};
        if (!collect(root, fields, positioned, budget, depthLimit, 0, alipay)) {
            diagnostic(source, "页面扫描中止", fields.size());
            return;
        }
        Bill parsed = Bill.parse(source, fields);
        if (parsed == null && !positioned.isEmpty()) {
            positioned.sort(java.util.Comparator.comparingInt(ScreenText::top).thenComparingInt(ScreenText::left));
            parsed = Bill.parse(source, positioned.stream().map(ScreenText::text).toList());
        }
        if (parsed == null) {
            diagnostic(source, fields.isEmpty() ? "页面未提供可读文字" : "已读到文字，未匹配成功账单", fields.size());
            if (fields.isEmpty() && attempt < 2) {
                handler.postDelayed(() -> scan(source, attempt + 1), 250L << attempt);
            }
            return;
        }
        Bill bill = parsed;

        String fingerprint = bill.fingerprint();
        long observed = android.os.SystemClock.uptimeMillis();
        if (lastWindow == scannedWindow && lastFingerprint.equals(fingerprint) && observed - lastObserved < 5000) {
            lastObserved = observed;
            return;
        }
        if (processing) {
            handler.postDelayed(() -> scan(source, 0), 250);
            return;
        }
        lastWindow = scannedWindow;
        lastFingerprint = fingerprint;
        lastObserved = observed;
        boolean post = bill.shouldPost(Cloud.prefs(this).getBoolean("history", false),
                java.time.Instant.now(), java.time.ZoneId.systemDefault());
        diagnostic(source, post ? "已识别账单" : "已识别账单，将放入草稿", fields.size());
        processing = true;
        long generation = Ledger.get(this).generation();
        status(this, "解析中", 0, false);
        writer.execute(() -> {
            long id = 0;
            String result = "记账失败";
            try {
                Ledger ledger = Ledger.get(this);
                synchronized (ledger) {
                    if (ledger.generation() == generation && allowed(this, source)) {
                        id = ledger.add(bill, post);
                        result = id < 0 ? "已重复" : ledger.entry(id).state() == Ledger.POSTED
                                ? signed(bill.cents(), bill.direction()) : "待确认";
                    }
                }
            } catch (android.database.SQLException unavailable) {
                // Report a failed write without showing a completed amount.
            }
            long entry = id;
            String title = result;
            boolean posted = entry > 0 && !title.equals("待确认");
            completion.postAtTime(() -> {
                processing = false;
                if (destroyed || generation != Ledger.get(this).generation()) return;
                if (entry > 0) {
                    changed(this);
                    if (posted && !bill.refund()) Cloud.enrich(this, entry);
                }
                if (allowed(this, source)) status(this, title, entry, posted);
                else getSystemService(NotificationManager.class).cancel(CHANNEL, 1);
                // A new payment may have appeared during the previous database write.
                if (scanStart == 0) {
                    AccessibilityNodeInfo current = getRootInActiveWindow();
                    if (current != null && current.getPackageName() != null
                            && (current.getWindowId() != scannedWindow || !source.contentEquals(current.getPackageName()))) {
                        scan(current.getPackageName().toString(), 0);
                    }
                }
            }, observed + 450);
        });
    }

    @Override public void onDestroy() {
        destroyed = true;
        closeInspector();
        onInterrupt();
        getSystemService(NotificationManager.class).cancel(CHANNEL, 1);
        writer.shutdown();
        super.onDestroy();
    }

    static String signed(long cents, int direction) {
        return (direction < 0 ? "-" : "+") + java.math.BigDecimal.valueOf(cents, 2).toPlainString();
    }

    private record ScreenText(String text, int top, int left) {}

    /** 普通应用窗口的节点预算；小程序与 H5 页面在 WebView 内另用更宽的预算。 */
    static final int PLAIN_DEPTH = 28;
    private static final int PLAIN_NODES = 450;
    private static final int WEB_DEPTH = 60;
    private static final int WEB_NODES = 1200;

    static boolean isWebContainer(CharSequence className) {
        if (className == null) return false;
        String name = className.toString();
        return name.contains("WebView") || name.contains("XWeb");
    }

    private boolean collect(AccessibilityNodeInfo node, ArrayList<String> fields, ArrayList<ScreenText> positioned,
                            int[] budget, int[] depthLimit, int depth, boolean alipay) {
        if (node.isPassword()) return false;
        if (isWebContainer(node.getClassName())) {
            // 小程序交易详情页的标签行位于 WebView 内 30 层以上，普通预算会整段截断。
            depthLimit[0] = Math.max(depthLimit[0], WEB_DEPTH);
            budget[0] = Math.max(budget[0], WEB_NODES);
        }
        if (--budget[0] < 0 || depth > depthLimit[0]) return alipay;
        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence description = node.getContentDescription();
            add(fields, positioned, text, node, alipay);
            if (text == null || text.length() == 0 || (alipay && description != null && !description.toString().contentEquals(text))) {
                add(fields, positioned, description, node, alipay);
            }
        } else if (!alipay && depthLimit[0] <= PLAIN_DEPTH) return true;
        for (int index = 0; index < node.getChildCount(); index++) {
            if (alipay && budget[0] <= 0) break;
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null && !collect(child, fields, positioned, budget, depthLimit, depth + 1, alipay)) return false;
        }
        return true;
    }

    private void add(ArrayList<String> fields, ArrayList<ScreenText> positioned, CharSequence value,
                     AccessibilityNodeInfo node, boolean alipay) {
        if (value == null || value.length() == 0 || value.length() > (alipay ? 4096 : 512)) return;
        String text = value.toString();
        fields.add(text);
        var bounds = new android.graphics.Rect();
        node.getBoundsInScreen(bounds);
        positioned.add(new ScreenText(text, bounds.centerY(), bounds.left));
    }

    private void diagnostic(String source, String result, int count) {
        String status = result + "（" + count + " 个文字节点）";
        var settings = Cloud.prefs(this);
        String key = source.equals(Bill.ALIPAY) ? "alipay_diagnostic" : "wechat_diagnostic";
        if (!status.equals(settings.getString(key, ""))) {
            settings.edit().putString(key, status).apply();
        }
    }

    @Override public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        scanStart = 0;
    }

    @Override public boolean onUnbind(Intent intent) {
        closeInspector();
        onInterrupt();
        return super.onUnbind(intent);
    }

    private static void status(Context context, String title, long id, boolean canUndo) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        var manager = context.getSystemService(NotificationManager.class);
        var channel = new NotificationChannel(CHANNEL, "自动记账状态", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);

        boolean ongoing = title.equals("解析中");
        boolean promote = Cloud.prefs(context).getBoolean("live", false) && manager.canPostPromotedNotifications();
        var builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ledger).setContentTitle(title)
                .setVisibility(Notification.VISIBILITY_SECRET).setShowWhen(false)
                .setOnlyAlertOnce(true).setTimeoutAfter(ongoing ? 10000 : 5000)
                .setOngoing(ongoing || promote);
        if (promote) {
            builder.setShortCriticalText(title);
            var extras = new android.os.Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            builder.addExtras(extras);
        }
        if (id > 0) {
            var open = new Intent(context, MainActivity.class).setData(entryUri(id))
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
            builder.setContentIntent(PendingIntent.getActivity(context, 0, open, flags));
            if (canUndo) {
                var undo = new Intent(context, UndoReceiver.class).setData(entryUri(id));
                builder.addAction(new Notification.Action.Builder(null, "撤销",
                        PendingIntent.getBroadcast(context, 0, undo, flags)).build());
            }
        }
        var extras = new android.os.Bundle();
        extras.putLong("ledger_entry", id);
        builder.addExtras(extras);
        manager.notify(CHANNEL, 1, builder.build());
    }

    private static android.net.Uri entryUri(long id) {
        return android.net.Uri.parse("aiaccount://entry/" + id);
    }

    static long entryId(Intent intent) {
        if (intent.getData() == null || !"aiaccount".equals(intent.getData().getScheme())
                || !"entry".equals(intent.getData().getHost())) return 0;
        try {
            return Long.parseLong(intent.getData().getLastPathSegment());
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }
}
