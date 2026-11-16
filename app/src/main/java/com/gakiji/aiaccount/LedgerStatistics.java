package com.gakiji.aiaccount;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.TreeMap;

final class LedgerStatistics {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-M-d", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    static final class Totals {
        long income, expense, refund, payments, receipts, refunds;

        void add(long cents, int direction, boolean returned) {
            if (returned) { refund += cents; refunds++; }
            else if (direction < 0) { expense += cents; payments++; }
            else { income += cents; receipts++; }
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("income_cents", income).put("gross_expense_cents", expense)
                    .put("refund_cents", refund).put("net_expense_cents", expense - refund)
                    .put("net_cashflow_cents", income - expense + refund)
                    .put("expense_count", payments).put("income_count", receipts).put("refund_count", refunds);
        }
    }

    static JSONObject read(Ledger ledger, McpProtocol.Query query) throws JSONException {
        ZoneId zone = ZoneId.systemDefault();
        var totals = new Totals();
        var groups = new TreeMap<String, Totals>();
        try (var rows = postedRows(ledger)) {
            while (rows.moveToNext()) {
                LocalDate date = ledgerDate(rows.getString(2), rows.getLong(3), zone);
                if (date.isBefore(query.from()) || date.isAfter(query.to())) continue;
                long cents = rows.getLong(0);
                int direction = rows.getInt(1);
                boolean refund = !rows.isNull(6);
                totals.add(cents, direction, refund);
                String group = switch (query.group()) {
                    case "day" -> date.toString();
                    case "source" -> Bill.label(rows.getString(4));
                    case "category" -> Categories.label(rows.getString(refund ? 7 : 5), refund ? -1 : direction);
                    default -> "";
                };
                if (!group.isEmpty()) groups.computeIfAbsent(group, key -> new Totals()).add(cents, direction, refund);
            }
        }
        var output = new JSONArray();
        for (var group : groups.entrySet()) output.put(group.getValue().json().put("group", group.getKey()));
        return new JSONObject().put("currency", "CNY").put("timezone", zone.getId())
                .put("from_date", query.from().toString()).put("to_date", query.to().toString())
                .put("group_by", query.group()).put("totals", totals.json()).put("groups", output);
    }

    private static android.database.Cursor postedRows(Ledger ledger) {
        return ledger.getReadableDatabase().rawQuery("""
                SELECT e.cents,e.direction,e.trade_time,e.created,e.source,e.category,e.refund_of,p.category,a.name
                FROM entries e LEFT JOIN entries p ON p.fingerprint=e.refund_of
                LEFT JOIN accounts a ON a.id=e.account WHERE e.state=1 AND e.category<>?
                """, new String[]{Categories.BALANCE_ONLY});
    }

    enum Period {
        MONTH, YEAR;

        LocalDate start(LocalDate date) {
            return switch (this) {
                case MONTH -> date.withDayOfMonth(1);
                case YEAR -> date.withDayOfYear(1);
            };
        }

        LocalDate shift(LocalDate date, int count) {
            return switch (this) {
                case MONTH -> date.plusMonths(count);
                case YEAR -> date.plusYears(count);
            };
        }

        int slots(LocalDate start) {
            return switch (this) {
                case MONTH -> start.lengthOfMonth();
                case YEAR -> 12;
            };
        }

        int bucket(LocalDate day) {
            return switch (this) {
                case MONTH -> day.getDayOfMonth() - 1;
                case YEAR -> day.getMonthValue() - 1;
            };
        }
    }

    enum Group {
        CATEGORY("分类"), ACCOUNT("账户");

        final String label;
        Group(String label) { this.label = label; }
    }

    record ChartData(Totals totals, long[] expenses, long[] incomes,
                     java.util.Map<String, Long> expenseGroups, java.util.Map<String, Long> incomeGroups, long days, Totals selectedDay) {}

    static ChartData chart(Ledger ledger, Period period, LocalDate selected) {
        return chart(ledger, period, selected, Group.CATEGORY);
    }

    static ChartData chart(Ledger ledger, Period period, LocalDate selected, Group group) {
        var zone = ZoneId.systemDefault();
        LocalDate start = period.start(selected);
        LocalDate end = period.shift(start, 1);
        var totals = new Totals();
        var selectedDay = new Totals();
        long[] expenses = new long[period.slots(start)];
        long[] incomes = new long[expenses.length];
        var expenseGroups = new TreeMap<String, Long>();
        var incomeGroups = new TreeMap<String, Long>();
        try (var rows = postedRows(ledger)) {
            while (rows.moveToNext()) {
                String time = rows.getString(2);
                long created = rows.getLong(3);
                LocalDate day = ledgerDate(time, created, zone);
                if (day.isBefore(start) || !day.isBefore(end)) continue;
                long cents = rows.getLong(0);
                int direction = rows.getInt(1);
                boolean refund = !rows.isNull(6);
                totals.add(cents, direction, refund);
                if (day.equals(selected)) selectedDay.add(cents, direction, refund);
                if (refund) continue;
                int slot = period.bucket(day);
                (direction > 0 ? incomes : expenses)[slot] += cents;
                String name = switch (group) {
                    case CATEGORY -> Categories.label(rows.getString(5), direction);
                    case ACCOUNT -> rows.isNull(8) ? "未分配账户" : rows.getString(8);
                };
                (direction > 0 ? incomeGroups : expenseGroups).merge(name, cents, Long::sum);
            }
        }
        LocalDate today = LocalDate.now(zone);
        LocalDate averageEnd = !today.isBefore(start) && today.isBefore(end) ? today.plusDays(1) : end;
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, averageEnd);
        return new ChartData(totals, expenses, incomes, expenseGroups, incomeGroups, days, selectedDay);
    }

    static LocalDate ledgerDate(String value, long created, ZoneId zone) {
        String date = value.strip().split("\\s|T", 2)[0]
                .replace('/', '-').replace('年', '-').replace('月', '-').replace("日", "");
        if (!date.isEmpty()) {
            try { return LocalDate.parse(date, DATE); }
            catch (DateTimeParseException invalid) { /* Use the saved observation timestamp. */ }
        }
        return Instant.ofEpochMilli(created).atZone(zone).toLocalDate();
    }
}
