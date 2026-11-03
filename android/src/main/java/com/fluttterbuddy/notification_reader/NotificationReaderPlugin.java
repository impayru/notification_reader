package com.fluttterbuddy.notification_reader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;

public class NotificationReaderPlugin implements FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware {
    private MethodChannel channel;
    private Activity activity;
    private Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        channel = new MethodChannel(binding.getBinaryMessenger(), "notification_reader");
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        switch (call.method) {
            case "getPlatformVersion":
                result.success("Android " + Build.VERSION.RELEASE);
                break;
            case "openNotificationReaderSettings":
                Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                if (activity != null) activity.startActivity(intent);
                else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                result.success("Activity Start");
                break;
            case "onNotificationRecieve":
                database(result, store -> {
                    JSONObject latest = store.latest();
                    return latest == null ? null : latest.toString();
                });
                break;
            case "readPendingNotifications":
                Number limit = call.argument("limit");
                database(result, store -> store.pending(limit == null ? 50 : limit.intValue()).toString());
                break;
            case "claimNotification":
                database(result, store -> {
                    JSONObject data = store.claim(required(call, "eventId"),
                            required(call, "phone1"), required(call, "phone2"));
                    return data == null ? null : data.toString();
                });
                break;
            case "acknowledgeNotification":
                database(result, store -> store.acknowledge(required(call, "eventId"), required(call, "deliveryToken")));
                break;
            case "deferNotification":
                database(result, store -> {
                    Number retryAt = call.argument("retryAt");
                    if (retryAt == null) throw new IllegalArgumentException("Missing retryAt");
                    return store.defer(required(call, "eventId"), required(call, "deliveryToken"),
                            required(call, "error"), retryAt.longValue(), Boolean.TRUE.equals(call.argument("permanent")));
                });
                break;
            case "retryFailedNotifications":
                database(result, NotificationStore::retryFailed);
                break;
            case "getNotificationQueueStatus":
                database(result, store -> store.status().toString());
                break;
            default:
                result.notImplemented();
        }
    }

    private static String required(MethodCall call, String name) {
        String value = call.argument(name);
        if (value == null) throw new IllegalArgumentException("Missing " + name);
        return value;
    }

    private interface DatabaseCall {
        Object run(NotificationStore store) throws Exception;
    }

    private void database(Result result, DatabaseCall action) {
        NotificationStore.EXECUTOR.execute(() -> {
            try {
                Object value = action.run(NotificationStore.get(context));
                mainHandler.post(() -> result.success(value));
            } catch (Exception e) {
                mainHandler.post(() -> result.error("NOTIFICATION_STORAGE_ERROR", e.getClass().getSimpleName(), null));
            }
        });
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        activity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }
}
