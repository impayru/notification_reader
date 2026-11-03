package com.fluttterbuddy.notification_reader;

import android.content.ComponentName;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONObject;

public class NotificationService extends NotificationListenerService {
    private static final String TAG = "NotificationService";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        NotificationStore.listenerConnected = true;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification notification : active) onNotificationPosted(notification);
            }
        } catch (Exception e) {
            NotificationStore.captureError = "Unable to recover active notifications: " + e.getClass().getSimpleName();
            Log.e(TAG, NotificationStore.captureError);
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        NotificationStore.listenerConnected = false;
        Log.w(TAG, "Notification listener disconnected");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(new ComponentName(this, NotificationService.class));
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            // Snapshot before leaving the callback; never keep a mutable single slot.
            final JSONObject data = NotificationStore.snapshot(sbn);
            NotificationStore.EXECUTOR.execute(() -> {
                try {
                    if (!NotificationStore.get(getApplicationContext()).enqueue(data)) {
                        Log.d(TAG, "Repeated notification update suppressed");
                    }
                } catch (Exception e) {
                    NotificationStore.captureError = "Unable to save notification: " + e.getClass().getSimpleName();
                    Log.e(TAG, NotificationStore.captureError);
                }
            });
        } catch (Exception e) {
            NotificationStore.captureError = "Unable to read notification: " + e.getClass().getSimpleName();
            Log.e(TAG, NotificationStore.captureError);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Removal must not overwrite or acknowledge a queued delivery.
    }

    @Override
    public void onDestroy() {
        NotificationStore.listenerConnected = false;
        super.onDestroy();
    }
}
