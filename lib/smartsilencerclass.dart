import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:shared_preferences/shared_preferences.dart';

class SmartSilencerController {
  static const MethodChannel _platform = MethodChannel('com.yourapp/foreground');
  final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin;

  SmartSilencerController(this.flutterLocalNotificationsPlugin) {
    _setupMethodChannel();
  }

  /// Initialize notification plugin
  static Future<void> initialize() async {
    const AndroidInitializationSettings initializationSettingsAndroid =
        AndroidInitializationSettings('@mipmap/ic_launcher');

    await FlutterLocalNotificationsPlugin().initialize(
      const InitializationSettings(android: initializationSettingsAndroid),
      onDidReceiveNotificationResponse: (NotificationResponse response) async {
        await handleNotificationAction(response.actionId ?? response.payload ?? '');
      },
    );
  }

  void _setupMethodChannel() {
    _platform.setMethodCallHandler((call) async {
      if (call.method == "startSilencerLogic") {
        final String mode = call.arguments;
        await _handleSilencerMode(mode);
      }
    });
  }

  Future<void> _handleSilencerMode(String mode) async {
    switch (mode) {
      case "auto":
        await _setDndMode(true);
        final prefs = await SharedPreferences.getInstance();
        prefs.setInt("skipCount", 0);
        break;
      case "gps":
        await _startSilencerService(); // Location tracking runs in Android service
        break;
      case "notification":
      default:
        await _showNotificationPrompt();
        break;
    }
  }

  static Future<void> handleNotificationAction(String action) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (action == 'yes' || action.contains('silencer_prompt_yes')) {
        await _setDndMode(true);
        prefs.setInt("skipCount", 0);
      } else if (action == 'no' || action.contains('silencer_prompt_no')) {
        prefs.setInt("skipCount", (prefs.getInt("skipCount") ?? 0) + 1);
      }
    } on PlatformException catch (e) {
      debugPrint("Notification action error: ${e.message}");
    }
  }

  Future<void> checkModeAndAct() async {
    final prefs = await SharedPreferences.getInstance();
    final selectedMode = prefs.getString("selectedMode") ?? "notification";

    try {
      await _platform.invokeMethod('startService', {'mode': selectedMode});
    } on PlatformException catch (e) {
      debugPrint("Failed to start foreground service: ${e.message}");
    }
  }

  Future<void> _showNotificationPrompt() async {
    const androidPlatformChannelSpecifics = AndroidNotificationDetails(
      'silencer_channel',
      'Prayer Silencer',
      importance: Importance.max,
      priority: Priority.high,
      actions: [
        AndroidNotificationAction('yes', 'Yes'),
        AndroidNotificationAction('no', 'No'),
      ],
    );

    await flutterLocalNotificationsPlugin.show(
      0,
      'Prayer Time',
      'Silence phone for prayer?',
      const NotificationDetails(android: androidPlatformChannelSpecifics),
      payload: 'silencer_prompt',
    );
  }

  Future<void> _startSilencerService() async {
    try {
      await _platform.invokeMethod('startService', {'mode': 'gps'});
    } on PlatformException catch (e) {
      debugPrint("Service start failed: ${e.message}");
    }
  }

  static Future<void> _setDndMode(bool enable) async {
    try {
      await _platform.invokeMethod('setDndMode', {'enable': enable});
    } on PlatformException catch (e) {
      debugPrint("DND mode error: ${e.message}");
    }
  }
}
