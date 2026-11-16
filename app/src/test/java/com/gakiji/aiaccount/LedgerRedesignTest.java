package com.gakiji.aiaccount;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LedgerRedesignTest {
    @Test public void homeStatisticsAndManualEntryUseTheSameLedger() throws Exception {
        var store = Ledger.get(RuntimeEnvironment.getApplication());
        String today = LocalDate.now().toString() + " 12:00:00";
        assertTrue(store.saveAccount(0, "中国银行", 700000, "中国银行储蓄卡(1234)"));
        assertTrue(store.saveAccount(0, "微信零钱", 28000, "零钱"));
        assertTrue(store.saveAccount(0, "亲属卡", 12000, "亲情卡、亲属卡"));
        assertTrue(store.saveAccount(0, "现金", 8600, "现金"));
        store.add(new Bill(Bill.ALIPAY, "preview-0001", 14474, -1, "城市超市",
                "中国银行储蓄卡(1234)", "1234", today, false), true);
        store.add(new Bill(Bill.WECHAT, "preview-0002", 53900, 1, "微信转账收款",
                "零钱", "", today, false), true);
        store.add(new Bill(Bill.ALIPAY, "preview-0003", 1079, -1, "出行服务",
                "亲情卡", "", today, false), true);
        store.add(new Bill(Bill.WECHAT, "preview-0004", 1800, -1, "街角咖啡",
                "亲属卡", "", today, false), false);

        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            var view = controller.get().findViewById(android.R.id.content);
            assertNotNull(find(view, "本月净收支", false));
            assertNotNull(find(view, "¥383.47", false));
            assertNotNull(find(view, "1 笔", false));
            assertNotNull(find(view, "收 539.00", false));
            assertNotNull(find(view, "支 155.53", false));
            assertNotNull(find(view, "城市超市", false));
            var amount = (TextView) find(view, "-144.74", false);
            var payment = (TextView) find((View) amount.getParent(), "中国银行", false);
            assertEquals(android.view.Gravity.END, amount.getGravity() & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
            assertEquals(android.view.Gravity.END, payment.getGravity() & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
            var tag = find(view, "#支付宝", false);
            assertNotNull(find((View) tag.getParent(), "其他支出", false));
            capture(view, "首页");

            var pending = find(view, "待确认账单", false);
            assertTrue(((View) pending.getParent()).performClick());
            assertNotNull(find(view, "街角咖啡", false));
            store.remove(store.entries(Ledger.DRAFT, "", 0, 1).get(0).id());
            assertTrue(find(view, "首页", true).performClick());
            assertNull(find(view, "待确认账单", false));

            assertNull(find(view, "搜索商家或支付方式", false));
            assertTrue(find(view, "更多明细  ›", false).performClick());
            assertNotNull(find(view, "搜索商家或支付方式", false));
            assertNull(find(view, "本月净收支", false));
            var detailAmount = (TextView) find(view, "-144.74", false);
            var detailTime = (TextView) find((View) detailAmount.getParent(), "12:00", false);
            assertEquals(android.view.Gravity.END, detailTime.getGravity() & android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
            capture(view, "流水");
            assertTrue(find(view, "收入", false).performClick());
            assertNotNull(find(view, "微信转账收款", false));
            assertNull(find(view, "城市超市", false));
            ((EditText) find(view, "搜索商家或支付方式", false)).setText("银行");
            assertNull(find(view, "微信转账收款", false));
            assertTrue(find(view, "全部", false).performClick());
            assertNotNull(find(view, "城市超市", false));

            assertTrue(find(view, "统计", true).performClick());
            assertNotNull(find(view, "¥155.53", false));
            assertNotNull(find(view, "¥539.00", false));
            assertNotNull(find(view, "净收支", false));
            assertNull(find(view, "城市超市", false));
            assertNotNull(find(view, "逐日支出", false));
            assertNotNull(find(view, "日均收入", false));
            assertNull(find(view, "按商家统计", true));
            assertNull(find(view, "日", false));
            assertTrue(find(view, "按账户统计", true).performClick());
            assertNotNull(find(view, "中国银行", false));
            assertTrue(find(view, "按分类统计", true).performClick());
            capture(view, "统计");
            var now = LocalDate.now();
            String month = java.time.YearMonth.from(now).toString();
            assertTrue(find(view, "选择统计日期，当前 " + month, true).performClick());
            var picker = org.robolectric.shadows.ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertNull(find(picker, "自定义", false));
            assertTrue(find(picker, "上一年", true).performClick());
            assertTrue(find(picker, now.getMonthValue() + "月", false).performClick());
            assertNull(find(view, "¥155.53", false));

            String previous = java.time.YearMonth.from(now.minusYears(1)).toString();
            assertTrue(find(view, "选择统计日期，当前 " + previous, true).performClick());
            picker = org.robolectric.shadows.ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertTrue(find(picker, "年账单", false).performClick());
            assertTrue(find(picker, "今年", false).performClick());
            assertNotNull(find(view, "逐月支出", false));
            assertNotNull(find(view, "¥539.00", false));
            assertTrue(find(view, "上一时段", true).performClick());
            assertNull(find(view, "¥155.53", false));
            assertTrue(find(view, "下一时段", true).performClick());
            assertNotNull(find(view, "¥155.53", false));

            assertTrue(find(view, "记一笔", true).performClick());
            for (String label : Categories.forDirection(-1).values()) assertNotNull(find(view, label, false));
            var food = (android.widget.Button) find(view, "餐饮", false);
            assertNotNull(food.getCompoundDrawables()[1]);
            assertTrue(food.performClick());
            assertTrue(food.isSelected());
            assertNotNull(find(view, "下次自动沿用此分类", false));
            var launcher = controller.get().getDrawable(R.mipmap.ic_launcher);
            assertTrue(launcher instanceof android.graphics.drawable.AdaptiveIconDrawable);
            capture(view, "记账");
            assertTrue(find(view, "收入", false).performClick());
            for (String label : Categories.forDirection(1).values()) assertNotNull(find(view, label, false));
            ((EditText) find(view, "商家或说明", false)).setText("手动记录测试");
            for (String key : new String[]{"1", "2", ".", "3", "5", "⌫", "4", "8"}) {
                assertTrue(find(view, key, false).performClick());
            }
            assertTrue(find(view, "保存", false).performClick());
            var manual = store.entries(Ledger.POSTED, "手动记录测试", 1, 1).get(0);
            assertEquals(1234, manual.cents());
            assertEquals("other_income", manual.category());
            assertTrue(manual.tradeTime().startsWith(LocalDate.now().toString()));

            assertTrue(find(view, "账户", true).performClick());
            assertNotNull(find(view, "中国银行", false));
            assertNotNull(find(view, "亲情卡、亲属卡", false));
            capture(view, "账户");
            assertTrue(find(view, "设置", true).performClick());
            assertNotNull(find(view, "打开旧账单时自动入账", false));
            assertNotNull(find(view, "局域网 MCP", false));
        }
    }

    @Test public void homeLimitsRecentEntriesAndDailyTotalsUseTransactionDate() {
        var now = LocalDate.now();
        var store = Ledger.get(RuntimeEnvironment.getApplication());
        store.getWritableDatabase().delete("entries", null, null);
        for (int i = 0; i < 5; i++) {
            var day = i < 4 ? now.minusDays(1) : now;
            store.add(new Bill(Bill.ALIPAY, "home-limit-" + i, 120, -1, "记录" + i,
                    "现金", "", day + " 12:00:00", false), true);
        }
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            var view = controller.get().findViewById(android.R.id.content);
            assertNotNull(find(view, "支 1.20", false));
            assertNull(find(view, "记录0", false));
            for (int i = 1; i < 5; i++) assertNotNull(find(view, "记录" + i, false));
            assertTrue(find(view, "更多明细  ›", false).performClick());
            assertNotNull(find(view, "记录0", false));
        }
    }

    @Test public void dailyBackupAcceptsOnlyItsDocumentsFolderAndSkipsCompletedDates() throws Exception {
        var app = RuntimeEnvironment.getApplication();
        var documents = android.provider.DocumentsContract.buildTreeDocumentUri(DailyBackup.AUTHORITY, DailyBackup.DOCUMENTS);
        assertTrue(DailyBackup.isDestination(documents));
        assertTrue(DailyBackup.isDestination(android.provider.DocumentsContract.buildTreeDocumentUri(
                DailyBackup.AUTHORITY, "primary:Documents/AI记账")));
        assertFalse(DailyBackup.isDestination(android.provider.DocumentsContract.buildTreeDocumentUri(
                DailyBackup.AUTHORITY, "primary:Download")));
        var date = LocalDate.now();
        assertFalse(DailyBackup.saveIfDue(app, date));
        Cloud.prefs(app).edit().putString(DailyBackup.FOLDER, documents.toString())
                .putString(DailyBackup.LAST_DATE, date.toString()).commit();
        assertFalse(DailyBackup.saveIfDue(app, date));
    }

    @Test public void accountAdjustmentsAffectBalanceAndOnlyCheckedDifferencesEnterStatistics() throws Exception {
        var store = Ledger.get(RuntimeEnvironment.getApplication());
        var before = LedgerStatistics.chart(store, LedgerStatistics.Period.MONTH, LocalDate.now()).totals();
        assertTrue(store.saveAccount(0, "平账测试账户", 10000, ""));
        long account = store.accounts().stream().filter(a -> a.name().equals("平账测试账户")).findFirst().orElseThrow().id();
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            var view = controller.get().findViewById(android.R.id.content);
            assertTrue(find(view, "账户", true).performClick());
            var name = find(view, "平账测试账户", false);
            assertTrue(((View) name.getParent().getParent()).performClick());
            assertNotNull(find(view, "人民币余额", false));
            assertTrue(find(view, "调整余额", false).performClick());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            var dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            var amount = (EditText) find(dialog.getWindow().getDecorView(), "调整后余额（元）", false);
            amount.setText("126.00");
            assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick());
            assertNotNull(String.valueOf(amount.getError()), find(view, "¥126.00", false));
            assertNotNull(find(view, "不计入收支", false));

            assertTrue(find(view, "调整余额", false).performClick());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            var form = dialog.getWindow().getDecorView();
            ((EditText) find(form, "调整后余额（元）", false)).setText("110.00");
            ((android.widget.CheckBox) find(form, "将差额计入收支", false)).setChecked(true);
            assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick());
            assertNotNull(find(view, "¥110.00", false));
            assertNotNull(find(view, "计入收支", false));

            var rows = store.accountEntries(account);
            assertEquals(2, rows.size());
            assertEquals(1600, rows.get(0).cents());
            assertEquals(-1, rows.get(0).direction());
            assertEquals(Categories.RECONCILIATION, rows.get(0).category());
            assertEquals(2600, rows.get(1).cents());
            assertEquals(Categories.BALANCE_ONLY, rows.get(1).category());
            assertTrue(store.adjustBalance(account, 11000, true));
            assertEquals(2, store.accountEntries(account).size());
            var data = LedgerStatistics.chart(store, LedgerStatistics.Period.MONTH, LocalDate.now());
            assertEquals(before.income, data.totals().income);
            assertEquals(before.expense + 1600, data.totals().expense);
            assertEquals(Long.valueOf(1600), data.expenseGroups().get("平账"));
            var ids = store.entries(Ledger.POSTED, "", 0, 100).stream().map(Ledger.Entry::id).toList();
            assertTrue(ids.contains(rows.get(0).id()));
            assertFalse(ids.contains(rows.get(1).id()));
            assertTrue(find(view, "返回账户", true).performClick());
        }
    }

    @Test public void canBatchPostOnlySelectedDraftsIntoOneAccount() {
        var store = Ledger.get(RuntimeEnvironment.getApplication());
        assertTrue(store.saveAccount(0, "批量入账账户", 10000, ""));
        long account = store.accounts().stream().filter(a -> a.name().equals("批量入账账户")).findFirst().orElseThrow().id();
        long first = store.add(new Bill(Bill.WECHAT, "BATCH-TEST-1", 1234, -1, "批量甲", "", "", "", true), false);
        long second = store.add(new Bill(Bill.WECHAT, "BATCH-TEST-2", 456, -1, "批量乙", "", "", "", true), false);
        long third = store.add(new Bill(Bill.WECHAT, "BATCH-TEST-3", 900, -1, "批量丙", "", "", "", true), false);
        long refund = store.add(new Bill(Bill.WECHAT, "BATCH-REFUND", 500, 1, "", "", "", "", false,
                "MISSING-ORIGINAL"), false);
        assertFalse(store.postDrafts(java.util.List.of(first, refund), account));
        assertEquals(Ledger.DRAFT, store.entry(first).state());
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            var view = controller.get().findViewById(android.R.id.content);
            var pending = find(view, "待确认账单", false);
            assertTrue(((View) pending.getParent()).performClick());
            assertTrue(find(view, "多选", false).performClick());
            assertTrue(find(view, "选择 批量甲 -12.34", true).performClick());
            assertTrue(find(view, "选择 批量乙 -4.56", true).performClick());
            assertTrue(find(view, "统一入账（2）", false).performClick());
            var dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            var list = dialog.getListView();
            int index = 0;
            while (!"批量入账账户".equals(list.getAdapter().getItem(index))) index++;
            list.setItemChecked(index, true);
            assertTrue(list.performItemClick(list.getAdapter().getView(index, null, list), index, list.getItemIdAtPosition(index)));
            assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(Ledger.POSTED, store.entry(first).state());
            assertEquals(account, store.entry(first).account());
            assertEquals(account, store.entry(second).account());
            assertEquals(Ledger.DRAFT, store.entry(third).state());
            assertEquals(8310, store.accounts().stream().filter(a -> a.id() == account).findFirst().orElseThrow().balance());
        }
    }

    private static View find(View view, String label, boolean description) {
        if (description && label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (!description && view instanceof TextView text
                && (label.contentEquals(text.getText()) || label.contentEquals(text.getHint() == null ? "" : text.getHint()))) return view;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), label, description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void capture(View view, String name) throws IOException {
        if (!Boolean.getBoolean("aiaccount.uiPreview")) return;
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(411 * density);
        int height = Math.round(891 * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        Path output = Path.of("build/reports/ui-preview", name + ".png");
        Files.createDirectories(output.getParent());
        try (var stream = Files.newOutputStream(output)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
    }
}
