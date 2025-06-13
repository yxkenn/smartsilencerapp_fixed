import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'prayer_times_page.dart';
import 'smart_silencer_screen.dart';
import 'smartsilencerclass.dart';
import 'reminder.dart';
import 'weekly_preferences_screen.dart';
import 'localization.dart';

final FlutterLocalNotificationsPlugin flutterLocalNotificationsPlugin =
    FlutterLocalNotificationsPlugin();

late SmartSilencerController silencerController;

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await _initializeApp();
  runApp(const SmartSilencerApp());
}

Future<void> _initializeApp() async {
  const AndroidInitializationSettings initializationSettingsAndroid =
      AndroidInitializationSettings('@mipmap/ic_launcher');

  await flutterLocalNotificationsPlugin.initialize(
    const InitializationSettings(android: initializationSettingsAndroid),
    onDidReceiveNotificationResponse: (NotificationResponse response) async {
      await SmartSilencerController.handleNotificationAction(
        response.actionId ?? response.payload ?? '',
      );
    },
  );

  silencerController = SmartSilencerController(flutterLocalNotificationsPlugin);
  await NativeAlarms.schedulePrayerAlarms();
}



class SmartSilencerApp extends StatefulWidget {
  const SmartSilencerApp({super.key});

  static void setLocale(BuildContext context, Locale newLocale) {
    _SmartSilencerAppState? state =
        context.findAncestorStateOfType<_SmartSilencerAppState>();
    state?.setLocale(newLocale);
  }

  @override
  State<SmartSilencerApp> createState() => _SmartSilencerAppState();
}

class _SmartSilencerAppState extends State<SmartSilencerApp> {
  Locale? _locale;

  @override
  void initState() {
    super.initState();
    _loadLocale();
  }

  Future<void> _loadLocale() async {
    final prefs = await SharedPreferences.getInstance();
    final langCode = prefs.getString('language');
    if (langCode != null) {
      setState(() {
        _locale = Locale(langCode);
      });
    }
  }

  void setLocale(Locale locale) {
    setState(() {
      _locale = locale;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Smart Silencer',
      debugShowCheckedModeBanner: false,
      locale: _locale,
      supportedLocales: AppLocalizations.supportedLocales,
      localizationsDelegates: const [
        AppLocalizationsDelegate(),
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      builder: (context, child) {
        final locale = Localizations.localeOf(context);
        return Directionality(
          textDirection:
              locale.languageCode == 'ar' ? TextDirection.rtl : TextDirection.ltr,
          child: child!,
        );
      },
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
        useMaterial3: true,
        textTheme: TextTheme(
          bodyLarge: TextStyle(color: Colors.black.withOpacity(0.7)),
          bodyMedium: TextStyle(color: Colors.black.withOpacity(0.7)),
          titleLarge: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
        ),
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.dark(
          primary: Colors.teal[200]!,
          secondary: Colors.teal[200]!,
        ),
        useMaterial3: true,
        textTheme: TextTheme(
          bodyLarge: TextStyle(color: Colors.white.withOpacity(0.9)),
          bodyMedium: TextStyle(color: Colors.white.withOpacity(0.8)),
          titleLarge: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
        ),
      ),
      themeMode: ThemeMode.system,
      home: const MainNavigation(),
    );
  }
}


extension on Future<SharedPreferences> {
  getString(String s) {}
}

class MainNavigation extends StatefulWidget {
  const MainNavigation({super.key});

  @override
  State<MainNavigation> createState() => _MainNavigationState();
}

class _MainNavigationState extends State<MainNavigation> {
  int _selectedIndex = 0;

  final List<Widget> _widgetOptions = [
    PrayerTimesPage(),
    const SmartSilencerScreen(prayerTimes: {}),
    ReminderSettingsScreen(),
    WeeklyPreferencesScreen(),
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
