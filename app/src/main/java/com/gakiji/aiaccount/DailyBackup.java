package com.gakiji.aiaccount;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;

final class DailyBackup {
    private static final String DIRECTORY = "AI记账";
    static final String DOCUMENTS = "primary:Documents";
    static final String AUTHORITY = "com.android.externalstorage.documents";
    static final String FOLDER = "daily_backup_folder";
    static final String LAST_DATE = "daily_backup_date";
    private static final String PASSWORD = "daily_backup_password";
    private static final int PASSWORD_AAD = 2;

    static boolean isDestination(Uri tree) {
        if (!AUTHORITY.equals(tree.getAuthority()) || !DocumentsContract.isTreeUri(tree)) return false;
        String id = DocumentsContract.getTreeDocumentId(tree);
        return DOCUMENTS.equals(id) || (DOCUMENTS + "/" + DIRECTORY).equals(id);
    }

    static synchronized void configure(Context context, Uri tree, char[] password) throws Exception {
        var prefs = Cloud.prefs(context);
        String encrypted = Cloud.encryptSecret(PASSWORD_AAD, new String(password));
        if (!prefs.edit().putString(FOLDER, tree.toString()).putString(PASSWORD, encrypted)
                .remove(LAST_DATE).commit()) throw new IOException("Cannot save backup settings");
    }

    static synchronized boolean saveIfDue(Context context, LocalDate date) throws Exception {
        var prefs = Cloud.prefs(context);
        String location = prefs.getString(FOLDER, "");
        if (location.isEmpty() || date.toString().equals(prefs.getString(LAST_DATE, ""))) return false;

        Uri tree = Uri.parse(location);
        String id = DocumentsContract.getTreeDocumentId(tree);
        Uri folder = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        ContentResolver resolver = context.getContentResolver();
        if (DOCUMENTS.equals(id)) {
            Uri child = find(resolver, folder, DIRECTORY, true);
            folder = child != null ? child
                    : DocumentsContract.createDocument(resolver, folder, Document.MIME_TYPE_DIR, DIRECTORY);
            if (folder == null) throw new IOException("Cannot create backup folder");
        }

        String name = DIRECTORY + "-" + date + ".aia";
        boolean created = find(resolver, folder, name, false) == null;
        if (created) {
            String pendingName = name + ".pending";
            Uri stale = find(resolver, folder, pendingName, false);
            if (stale != null) DocumentsContract.deleteDocument(resolver, stale);
            Uri pending = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", pendingName);
            if (pending == null) throw new IOException("Cannot create backup file");
            try {
                char[] password = Cloud.decryptSecret(PASSWORD_AAD, prefs.getString(PASSWORD, "")).toCharArray();
                try {
                    if (password.length < 8) throw new IOException("Missing backup password");
                    Backup.write(context, pending, password);
                } finally {
                    Arrays.fill(password, '\0');
                }
                if (DocumentsContract.renameDocument(resolver, pending, name) == null) {
                    throw new IOException("Cannot publish backup file");
                }
            } catch (Exception failure) {
                try { DocumentsContract.deleteDocument(resolver, pending); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        if (!prefs.edit().putString(LAST_DATE, date.toString()).commit()) {
            throw new IOException("Cannot save backup date");
        }
        return created;
    }

    private static Uri find(ContentResolver resolver, Uri folder, String name, boolean directory) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder));
        String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
        try (var rows = resolver.query(children, columns, null, null, null)) {
            if (rows == null) throw new IOException("Cannot list backup folder");
            while (rows.moveToNext()) {
                boolean isDirectory = Document.MIME_TYPE_DIR.equals(rows.getString(2));
                if (name.equals(rows.getString(1)) && directory == isDirectory) {
                    return DocumentsContract.buildDocumentUriUsingTree(folder, rows.getString(0));
                }
            }
        }
        return null;
    }
}
