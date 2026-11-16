package com.gakiji.aiaccount;

import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;
import android.widget.Toast;

final class PageInspector {
    static final String ENABLED = "page_inspector";
    static final String REFRESH = "com.gakiji.aiaccount.REFRESH_INSPECTOR";
    private static final int TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private static final int MAX_NODES = 320;
    private static final int MAX_REPORT = 48_000;
    private final BillService service;
    private android.content.Context context;
    private WindowManager windows;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private java.util.concurrent.Future<?> capture;
    private View button;
    private AlertDialog reportDialog;
    private int eventWindow = -1;
    private String eventPackage = "";
    private String eventClass = "";
    private boolean disposed;

    /**
     * 系统或厂商策略会在切到别的应用后回收悬浮窗，窗口上下文也可能随显示设置失效。
     * 定时检查并在丢失时补回，用户不必回到本应用重新触发。
     */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (disposed) return;
            refresh();
            service.syncOverlay();
            main.postDelayed(this, 3000);
        }
    };

    PageInspector(BillService service) {
        this.service = service;
        rebuild();
        main.postDelayed(watchdog, 3000);
    }

    private void rebuild() {
        var display = service.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY);
        context = service.createDisplayContext(display).createWindowContext(TYPE, null);
        windows = context.getSystemService(WindowManager.class);
    }

    boolean refresh() {
        if (disposed) return false;
        if (!Cloud.prefs(service).getBoolean(ENABLED, false) || !Settings.canDrawOverlays(service)) {
            close();
            return false;
        }
        if (button != null && button.getParent() != null) return true;
        button = null;
        if (show()) return true;
        rebuild();
        return show();
    }

    private boolean show() {
        var overlay = new ImageButton(new android.view.ContextThemeWrapper(context, R.style.AppTheme));
        overlay.setImageResource(R.drawable.ic_ledger);
        overlay.setContentDescription("捕获当前页面");
        overlay.setPadding(dp(10), dp(10), dp(10), dp(10));
        overlay.setOnClickListener(view -> inspect());
        overlay.setOnLongClickListener(view -> {
            Cloud.prefs(service).edit().putBoolean(ENABLED, false).apply();
            return true;
        });
        overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }

            @Override public void onViewDetachedFromWindow(View view) {
                if (button != view) return;
                button = null;
                main.removeCallbacks(watchdog);
                main.postDelayed(watchdog, 500);
            }
        });
        var params = new WindowManager.LayoutParams(dp(48), dp(48), TYPE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(8);
        try {
            windows.addView(overlay, params);
            button = overlay;
            return true;
        } catch (SecurityException | WindowManager.BadTokenException failure) {
            button = null;
            return false;
        }
    }

    void observe(AccessibilityEvent event) {
        if (button == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null || event.getClassName() == null
                || service.getPackageName().contentEquals(event.getPackageName())) return;
        eventPackage = event.getPackageName().toString();
        eventWindow = event.getWindowId();
        eventClass = event.getClassName().toString();
    }

    void dispose() {
        disposed = true;
        main.removeCallbacks(watchdog);
        close();
        worker.shutdownNow();
    }

    private void close() {
        if (capture != null) {
            capture.cancel(true);
            capture = null;
        }
        if (reportDialog != null) {
            reportDialog.dismiss();
            reportDialog = null;
        }
        if (button != null) {
            View view = button;
            button = null;
            if (view.getParent() != null) {
                try {
                    windows.removeViewImmediate(view);
                } catch (IllegalArgumentException alreadyGone) {
                    // 窗口已被系统移除。
                }
            }
        }
        eventWindow = -1;
        eventPackage = "";
        eventClass = "";
    }

    private void inspect() {
        if (capture != null || button == null || !Settings.canDrawOverlays(service)
                || service.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
        View origin = button;
        int observedWindow = eventWindow;
        String observedPackage = eventPackage;
        String observedClass = eventClass;
        capture = worker.submit(() -> {
            String result;
            try {
                result = captureReport(observedWindow, observedPackage, observedClass);
            } catch (InterruptedException canceled) {
                Thread.currentThread().interrupt();
                result = null;
            } catch (RuntimeException unavailable) {
                result = null;
            }
            String report = result;
            main.post(() -> {
                if (button != origin) return;
                capture = null;
                if (!Settings.canDrawOverlays(service)
                        || service.getSystemService(KeyguardManager.class).isKeyguardLocked()) return;
                if (report == null) {
                    Toast.makeText(service, "未读取到当前页面", Toast.LENGTH_SHORT).show();
                    return;
                }
                show(report);
            });
        });
    }

    private String captureReport(int observedWindow, String observedPackage, String observedClass)
            throws InterruptedException {
        AccessibilityNodeInfo root = service.activePage(null);
        String initialPackage = root != null && root.getPackageName() != null ? root.getPackageName().toString() : observedPackage;
        if (initialPackage.isEmpty() || service.getPackageName().equals(initialPackage)) return null;
        if (!BillService.hasTree(root) || root.getPackageName() == null) {
            for (int delay : new int[]{200, 400, 700}) {
                Thread.sleep(delay);
                root = service.activePage(initialPackage);
                if (BillService.hasTree(root) && root.getPackageName() != null) break;
            }
            if (!BillService.hasTree(root) || root.getPackageName() == null) {
                return "包名（最近窗口）: " + initialPackage
                        + "\n窗口事件类: " + (initialPackage.equals(observedPackage) ? observedClass : "未观察到")
                        + "\n读取结果: 重试后仍未获取到可读的无障碍布局。";
            }
        }
        String pkg = root.getPackageName().toString();
        var report = new StringBuilder("包名: ").append(pkg)
                .append("\n窗口 ID: ").append(root.getWindowId())
                .append("\nActivity / 窗口事件类: ")
                .append(root.getWindowId() == observedWindow && pkg.equals(observedPackage) ? observedClass : "未观察到")
                .append("\n根布局类: ").append(root.getClassName()).append("\n\n无障碍节点树\n");
        var window = root.getWindow();
        if (window != null && window.getTitle() != null) report.insert(report.indexOf("\n\n"), "\n窗口标题: " + safeText(window.getTitle().toString()));
        int[] count = {0};
        appendNode(root, report, count, 0);
        if (count[0] >= MAX_NODES || report.length() >= MAX_REPORT) report.append("\n[报告已截断]");
        return report.toString();
    }

    private void appendNode(AccessibilityNodeInfo node, StringBuilder report, int[] count, int depth) {
        if (Thread.currentThread().isInterrupted() || count[0] >= MAX_NODES || report.length() >= MAX_REPORT || depth > 40) return;
        count[0]++;
        var bounds = new Rect();
        node.getBoundsInScreen(bounds);
        report.append("  ".repeat(depth)).append(node.getClassName()).append(' ').append(bounds.toShortString());
        if (node.getViewIdResourceName() != null) report.append(" id=").append(node.getViewIdResourceName());
        if (node.isPassword() || node.isEditable() || node.isAccessibilityDataSensitive()) {
            report.append(" [敏感节点已跳过]\n");
            return;
        }
        if (!node.isVisibleToUser()) report.append(" [不可见]");
        if (node.isClickable()) report.append(" [可点击]");
        if (node.isScrollable()) report.append(" [可滚动]");
        if (node.isVisibleToUser()) {
            if (node.getText() != null) report.append(" text=").append(safeText(node.getText().toString()));
            if (node.getContentDescription() != null) report.append(" desc=").append(safeText(node.getContentDescription().toString()));
        }
        report.append('\n');
        for (int i = 0; i < node.getChildCount() && count[0] < MAX_NODES && report.length() < MAX_REPORT
                && !Thread.currentThread().isInterrupted(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) appendNode(child, report, count, depth + 1);
        }
    }

    static String safeText(String value) {
        String redacted = value.replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+[.][A-Z]{2,}|https?://\\S+"
                + "|[\\d０-９](?:[ \\u00a0*＊-]*[\\d０-９]){7,}", "…").replace("\r", "").replace("\n", "\\n");
        return Bill.shorten(redacted, 240);
    }

    private void show(String report) {
        var themed = new android.view.ContextThemeWrapper(context, R.style.AppTheme);
        var dialog = new AlertDialog.Builder(themed).setTitle("已捕获")
                .setNegativeButton("取消", null)
                .setPositiveButton("复制", (d, which) -> copy(report)).create();
        dialog.setCanceledOnTouchOutside(true);
        var window = dialog.getWindow();
        window.setType(TYPE);
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(ignored -> {
            reportDialog = null;
            if (button != null) button.setVisibility(View.VISIBLE);
        });
        try {
            dialog.show();
            reportDialog = dialog;
            if (button != null) button.setVisibility(View.GONE);
        } catch (SecurityException | WindowManager.BadTokenException failure) {
            Toast.makeText(service, "系统未允许显示调试报告", Toast.LENGTH_SHORT).show();
        }
    }

    private void copy(String report) {
        var clip = ClipData.newPlainText("页面调试报告", report);
        var extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        clip.getDescription().setExtras(extras);
        service.getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        Toast.makeText(service, "已复制调试报告", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
