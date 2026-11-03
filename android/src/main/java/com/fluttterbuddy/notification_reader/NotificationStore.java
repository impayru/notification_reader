package com.fluttterbuddy.notification_reader;

import android.app.Notification;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable inbox of individual messages, shared by the listener and Flutter engines. */
final class NotificationStore extends SQLiteOpenHelper {
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    static volatile boolean listenerConnected;
    static volatile String captureError;
    private static NotificationStore instance;
    private static final long LEASE_MILLIS = 120_000L;
    private static final long HISTORY_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final long SNAPSHOT_GRACE_MILLIS = 2_000L;

    static synchronized NotificationStore get(Context context) {
        if (instance == null) instance = new NotificationStore(context.getApplicationContext());
        return instance;
    }

    private NotificationStore(Context context) {
        super(context, "notification_reader_outbox.db", null, 3);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notifications (event_id TEXT PRIMARY KEY, "
                + "source_key TEXT NOT NULL UNIQUE, content_key TEXT NOT NULL, payload TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'pending', "
                + "attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0, "
                + "lease_until INTEGER NOT NULL DEFAULT 0, lease_token TEXT, "
                + "last_error TEXT, delivered_at INTEGER)");
        db.execSQL("CREATE INDEX notifications_ready ON notifications (status, next_attempt_at, lease_until)");
        db.execSQL("CREATE INDEX notifications_content ON notifications (content_key)");
        createCaptureStats(db);
    }

    private void createCaptureStats(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE capture_stats (id INTEGER PRIMARY KEY, callbacks INTEGER NOT NULL DEFAULT 0, "
                + "extracted_messages INTEGER NOT NULL DEFAULT 0, queued_events INTEGER NOT NULL DEFAULT 0, "
                + "replays INTEGER NOT NULL DEFAULT 0, last_package TEXT, last_message_count INTEGER NOT NULL DEFAULT 0, "
                + "last_structured INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("INSERT INTO capture_stats (id) VALUES (1)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if ((oldVersion != 1 && oldVersion != 2) || newVersion != 3) {
            throw new IllegalStateException("Unsupported notification database migration");
        }
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE notifications ADD COLUMN content_key TEXT");
            long lastRow = 0;
            try {
                while (true) {
                    ArrayList<ContentValues> updates = new ArrayList<>();
                    try (Cursor cursor = db.query("notifications", new String[]{"rowid", "payload"},
                            "rowid > ?", new String[]{Long.toString(lastRow)}, null, null, "rowid", "100")) {
                        while (cursor.moveToNext()) {
                            lastRow = cursor.getLong(0);
                            ContentValues update = new ContentValues();
                            update.put("rowid", lastRow);
                            update.put("content_key", contentKey(new JSONObject(cursor.getString(1))));
                            updates.add(update);
                        }
                    }
                    if (updates.isEmpty()) break;
                    for (ContentValues update : updates) {
                        String row = update.getAsString("rowid");
                        update.remove("rowid");
                        db.update("notifications", update, "rowid = ?", new String[]{row});
                    }
                }
            } catch (JSONException e) {
                throw new IllegalStateException("Invalid stored notification", e);
            }
            db.execSQL("CREATE INDEX notifications_content ON notifications (content_key)");
        }
        // Keep existing payloads, IDs, leases and retry state intact.
        createCaptureStats(db);
    }

    static JSONObject snapshot(StatusBarNotification sbn) throws JSONException {
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        JSONArray messages = NotificationMessages.read(extras);
        String text = string(extras, Notification.EXTRA_BIG_TEXT);
        if (text == null) text = string(extras, Notification.EXTRA_TEXT);
        if (text == null && extras != null) {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                StringBuilder body = new StringBuilder();
                for (CharSequence line : lines) {
                    if (line == null || line.length() == 0) continue;
                    if (body.length() > 0) body.append('\n');
                    body.append(line);
                }
                if (body.length() > 0) text = body.toString();
            }
        }
        JSONObject data = new JSONObject();
        data.put("packageName", sbn.getPackageName());
        data.put("notificationKey", sbn.getKey());
        data.put("notificationId", sbn.getId());
        data.put("notificationTag", sbn.getTag());
        data.put("user", sbn.getUser().toString());
        data.put("postTime", sbn.getPostTime());
        data.put("notificationWhen", notification.when);
        data.put("notificationNumber", notification.number);
        data.put("notificationGroup", notification.getGroup());
        data.put("android.title", string(extras, Notification.EXTRA_TITLE));
        data.put("android.text", text);
        data.put("isGroupSummary", (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
        data.put("messages", messages);
        data.put("sourceKey", NotificationMessages.digest(data.toString()));
        data.put("eventId", UUID.randomUUID().toString());
        data.put("receivedAt", System.currentTimeMillis());
        return data;
    }

    private static String string(Bundle extras, String key) {
        Object value = extras == null ? null : extras.get(key);
        if (!(value instanceof CharSequence)) return null;
        String text = value.toString();
        return text.isEmpty() ? null : text;
    }

    private static String contentKey(JSONObject data) {
        return NotificationMessages.digest(new JSONArray().put(data.optString("packageName", ""))
                .put(data.optString("user", "")).put(data.optString("android.title", ""))
                .put(data.optString("android.text", "")).toString());
    }

    private String representedMessage(SQLiteDatabase db, JSONObject snapshot) throws JSONException {
        if ("message".equals(snapshot.optString("captureKind"))) return null;
        try (Cursor cursor = db.query("notifications", new String[]{"event_id", "payload"},
                "content_key = ? AND status IN ('pending', 'failed', 'delivered')",
                new String[]{contentKey(snapshot)}, null, null, "rowid")) {
            while (cursor.moveToNext()) {
                if (NotificationMessages.represents(new JSONObject(cursor.getString(1)), snapshot)) {
                    return cursor.getString(0);
                }
            }
        }
        return null;
    }

    boolean enqueue(JSONObject snapshot) throws JSONException {
        JSONArray events = NotificationMessages.expand(snapshot);
        JSONArray messages = snapshot.optJSONArray("messages");
        int messageCount = messages == null ? 0 : messages.length();
        int inserted = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("notifications", "status IN ('delivered', 'duplicate') AND delivered_at < ?",
                    new String[]{Long.toString(System.currentTimeMillis() - HISTORY_MILLIS)});
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                boolean structured = "message".equals(event.getString("captureKind"));
                if (!structured && representedMessage(db, event) != null) continue;
                ContentValues row = new ContentValues();
                row.put("event_id", event.getString("eventId"));
                row.put("source_key", event.getString("sourceKey"));
                row.put("content_key", contentKey(event));
                row.put("payload", event.toString());
                row.put("created_at", event.getLong("receivedAt"));
                // Allow a plain aggregate view to be replaced by its actual message history.
                if (!structured) row.put("next_attempt_at", event.getLong("receivedAt") + SNAPSHOT_GRACE_MILLIS);
                if (db.insertWithOnConflict("notifications", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1) inserted++;
            }
            db.execSQL("UPDATE capture_stats SET callbacks = callbacks + 1, extracted_messages = extracted_messages + ?, "
                            + "queued_events = queued_events + ?, replays = replays + ?, "
                            + "last_package = ?, last_message_count = ?, last_structured = ? WHERE id = 1",
                    new Object[]{messageCount, inserted, events.length() - inserted,
                            snapshot.optString("packageName", ""), messageCount, messageCount > 0 ? 1 : 0});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        Log.i("NotificationReader", "capture_v3 package=" + snapshot.optString("packageName", "")
                + " key=" + NotificationMessages.digest(snapshot.optString("notificationKey", "")).substring(0, 12)
                + " messages=" + messageCount + " added=" + inserted + " reused=" + (events.length() - inserted));
        return inserted > 0;
    }

    JSONObject latest() throws JSONException {
        try (Cursor cursor = getReadableDatabase().query("notifications", new String[]{"payload"},
                null, null, null, null, "created_at DESC, rowid DESC", "1")) {
            return cursor.moveToFirst() ? new JSONObject(cursor.getString(0)) : null;
        }
    }

    JSONArray pending(int limit) throws JSONException {
        JSONArray result = new JSONArray();
        String now = Long.toString(System.currentTimeMillis());
        try (Cursor cursor = getReadableDatabase().query("notifications", new String[]{"payload"},
                "status = 'pending' AND next_attempt_at <= ? AND lease_until <= ?",
                new String[]{now, now}, null, null, "created_at, rowid", Integer.toString(Math.max(1, Math.min(limit, 50))))) {
            while (cursor.moveToNext()) result.put(new JSONObject(cursor.getString(0)));
        }
        return result;
    }

    JSONObject claim(String id, String phone1, String phone2) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String now = Long.toString(System.currentTimeMillis());
            try (Cursor cursor = db.query("notifications", new String[]{"payload", "attempts"},
                    "event_id = ? AND status = 'pending' AND next_attempt_at <= ? AND lease_until <= ?",
                    new String[]{id, now, now}, null, null, null)) {
                if (!cursor.moveToFirst()) return null;
                JSONObject data = new JSONObject(cursor.getString(0));
                String represented = representedMessage(db, data);
                if (represented != null) {
                    ContentValues update = new ContentValues();
                    update.put("status", "duplicate");
                    update.put("delivered_at", System.currentTimeMillis());
                    update.put("last_error", "Represented by message " + represented);
                    db.update("notifications", update, "event_id = ?", new String[]{id});
                    db.setTransactionSuccessful();
                    return null;
                }
                if (!data.has("phone1")) data.put("phone1", phone1);
                if (!data.has("phone2")) data.put("phone2", phone2);
                if (!data.has("deliveryTime")) {
                    data.put("deliveryTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                            .format(new Date(data.getLong("postTime"))));
                }
                int attempts = cursor.getInt(1) + 1;
                String token = UUID.randomUUID().toString();
                ContentValues update = new ContentValues();
                update.put("payload", data.toString());
                update.put("attempts", attempts);
                update.put("lease_until", System.currentTimeMillis() + LEASE_MILLIS);
                update.put("lease_token", token);
                db.update("notifications", update, "event_id = ?", new String[]{id});
                db.setTransactionSuccessful();
                data.put("deliveryToken", token);
                data.put("deliveryAttempts", attempts);
                return data;
            }
        } finally {
            db.endTransaction();
        }
    }

    boolean acknowledge(String id, String token) {
        ContentValues update = new ContentValues();
        update.put("status", "delivered");
        update.put("delivered_at", System.currentTimeMillis());
        update.put("lease_until", 0);
        update.putNull("lease_token");
        update.putNull("last_error");
        return getWritableDatabase().update("notifications", update,
                "event_id = ? AND lease_token = ? AND status = 'pending'", new String[]{id, token}) == 1;
    }

    boolean defer(String id, String token, String error, long retryAt, boolean permanent) {
        ContentValues update = new ContentValues();
        update.put("status", permanent ? "failed" : "pending");
        update.put("last_error", error);
        update.put("next_attempt_at", retryAt);
        update.put("lease_until", 0);
        update.putNull("lease_token");
        return getWritableDatabase().update("notifications", update,
                "event_id = ? AND lease_token = ? AND status = 'pending'", new String[]{id, token}) == 1;
    }

    int retryFailed() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        int count = 0;
        try {
            JSONArray rejected = new JSONArray();
            try (Cursor cursor = db.query("notifications", new String[]{"payload"},
                    "status = 'failed'", null, null, null, null)) {
                while (cursor.moveToNext()) rejected.put(new JSONObject(cursor.getString(0)));
            }
            for (int i = 0; i < rejected.length(); i++) {
                JSONObject data = rejected.getJSONObject(i);
                data.remove("phone1");
                data.remove("phone2");
                ContentValues update = new ContentValues();
                update.put("status", "pending");
                update.put("next_attempt_at", 0);
                update.put("payload", data.toString());
                count += db.update("notifications", update, "event_id = ?", new String[]{data.getString("eventId")});
            }
            db.setTransactionSuccessful();
            return count;
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid stored notification", e);
        } finally {
            db.endTransaction();
        }
    }

    JSONObject status() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("listenerConnected", listenerConnected);
        result.put("captureError", captureError);
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT status, COUNT(*), MIN(created_at) FROM notifications "
                        + "WHERE status IN ('pending', 'failed') GROUP BY status", null)) {
            result.put("pending", 0);
            result.put("failed", 0);
            while (cursor.moveToNext()) {
                result.put(cursor.getString(0), cursor.getInt(1));
                if ("pending".equals(cursor.getString(0))) result.put("oldestPendingAt", cursor.getLong(2));
            }
        }
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT last_error FROM notifications WHERE status IN ('pending', 'failed') "
                        + "AND last_error IS NOT NULL ORDER BY created_at DESC, rowid DESC LIMIT 1", null)) {
            if (cursor.moveToNext()) result.put("lastError", cursor.getString(0));
        }
        try (Cursor cursor = getReadableDatabase().query("capture_stats", null, "id = 1", null, null, null, null)) {
            if (cursor.moveToFirst()) {
                result.put("capturedCallbacks", cursor.getLong(cursor.getColumnIndexOrThrow("callbacks")));
                result.put("queuedEvents", cursor.getLong(cursor.getColumnIndexOrThrow("queued_events")));
                result.put("reusedEvents", cursor.getLong(cursor.getColumnIndexOrThrow("replays")));
                result.put("lastCapturePackage", cursor.getString(cursor.getColumnIndexOrThrow("last_package")));
                result.put("lastCaptureMessageCount", cursor.getInt(cursor.getColumnIndexOrThrow("last_message_count")));
                result.put("lastCaptureStructured", cursor.getInt(cursor.getColumnIndexOrThrow("last_structured")) != 0);
            }
        }
        return result;
    }
}
