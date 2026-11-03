package com.fluttterbuddy.notification_reader;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
public class NotificationStoreTest {
    private static final String[] BURST = {"333", "555", "66", "77", "88", "99", "323", "121", "2", "2", "2"};
    private NotificationStore store;
    private Context context;

    @Before
    public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("notification_reader_outbox.db");
        Constructor<NotificationStore> constructor = NotificationStore.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        store = constructor.newInstance(context);
    }

    @After
    public void tearDown() {
        if (store != null) store.close();
    }

    private StatusBarNotification notification(String[] bodies, long postTime) {
        return notification(bodies, postTime, false);
    }

    private StatusBarNotification notification(String[] bodies, long postTime, boolean sameTime) {
        Notification notification = new Notification.Builder(context, "messages")
                .setContentTitle("Asterisk").setContentText(bodies[bodies.length - 1])
                .setGroup("messages").build();
        Parcelable[] messages = new Parcelable[bodies.length];
        for (int i = 0; i < bodies.length; i++) {
            Bundle message = new Bundle();
            message.putCharSequence("text", bodies[i]);
            // The last three real messages have exactly the same text, sender and second.
            message.putLong("time", 1_793_700_000_000L + (sameTime ? 0 : Math.min(i, 8)) * 1_000L);
            message.putCharSequence("sender", "Anton Teleport");
            messages[i] = message;
        }
        notification.extras.putParcelableArray("android.messages", messages);
        notification.when = 1_793_700_000_000L + (sameTime ? 0 : Math.min(bodies.length - 1, 8)) * 1_000L;
        notification.number = bodies.length;
        return new StatusBarNotification("org.telegram.messenger", "org.telegram.messenger", 1,
                "chat", 0, 0, 0, notification, android.os.Process.myUserHandle(), postTime);
    }

    @Test
    public void twoGroupedNotificationsMustPreserveAllElevenMessagesIncludingThreeTwos() throws Exception {
        store.enqueue(NotificationStore.snapshot(notification(Arrays.copyOf(BURST, 8), 1_793_700_010_021L)));
        store.enqueue(NotificationStore.snapshot(notification(BURST, 1_793_700_011_874L)));
        JSONArray pending = store.pending(50);
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < pending.length(); i++) actual.add(pending.getJSONObject(i).getString("android.text"));
        assertEquals(Arrays.asList(BURST), actual);
        assertEquals(11, store.status().getInt("queuedEvents"));
        assertEquals(11, store.status().getInt("lastCaptureMessageCount"));
        assertEquals(8, store.status().getInt("reusedEvents"));
    }

    private List<String> deliverPending() throws Exception {
        JSONArray pending = store.pending(50);
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject claimed = store.claim(pending.getJSONObject(i).getString("eventId"), "phone-1", "phone-2");
            if (claimed == null) continue;
            bodies.add(claimed.getString("android.text"));
            assertTrue(store.acknowledge(claimed.getString("eventId"), claimed.getString("deliveryToken")));
        }
        return bodies;
    }

    private void makeSnapshotsDue() {
        store.getWritableDatabase().execSQL("UPDATE notifications SET next_attempt_at = 0 WHERE status = 'pending'");
    }

    private NotificationStore reopen() throws Exception {
        store.close();
        Constructor<NotificationStore> constructor = NotificationStore.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        store = constructor.newInstance(context);
        return store;
    }

    @Test
    public void repeatedHistoryAfterAcknowledgementAndRestartMustNotResend() throws Exception {
        store.enqueue(NotificationStore.snapshot(notification(BURST, 1_793_700_010_021L)));
        assertEquals(Arrays.asList(BURST), deliverPending());
        reopen();
        store.enqueue(NotificationStore.snapshot(notification(BURST, 1_793_700_099_999L)));
        assertEquals(0, store.pending(50).length());
        assertEquals(11, store.status().getInt("queuedEvents"));
        assertEquals(11, store.status().getInt("reusedEvents"));
    }

    @Test
    public void growingHistoryMustOnlyQueueTheNewTail() throws Exception {
        store.enqueue(NotificationStore.snapshot(notification(Arrays.copyOf(BURST, 8), 1_793_700_010_021L)));
        assertEquals(Arrays.asList(Arrays.copyOf(BURST, 8)), deliverPending());
        store.enqueue(NotificationStore.snapshot(notification(BURST, 1_793_700_011_874L)));
        assertEquals(Arrays.asList("2", "2", "2"), deliverPending());
        store.enqueue(NotificationStore.snapshot(notification(BURST, 1_793_700_012_100L)));
        assertTrue(deliverPending().isEmpty());
    }

    @Test
    public void threeIdenticalMessagesInTheSameSecondMustHaveDifferentIds() throws Exception {
        for (int count = 1; count <= 3; count++) {
            String[] bodies = new String[count];
            Arrays.fill(bodies, "2");
            store.enqueue(NotificationStore.snapshot(notification(bodies, 1_793_700_010_000L + count, true)));
        }
        JSONArray pending = store.pending(50);
        Set<String> ids = new HashSet<>();
        Set<String> sources = new HashSet<>();
        for (int i = 0; i < pending.length(); i++) {
            ids.add(pending.getJSONObject(i).getString("eventId"));
            sources.add(pending.getJSONObject(i).getString("sourceKey"));
        }
        assertEquals(3, ids.size());
        assertEquals(3, sources.size());
        assertEquals(Arrays.asList("2", "2", "2"), deliverPending());
    }

    @Test
    public void summaryTextAndRenamedTitleMustNotHideOrReplayHistory() throws Exception {
        StatusBarNotification first = notification(BURST, 1_793_700_010_000L);
        first.getNotification().extras.putCharSequence(Notification.EXTRA_BIG_TEXT, "Only a rendered summary");
        store.enqueue(NotificationStore.snapshot(first));
        assertEquals(Arrays.asList(BURST), deliverPending());
        StatusBarNotification repost = notification(BURST, 1_793_700_020_000L);
        repost.getNotification().extras.putCharSequence(Notification.EXTRA_TITLE, "Renamed chat");
        store.enqueue(NotificationStore.snapshot(repost));
        assertTrue(deliverPending().isEmpty());
    }

    @Test
    public void independentNotificationKeysMustRemainIndependent() throws Exception {
        StatusBarNotification first = notification(new String[]{"2"}, 1_793_700_010_000L);
        StatusBarNotification other = new StatusBarNotification(first.getPackageName(), first.getOpPkg(), 2,
                "another-chat", 0, 0, 0, first.getNotification(), first.getUser(), first.getPostTime());
        store.enqueue(NotificationStore.snapshot(first));
        store.enqueue(NotificationStore.snapshot(other));
        assertEquals(Arrays.asList("2", "2"), deliverPending());
    }

    @Test
    public void realAndroidMessagingStyleBundlesMustPreserveAllMessagesAndStablePersonKey() throws Exception {
        Person author = new Person.Builder().setKey("author-id").setName("Anton Teleport").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(
                new Person.Builder().setKey("self").setName("Reader").build()).setConversationTitle("Asterisk");
        for (int i = 0; i < BURST.length; i++) {
            style.addMessage(BURST[i], 1_793_700_000_000L + Math.min(i, 8) * 1_000L, author);
        }
        Notification notification = new Notification.Builder(context, "messages").setStyle(style).build();
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, "Asterisk");
        StatusBarNotification source = new StatusBarNotification("org.telegram.messenger", "org.telegram.messenger", 1,
                "chat", 0, 0, 0, notification, android.os.Process.myUserHandle(), 1_793_700_010_000L);
        store.enqueue(NotificationStore.snapshot(source));
        JSONArray pending = store.pending(50);
        assertEquals(11, pending.length());
        for (int i = 0; i < pending.length(); i++) {
            assertEquals("author-id", pending.getJSONObject(i).getString("messageSenderKey"));
            assertEquals("Anton Teleport", pending.getJSONObject(i).getString("messageSender"));
        }
        assertEquals(Arrays.asList(BURST), deliverPending());
    }

    @Test
    public void structuredMessagesWithoutTimestampsMustNotCollapseAcrossDistinctPublications() throws Exception {
        for (long postTime : new long[]{1_793_700_010_615L, 1_793_700_010_922L, 1_793_700_010_922L}) {
            StatusBarNotification source = notification(new String[]{"2"}, postTime);
            Bundle message = (Bundle) source.getNotification().extras.getParcelableArray("android.messages")[0];
            message.putLong("time", 0);
            store.enqueue(NotificationStore.snapshot(source));
        }
        assertEquals(Arrays.asList("2", "2"), deliverPending());
    }

    private StatusBarNotification plain(String body, long postTime, int number, boolean summary) {
        StatusBarNotification source = notification(new String[]{body}, postTime, true);
        source.getNotification().extras.remove("android.messages");
        source.getNotification().number = number;
        if (summary) source.getNotification().flags |= Notification.FLAG_GROUP_SUMMARY;
        return new StatusBarNotification(source.getPackageName(), source.getOpPkg(), summary ? 99 : 1,
                summary ? "summary" : "chat", 0, 0, 0, source.getNotification(), source.getUser(), postTime);
    }

    @Test
    public void summaryMustWaitForChildThenYieldToAnActuallyQueuedMessage() throws Exception {
        store.enqueue(NotificationStore.snapshot(plain("456", 1_793_700_010_615L, 1, true)));
        assertEquals(0, store.pending(50).length());
        store.enqueue(NotificationStore.snapshot(notification(new String[]{"456"}, 1_793_700_010_922L, true)));
        makeSnapshotsDue();
        assertEquals(Arrays.asList("456"), deliverPending());
        assertEquals(0, store.status().getInt("pending"));
    }

    @Test
    public void summaryWithoutAnyChildMustStillBeDelivered() throws Exception {
        store.enqueue(NotificationStore.snapshot(plain("456", 1_793_700_010_615L, 1, true)));
        makeSnapshotsDue();
        assertEquals(Arrays.asList("456"), deliverPending());
    }

    @Test
    public void oldChildMustNotHideANewerSummaryOrThreeActualTwos() throws Exception {
        store.enqueue(NotificationStore.snapshot(notification(new String[]{"2"}, 1_793_700_010_001L, true)));
        assertEquals(Arrays.asList("2"), deliverPending());
        store.enqueue(NotificationStore.snapshot(plain("2", 1_793_700_010_300L, 3, true)));
        assertEquals(1, store.status().getInt("pending"));
        store.enqueue(NotificationStore.snapshot(notification(new String[]{"2", "2", "2"}, 1_793_700_010_500L, true)));
        makeSnapshotsDue();
        assertEquals(Arrays.asList("2", "2"), deliverPending());
    }

    @Test
    public void missingMessageMetadataMustNotTriggerAContentTimeWindowFilter() throws Exception {
        store.enqueue(NotificationStore.snapshot(plain("2", 1_793_700_010_615L, 0, false)));
        store.enqueue(NotificationStore.snapshot(plain("2", 1_793_700_010_922L, 0, false)));
        store.enqueue(NotificationStore.snapshot(plain("2", 1_793_700_011_013L, 0, false)));
        makeSnapshotsDue();
        assertEquals(Arrays.asList("2", "2", "2"), deliverPending());
        assertFalse(store.status().getBoolean("lastCaptureStructured"));
        assertEquals(0, store.status().getInt("lastCaptureMessageCount"));
    }

    @Test
    public void replayMustNotResetOfflineRetryOrCreateAnotherEventId() throws Exception {
        store.enqueue(NotificationStore.snapshot(notification(new String[]{"2"}, 1_793_700_010_000L, true)));
        String id = store.pending(50).getJSONObject(0).getString("eventId");
        JSONObject claimed = store.claim(id, "phone-1", "phone-2");
        long retryAt = System.currentTimeMillis() + 60_000L;
        assertTrue(store.defer(id, claimed.getString("deliveryToken"), "offline", retryAt, false));
        store.enqueue(NotificationStore.snapshot(notification(new String[]{"2"}, 1_793_700_020_000L, true)));
        assertEquals(0, store.pending(50).length());
        try (Cursor cursor = store.getReadableDatabase().query("notifications",
                new String[]{"event_id", "attempts", "next_attempt_at"}, null, null, null, null, null)) {
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals(id, cursor.getString(0));
            assertEquals(1, cursor.getInt(1));
            assertEquals(retryAt, cursor.getLong(2));
        }
    }

    @Test
    public void leaseAndPayloadMustSurviveRestartWithoutParallelClaims() throws Exception {
        store.enqueue(NotificationStore.snapshot(notification(new String[]{"2"}, 1_793_700_010_000L, true)));
        String id = store.pending(50).getJSONObject(0).getString("eventId");
        JSONObject first = store.claim(id, "phone-1", "phone-2");
        assertNotNull(first);
        assertNull(store.claim(id, "other", "other"));
        reopen();
        assertNull(store.claim(id, "other", "other"));
        store.getWritableDatabase().execSQL("UPDATE notifications SET lease_until = 0");
        JSONObject second = store.claim(id, "other", "other");
        assertEquals(id, second.getString("eventId"));
        assertEquals("phone-1", second.getString("phone1"));
        assertEquals(first.getString("deliveryTime"), second.getString("deliveryTime"));
        assertEquals(2, second.getInt("deliveryAttempts"));
    }

    private void seedLegacyDatabase(int version, int count) throws Exception {
        store.getWritableDatabase();
        store.close();
        context.deleteDatabase("notification_reader_outbox.db");
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("notification_reader_outbox.db"), null)) {
            db.execSQL("CREATE TABLE notifications (event_id TEXT PRIMARY KEY, source_key TEXT NOT NULL UNIQUE, "
                    + (version == 2 ? "content_key TEXT, " : "")
                    + "payload TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'pending', "
                    + "attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0, "
                    + "lease_until INTEGER NOT NULL DEFAULT 0, lease_token TEXT, last_error TEXT, delivered_at INTEGER)");
            db.execSQL("CREATE INDEX notifications_ready ON notifications (status, next_attempt_at, lease_until)");
            if (version == 2) db.execSQL("CREATE INDEX notifications_content ON notifications (content_key)");
            for (int i = 0; i < count; i++) {
                JSONObject payload = new JSONObject().put("eventId", "old-" + i).put("packageName", "org.telegram.messenger")
                        .put("user", "UserHandle{0}").put("android.title", "Asterisk").put("android.text", "legacy-" + i)
                        .put("postTime", 1_793_700_000_000L).put("phone1", "original-phone");
                ContentValues row = new ContentValues();
                row.put("event_id", "old-" + i);
                row.put("source_key", "source-" + i);
                if (version == 2) row.put("content_key", "content-" + i);
                row.put("payload", payload.toString());
                row.put("created_at", System.currentTimeMillis());
                row.put("attempts", 3);
                row.put("next_attempt_at", 1234L);
                row.put("last_error", "offline");
                assertTrue(db.insertOrThrow("notifications", null, row) != -1);
            }
            db.setVersion(version);
        }
        Constructor<NotificationStore> constructor = NotificationStore.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        store = constructor.newInstance(context);
    }

    @Test
    public void migrationFromVersionTwoMustPreserveIdsRetriesAndPhoneSnapshot() throws Exception {
        seedLegacyDatabase(2, 1);
        SQLiteDatabase db = store.getReadableDatabase();
        assertEquals(3, db.getVersion());
        try (Cursor cursor = db.query("notifications", null, null, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("old-0", cursor.getString(cursor.getColumnIndexOrThrow("event_id")));
            assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("attempts")));
            assertEquals(1234L, cursor.getLong(cursor.getColumnIndexOrThrow("next_attempt_at")));
            assertEquals("original-phone", new JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("payload"))).getString("phone1"));
        }
        assertEquals(0, store.status().getInt("capturedCallbacks"));
    }

    @Test
    public void migrationFromVersionOneMustBackfillMoreThanOneBatchWithoutLosingRows() throws Exception {
        seedLegacyDatabase(1, 205);
        SQLiteDatabase db = store.getReadableDatabase();
        assertEquals(3, db.getVersion());
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*), COUNT(content_key), MIN(attempts), MAX(next_attempt_at) FROM notifications", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(205, cursor.getInt(0));
            assertEquals(205, cursor.getInt(1));
            assertEquals(3, cursor.getInt(2));
            assertEquals(1234L, cursor.getLong(3));
        }
    }
}
