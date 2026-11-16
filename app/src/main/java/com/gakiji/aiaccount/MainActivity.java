package com.gakiji.aiaccount;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.app.AlertDialog.Builder;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.InputFilter;
import android.text.InputFilter.LengthFilter;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.WindowInsets.Type;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout.LayoutParams;
import com.gakiji.aiaccount.Ledger.Account;
import com.gakiji.aiaccount.Ledger.Entry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int INK = 0xff20253f;
    private static final int MUTED = 0xff596385;
    private static final int ACCENT = 0xff4656a7;
    private static final int NAVY = 0xff24294d;
    private static final int POSITIVE = 0xff087354;
    private static final int SURFACE = 0xfff7f8fe;
    private LinearLayout root;
    private LinearLayout body;
    private Ledger store;
    private LanMcpServer mcp;
    private String mcpLink = "";
    private final Handler mcpTimer = new Handler(Looper.getMainLooper());
    private static final long MCP_DURATION = 15 * 60 * 1000L;
    private int page;
    private final android.window.OnBackInvokedCallback backHome = () -> navigate(page == 7 ? 2 : 0);
    private long selectedAccount;
    private boolean selectingDrafts;
    private final java.util.Set<Long> selectedDrafts = new java.util.LinkedHashSet<>();
    private boolean backRegistered;
    private LedgerStatistics.Period statsPeriod = LedgerStatistics.Period.MONTH;
    private LedgerStatistics.Group statsGroup = LedgerStatistics.Group.CATEGORY;
    private java.time.LocalDate statsDate = java.time.LocalDate.now();
    private int historyDirection;
    private String historySearch = "";
    private LinearLayout historyRows;
    private boolean serviceEnabled;
    private static final String UPDATE_STORE = "updates";
    private boolean updateChecking;
    private Updates.Release updateRelease;
    private String updateMessage = "";
    private AlertDialog updateDialog;
    private final AccessibilityManager.AccessibilityServicesStateChangeListener accessibilityChanged =
            manager -> {
                boolean enabled = billServiceEnabled();
                if (enabled == serviceEnabled) return;
                serviceEnabled = enabled;
                if (page == 3) render();
            };
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (page == 6) updateHistory();
            else if (page != 3 && page != 5) render();
        }
    };

    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = Ledger.get(this);
        serviceEnabled = billServiceEnabled();
        if (state != null) {
            page = state.getInt("page");
            selectedAccount = state.getLong("selectedAccount");
        }

        root = column(0);
        root.setBackgroundColor(SURFACE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets edges = insets.getInsets(Type.systemBars() | Type.displayCutout());
            view.setPadding(edges.left, edges.top, edges.right, Math.max(edges.bottom, insets.getInsets(Type.ime()).bottom));
            return insets;
        });
        setContentView(root);
        getSystemService(AccessibilityManager.class).addAccessibilityServicesStateChangeListener(
                getMainExecutor(), accessibilityChanged);
        registerReceiver(changes, new IntentFilter(BillService.CHANGED), Context.RECEIVER_NOT_EXPORTED);
        restoreUpdate();
        render();
        openEntry(getIntent());
    }

    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
        state.putLong("selectedAccount", selectedAccount);
    }

    protected void onResume() {
        super.onResume();
        serviceEnabled = billServiceEnabled();
        sendBroadcast(new Intent(PageInspector.REFRESH).setPackage(getPackageName()));
        if (root != null && page != 5) render();
        autoBackup();
        checkUpdates(false);
        if (updateRelease != null) showUpdatePrompt();
    }

    protected void onDestroy() {
        stopMcp();
        if (backRegistered) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backHome);
        getSystemService(AccessibilityManager.class).removeAccessibilityServicesStateChangeListener(accessibilityChanged);
        unregisterReceiver(changes);
        super.onDestroy();
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openEntry(intent);
    }

    private void openEntry(Intent intent) {
        long id = BillService.entryId(intent);
        Entry entry = id > 0L ? store.entry(id) : null;
        if (entry != null && entry.state() != Ledger.REMOVED) {
            edit(entry);
        }
    }

    private void render() {
        if ((page != 0) != backRegistered) {
            backRegistered = page != 0;
            if (backRegistered) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backHome);
            else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backHome);
        }
        root.removeAllViews();
        historyRows = null;
        Account details = page == 7 ? store.accounts().stream().filter(a -> a.id() == selectedAccount)
                .findFirst().orElse(null) : null;
        String title = switch (page) {
            case 1 -> "待确认";
            case 2 -> "账户";
            case 3 -> "设置";
            case 4 -> "统计";
            case 5 -> "记一笔";
            case 6 -> "流水";
            case 7 -> details == null ? "账户流水" : details.name();
            default -> "AI 记账";
        };
        LinearLayout header = horizontal();
        header.setPadding(dp(20), dp(12), dp(20), dp(8));
        if (page == 1 || page == 5 || page == 6) header.addView(iconAction(R.drawable.ui_back, "返回首页", () -> navigate(0)));
        if (page == 7) header.addView(iconAction(R.drawable.ui_back, "返回账户", () -> navigate(2)));
        TextView heading = text(title, 26, INK, true);
        heading.setAccessibilityHeading(true);
        header.addView(heading, new LayoutParams(0, -2, 1));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        body = column(0);
        body.setPadding(dp(20), dp(4), dp(20), dp(18));
        scroll.addView(body);
        root.addView(scroll, new LayoutParams(-1, 0, 1));
        switch (page) {
            case 1 -> drafts();
            case 2 -> accounts();
            case 3 -> settings();
            case 4 -> statistics();
            case 5 -> manualEntry();
            case 6 -> history();
            case 7 -> accountDetails(details);
            default -> dashboard();
        }
        if (page != 5) addNavigation();
    }

    private void navigate(int destination) {
        if (destination != 1) {
            selectingDrafts = false;
            selectedDrafts.clear();
        }
        page = destination;
        render();
    }

    private void addNavigation() {
        LinearLayout bar = horizontal();
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        bar.setBackground(shape(NAVY, 30));
        String[] labels = {"首页", "统计", "记一笔", "账户", "设置"};
        int[] destinations = {0, 4, 5, 2, 3};
        int[] icons = {R.drawable.ui_home, R.drawable.ui_investment, R.drawable.ui_plus, R.drawable.ui_wallet, R.drawable.ui_settings};
        for (int i = 0; i < labels.length; i++) {
            int destination = destinations[i];
            boolean current = page == destination || ((page == 1 || page == 6) && destination == 0) || (page == 7 && destination == 2);
            LinearLayout item = column(0);
            item.setGravity(android.view.Gravity.CENTER);
            var glyph = icon(icons[i], destination == 5 ? NAVY : current ? Color.WHITE : 0xffc7cae2,
                    destination == 5 ? 22 : 20);
            if (destination == 5) {
                var button = new android.widget.FrameLayout(this);
                button.setBackground(shape(0xffdde0ff, 22));
                button.addView(glyph, new android.widget.FrameLayout.LayoutParams(dp(22), dp(22), android.view.Gravity.CENTER));
                item.addView(button, new LayoutParams(dp(40), dp(40)));
            } else {
                item.addView(glyph);
                var label = text(labels[i], 11, current ? Color.WHITE : 0xffc7cae2, current);
                label.setPadding(0, dp(3), 0, 0);
                label.setGravity(android.view.Gravity.CENTER);
                item.addView(label);
            }
            item.setContentDescription(labels[i]);
            item.setSelected(current);
            item.setFocusable(true);
            item.setOnClickListener(view -> navigate(destination));
            bar.addView(item, new LayoutParams(0, dp(52), 1));
        }
        var params = new LayoutParams(-1, -2);
        params.setMargins(dp(14), dp(3), dp(14), dp(8));
        root.addView(bar, params);
    }

    private void dashboard() {
        var month = java.time.LocalDate.now();
        var overview = LedgerStatistics.chart(store, LedgerStatistics.Period.MONTH, month);
        var totals = overview.totals();
        long income = totals.income;
        long expense = totals.expense - totals.refund;
        var summary = column(20);
        var gradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff202447, 0xff363c77, 0xff5463bf});
        gradient.setCornerRadius(dp(25));
        summary.setBackground(gradient);
        var heroParams = new LayoutParams(-1, -2);
        heroParams.bottomMargin = dp(12);
        body.addView(summary, heroParams);

        LinearLayout top = horizontal();
        top.addView(text("本月净收支", 14, 0xffe2e5ff, true), new LayoutParams(0, -2, 1));
        top.addView(text(month.getMonthValue() + "月", 13, 0xffd7dcff, false));
        summary.addView(top);
        TextView balance = text("¥" + money(income - expense), 33, Color.WHITE, true);
        balance.setFontFeatureSettings("tnum");
        balance.setPadding(0, dp(6), 0, dp(14));
        summary.addView(balance);
        LinearLayout split = horizontal();
        split.addView(summaryValue("收入", income, R.drawable.ui_down), new LayoutParams(0, -2, 1));
        split.addView(summaryValue("净支出", expense, R.drawable.ui_up), new LayoutParams(0, -2, 1));
        summary.addView(split);

        var daily = overview.selectedDay();
        var today = panel();
        var todayRow = horizontal();
        String date = month.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd E", java.util.Locale.CHINA));
        todayRow.addView(text("今天 " + date, 14, INK, true), new LayoutParams(0, -2, 1));
        todayRow.addView(text("收 " + money(daily.income), 13, POSITIVE, true));
        var spent = text("支 " + money(daily.expense - daily.refund), 13, 0xffad4237, true);
        spent.setPadding(dp(9), dp(4), 0, dp(6));
        todayRow.addView(spent);
        today.addView(todayRow);
        body.addView(today);

        var recent = panel();
        var latest = store.entries(Ledger.POSTED, "", 0, 4);
        var names = new java.util.HashMap<Long, String>();
        if (!latest.isEmpty()) store.accounts().forEach(account -> names.put(account.id(), account.name()));
        if (latest.isEmpty()) recent.addView(text("暂无流水", 14, MUTED, false));
        for (Entry entry : latest) {
            recent.addView(transactionRow(entry, month, names.get(entry.account())));
        }
        var more = action("更多明细  ›", () -> navigate(6));
        more.setGravity(android.view.Gravity.CENTER);
        recent.addView(more);
        body.addView(recent);

        int pendingCount = store.draftCount();
        if (pendingCount > 0) {
            LinearLayout pending = horizontal();
            pending.setPadding(dp(14), dp(10), dp(14), dp(14));
            pending.setBackground(shape(0xffeceeff, 18));
            pending.addView(icon(R.drawable.ui_pending, ACCENT, 20));
            var title = text("待确认账单", 15, INK, true);
            title.setPadding(dp(12), 0, 0, 0);
            pending.addView(title, new LayoutParams(0, -2, 1));
            pending.addView(text(pendingCount + " 笔", 13, ACCENT, true));
            pending.addView(icon(R.drawable.ui_chevron, ACCENT, 20));
            pending.setOnClickListener(view -> navigate(1));
            pending.setFocusable(true);
            var spacing = new LayoutParams(-1, -2);
            spacing.bottomMargin = dp(12);
            body.addView(pending, spacing);
        }
    }

    private LinearLayout summaryValue(String title, long cents, int glyph) {
        var group = column(0);
        LinearLayout label = horizontal();
        label.addView(icon(glyph, 0xffdce2ff, 15));
        var description = text(title, 13, 0xffe0e4ff, false);
        description.setPadding(dp(4), 0, 0, 0);
        label.addView(description);
        group.addView(label);
        group.addView(text("¥" + money(cents), 17, Color.WHITE, true));
        return group;
    }

    private void statistics() {
        LinearLayout selection = horizontal();
        selection.setPadding(0, dp(9), 0, dp(9));
        selection.addView(iconAction(R.drawable.ui_back, "上一时段", () -> {
            statsDate = statsPeriod.shift(statsPeriod.start(statsDate), -1);
            render();
        }));
        String date = statsPeriod == LedgerStatistics.Period.MONTH
                ? java.time.YearMonth.from(statsDate).toString() : statsDate.getYear() + "年";
        var caption = button(date + " ▾", false, this::chooseStatsPeriod);
        caption.setContentDescription("选择统计日期，当前 " + date);
        styleFilter(caption, false);
        addKey(selection, caption);
        selection.addView(iconAction(R.drawable.ui_chevron, "下一时段", () -> {
            statsDate = statsPeriod.shift(statsPeriod.start(statsDate), 1);
            render();
        }));
        body.addView(selection);

        LinearLayout groups = horizontal();
        for (var group : LedgerStatistics.Group.values()) {
            var option = button(group.label, group == statsGroup, () -> {
                statsGroup = group;
                render();
            });
            styleFilter(option, group == statsGroup);
            option.setContentDescription("按" + group.label + "统计");
            addKey(groups, option);
        }
        body.addView(groups);

        var data = LedgerStatistics.chart(store, statsPeriod, statsDate, statsGroup);
        var totals = data.totals();
        body.addView(statCard("支出", totals.expense, totals.payments, data.expenses(), data.expenseGroups(), 0xffbd5140, data.days()));
        body.addView(statCard("收入", totals.income, totals.receipts, data.incomes(), data.incomeGroups(), POSITIVE, data.days()));
        var summary = panel();
        summary.addView(text("收支汇总", 16, INK, true));
        summary.addView(statLine("退款", "¥" + money(totals.refund) + " · " + totals.refunds + " 笔"));
        summary.addView(statLine("净支出", "¥" + money(totals.expense - totals.refund)));
        summary.addView(statLine("净收支", "¥" + money(totals.income - totals.expense + totals.refund)));
        body.addView(summary);
    }

    private void chooseStatsPeriod() {
        var dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        var sheet = column(16);
        sheet.setBackground(shape(SURFACE, 24));
        dialog.setContentView(sheet);
        var window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(android.view.Gravity.BOTTOM);

        var controls = horizontal();
        sheet.addView(controls);
        var choices = column(0);
        sheet.addView(choices);
        int[] year = {statsDate.getYear()};
        var mode = new LedgerStatistics.Period[]{statsPeriod};
        java.util.function.BiConsumer<LedgerStatistics.Period, java.time.LocalDate> select = (period, date) -> {
            statsPeriod = period;
            statsDate = period.start(date);
            dialog.dismiss();
            render();
        };
        var yearRow = horizontal();
        var yearLabel = text("", 16, INK, true);
        yearLabel.setGravity(android.view.Gravity.CENTER);
        var monthOption = button("月账单", false, () -> {});
        var yearOption = button("年账单", false, () -> {});
        addKey(controls, monthOption);
        addKey(controls, yearOption);

        Runnable update = () -> {
            boolean monthly = mode[0] == LedgerStatistics.Period.MONTH;
            styleFilter(monthOption, monthly);
            styleFilter(yearOption, !monthly);
            yearLabel.setText(year[0] + "年");
            choices.removeAllViews();
            if (monthly) {
                for (int start = 1; start <= 12; start += 6) {
                    var row = horizontal();
                    for (int i = start; i < start + 6; i++) {
                        int month = i;
                        var option = button(month + "月", false, () -> select.accept(mode[0],
                                java.time.LocalDate.of(year[0], month, 1)));
                        option.setPadding(0, 0, 0, 0);
                        styleFilter(option, statsPeriod == mode[0] && year[0] == statsDate.getYear()
                                && month == statsDate.getMonthValue());
                        addKey(row, option);
                    }
                    choices.addView(row);
                }
            } else {
                var option = button("查看 " + year[0] + " 年", true, () -> select.accept(mode[0],
                        java.time.LocalDate.of(year[0], 1, 1)));
                choices.addView(option, keySize(48));
            }
            var current = button(monthly ? "本月" : "今年", false,
                    () -> select.accept(mode[0], java.time.LocalDate.now()));
            styleFilter(current, false);
            choices.addView(current, keySize(48));
        };

        monthOption.setOnClickListener(view -> { mode[0] = LedgerStatistics.Period.MONTH; update.run(); });
        yearOption.setOnClickListener(view -> { mode[0] = LedgerStatistics.Period.YEAR; update.run(); });
        yearRow.addView(iconAction(R.drawable.ui_back, "上一年", () -> { year[0]--; update.run(); }));
        yearRow.addView(yearLabel, new LayoutParams(0, -2, 1));
        yearRow.addView(iconAction(R.drawable.ui_chevron, "下一年", () -> { year[0]++; update.run(); }));
        sheet.addView(yearRow, 1);
        update.run();
        dialog.show();
        window.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout statCard(String title, long cents, long count, long[] values,
                                  java.util.Map<String, Long> groups, int color, long days) {
        var card = panel();
        card.addView(text(title + "构成", 17, INK, true));
        var ranked = new ArrayList<>(groups.entrySet());
        ranked.sort(java.util.Map.Entry.<String, Long>comparingByValue().reversed());
        int[] colors = {color, 0xff405bb2, 0xff936113, 0xff15748b, 0xff7a4989};
        long[] slices = new long[Math.min(colors.length, ranked.size())];
        var breakdown = horizontal();
        var ring = new android.widget.FrameLayout(this);
        ring.addView(new PeriodChart.Ring(this, slices, colors), new android.widget.FrameLayout.LayoutParams(-1, -1));
        var center = column(0);
        center.setGravity(android.view.Gravity.CENTER);
        var total = text("总" + title, 13, MUTED, false);
        total.setPadding(0, 0, 0, 0);
        total.setGravity(android.view.Gravity.CENTER);
        var amount = text("¥" + money(cents), 14, INK, true);
        amount.setPadding(0, 0, 0, 0);
        amount.setGravity(android.view.Gravity.CENTER);
        amount.setMaxLines(1);
        amount.setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        center.addView(total);
        center.addView(amount, new LayoutParams(dp(99), -2));
        ring.addView(center, new android.widget.FrameLayout.LayoutParams(-1, -1));
        breakdown.addView(ring, new LayoutParams(dp(125), dp(125)));
        var legend = column(0);
        legend.setPadding(dp(10), 0, 0, 0);
        if (ranked.isEmpty()) legend.addView(text("该时段暂无" + title, 14, MUTED, false));
        for (int i = 0; i < slices.length; i++) {
            var group = ranked.get(i);
            boolean combined = i == slices.length - 1 && ranked.size() > slices.length;
            slices[i] = combined ? ranked.subList(i, ranked.size()).stream().mapToLong(java.util.Map.Entry::getValue).sum() : group.getValue();
            String name = combined ? "其余" + statsGroup.label : group.getKey();
            var line = horizontal();
            line.addView(text("●", 10, colors[i], false));
            var heading = text(name, 13, INK, true);
            heading.setMaxLines(2);
            heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
            heading.setPadding(dp(4), 0, dp(4), 0);
            line.addView(heading, new LayoutParams(0, -2, 1));
            line.addView(text(money(slices[i]), 13, INK, true));
            legend.addView(line);
        }
        breakdown.addView(legend, new LayoutParams(0, -2, 1));
        card.addView(breakdown);
        String unit = statsPeriod == LedgerStatistics.Period.MONTH ? "逐日" : "逐月";
        card.addView(text(unit + title, 15, INK, true));
        card.addView(new PeriodChart(this, statsPeriod, values, color), new LayoutParams(-1, dp(142)));
        card.addView(statLine("日均" + title, "¥" + money(Math.round(cents / (double) days)) + "  ·  共 " + count + " 笔"));
        return card;
    }

    private LinearLayout statLine(String label, String value) {
        LinearLayout line = horizontal();
        line.addView(text(label, 14, INK, true), new LayoutParams(0, -2, 1));
        line.addView(text(value, 13, MUTED, false));
        return line;
    }

    private void manualEntry() {
        var form = panel();
        form.setFocusableInTouchMode(true);
        LinearLayout types = horizontal();
        Button expense = button("支出", true, () -> {});
        Button income = button("收入", false, () -> {});
        types.addView(expense, new LayoutParams(0, dp(48), 1));
        var second = new LayoutParams(0, dp(48), 1);
        second.leftMargin = dp(6);
        types.addView(income, second);
        form.addView(types);

        int[] direction = {-1};
        String[] category = {Categories.fallback(-1)};
        StringBuilder amount = new StringBuilder();
        LinearLayout heading = horizontal();
        TextView chosen = text("", 17, INK, true);
        chosen.setCompoundDrawablePadding(dp(7));
        chosen.setCompoundDrawableTintList(ColorStateList.valueOf(ACCENT));
        TextView amountLabel = text("0.00", 30, INK, true);
        amountLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        heading.addView(chosen, new LayoutParams(0, -2, 1));
        heading.addView(amountLabel);
        form.addView(heading);
        LinearLayout grid = column(0);
        form.addView(grid);
        java.util.function.IntConsumer chooseDirection = value -> {
            if (direction[0] != value) category[0] = Categories.fallback(value);
            direction[0] = value;
            styleFilter(expense, value < 0);
            styleFilter(income, value > 0);
            showChosenCategory(chosen, category[0], value);
            amountLabel.setTextColor(value < 0 ? 0xffac4737 : POSITIVE);
            grid.removeAllViews();
            var chips = new java.util.LinkedHashMap<String, Button>();
            LinearLayout row = null;
            for (var option : Categories.forDirection(value).entrySet()) {
                if (chips.size() % 4 == 0) {
                    row = horizontal();
                    grid.addView(row);
                }
                String id = option.getKey();
                Button chip = button(option.getValue(), false, () -> {
                    category[0] = id;
                    showChosenCategory(chosen, id, value);
                    chips.forEach((key, item) -> styleCategory(item, key.equals(id)));
                });
                chip.setTextSize(12);
                chip.setPadding(dp(2), dp(5), dp(2), dp(4));
                chip.setCompoundDrawables(null, categoryDrawable(id), null, null);
                styleCategory(chip, id.equals(category[0]));
                chips.put(id, chip);
                var cell = keySize(62);
                cell.width = 0;
                cell.weight = 1;
                row.addView(chip, cell);
            }
            while (row.getChildCount() < 4) row.addView(new View(this), new LayoutParams(0, 0, 1));
        };
        expense.setOnClickListener(view -> chooseDirection.accept(-1));
        income.setOnClickListener(view -> chooseDirection.accept(1));
        chooseDirection.accept(-1);

        EditText merchant = input(form, "商家或说明", "", InputType.TYPE_CLASS_TEXT, 80);
        EditText payment = input(form, "支付方式（可选）", "", InputType.TYPE_CLASS_TEXT, 80);
        var accounts = store.accounts();
        var accountNames = new ArrayList<String>();
        accountNames.add("未分配账户");
        accounts.forEach(account -> accountNames.add(account.name()));
        Spinner account = spinner(form, "账户", accountNames);

        java.time.LocalDateTime[] selected = {java.time.LocalDateTime.now()};
        var dateFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LinearLayout dateRow = horizontal();
        var date = button(selected[0].toLocalDate().toString(), false, () -> {});
        var time = button(selected[0].format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")), false, () -> {});
        dateRow.addView(date, new LayoutParams(0, dp(48), 1));
        dateRow.addView(time, new LayoutParams(0, dp(48), 1));
        form.addView(dateRow);
        date.setOnClickListener(view -> new android.app.DatePickerDialog(this, (picker, year, month, day) -> {
            selected[0] = java.time.LocalDateTime.of(java.time.LocalDate.of(year, month + 1, day), selected[0].toLocalTime());
            date.setText(selected[0].toLocalDate().toString());
        }, selected[0].getYear(), selected[0].getMonthValue() - 1, selected[0].getDayOfMonth()).show());
        time.setOnClickListener(view -> new android.app.TimePickerDialog(this, (picker, hour, minute) -> {
            selected[0] = selected[0].withHour(hour).withMinute(minute);
            time.setText(selected[0].format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        }, selected[0].getHour(), selected[0].getMinute(), true).show());

        CheckBox remember = new CheckBox(this);
        addCategoryRule(form, remember);
        java.util.function.Consumer<Boolean> save = again -> {
            long cents = Bill.amountCents(amount.toString());
            if (cents <= 0) {
                toast("请输入大于零的金额");
                return;
            }
            int index = account.getSelectedItemPosition();
            long accountId = index <= 0 ? 0 : accounts.get(index - 1).id();
            String method = value(payment).replaceAll("\\d{5,}", "••••");
            Bill bill = new Bill("", "", cents, direction[0], value(merchant), method, "", selected[0].format(dateFormat), false);
            long id = store.addManual(bill, category[0], accountId, remember.isChecked());
            if (id < 0) {
                toast("账单保存失败");
                return;
            }
            getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(form.getWindowToken(), 0);
            Cloud.enrich(this, id);
            if (again) {
                amount.setLength(0);
                amountLabel.setText("0.00");
                merchant.setText("");
                remember.setChecked(false);
                form.requestFocus();
            } else navigate(0);
            toast("已保存");
        };
        body.addView(form);
        form.requestFocus();

        var keyboard = horizontal();
        keyboard.setPadding(dp(4), dp(4), dp(4), dp(4));
        keyboard.setBackground(shape(0xffedf0fb, 18));
        var digits = column(0);
        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"清空", "0", "."}};
        for (String[] keys : rows) {
            LinearLayout row = horizontal();
            for (String key : keys) {
                Button item = button(key, false, () -> {
                    if (key.equals("清空")) amount.setLength(0);
                    else {
                        String next = (amount.toString() + key).replaceFirst("^0+(?=[0-9])", "");
                        if (next.startsWith(".")) next = "0" + next;
                        if (next.matches("[0-9]{1,10}(\\.[0-9]{0,2})?")) {
                            amount.replace(0, amount.length(), next);
                        }
                    }
                    amountLabel.setText(amount.isEmpty() ? "0.00" : amount.toString());
                });
                styleFilter(item, false);
                item.setTextSize(key.length() == 1 ? 21 : 14);
                addKey(row, item);
            }
            digits.addView(row);
        }
        keyboard.addView(digits, new LayoutParams(0, -2, 3));
        var controls = column(0);
        Button backspace = button("⌫", false, () -> {
            if (!amount.isEmpty()) amount.setLength(amount.length() - 1);
            amountLabel.setText(amount.isEmpty() ? "0.00" : amount.toString());
        });
        backspace.setContentDescription("删除一位");
        styleFilter(backspace, false);
        controls.addView(backspace, keySize(48));
        Button again = button("再记", false, () -> save.accept(true));
        styleFilter(again, true);
        controls.addView(again, keySize(48));
        Button done = button("保存", true, () -> save.accept(false));
        done.setBackgroundTintList(null);
        done.setBackground(shape(ACCENT, 18));
        ripple(done);
        controls.addView(done, keySize(100));
        keyboard.addView(controls, new LayoutParams(0, -2, 1));
        root.addView(keyboard);
    }

    private android.graphics.drawable.Drawable categoryDrawable(String id) {
        var glyph = getDrawable(LedgerIcons.category(id));
        glyph.setBounds(0, 0, dp(21), dp(21));
        return glyph;
    }

    private void showChosenCategory(TextView label, String id, int direction) {
        label.setText(Categories.label(id, direction));
        label.setCompoundDrawablesRelative(categoryDrawable(id), null, null, null);
    }

    private void styleCategory(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? ACCENT : INK);
        button.setCompoundDrawableTintList(ColorStateList.valueOf(selected ? ACCENT : MUTED));
        button.setBackgroundTintList(null);
        button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x183e4e9e),
                shape(selected ? 0xffdfe4ff : 0xfff5f6fc, 14), null));
    }

    private void addCategoryRule(LinearLayout form, CheckBox remember) {
        remember.setText("下次自动沿用此分类");
        form.addView(remember);
        var hint = text("仅对之后识别到的同名商家、同为支出或收入的账单生效。", 12, MUTED, false);
        hint.setVisibility(View.GONE);
        form.addView(hint);
        remember.setOnCheckedChangeListener((view, checked) -> hint.setVisibility(checked ? View.VISIBLE : View.GONE));
    }

    private LayoutParams keySize(int height) {
        var params = new LayoutParams(-1, dp(height));
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private void addKey(LinearLayout row, Button key) {
        var params = keySize(48);
        params.width = 0;
        params.weight = 1;
        row.addView(key, params);
    }

    private void drafts() {
        var rows = store.entries(Ledger.DRAFT, "", 0, 100);
        var visible = rows.stream().map(Entry::id).toList();
        selectedDrafts.retainAll(visible);
        if (rows.isEmpty()) selectingDrafts = false;
        if (!selectingDrafts) {
            if (!rows.isEmpty()) body.addView(button("多选", false, () -> {
                selectingDrafts = true;
                render();
            }));
            transactionRows(body, rows, "暂无待确认账单", true);
            return;
        }

        var controls = horizontal();
        var all = new CheckBox(this);
        all.setText("全选本页");
        var submit = button("", true, this::chooseDraftAccount);
        var checks = new ArrayList<CheckBox>();
        Runnable update = () -> {
            all.setChecked(selectedDrafts.size() == rows.size());
            submit.setText("统一入账（" + selectedDrafts.size() + "）");
            submit.setEnabled(!selectedDrafts.isEmpty());
        };
        controls.addView(all, new LayoutParams(0, -2, 1));
        controls.addView(action("取消多选", () -> {
            selectingDrafts = false;
            selectedDrafts.clear();
            render();
        }));
        body.addView(controls);
        body.addView(submit, new LayoutParams(-1, dp(48)));
        for (Entry entry : rows) {
            var row = horizontal();
            var check = new CheckBox(this);
            check.setMinWidth(dp(48));
            String name = entry.merchant().isBlank() ? Bill.label(entry.source()) : entry.merchant();
            check.setContentDescription("选择 " + name + " " + BillService.signed(entry.cents(), entry.direction()));
            check.setChecked(selectedDrafts.contains(entry.id()));
            check.setOnClickListener(view -> {
                if (check.isChecked()) selectedDrafts.add(entry.id()); else selectedDrafts.remove(entry.id());
                update.run();
            });
            checks.add(check);
            row.addView(check);
            row.addView(transactionRow(entry), new LayoutParams(0, -2, 1));
            body.addView(row);
        }
        all.setOnClickListener(view -> {
            if (all.isChecked()) selectedDrafts.addAll(visible); else selectedDrafts.clear();
            checks.forEach(check -> check.setChecked(all.isChecked()));
            update.run();
        });
        update.run();
    }

    private void chooseDraftAccount() {
        var accounts = store.accounts();
        if (accounts.isEmpty()) {
            toast("请先在账户页创建一个账户");
            return;
        }
        var ids = List.copyOf(selectedDrafts);
        String[] names = accounts.stream().map(Account::name).toArray(String[]::new);
        var dialog = new Builder(this).setTitle("选择 " + ids.size() + " 笔账单的入账账户")
                .setSingleChoiceItems(names, -1, (picker, index) ->
                        ((AlertDialog) picker).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true))
                .setNegativeButton("取消", null).setPositiveButton("确认入账", (picker, which) -> {
                    int index = ((AlertDialog) picker).getListView().getCheckedItemPosition();
                    if (index < 0 || !store.postDrafts(ids, accounts.get(index).id())) {
                        toast("未入账，请确认账单仍待确认且退款已有原单");
                        render();
                        return;
                    }
                    for (long id : ids) {
                        var entry = store.entry(id);
                        if (entry != null && !entry.refund()) Cloud.enrich(this, id);
                    }
                    selectedDrafts.clear();
                    selectingDrafts = false;
                    render();
                    toast("已入账 " + ids.size() + " 笔");
                }).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void history() {
        var search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint("搜索商家或支付方式");
        search.setText(historySearch);
        search.setPadding(dp(15), 0, dp(12), 0);
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ui_search, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(MUTED));
        search.setCompoundDrawablePadding(dp(10));
        search.setBackground(shape(0xffeff1fa, 16));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setFilters(new InputFilter[]{new LengthFilter(100)});
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        body.addView(search, new LayoutParams(-1, dp(50)));

        LinearLayout filters = horizontal();
        filters.setPadding(0, dp(8), 0, dp(8));
        String[] labels = {"全部", "收入", "支出"};
        int[] directions = {0, 1, -1};
        Button[] chips = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            int direction = directions[i];
            Button chip = button(labels[i], direction == historyDirection, () -> {});
            chips[i] = chip;
            chip.setSelected(direction == historyDirection);
            chip.setOnClickListener(view -> {
                historyDirection = direction;
                for (int j = 0; j < chips.length; j++) styleFilter(chips[j], directions[j] == direction);
                updateHistory();
            });
            styleFilter(chip, direction == historyDirection);
            var params = new LayoutParams(-2, dp(48));
            params.rightMargin = dp(6);
            filters.addView(chip, params);
        }
        body.addView(filters);
        historyRows = column(0);
        body.addView(historyRows);
        updateHistory();
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                historySearch = s.toString();
                updateHistory();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void styleFilter(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? ACCENT : INK);
        button.setBackgroundTintList(null);
        var pill = new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x183e4e9e),
                shape(selected ? 0xffdfe4ff : 0xffeff1fa, 22), null);
        button.setBackground(pill);
    }

    private void updateHistory() {
        if (historyRows == null) return;
        historyRows.removeAllViews();
        transactionRows(historyRows, store.entries(Ledger.POSTED, historySearch.strip(), historyDirection, 100),
                historySearch.isBlank() ? "暂无符合条件的流水" : "没有找到匹配的流水", true);
    }

    private void transactionRows(LinearLayout target, List<Entry> rows, String empty, boolean groupByDay) {
        if (rows.isEmpty()) {
            var card = panel();
            card.addView(text(empty, 15, MUTED, false));
            target.addView(card);
            return;
        }
        var zone = java.time.ZoneId.systemDefault();
        var today = java.time.LocalDate.now(zone);
        var dateFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日");
        var groups = new java.util.TreeMap<java.time.LocalDate, List<Entry>>(java.util.Comparator.reverseOrder());
        for (Entry entry : rows) {
            var day = LedgerStatistics.ledgerDate(entry.tradeTime(), entry.created(), zone);
            groups.computeIfAbsent(day, key -> new ArrayList<>()).add(entry);
        }
        for (var group : groups.entrySet()) {
            if (groupByDay) {
                var date = group.getKey();
                var label = text(date.equals(today) ? "今天" : date.equals(today.minusDays(1)) ? "昨天" : date.format(dateFormat),
                        13, ACCENT, true);
                label.setAccessibilityHeading(true);
                target.addView(label);
            }
            for (Entry entry : group.getValue()) target.addView(transactionRow(entry));
        }
    }

    private View transactionRow(Entry entry) {
        return transactionRow(entry, null, null);
    }

    private View transactionRow(Entry entry, java.time.LocalDate today, String account) {
        LinearLayout card = horizontal();
        card.setPadding(dp(12), dp(8), dp(12), dp(8));
        card.setMinimumHeight(dp(68));
        card.setBackground(shape(Color.WHITE, 16));
        ripple(card);
        var params = new LayoutParams(-1, -2);
        params.bottomMargin = dp(5);
        card.setLayoutParams(params);
        boolean home = today != null;
        int color = entry.refund() ? MUTED : entry.direction() > 0 ? POSITIVE : 0xffad4237;
        String category = entry.refund() ? "退款" : Categories.label(entry.category(), entry.direction());
        int categoryIcon = entry.refund() ? R.drawable.ui_refund
                : LedgerIcons.category(Categories.visibleId(entry.category(), entry.direction()));
        var categoryBadge = badge(categoryIcon, 36, 19);
        categoryBadge.setBackground(shape(entry.refund() ? 0xffedf0fb
                : entry.direction() > 0 ? 0xffe5f4ed : 0xfffbece8, 18));
        ((android.widget.ImageView) categoryBadge.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
        card.addView(categoryBadge);

        LinearLayout copy = column(0);
        copy.setPadding(dp(9), 0, dp(8), 0);
        LinearLayout first = horizontal();
        first.addView(shortLabel(category, 15, INK, true));
        String tag = Categories.BALANCE_ONLY.equals(entry.category()) ? "不计入收支"
                : Categories.RECONCILIATION.equals(entry.category()) ? "计入收支" : "#" + Bill.label(entry.source());
        var tagText = shortLabel(tag, 11, ACCENT, false);
        tagText.setPadding(dp(6), 0, 0, 0);
        first.addView(tagText, new LayoutParams(0, -2, 1));
        copy.addView(first);

        String original = entry.tradeTime();
        var found = java.util.regex.Pattern.compile("[0-9]{1,2}:[0-9]{2}").matcher(original);
        String time = found.find() ? found.group() : java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(entry.created()));
        var description = horizontal();
        if (home) {
            var day = LedgerStatistics.ledgerDate(original, entry.created(), java.time.ZoneId.systemDefault());
            String date = day.equals(today) ? "今天" : day.equals(today.minusDays(1)) ? "昨天"
                    : day.format(java.time.format.DateTimeFormatter.ofPattern(day.getYear() == today.getYear() ? "MM-dd" : "yy-MM-dd"));
            description.addView(shortLabel(date + " " + time, 12, MUTED, false));
        }
        if (!entry.merchant().isBlank()) {
            var merchant = shortLabel(entry.merchant(), 12, MUTED, false);
            if (home) merchant.setPadding(dp(5), merchant.getPaddingTop(), 0, merchant.getPaddingBottom());
            description.addView(merchant, new LayoutParams(0, -2, 1));
        }
        copy.addView(description);
        card.addView(copy, new LayoutParams(0, -2, 1));

        LinearLayout amounts = column(0);
        var amount = shortLabel(BillService.signed(entry.cents(), entry.direction()), 15, color, true);
        amount.setFontFeatureSettings("tnum");
        amount.setGravity(android.view.Gravity.END);
        amounts.addView(amount, new LayoutParams(-1, -2));
        String detail = home ? account == null ? displayPayment(entry) : account : time;
        var subtitle = shortLabel(detail, 12, MUTED, false);
        subtitle.setGravity(android.view.Gravity.END);
        if (home) subtitle.setMaxWidth(dp(100));
        amounts.addView(subtitle, new LayoutParams(-1, -2));
        card.addView(amounts, new LayoutParams(-2, -2));
        card.setFocusable(true);
        card.setOnClickListener(v -> edit(entry));
        return card;
    }

    private String displayPayment(Entry entry) {
        if (entry.method().isEmpty()) {
            return entry.payment().isEmpty() ? "未识别" : entry.payment();
        } else {
            return entry.bank() + entry.method() + (entry.tail().isEmpty() ? "" : " · 尾号 " + entry.tail());
        }
    }

    private void edit(Entry entry) {
        int direction = entry.direction();
        LinearLayout form = column(20);
        if (entry != null) {
            addDetailLine(form, LedgerIcons.source(entry.source()), "来源 · " + Bill.label(entry.source()));
            int tender = LedgerIcons.payment(entry.bank() + " " + entry.payment(), entry.source());
            addDetailLine(form, tender == 0 ? R.drawable.ui_wallet : tender, "支付方式 · " + displayPayment(entry));
        }

        EditText merchant = input(form, "商家或说明", entry.merchant(), InputType.TYPE_CLASS_TEXT, 80);
        EditText payment = input(form, "账单支付方式", entry.payment(), InputType.TYPE_CLASS_TEXT, 80);
        EditText amount = input(form, "金额（元）", decimal(entry.cents()), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, 13);
        boolean adjustment = Categories.adjustment(entry.category());
        Map<String, String> categories = adjustment ? Map.of(entry.category(), "平账") : Categories.forDirection(direction);
        ArrayList<String> ids = new ArrayList<>(categories.keySet());
        Spinner selection = spinner(form, "分类", new ArrayList<>(categories.values()));
        selection.setSelection(adjustment ? 0 : ids.indexOf(Categories.visibleId(entry.category(), direction)));

        List<Account> accounts = store.accounts();
        ArrayList<String> names = new ArrayList<>();
        names.add("未分配账户");
        accounts.forEach(account -> names.add(account.name()));
        Spinner accountSelection = spinner(form, "账户", names);
        if (entry != null) {
            for (int i = 0; i < accounts.size(); i++) {
                if (accounts.get(i).id() == entry.account()) {
                    accountSelection.setSelection(i + 1);
                }
            }
        }

        CheckBox remember = new CheckBox(this);
        boolean refund = entry.refund();
        if (refund) {
            selection.setEnabled(false);
            remember.setEnabled(false);
            form.addView(text("退款会冲减原支出。请先保存对应的微信原单。", 14, MUTED, false));
        } else if (adjustment) {
            selection.setEnabled(false);
            accountSelection.setEnabled(false);
            form.addView(text(Categories.BALANCE_ONLY.equals(entry.category())
                    ? "仅调整账户余额 · 不计入收支" : "差额已计入收支", 14, MUTED, false));
        } else {
            addCategoryRule(form, remember);
        }

        CheckBox learnAccount = new CheckBox(this);
        if (!refund && !adjustment && !accounts.isEmpty()) {
            learnAccount.setText("记住该支付方式，下次自动分配到此账户");
            learnAccount.setEnabled(accountSelection.getSelectedItemPosition() > 0);
            form.addView(learnAccount);
            form.addView(text("保存时把「" + (entry.payment().isBlank() ? "当前支付方式" : entry.payment())
                    + "」加入所选账户的别名。", 12, MUTED, false));
            accountSelection.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (position == 0) learnAccount.setChecked(false);
                    learnAccount.setEnabled(position > 0);
                }

                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
        }

        Builder dialog = new Builder(this)
            .setTitle(adjustment ? "平账" : refund ? "退款" : (direction < 0 ? "支出" : "收入"))
            .setView(scroll(form))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null);
        if (entry != null) {
            dialog.setNeutralButton(
                "移除",
                (ignored, which) -> new Builder(this)
                    .setMessage(Categories.BALANCE_ONLY.equals(entry.category()) ? "移除这笔平账？账户余额将随之调整。"
                            : "移除这笔记录？统计和账户余额将随之调整。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除", (alertx, action) -> {
                        store.remove(entry.id());
                        render();
                    })
                    .show()
            );
        }

        AlertDialog alert = dialog.create();
        alert.setOnShowListener(ignored -> alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long cents = Bill.amountCents(value(amount));
            if (cents <= 0L) {
                amount.setError("请输入大于零的金额");
            } else {
                int accountIndex = accountSelection.getSelectedItemPosition();
                long account = accountIndex == 0 ? 0L : accounts.get(accountIndex - 1).id();
                String category = ids.get(selection.getSelectedItemPosition());
                String method = value(payment).replaceAll("\\d{5,}", "••••");
                Entry target = store.entry(entry.id());
                if (target != null && store.save(target, value(merchant), method, cents, category, account, remember.isChecked())) {
                    if (target.state() == Ledger.DRAFT) {
                        Cloud.enrich(this, target.id());
                    }
                    if (learnAccount.isChecked()) learnAccountAlias(account, method);

                    alert.dismiss();
                    render();
                } else {
                    toast(refund ? "请核对原单及剩余可退款金额" : "记录已变更或金额低于已退款金额");
                    alert.dismiss();
                    render();
                }
            }
        }));
        alert.show();
    }

    /** 把账单上的支付方式写进账户别名，之后同一付款方式可自动分配。 */
    private void learnAccountAlias(long account, String payment) {
        if (account <= 0 || payment.isBlank()) return;
        for (Account target : store.accounts()) {
            if (target.id() != account) continue;
            store.saveAccount(target.id(), target.name(), 0,
                    target.matchPayment().isBlank() ? payment : target.matchPayment() + "、" + payment);
            return;
        }
    }

    private void accounts() {
        List<Account> accounts = store.accounts();
        long total = accounts.stream().mapToLong(Account::balance).sum();
        var balance = panel();
        balance.setBackground(shape(NAVY, 22));
        balance.addView(text("账户总余额", 14, 0xffdce0ff, true));
        balance.addView(text("¥" + money(total), 28, Color.WHITE, true));
        body.addView(balance);

        var heading = horizontal();
        heading.addView(text("我的账户", 19, INK, true), new LayoutParams(0, -2, 1));
        heading.addView(action("新建账户", () -> editAccount(null)));
        body.addView(heading);
        if (accounts.isEmpty()) body.addView(text("暂无账户", 15, MUTED, false));
        for (Account account : accounts) {
            LinearLayout card = horizontal();
            card.setPadding(dp(12), dp(9), dp(12), dp(9));
            card.setMinimumHeight(dp(68));
            card.setBackground(shape(Color.WHITE, 16));
            ripple(card);
            int logo = LedgerIcons.payment(account.name(), "");
            for (String alias : account.matchPayment().split("[、,，;；]")) {
                if (logo != 0) break;
                logo = LedgerIcons.payment(alias, "");
            }
            card.addView(badge(logo == 0 ? R.drawable.ui_wallet : logo, 42, 23));
            LinearLayout label = column(0);
            label.setPadding(dp(10), 0, dp(6), 0);
            label.addView(shortLabel(account.name(), 16, INK, true));
            if (!account.matchPayment().isBlank()) label.addView(shortLabel(account.matchPayment(), 12, MUTED, false));
            card.addView(label, new LayoutParams(0, -2, 1));
            card.addView(text("¥" + money(account.balance()), 16, INK, true));
            card.addView(icon(R.drawable.ui_chevron, MUTED, 17));
            card.setOnClickListener(v -> {
                selectedAccount = account.id();
                navigate(7);
            });
            card.setFocusable(true);
            var params = new LayoutParams(-1, -2);
            params.bottomMargin = dp(7);
            body.addView(card, params);
        }
    }

    private void accountDetails(Account account) {
        if (account == null) {
            body.addView(text("账户已不存在", 15, MUTED, false));
            return;
        }
        var card = panel();
        card.setBackground(shape(0xffe5eaff, 22));
        var heading = horizontal();
        heading.addView(text("人民币余额", 14, MUTED, true), new LayoutParams(0, -2, 1));
        heading.addView(action("编辑账户", () -> editAccount(account)));
        card.addView(heading);
        var current = horizontal();
        var amount = text("¥" + money(account.balance()), 29, NAVY, true);
        current.addView(amount, new LayoutParams(0, -2, 1));
        current.addView(action("调整余额", () -> editBalance(account)));
        card.addView(current);
        body.addView(card);

        var rows = store.accountEntries(account.id());
        long incoming = 0;
        long outgoing = 0;
        for (Entry entry : rows) {
            if (Categories.BALANCE_ONLY.equals(entry.category())) continue;
            if (entry.direction() > 0) incoming += entry.cents();
            else outgoing += entry.cents();
        }
        card.addView(statLine("累计流入", "¥" + money(incoming)));
        card.addView(statLine("累计流出", "¥" + money(outgoing)));
        body.addView(text("账户流水", 19, INK, true));
        transactionRows(body, rows, "暂无账户流水", true);
    }

    private void editBalance(Account account) {
        var form = column(20);
        EditText amount = input(form, "调整后余额（元）", decimal(account.balance()),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL, 16);
        amount.setSelectAllOnFocus(true);
        var include = new CheckBox(this);
        include.setText("将差额计入收支");
        form.addView(include);
        form.addView(text("当前余额 ¥" + money(account.balance()) + "。默认仅调整账户余额，账户流水中仍会保留平账记录。", 14, MUTED, false));
        var dialog = new Builder(this).setTitle("调整余额").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("确定", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Long target = balanceCents(value(amount));
            if (target == null) {
                amount.setError("请输入有效余额，最多保留两位小数");
            } else if (!store.adjustBalance(account.id(), target, include.isChecked())) {
                amount.setError("无法调整余额，请核对金额");
            } else {
                dialog.dismiss();
                render();
            }
        }));
        dialog.show();
    }

    private void editAccount(Account account) {
        var form = column(20);
        var name = input(form, "账户名称", account == null ? "" : account.name(), InputType.TYPE_CLASS_TEXT, 50);
        var opening = account == null ? input(form, "期初余额（元）", "0",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL, 13) : null;
        var payment = input(form, "支付方式别名（可空）", account == null ? "" : account.matchPayment(), InputType.TYPE_CLASS_TEXT, 320);
        form.addView(text("可填多个，用顿号或逗号分隔，例如：亲属卡、亲情卡。银行、储蓄卡、尾号等通用词会被忽略，"
                + "填「网商」即可匹配网商银行储蓄卡(6627)；两个账户都能匹配时留空。", 14, MUTED, false));
        var dialog = new Builder(this).setTitle(account == null ? "新建账户" : "编辑账户").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Long cents = opening == null ? Long.valueOf(0) : balanceCents(value(opening));
            if (value(name).isEmpty()) name.setError("请输入名称");
            else if (cents == null) opening.setError("请输入有效余额");
            else if (!store.saveAccount(account == null ? 0 : account.id(), value(name), cents,
                    value(payment).replaceAll("\\d{5,}", "••••"))) name.setError("名称已存在");
            else {
                dialog.dismiss();
                render();
            }
        }));
        dialog.show();
    }

    private Long balanceCents(String input) {
        if (!input.matches("-?[0-9]{1,13}(?:\\.[0-9]{1,2})?")) return null;
        long cents = new BigDecimal(input).movePointRight(2).longValueExact();
        return Math.abs(cents) <= 99_999_999_999L ? cents : null;
    }

    private boolean billServiceEnabled() {
        var manager = getSystemService(AccessibilityManager.class);
        if (!manager.isEnabled()) return false;
        for (var info : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            var service = info.getResolveInfo().serviceInfo;
            if (getPackageName().equals(service.packageName) && BillService.class.getName().equals(service.name)) return true;
        }
        return false;
    }

    private void settings() {
        SharedPreferences preferences = Cloud.prefs(this);
        body.addView(text("自动记账", 21, INK, true));
        boolean captureRequested = preferences.getBoolean("capture", false);
        Switch capture = toggle("读取已打开的账单", serviceEnabled && captureRequested);
        body.addView(text(serviceEnabled ? "系统无障碍服务已开启" : "系统无障碍服务已关闭，自动记账不可用", 14, MUTED, false));
        capture.setOnClickListener(view -> {
            if (!capture.isChecked()) {
                preferences.edit().putBoolean("capture", false).apply();
                getSystemService(NotificationManager.class).cancel(BillService.CHANNEL, 1);
                return;
            }
            capture.setChecked(false);
            Runnable enable = () -> {
                preferences.edit().putBoolean("capture", true).apply();
                serviceEnabled = billServiceEnabled();
                capture.setChecked(serviceEnabled);
                if (!serviceEnabled) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            };
            if (preferences.getBoolean("capture", false)) {
                enable.run();
            } else {
                new Builder(this).setTitle("启用账单读取")
                        .setMessage("无障碍服务会读取你打开的微信和支付宝账单界面，提取金额、商家、交易标识及支付方式。账单保存在本机。你可随时暂停读取。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("同意并继续", (dialog, which) -> enable.run())
                        .show();
            }
        });
        settingSwitch(Bill.WECHAT, "微信账单", true);
        body.addView(text("微信识别诊断：" + preferences.getString("wechat_diagnostic", "尚未收到微信页面事件"),
                14, MUTED, false));
        settingSwitch(Bill.ALIPAY, "支付宝账单", true);
        body.addView(text("支付宝识别诊断：" + Cloud.prefs(this).getString("alipay_diagnostic", "尚未收到支付宝页面事件"),
                14, MUTED, false));
        settingSwitch("history", "打开旧账单时自动入账", false);
        body.addView(text("开启后，打开尚未记录的微信或支付宝旧账单详情，有单号即可自动入账。关闭时，超出最近 5 分钟的旧账单通常进入待确认。", 13, MUTED, false));
        settingSwitch("live", "尝试显示 Android 16 实时更新", false);
        body.addView(button("系统无障碍设置", false, () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        body.addView(button("开启或管理记账通知", false, () -> {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            }
        }));
        body.addView(text("页面调试", 21, INK, true));
        boolean overlayGranted = Settings.canDrawOverlays(this);
        Switch debug = toggle("悬浮窗调试",
                serviceEnabled && overlayGranted && preferences.getBoolean(PageInspector.ENABLED, false));
        debug.setOnClickListener(view -> {
            boolean requested = debug.isChecked();
            preferences.edit().putBoolean(PageInspector.ENABLED, requested).apply();
            if (!requested) return;
            boolean granted = Settings.canDrawOverlays(this);
            serviceEnabled = billServiceEnabled();
            debug.setChecked(granted && serviceEnabled);
            if (!granted) openOverlaySettings();
            else if (!serviceEnabled) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        if (!overlayGranted) {
            body.addView(text("请先允许「在其他应用上层显示」。", 14, MUTED, false));
            body.addView(button("授权悬浮窗", false, this::openOverlaySettings));
        } else if (!serviceEnabled) {
            body.addView(text("悬浮窗权限已授权，请开启系统无障碍服务。", 14, MUTED, false));
        }
        body.addView(text("AI 分类与支付方式", 21, INK, true));
        int active = Cloud.active(this);
        body.addView(text(Cloud.label(this, active), 15, MUTED, false));

        for (int i = 0; i < Cloud.PROVIDERS.length; i++) {
            int provider = i;
            body.addView(button(Cloud.PROVIDERS[i].label(), active == i, () -> configure(provider)));
        }

        body.addView(text("局域网 MCP", 21, INK, true));
        if (mcp == null) {
            body.addView(text("手机与电脑连接同一可信 Wi-Fi。开启后可在电脑上查询账单汇总。", 14, MUTED, false));
            body.addView(button("开启 MCP", false, this::startMcp));
        } else {
            body.addView(text(mcp.url(), 15, INK, false));
            body.addView(text("本机网卡 " + mcpLink + "，电脑的 IPv4 需在同一网段（前三段相同）。", 14, MUTED, false));
            body.addView(text("使用期间请保持应用在前台。会话在 15 分钟后关闭。", 14, MUTED, false));
            body.addView(button("复制连接指引和提问示例", true, () -> {
                if (mcp != null) {
                    copyMcp("AI 记账连接指引", mcpGuide(mcp.url(), mcpLink), false);
                }
            }));
            body.addView(button("复制临时令牌", false, () -> {
                if (mcp != null) {
                    copyMcp("AI 记账临时令牌", mcp.token(), true);
                }
            }));
            body.addView(button("关闭 MCP", false, () -> {
                stopMcp();
                render();
            }));
        }

        body.addView(text("备份与恢复", 21, INK, true));
        var backupPrefs = Cloud.prefs(this);
        boolean backupReady = backupPrefs.contains(DailyBackup.FOLDER);
        String savedDate = backupPrefs.getString(DailyBackup.LAST_DATE, "");
        body.addView(text(!backupReady ? "自动备份未启用"
                : savedDate.isEmpty() ? "自动备份位置  Documents/AI记账" : "最近自动备份  " + savedDate,
                14, MUTED, false));
        body.addView(button(backupReady ? "重新设置自动备份" : "启用每日自动备份", false, this::chooseBackupDirectory));
        body.addView(button("导出加密备份", false, () -> backupPassword(true, null)));
        body
            .addView(
                button(
                    "从文件恢复",
                    false,
                    () -> startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE), 3
                    )
                )
            );

        body.addView(text("关于", 21, INK, true));
        body.addView(text("AI记账 " + installedVersion() + " · 正式版本从 GitHub 发布", 14, MUTED, false));
        body.addView(button(updateChecking ? "正在检查更新" : "检查更新", false, () -> {
            if (!updateChecking) checkUpdates(true);
        }));
    }

    private SharedPreferences updatePrefs() { return getSharedPreferences(UPDATE_STORE, MODE_PRIVATE); }

    private String installedVersion() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(),
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)).versionName;
            return name == null ? "" : name;
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
            return "";
        }
    }

    private void restoreUpdate() {
        String cached = updatePrefs().getString("requiredRelease", "");
        if (cached.isEmpty()) return;
        try {
            updateRelease = Updates.parse(cached, installedVersion());
        } catch (Updates.CheckFailure stale) {
            updatePrefs().edit().remove("requiredRelease").apply();
        }
    }

    private void checkUpdates(boolean manual) {
        if (updateChecking) return;
        if (!getPackageName().equals("com.gakiji.aiaccount")) {
            if (manual) {
                updateMessage = "独立测试版不使用正式更新渠道。";
                showUpdatePrompt();
            }
            return;
        }
        String installed = installedVersion();
        long now = System.currentTimeMillis();
        if (!manual && installed.equals(updatePrefs().getString("checkedVersion", ""))
                && !Updates.shouldCheck(updatePrefs().getLong("lastAttempt", 0), now)) return;
        updatePrefs().edit().putLong("lastAttempt", now).putString("checkedVersion", installed).apply();
        updateChecking = true;
        if (page == 3 && root != null) render();
        String agent = "AiAccount/" + installed;
        Cloud.TESTS.execute(() -> {
            Updates.Release found = null;
            String error = "";
            try {
                found = Updates.latest(installed, agent, Updates.http());
            } catch (Updates.CheckFailure unavailable) {
                error = unavailable.getMessage();
            } catch (Exception offline) {
                error = "暂时无法连接更新服务，请检查网络后重试。";
            }
            Updates.Release discovered = found;
            String failure = error;
            runOnUiThread(() -> {
                updateChecking = false;
                if (isDestroyed()) return;
                if (discovered != null) {
                    updateRelease = Updates.required(updateRelease, discovered);
                    try {
                        updatePrefs().edit().putString("requiredRelease", updateRelease.cache()).commit();
                    } catch (Exception unreadable) {
                        // 缓存写不进去时仍然按本次结果提示。
                    }
                }
                if (!failure.isEmpty()) {
                    updateMessage = manual ? failure : "";
                } else if (manual && updateRelease == null) {
                    updateMessage = "当前没有可用的新版本。当前版本：" + installed + "。";
                } else {
                    updateMessage = "";
                }
                if (page == 3 && root != null) render();
                showUpdatePrompt();
            });
        });
    }

    private void showUpdatePrompt() {
        if (updateRelease != null) {
            if (updateDialog != null && updateDialog.isShowing()) return;
            String text = String.format(java.util.Locale.ROOT, "安装包 %.1f MB\n\n%s\n\n安装新版本后才能继续使用。",
                    updateRelease.size() / 1024.0 / 1024.0,
                    updateRelease.notes().isBlank() ? "新版本已发布。" : updateRelease.notes());
            updateDialog = new Builder(this)
                    .setTitle("请更新至 " + updateRelease.version())
                    .setMessage(updateMessage.isEmpty() ? text : text + "\n\n" + updateMessage)
                    .setCancelable(false)
                    .setPositiveButton("立即更新", (dialog, which) -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(updateRelease.apkUrl())));
                        } catch (Exception noBrowser) {
                            updateMessage = "无法打开下载链接，请确认已安装浏览器后重试。";
                        }
                    })
                    .create();
            updateDialog.setCanceledOnTouchOutside(false);
            updateDialog.show();
            return;
        }
        if (updateMessage.isEmpty()) return;
        new Builder(this).setTitle("检查更新").setMessage(updateMessage)
                .setPositiveButton("知道了", (dialog, which) -> updateMessage = "")
                .show();
    }

    private void openOverlaySettings() {
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    private void startMcp() {
        if (mcp == null) {
            ConnectivityManager service = getSystemService(ConnectivityManager.class);
            LinkProperties properties = wifiLink(service);
            LinkAddress link = properties == null ? null : ipv4(properties);
            if (properties == null) {
                toast("请先连接可信 Wi-Fi");
                return;
            }
            if (link == null) {
                toast("当前 Wi-Fi 没有局域网 IPv4 地址，无法供电脑连接");
                return;
            }
            InetAddress address = link.getAddress();
            IpPrefix subnet = new IpPrefix(address, link.getPrefixLength());
            long expires = SystemClock.elapsedRealtime() + MCP_DURATION;
            try {
                mcp = new LanMcpServer(
                    address,
                    8765,
                    peer -> subnet.contains(peer) && SystemClock.elapsedRealtime() < expires,
                    query -> LedgerStatistics.read(store, query)
                );
            } catch (SecurityException | IOException bindFailed) {
                toast("无法开启 MCP，请检查局域网设置或端口");
                return;
            }
            String name = properties.getInterfaceName();
            mcpLink = (name == null || name.isEmpty() ? "wlan" : name) + " "
                    + address.getHostAddress() + "/" + link.getPrefixLength();
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            mcpTimer.postDelayed(() -> {
                stopMcp();
                if (page == 3) {
                    render();
                }
            }, MCP_DURATION);
            render();
        }
    }

    /** 只认真实 Wi-Fi 网卡，跳过 VPN、热点桥接与蜂窝网卡给出的地址。 */
    private LinkProperties wifiLink(ConnectivityManager service) {
        var networks = new ArrayList<Network>();
        Network active = service.getActiveNetwork();
        if (active != null) networks.add(active);
        for (Network network : service.getAllNetworks()) {
            if (!networks.contains(network)) networks.add(network);
        }
        for (Network network : networks) {
            NetworkCapabilities capabilities = service.getNetworkCapabilities(network);
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties properties = service.getLinkProperties(network);
            if (properties == null || !wifiInterface(properties.getInterfaceName())) continue;
            if (ipv4(properties) != null) return properties;
        }
        return null;
    }

    private static boolean wifiInterface(String name) {
        return name != null && !name.isEmpty() && !name.startsWith("tun") && !name.startsWith("ppp")
                && !name.startsWith("rmnet") && !name.startsWith("ipsec");
    }

    private static LinkAddress ipv4(LinkProperties properties) {
        for (LinkAddress link : properties.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (address instanceof Inet4Address && address.isSiteLocalAddress()) return link;
        }
        return null;
    }

    private void stopMcp() {
        mcpTimer.removeCallbacksAndMessages(null);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (mcp != null) {
            mcp.close();
            mcp = null;
        }
    }

    private void copyMcp(String label, String content, boolean sensitive) {
        ClipData clip = ClipData.newPlainText(label, content);
        if (sensitive) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }

        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
    }

    private String mcpGuide(String url, String link) {
        return """
                请在电脑的本地客户端连接 AI 记账，再发起统计。手机和电脑需连接同一个可信 Wi-Fi。
                名称：aiaccount
                URL：%1$s
                手机网卡：%2$s
                传输：Streamable HTTP
                鉴权：Authorization 请求头，值为 Bearer <临时令牌>。
                连接前先确认电脑的 IPv4 与手机网卡在同一网段（前三段相同）。不同网段、或电脑开着代理/VPN 把私有网段路由走隧道，都会表现为端口能连上但 HTTP 收不到回复。
                请提示我把手机上单独复制的临时令牌填到本地连接设置或终端。

                WorkBuddy：在「连接器 → 自定义连接器」配置 MCP，在本地设置中替换令牌占位符。
                {"mcpServers":{"aiaccount":{"type":"streamableHttp","url":"%1$s","headers":{"Authorization":"Bearer <临时令牌>"}}}}

                Codex CLI：在电脑 Bash 或 Zsh 终端运行以下命令。第二行等待输入时粘贴临时令牌并回车。
                codex mcp add aiaccount --url %1$s --bearer-token-env-var AIACCOUNT_MCP_TOKEN
                printf 'MCP Token: '; IFS= read -rs AIACCOUNT_MCP_TOKEN; echo
                export AIACCOUNT_MCP_TOKEN
                codex

                连接后请用 ledger_statistics 查询。例如：统计本月已入账收支，按分类列出净支出与退款，金额用人民币元展示。
                """.formatted(url, link);
    }

    private String tokenUsageText(int provider) {
        var usage = Cloud.tokenUsage(this, provider);
        var numbers = java.text.NumberFormat.getIntegerInstance(java.util.Locale.CHINA);
        return "本机累计 Token  " + numbers.format(usage.total()) + "\n输入 "
                + numbers.format(usage.input()) + " · 输出 " + numbers.format(usage.output());
    }

    private void configure(int provider) {
        boolean custom = Cloud.custom(provider);
        LinearLayout form = column(20);
        form.addView(text(custom
                ? "填写 OpenAI 兼容的对话接口、API Token 和模型名称。启用后，脱敏商家摘要和支付方式文字会发送到该地址，费用由该服务商账户承担。"
                : "启用后，脱敏商家摘要和支付方式文字会发送给该服务商。调用费用由此 API Key 的账户承担。", 14, MUTED, false));
        final EditText[] customFields = custom ? customInputs(form) : new EditText[0];
        EditText token = input(form, custom ? "API Token" : "API Key", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 300);
        if (Cloud.prefs(this).contains("key_" + provider)) {
            token.setHint((custom ? "API Token" : "API Key") + "（留空使用已保存密钥）");
        }
        var usage = text(tokenUsageText(provider), 13, MUTED, false);
        form.addView(usage);
        var builder = new AlertDialog.Builder(this).setTitle(Cloud.PROVIDERS[provider].label())
                .setView(custom ? scroll(form) : form)
                .setNegativeButton("取消", null).setPositiveButton("测试并启用", null);
        if (Cloud.prefs(this).contains("key_" + provider)) builder.setNeutralButton("清除密钥", (d, w) -> {
            Cloud.forget(this, provider);
            render();
        });
        var dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String entered = value(token);
            String address = customFields.length > 0 ? value(customFields[0]) : "";
            String model = customFields.length > 0 ? value(customFields[1]) : "";
            Button apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            apply.setEnabled(false);
            dialog.setCancelable(false);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
            Button forget = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (forget != null) forget.setEnabled(false);
            Cloud.TESTS.execute(() -> {
                String error = "";
                try {
                    String key = entered.isEmpty() ? Cloud.key(this, provider) : entered;
                    if (key.isEmpty()) throw new java.io.IOException(custom ? "请输入 API Token" : "请输入 API Key");
                    if (!key.matches("[!-~]{8,300}")) throw new java.io.IOException("密钥含空格或非法字符");
                    if (custom) {
                        Cloud.request(getApplicationContext(), provider, key, address, model, -1, "测试咖啡店", "测试银行卡");
                        Cloud.activateCustom(this, key, address, model);
                    } else {
                        Cloud.request(getApplicationContext(), provider, key, -1, "测试咖啡店", "测试银行卡");
                        Cloud.activate(this, provider, key);
                    }
                } catch (java.io.IOException e) {
                    error = e.getMessage();
                    if (error == null || !error.matches("^(API Key|请输入|服务商|模型|接口地址|密钥|无法保存).*")) {
                        error = "连接失败，请检查网络";
                    }
                } catch (Exception e) {
                    error = "验证失败，请检查服务商配置";
                }
                String message = error;
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    usage.setText(tokenUsageText(provider));
                    if (message.isEmpty()) { dialog.dismiss(); render(); toast("已连接"); }
                    else {
                        token.setError(message);
                        apply.setEnabled(true);
                        dialog.setCancelable(true);
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                        if (forget != null) forget.setEnabled(true);
                    }
                });
            });
        }));
        dialog.show();
    }

    private EditText[] customInputs(LinearLayout form) {
        EditText address = input(form, "接口地址", Cloud.endpoint(this),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 300);
        address.setHint("接口地址  https://…/v1/chat/completions");
        EditText model = input(form, "模型", Cloud.model(this), InputType.TYPE_CLASS_TEXT, 120);
        model.setHint("模型  例如 qwen3.6-flash");
        return new EditText[]{address, model};
    }

    private void chooseBackupDirectory() {
        var initial = android.provider.DocumentsContract.buildDocumentUri(DailyBackup.AUTHORITY, DailyBackup.DOCUMENTS);
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initial), 4);
    }

    private void autoBackup() {
        var prefs = Cloud.prefs(this);
        var date = java.time.LocalDate.now();
        if (!prefs.contains(DailyBackup.FOLDER) || date.toString().equals(prefs.getString(DailyBackup.LAST_DATE, ""))) return;
        Cloud.TESTS.execute(() -> {
            try {
                if (DailyBackup.saveIfDue(this, date)) runOnUiThread(() -> {
                    if (!isDestroyed() && page == 3) render();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!isDestroyed()) toast("自动备份失败，请在设置中检查目录或重新授权");
                });
            }
        });
    }

    private char[] backupKey;
    private void backupPassword(boolean export, android.net.Uri uri) {
        backupPassword(export, uri, false);
    }

    private void backupPassword(boolean export, android.net.Uri uri, boolean automatic) {
        LinearLayout form = column(20);
        EditText password = input(form, "备份密码（至少 8 个字符）", "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 128);
        form.addView(text(automatic ? "每天首次打开时加密保存到 Documents/AI记账。恢复时需要此密码。"
                : export ? "密码用于在新设备解密账本，请妥善保存。"
                : "恢复会替换本机账本及账户，并暂停自动记账。", 14, MUTED, false));
        var dialog = new AlertDialog.Builder(this).setTitle(automatic ? "设置自动备份密码" : export ? "导出加密备份" : "恢复账本")
                .setView(form).setNegativeButton("取消", null).setPositiveButton(automatic ? "启用" : export ? "选择位置" : "确认替换", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (password.length() < 8) { password.setError("至少 8 个字符"); return; }
            char[] key = password.getText().toString().toCharArray();
            dialog.dismiss();
            if (automatic) {
                Cloud.TESTS.execute(() -> {
                    try {
                        DailyBackup.configure(this, uri, key);
                        runOnUiThread(() -> {
                            if (isDestroyed()) return;
                            render();
                            autoBackup();
                        });
                    } catch (Exception failure) {
                        runOnUiThread(() -> { if (!isDestroyed()) toast("无法保存自动备份设置"); });
                    } finally {
                        java.util.Arrays.fill(key, '\0');
                    }
                });
            } else if (export) {
                backupKey = key;
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, "AI记账.aia"), 2);
            } else runBackup(false, uri, key);
        }));
        dialog.show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 2) {
            char[] key = backupKey;
            backupKey = null;
            if (key == null) return;
            if (result == RESULT_OK && data != null && data.getData() != null) runBackup(true, data.getData(), key);
            else java.util.Arrays.fill(key, '\0');
        }
        if (request == 4 && result == RESULT_OK && data != null && data.getData() != null) {
            var uri = data.getData();
            if (!DailyBackup.isDestination(uri)) {
                toast("请选择手机内部存储的 Documents 或 Documents/AI记账");
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                backupPassword(true, uri, true);
            } catch (SecurityException denied) {
                toast("无法获得目录写入权限，请重新选择");
            }
        }
        if (request == 3 && result == RESULT_OK && data != null && data.getData() != null) backupPassword(false, data.getData());
    }

    private void runBackup(boolean export, android.net.Uri uri, char[] key) {
        var progress = new AlertDialog.Builder(this).setMessage(export ? "正在备份…" : "正在核验并恢复…")
                .setCancelable(false).show();
        Cloud.TESTS.execute(() -> {
            String result;
            try {
                if (export) Backup.write(this, uri, key); else Backup.read(this, uri, key);
                result = export ? "备份已保存" : "恢复成功";
            } catch (Exception failed) {
                result = export ? "备份未完成，请检查目标位置" : "恢复失败，请检查密码或备份文件";
            } finally {
                java.util.Arrays.fill(key, '\0');
            }
            String message = result;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                progress.dismiss();
                render();
                toast(message);
            });
        });
    }

    private void settingSwitch(String key, String label, boolean fallback) {
        var p = Cloud.prefs(this);
        Switch control = toggle(label, p.getBoolean(key, fallback));
        control.setOnCheckedChangeListener((button, checked) -> p.edit().putBoolean(key, checked).apply());
    }

    private Switch toggle(String label, boolean enabled) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setMinHeight(dp(48));
        control.setChecked(enabled);
        body.addView(control);
        return control;
    }

    private Spinner spinner(LinearLayout parent, String label, List<String> items) {
        parent.addView(text(label, 14, MUTED, false));
        Spinner spinner = new Spinner(this);
        var adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(dp(48));
        spinner.setContentDescription(label);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
        return spinner;
    }

    private EditText input(LinearLayout parent, String hint, String initial, int type, int limit) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(type);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        edit.setHint(hint);
        edit.setText(initial);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, -2));
        return edit;
    }

    private ScrollView scroll(View form) {
        ScrollView view = new ScrollView(this);
        view.addView(form);
        return view;
    }

    private LinearLayout column(int padding) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        int px = dp(padding);
        view.setPadding(px, px, px, px);
        return view;
    }

    private LinearLayout panel() {
        LinearLayout panel = column(14);
        var surface = new GradientDrawable();
        surface.setCornerRadius(dp(16));
        surface.setColor(Color.WHITE);
        panel.setBackground(surface);
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        panel.setLayoutParams(params);
        return panel;
    }

    private LinearLayout horizontal() {
        var row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return row;
    }

    private GradientDrawable shape(int color, int radius) {
        var drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void ripple(View view) {
        var value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        if (value.resourceId != 0) view.setForeground(getDrawable(value.resourceId));
    }

    private android.widget.ImageView icon(int id, Integer tint, int size) {
        var icon = new android.widget.ImageView(this);
        icon.setImageResource(id);
        if (tint != null) icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setLayoutParams(new LayoutParams(dp(size), dp(size)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private android.widget.FrameLayout badge(int resource, int size, int glyph) {
        var frame = new android.widget.FrameLayout(this);
        frame.setLayoutParams(new LayoutParams(dp(size), dp(size)));
        frame.setBackground(shape(0xffedf0fb, size / 2));
        frame.addView(icon(resource, null, glyph),
                new android.widget.FrameLayout.LayoutParams(dp(glyph), dp(glyph), android.view.Gravity.CENTER));
        return frame;
    }

    private View iconAction(int resource, String label, Runnable listener) {
        var iconButton = new android.widget.ImageButton(this);
        iconButton.setImageResource(resource);
        iconButton.setImageTintList(ColorStateList.valueOf(ACCENT));
        iconButton.setBackground(shape(0xffedf0fb, 24));
        iconButton.setContentDescription(label);
        ripple(iconButton);
        iconButton.setLayoutParams(new LayoutParams(dp(48), dp(48)));
        iconButton.setOnClickListener(view -> listener.run());
        return iconButton;
    }

    private TextView action(String label, Runnable listener) {
        var view = text(label, 14, ACCENT, true);
        view.setMinHeight(dp(48));
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        view.setPadding(dp(9), 0, dp(4), 0);
        view.setFocusable(true);
        ripple(view);
        view.setOnClickListener(v -> listener.run());
        return view;
    }

    private void addDetailLine(LinearLayout form, int resource, String label) {
        LinearLayout row = horizontal();
        if (resource != 0) row.addView(icon(resource, null, 22));
        var labelView = text(label, 14, MUTED, false);
        labelView.setPadding(resource == 0 ? 0 : dp(8), dp(4), 0, dp(5));
        row.addView(labelView, new LayoutParams(0, -2, 1));
        form.addView(row);
    }

    private TextView shortLabel(String value, int size, int color, boolean strong) {
        var label = text(value, size, color, strong);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return label;
    }

    private String money(long cents) {
        var format = java.text.NumberFormat.getNumberInstance(java.util.Locale.CHINA);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(BigDecimal.valueOf(cents, 2));
    }

    private TextView text(String value, int size, int color, boolean strong) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(6));
        if (strong) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, boolean primary, Runnable listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : 0xffe8ebfb));
        button.setOnClickListener(view -> listener.run());
        return button;
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private String value(EditText edit) { return edit.getText().toString().strip(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String decimal(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
}
