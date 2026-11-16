package com.gakiji.aiaccount;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

final class Ledger extends SQLiteOpenHelper {
    private static Ledger instance;
    private volatile long generation;

    long generation() { return generation; }
    void invalidateTasks() { generation++; }
    static synchronized Ledger get(Context context) {
        if (instance == null) instance = new Ledger(context.getApplicationContext());
        return instance;
    }

    private Ledger(Context context) { super(context, "ledger.db", null, 2); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("""
                CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE,
                opening INTEGER NOT NULL, match_payment TEXT NOT NULL DEFAULT '')
                """);
        db.execSQL("""
                CREATE TABLE entries (id INTEGER PRIMARY KEY, fingerprint TEXT UNIQUE,
                source TEXT NOT NULL, merchant TEXT NOT NULL, payment TEXT NOT NULL,
                tail TEXT NOT NULL, trade_time TEXT NOT NULL, cents INTEGER NOT NULL CHECK(cents>0),
                direction INTEGER NOT NULL CHECK(direction IN (-1,1)), created INTEGER NOT NULL,
                category TEXT NOT NULL DEFAULT 'uncategorized', method TEXT NOT NULL DEFAULT '',
                bank TEXT NOT NULL DEFAULT '', state INTEGER NOT NULL CHECK(state IN (0,1,2)),
                account INTEGER REFERENCES accounts(id), revision INTEGER NOT NULL DEFAULT 0, refund_of TEXT)
                """);
        db.execSQL("CREATE TABLE rules (merchant TEXT NOT NULL, direction INTEGER NOT NULL,"
                + " category TEXT NOT NULL, PRIMARY KEY(merchant,direction))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE entries ADD COLUMN refund_of TEXT");
    }

    record Entry(long id, String source, String merchant, String payment, String tail,
                 String tradeTime, long cents, int direction, long created, String category,
                 String method, String bank, int state, long account, int revision, String refundOf) {
        boolean refund() { return refundOf != null; }
    }
    record Account(long id, String name, long opening, long balance, String matchPayment) {}

    static final int DRAFT = 0, POSTED = 1, REMOVED = 2;
    private static final String[] COLUMNS = {"id", "source", "merchant", "payment", "tail",
            "trade_time", "cents", "direction", "created", "category", "method", "bank", "state",
            "account", "revision", "refund_of"};

    long add(Bill bill, boolean post) {
        ContentValues values = new ContentValues();
        if (!bill.source().isEmpty()) values.put("fingerprint", bill.fingerprint());
        values.put("source", bill.source());
        if (bill.refund()) values.put("refund_of", bill.originalFingerprint());
        values.put("merchant", bill.merchant());
        values.put("payment", bill.payment());
        values.put("tail", bill.lastFour());
        values.put("trade_time", bill.tradeTime());
        values.put("cents", bill.cents());
        values.put("direction", bill.direction());
        values.put("created", System.currentTimeMillis());
        values.put("state", post ? POSTED : DRAFT);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor rows = db.query("rules", new String[]{"category"}, "merchant=? AND direction=?",
                    new String[]{bill.merchant(), String.valueOf(bill.direction())}, null, null, null)) {
                if (rows.moveToFirst()) values.put("category", rows.getString(0));
            }
            if (!bill.payment().isEmpty()) {
                long match = matchingAccount(db, bill.payment());
                if (match != 0) values.put("account", match);
            }
            if (bill.refund() && post && !canPostRefund(db, bill.originalFingerprint(), bill.cents(), 0)) {
                values.put("state", DRAFT);
            }
            if (values.containsKey("fingerprint")) {
                db.execSQL("UPDATE entries SET fingerprint=NULL WHERE state=? AND fingerprint=?",
                        new Object[]{REMOVED, values.getAsString("fingerprint")});
            }
            long id = db.insertWithOnConflict("entries", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    long addManual(Bill bill, String category, long account, boolean remember) {
        var db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = add(bill, false);
            Entry entry = id > 0 ? entry(id) : null;
            if (entry == null || !save(entry, bill.merchant(), bill.payment(), bill.cents(), category, account, remember)) {
                return -1;
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    private long matchingAccount(SQLiteDatabase db, String payment) {
        long match = 0;
        int bestScore = 0;
        try (Cursor rows = db.query("accounts", new String[]{"id", "match_payment"},
                "match_payment<>''", null, null, null, null)) {
            while (rows.moveToNext()) {
                int score = PaymentAliases.match(rows.getString(1), payment);
                if (score > bestScore) {
                    match = rows.getLong(0);
                    bestScore = score;
                } else if (score == bestScore && score > 0) {
                    match = 0;
                }
            }
        }
        return match;
    }

    private Entry read(Cursor c) {
        return new Entry(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getLong(6), c.getInt(7), c.getLong(8), c.getString(9),
                c.getString(10), c.getString(11), c.getInt(12), c.getLong(13), c.getInt(14), c.getString(15));
    }

    Entry entry(long id) {
        try (Cursor c = getReadableDatabase().query("entries", COLUMNS, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    int draftCount() {
        try (Cursor count = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM entries WHERE state=0", null)) {
            return count.moveToFirst() ? count.getInt(0) : 0;
        }
    }

    List<Entry> entries(int state, String search, int direction, int limit) {
        return entries(state, search, direction, limit, 0);
    }

    List<Entry> accountEntries(long account) {
        return entries(POSTED, "", 0, -1, account);
    }

    private List<Entry> entries(int state, String search, int direction, int limit, long account) {
        List<Entry> result = new ArrayList<>();
        String match = "state=? AND instr(lower(merchant || ' ' || payment || ' ' || bank || ' ' || method),lower(?))>0";
        if (direction > 0) match += " AND direction=1 AND refund_of IS NULL";
        if (direction < 0) match += " AND (direction=-1 OR refund_of IS NOT NULL)";
        String[] args;
        if (account == 0) {
            match += " AND category<>'" + Categories.BALANCE_ONLY + "'";
            args = new String[]{String.valueOf(state), search};
        } else {
            match += " AND account=?";
            args = new String[]{String.valueOf(state), search, String.valueOf(account)};
        }
        try (Cursor c = getReadableDatabase().query("entries", COLUMNS, match, args, null, null, "id DESC", limit < 0 ? null : String.valueOf(limit))) {
            while (c.moveToNext()) result.add(read(c));
        }
        return result;
    }

    synchronized boolean enrich(Entry entry, String category, String method, String bank, long generation) {
        if (this.generation != generation) return false;
        ContentValues values = new ContentValues();
        if (entry.category().equals(Categories.UNKNOWN)) values.put("category", category);
        values.put("method", method);
        values.put("bank", bank);
        return getWritableDatabase().update("entries", values, "id=? AND revision=? AND state=1",
                new String[]{String.valueOf(entry.id()), String.valueOf(entry.revision())}) == 1;
    }

    boolean save(Entry entry, String merchant, String payment, long cents, String category,
                 long account, boolean remember) {
        if (Categories.adjustment(entry.category())) {
            category = entry.category();
            account = entry.account();
            remember = false;
        }
        ContentValues values = new ContentValues();
        values.put("merchant", merchant);
        values.put("payment", payment);
        if (!entry.payment().equals(payment)) {
            values.put("method", "");
            values.put("bank", "");
            values.put("tail", "");
        }
        values.put("cents", cents);
        values.put("category", category);
        if (account == 0) values.putNull("account"); else values.put("account", account);
        values.put("state", POSTED);
        values.put("revision", entry.revision() + 1);

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (entry.refund() && !canPostRefund(db, entry.refundOf(), cents, entry.id())) return false;
            try (Cursor c = db.rawQuery("""
                    SELECT COALESCE(SUM(r.cents),0) FROM entries p JOIN entries r ON r.refund_of=p.fingerprint
                    WHERE p.id=? AND r.state=1
                    """, new String[]{String.valueOf(entry.id())})) {
                if (c.moveToFirst() && c.getLong(0) > cents) return false;
            }
            boolean saved = db.update("entries", values, "id=? AND revision=? AND state<>2",
                    new String[]{String.valueOf(entry.id()), String.valueOf(entry.revision())}) == 1;
            if (saved && remember && !merchant.isBlank() && !category.equals(Categories.UNKNOWN)) {
                ContentValues rule = new ContentValues();
                rule.put("merchant", merchant);
                rule.put("direction", entry.direction());
                rule.put("category", category);
                db.insertWithOnConflict("rules", null, rule, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
            return saved;
        } finally {
            db.endTransaction();
        }
    }

    boolean postDrafts(java.util.List<Long> ids, long account) {
        if (ids.isEmpty() || account <= 0) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor match = db.query("accounts", new String[]{"id"}, "id=?",
                    new String[]{String.valueOf(account)}, null, null, null)) {
                if (!match.moveToFirst()) return false;
            }
            var drafts = new ArrayList<Entry>();
            for (long id : ids) {
                Entry draft = entry(id);
                if (draft == null || draft.state() != DRAFT) return false;
                drafts.add(draft);
            }
            // A selected original purchase must post before its refunds.
            drafts.sort(java.util.Comparator.comparing(Entry::refund));
            for (Entry draft : drafts) {
                if (!save(draft, draft.merchant(), draft.payment(), draft.cents(),
                        draft.category(), account, false)) return false;
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    void remove(long id) {
        ContentValues values = new ContentValues();
        values.put("state", REMOVED);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String[] key = {String.valueOf(id)};
            db.update("entries", values, "id=?", key);
            db.execSQL("""
                    UPDATE entries SET state=0, revision=revision+1
                    WHERE state=1 AND refund_of=(SELECT fingerprint FROM entries WHERE id=?)
                    """, key);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private boolean canPostRefund(SQLiteDatabase db, String original, long cents, long id) {
        try (Cursor c = db.rawQuery("""
                SELECT p.cents-COALESCE(SUM(r.cents),0)
                FROM entries p LEFT JOIN entries r ON r.refund_of=p.fingerprint AND r.state=1 AND r.id<>?
                WHERE p.fingerprint=? AND p.state=1 AND p.direction=-1 AND p.refund_of IS NULL
                GROUP BY p.id
                """, new String[]{String.valueOf(id), original})) {
            return c.moveToFirst() && c.getLong(0) >= cents;
        }
    }

    List<Account> accounts() {
        List<Account> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("""
                SELECT a.id,a.name,a.opening,a.opening+COALESCE(SUM(e.cents*e.direction),0),a.match_payment
                FROM accounts a LEFT JOIN entries e ON e.account=a.id AND e.state=1
                GROUP BY a.id ORDER BY a.id
                """, null)) {
            while (c.moveToNext()) result.add(new Account(c.getLong(0), c.getString(1),
                    c.getLong(2), c.getLong(3), c.getString(4)));
        }
        return result;
    }

    boolean adjustBalance(long account, long target, boolean includeInStatistics) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long current;
            try (Cursor c = db.rawQuery("""
                    SELECT a.opening+COALESCE(SUM(e.cents*e.direction),0)
                    FROM accounts a LEFT JOIN entries e ON e.account=a.id AND e.state=1
                    WHERE a.id=? GROUP BY a.id
                    """, new String[]{String.valueOf(account)})) {
                if (!c.moveToFirst()) return false;
                current = c.getLong(0);
            }
            long difference = Math.subtractExact(target, current);
            if (difference != 0) {
                if (difference < -99_999_999_999L || difference > 99_999_999_999L) return false;
                String category = includeInStatistics ? Categories.RECONCILIATION : Categories.BALANCE_ONLY;
                var time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                var bill = new Bill("", "", Math.abs(difference), difference < 0 ? -1 : 1,
                        "", "", "", time, false);
                if (addManual(bill, category, account, false) < 0) return false;
            }
            db.setTransactionSuccessful();
            return true;
        } catch (ArithmeticException outOfRange) {
            return false;
        } finally {
            db.endTransaction();
        }
    }

    boolean saveAccount(long id, String name, long opening, String payment) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        if (id == 0) values.put("opening", opening);
        values.put("match_payment", PaymentAliases.save(payment));
        SQLiteDatabase db = getWritableDatabase();
        return id == 0 ? db.insertWithOnConflict("accounts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1
                : db.updateWithOnConflict("accounts", values, "id=?", new String[]{String.valueOf(id)},
                SQLiteDatabase.CONFLICT_IGNORE) == 1;
    }
}
