package com.gakiji.aiaccount;

import android.app.NotificationManager;
import android.content.Context;
import android.net.Uri;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class Backup {
    private static final byte[] MAGIC = {'A', 'I', 'A', 1};
    private static final int LIMIT = 16 * 1024 * 1024;
    private static final String[] TABLES = {"accounts", "entries", "rules"};

    static void write(Context context, Uri uri, char[] password) throws Exception {
        byte[] data = snapshot(Ledger.get(context).getWritableDatabase()).toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > LIMIT - 48) throw new IOException("Backup exceeds size limit");
        byte[] salt = random(16);
        byte[] nonce = random(12);
        byte[] header = ByteBuffer.allocate(32).put(MAGIC).put(salt).put(nonce).array();
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, password, header, data);
        try (var out = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Cannot write backup");
            out.write(header);
            out.write(encrypted);
        }
    }

    static void read(Context context, Uri uri, char[] password) throws Exception {
        byte[] file;
        try (var in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open backup");
            file = in.readNBytes(LIMIT + 1);
        }
        if (file.length < 48 || file.length > LIMIT || !Arrays.equals(MAGIC, Arrays.copyOf(file, 4))) {
            throw new IOException("Unsupported backup");
        }
        byte[] header = Arrays.copyOf(file, 32);
        byte[] data = crypt(Cipher.DECRYPT_MODE, password, header, Arrays.copyOfRange(file, 32, file.length));
        var contents = new JSONObject(new String(data, StandardCharsets.UTF_8));
        int version = contents.getInt("version");
        if (version != 1 && version != 2 && version != 3) throw new IOException("Unsupported backup version");

        Ledger ledger = Ledger.get(context);
        synchronized (ledger) {
            Cloud.prefs(context).edit().putBoolean("capture", false).apply();
            ledger.invalidateTasks();
            SQLiteDatabase db = ledger.getWritableDatabase();
            db.beginTransaction();
            try {
                for (int i = TABLES.length - 1; i >= 0; i--) db.delete(TABLES[i], null, null);
                for (String table : TABLES) restoreTable(db, table, contents.getJSONArray(table), version);
                try (Cursor invalid = db.rawQuery("""
                        SELECT 1 FROM entries r LEFT JOIN entries p ON p.fingerprint=r.refund_of
                        WHERE r.state=1 AND r.refund_of IS NOT NULL
                        GROUP BY r.refund_of
                        HAVING MIN(r.direction)<>1 OR p.id IS NULL OR p.state<>1 OR p.direction<>-1
                            OR p.refund_of IS NOT NULL OR SUM(r.cents)>p.cents
                        LIMIT 1
                        """, null)) {
                    if (invalid.moveToFirst()) throw new IOException("Invalid refund linkage");
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        context.getSystemService(NotificationManager.class).cancel(BillService.CHANNEL, 1);
        BillService.changed(context);
    }

    private static JSONObject snapshot(SQLiteDatabase db) throws Exception {
        var data = new JSONObject().put("version", 3);
        db.beginTransactionNonExclusive();
        try {
            for (String table : TABLES) {
                var rows = new JSONArray();
                try (Cursor cursor = db.query(table, null, null, null, null, null, null)) {
                    String[] columns = cursor.getColumnNames();
                    while (cursor.moveToNext()) {
                        var row = new JSONObject();
                        for (int i = 0; i < columns.length; i++) {
                            row.put(columns[i], switch (cursor.getType(i)) {
                                case Cursor.FIELD_TYPE_NULL -> JSONObject.NULL;
                                case Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i);
                                case Cursor.FIELD_TYPE_STRING -> cursor.getString(i);
                                case Cursor.FIELD_TYPE_FLOAT, Cursor.FIELD_TYPE_BLOB -> throw new IOException("Unexpected column type");
                                default -> throw new IOException("Unexpected storage type");
                            });
                        }
                        rows.put(row);
                    }
                }
                data.put(table, rows);
            }
            db.setTransactionSuccessful();
            return data;
        } finally {
            db.endTransaction();
        }
    }

    private static void restoreTable(SQLiteDatabase db, String table, JSONArray rows, int version) throws Exception {
        String[] columns;
        try (Cursor cursor = db.query(table, null, null, null, null, null, null, "0")) {
            columns = cursor.getColumnNames();
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            var values = new ContentValues();
            if (version == 1 && table.equals("entries")) row.put("refund_of", JSONObject.NULL);
            if (row.length() != columns.length) throw new IOException("Invalid backup columns");
            for (String column : columns) {
                Object value = row.get(column);
                boolean numeric = switch (column) {
                    case "id", "opening", "cents", "direction", "created", "state", "account", "revision" -> true;
                    default -> false;
                };
                if (value == JSONObject.NULL && (column.equals("account") || column.equals("fingerprint") || column.equals("refund_of"))) {
                    values.putNull(column);
                } else if (numeric && (value instanceof Integer || value instanceof Long)) {
                    long number = ((Number) value).longValue();
                    if ((column.equals("opening") || column.equals("cents")) && (number < -99_999_999_999L || number > 99_999_999_999L)) {
                        throw new IOException("Amount out of range");
                    }
                    if (column.equals("direction") && number != 1 && number != -1) throw new IOException("Invalid direction");
                    values.put(column, number);
                } else if (!numeric && value instanceof String text && text.length() <= 1024) {
                    values.put(column, text);
                } else throw new IOException("Invalid column value");
            }
            if (!table.equals("accounts")) {
                String category = row.getString("category");
                if (Categories.adjustment(category)) {
                    if (version < 3 || !table.equals("entries") || row.isNull("account")
                            || row.getInt("state") == Ledger.DRAFT || !row.isNull("refund_of")
                            || !row.isNull("fingerprint") || !row.getString("source").isEmpty()) {
                        throw new IOException("Invalid balance adjustment");
                    }
                } else if (!Categories.UNKNOWN.equals(category)
                        && !Categories.forDirection(row.getInt("direction")).containsKey(category)) {
                    throw new IOException("Invalid category");
                }
            }
            db.insertOrThrow(table, null, values);
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] crypt(int mode, char[] password, byte[] header, byte[] input) throws Exception {
        var spec = new PBEKeySpec(password, Arrays.copyOfRange(header, 4, 20), 600_000, 256);
        byte[] bytes;
        try {
            bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(bytes, "AES"), new GCMParameterSpec(128, header, 20, 12));
            cipher.updateAAD(header);
            return cipher.doFinal(input);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
