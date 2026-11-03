import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:notification_reader/NotificationData.dart';
export './NotificationData.dart';

class NotificationReader {
  static const MethodChannel _channel = MethodChannel('notification_reader');

  static Future<String?> get platformVersion =>
      _channel.invokeMethod<String>('getPlatformVersion');

  static Future<void> get openNotificationReaderSettings async {
    await _channel.invokeMethod('openNotificationReaderSettings');
  }

  /// Legacy latest snapshot. Reliable consumers must claim and acknowledge.
  static Future<NotificationData> onNotificationRecieve() async {
    final data = await _channel.invokeMethod<String>('onNotificationRecieve');
    return data == null ? NotificationData() : _decode(jsonDecode(data));
  }

  static Future<List<NotificationData>> readPendingNotifications(
      {int limit = 50}) async {
    final data = await _channel
        .invokeMethod<String>('readPendingNotifications', {'limit': limit});
    if (data == null) return [];
    return (jsonDecode(data) as List).map(_decode).toList();
  }

  static Future<NotificationData?> claimNotification(
      String eventId, String phone1, String phone2) async {
    final data = await _channel.invokeMethod<String>('claimNotification', {
      'eventId': eventId,
      'phone1': phone1,
      'phone2': phone2,
    });
    return data == null ? null : _decode(jsonDecode(data));
  }

  static Future<bool> acknowledgeNotification(
      NotificationData notification) async {
    return await _channel.invokeMethod<bool>('acknowledgeNotification', {
          'eventId': notification.eventId,
          'deliveryToken': notification.deliveryToken,
        }) ??
        false;
  }

  static Future<bool> deferNotification(
    NotificationData notification, {
    required String error,
    required DateTime retryAt,
    bool permanent = false,
  }) async {
    return await _channel.invokeMethod<bool>('deferNotification', {
          'eventId': notification.eventId,
          'deliveryToken': notification.deliveryToken,
          'error': error,
          'retryAt': retryAt.millisecondsSinceEpoch,
          'permanent': permanent,
        }) ??
        false;
  }

  static Future<int> retryFailedNotifications() async =>
      await _channel.invokeMethod<int>('retryFailedNotifications') ?? 0;

  static Future<Map<String, dynamic>> getNotificationQueueStatus() async {
    final data =
        await _channel.invokeMethod<String>('getNotificationQueueStatus');
    return data == null
        ? {}
        : Map<String, dynamic>.from(jsonDecode(data) as Map);
  }

  static Future<String?> get getJsonData =>
      _channel.invokeMethod<String>('onNotificationRecieve');

  static NotificationData _decode(dynamic value) {
    final data = Map<String, dynamic>.from(value as Map);
    String? body =
        _text(data['android.bigText']) ?? _text(data['android.text']);
    final lines = data['android.textLines'];
    if (body == null && lines is List) {
      body = _text(lines.map(_text).whereType<String>().join('\n'));
    }
    final messages = data['android.messages'];
    if (body == null && messages is List) {
      body = _text(messages
          .whereType<Map>()
          .map((message) => _text(message['text']))
          .whereType<String>()
          .join('\n'));
    }
    return NotificationData(
      title: _text(data['android.title']),
      body: body,
      packageName: _text(data['packageName']),
      data: data,
      eventId: _text(data['eventId']),
      deliveryToken: _text(data['deliveryToken']),
      postTime: (data['postTime'] as num?)?.toInt(),
      deliveryAttempts: (data['deliveryAttempts'] as num?)?.toInt() ?? 0,
    );
  }

  static String? _text(dynamic value) =>
      value is String && value.isNotEmpty ? value : null;
}
