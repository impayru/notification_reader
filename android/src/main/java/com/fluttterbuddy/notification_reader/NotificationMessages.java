package com.fluttterbuddy.notification_reader;

import android.app.Person;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.UUID;

/** Expands a MessagingStyle history into independently acknowledged messages. */
final class NotificationMessages {
    static JSONArray read(Bundle extras) throws JSONException {
        JSONArray result = new JSONArray();
        Parcelable[] messages = extras == null ? null : extras.getParcelableArray("android.messages");
        if (messages == null) return result;
        for (Parcelable item : messages) {
            if (!(item instanceof Bundle)) continue;
            Bundle message = (Bundle) item;
            JSONObject data = new JSONObject();
            data.put("text", text(message.get("text")));
            data.put("time", message.getLong("time", 0));
            String name = text(message.get("sender"));
            String senderKey = null;
            if (Build.VERSION.SDK_INT >= 28) {
                Parcelable value = message.getParcelable("sender_person");
                if (value instanceof Person) {
                    Person person = (Person) value;
                    senderKey = person.getKey();
                    if (name == null) name = text(person.getName());
                }
            }
            data.put("sender", name);
            data.put("senderKey", senderKey);
            result.put(data);
        }
        return result;
    }

    static JSONArray expand(JSONObject snapshot) throws JSONException {
        JSONArray messages = snapshot.optJSONArray("messages");
        JSONArray events = new JSONArray();
        if (messages == null || messages.length() == 0) {
            JSONObject event = new JSONObject(snapshot.toString());
            event.remove("messages");
            event.put("captureKind", "snapshot");
            events.put(event);
            return events;
        }
        HashMap<String, Integer> occurrences = new HashMap<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.getJSONObject(i);
            long time = message.optLong("time", 0);
            String author = message.optString("senderKey", message.optString("sender", ""));
            String token = new JSONArray().put(time).put(message.optString("text", ""))
                    .put(author).toString();
            int occurrence = occurrences.containsKey(token) ? occurrences.get(token) + 1 : 1;
            occurrences.put(token, occurrence);
            JSONArray source = new JSONArray().put("message-v3")
                    .put(snapshot.optString("packageName", "")).put(snapshot.optString("user", ""))
                    .put(snapshot.optString("notificationKey", "")).put(token).put(occurrence);
            // No reliable timestamp means no safe identity across distinct Android publications.
            if (time <= 0) source.put(snapshot.getLong("postTime"));

            JSONObject event = new JSONObject(snapshot.toString());
            event.remove("messages");
            event.put("captureKind", "message");
            event.put("android.text", message.opt("text"));
            event.put("messageTime", time);
            event.put("messageSender", message.opt("sender"));
            event.put("messageSenderKey", message.opt("senderKey"));
            event.put("messageOccurrence", occurrence);
            event.put("notificationPostTime", snapshot.getLong("postTime"));
            event.put("postTime", time > 0 ? time : snapshot.getLong("postTime"));
            event.put("sourceKey", digest(source.toString()));
            event.put("eventId", UUID.randomUUID().toString());
            events.put(event);
        }
        return events;
    }

    /** A plain view can be redundant only when an actual queued message represents it. */
    static boolean represents(JSONObject message, JSONObject snapshot) {
        if (!"message".equals(message.optString("captureKind"))
                || !"snapshot".equals(snapshot.optString("captureKind", "snapshot"))) return false;
        for (String field : new String[]{"packageName", "user", "android.title", "android.text"}) {
            if (!message.optString(field, "").equals(snapshot.optString(field, ""))) return false;
        }
        long time = snapshot.optLong("notificationWhen", 0);
        if (time <= 0 || time != message.optLong("messageTime", 0)) return false;
        int number = snapshot.optInt("notificationNumber", 0);
        if (number <= 0 || message.optInt("notificationNumber", 0) < number) return false;
        String key = snapshot.optString("notificationKey", "");
        if (!key.isEmpty() && key.equals(message.optString("notificationKey", ""))) return true;
        String group = snapshot.optString("notificationGroup", "");
        return snapshot.optBoolean("isGroupSummary", false) && !group.isEmpty()
                && group.equals(message.optString("notificationGroup", ""));
    }

    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(Object value) {
        return value instanceof CharSequence ? value.toString() : null;
    }
}
