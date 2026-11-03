// Keep the existing public import path compatible with notification_reader 1.1.1.
// ignore_for_file: file_names

class NotificationData {
  String? title;
  String? body;
  String? packageName;
  dynamic data;
  String? eventId;
  String? deliveryToken;
  int? postTime;
  int deliveryAttempts;

  NotificationData({
    this.title,
    this.body,
    this.packageName,
    this.data,
    this.eventId,
    this.deliveryToken,
    this.postTime,
    this.deliveryAttempts = 0,
  });
}
