import 'package:flutter/material.dart';
import 'prayer_times_page.dart';
import 'smart_silencer_screen.dart';

import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'smartsilencerclass.dart';
import 'reminder.dart';

final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin =
    FlutterLocalNotificationsPlugin();

late SmartSilencerController silencerController;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await _initializeApp();

  runApp(const SmartSilencerApp());
}

Future<void> _initializeApp() async {
  // Initialize notifications (no Workmanager here)
  const AndroidInitializationSettings initializationSettingsAndroid =
      AndroidInitializationSettings('@mipmap/ic_launcher');
  
  await flutterLocalNotificationsPlugin.initialize(
    const InitializationSettings(android: initializationSettingsAndroid),
    onDidReceiveNotificationResponse: (NotificationResponse response) async {
      await SmartSilencerController.handleNotificationAction(
          response.actionId ?? response.payload ?? '');
    },
  );

  // Initialize your silencer controller
  silencerController = SmartSilencerController(flutterLocalNotificationsPlugin);
  await NativeAlarms.schedulePrayerAlarms();

}

class SmartSilencerApp extends StatelessWidget {
  const SmartSilencerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Smart Silencer',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
        useMaterial3: true,
        textTheme: TextTheme(
          bodyLarge: TextStyle(color: Colors.black.withOpacity(0.7)),
          bodyMedium: TextStyle(color: Colors.black.withOpacity(0.7)),
          titleLarge: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
        ),
      ),
      home: const MainNavigation(),
    );
  }
}

class MainNavigation extends StatefulWidget {
  const MainNavigation({super.key});

  @override
  State<MainNavigation> createState() => _MainNavigationState();
}

class _MainNavigationState extends State<MainNavigation> {
  int _selectedIndex = 0;

  final List<Widget> _widgetOptions = <Widget>[
    PrayerTimesPage(),
    const SmartSilencerScreen(prayerTimes: {},),
    ReminderSettingsScreen(),

    const Center(child: Text('Settings Screen Placeholder')),
  ];

  void _onItemTapped(int index) {
    setState(() {
      _selectedIndex = index;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _widgetOptions[_selectedIndex],
      bottomNavigationBar: BottomNavigationBar(
        currentIndex: _selectedIndex,
        onTap: _onItemTapped,
        selectedItemColor: Colors.teal,
        unselectedItemColor: Colors.grey,
        showUnselectedLabels: true,
        items: const [
          BottomNavigationBarItem(
              icon: Icon(Icons.access_time), label: 'Prayer Times'),
          BottomNavigationBarItem(icon: Icon(Icons.volume_off), label: 'Silencer'),
          BottomNavigationBarItem(icon: Icon(Icons.notifications), label: 'Reminder'),
          BottomNavigationBarItem(icon: Icon(Icons.settings), label: 'Settings'),
        ],
      ),
    );
  }
}
