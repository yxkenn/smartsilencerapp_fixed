import 'dart:async';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:adhan/adhan.dart';
import 'package:geolocator/geolocator.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:hijri/hijri_calendar.dart';
import 'package:permission_handler/permission_handler.dart';
import 'localization.dart';
import 'main.dart';


class PrayerTimesPage extends StatefulWidget {
  @override
  _PrayerTimesPageState createState() => _PrayerTimesPageState();
}

class _PrayerTimesPageState extends State<PrayerTimesPage> with WidgetsBindingObserver {
  // ... your existing state variables ...
  PrayerTimes? prayerTimes;
  DateTime? currentTime;
  Timer? timer;
  Coordinates? coordinates;
  bool locationPermissionGranted = false;

  static const platform = MethodChannel('com.example.smartsilencerapp_fixed/foreground');
  static const reschedulerChannel = MethodChannel('com.example.smartsilencerapp_fixed/rescheduler');
  String silencerMode = "gps"; // default fallback

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    
    // Add this to handle initial language sync
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      final prefs = await SharedPreferences.getInstance();
      final savedLanguage = prefs.getString('language');
      if (savedLanguage != null && mounted) {
        final locale = Locale(savedLanguage);
        if (Localizations.localeOf(context) != locale) {
          await _syncLanguageAndAlarms(locale);
        }
      }
      
      // Then proceed with normal initialization
      _initLocationAndPrayerTimes();
    });

    // Rest of your existing initState code...
    timer = Timer.periodic(Duration(seconds: 1), (_) {
      setState(() {
        currentTime = DateTime.now();
      });
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this); 
    timer?.cancel();
    super.dispose();
  }

  // -------------------- Initialization --------------------

  Future<void> _initLocationAndPrayerTimes() async {
    final prefs = await SharedPreferences.getInstance();
    silencerMode = prefs.getString('silencer_mode') ?? 'gps';

    final lat = prefs.getDouble('latitude');
    final lon = prefs.getDouble('longitude');

    if (lat != null && lon != null) {
      coordinates = Coordinates(lat, lon);
      locationPermissionGranted = true;
    } else {
      locationPermissionGranted = await _requestLocationPermission();
      if (locationPermissionGranted) {
        final pos = await _getCurrentLocation();
        if (pos != null) {
          coordinates = Coordinates(pos.latitude, pos.longitude);
          await prefs.setDouble('latitude', pos.latitude);
          await prefs.setDouble('longitude', pos.longitude);
        }
      }
    }

    if (prayerTimes != null && _loadPrayerTimesFromPrefs(prefs)) {
      setState(() {});
      await schedulePrayerAlarms({
        'fajr': prayerTimes!.fajr!,
        'dhuhr': prayerTimes!.dhuhr!,
        'asr': prayerTimes!.asr!,
        'maghrib': prayerTimes!.maghrib!,
        'isha': prayerTimes!.isha!,
      });
      final locale = Localizations.localeOf(context).languageCode;
      await startNativeSilencerService(prayerTimes!, silencerMode, locale);
    } else {
      await _calculatePrayerTimes();
    }
  }



  Future<bool> _requestLocationPermission() async {
    LocationPermission permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.deniedForever) return false;
    return permission == LocationPermission.whileInUse || permission == LocationPermission.always;
  }

  Future<Position?> _getCurrentLocation() async {
    try {
      return await Geolocator.getCurrentPosition(desiredAccuracy: LocationAccuracy.high);
    } catch (e) {
      print("Error getting location: $e");
      return null;
    }
  }
  
  Future<void> schedulePrayerAlarms(Map<String, DateTime> prayerTimes) async {
    try {
      final timesInMillis = prayerTimes.map(
        (key, value) => MapEntry(key, value.millisecondsSinceEpoch),
      );

      // Get current locale
      final locale = Localizations.localeOf(context).languageCode;
      
      // Save locale to shared prefs
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('current_locale', locale);

      final result = await MethodChannel('com.example.smartsilencerapp_fixed/alarms')
          .invokeMethod('schedulePrayerAlarms', {
        'prayerTimes': timesInMillis,
        'mode': silencerMode,
        'locale': locale,
      });
      print('✅ Alarms scheduled successfully with locale: $locale');
    } on PlatformException catch (e) {
      print('❌ Failed to schedule alarms: ${e.message}');
    }
  }


  Future<void> _syncLanguageAndAlarms(Locale newLocale) async {
    print('Syncing language to ${newLocale.languageCode}');
    final prefs = await SharedPreferences.getInstance();
    
    // Save the new language preference
    await prefs.setString('language', newLocale.languageCode);
    
    // Update app locale
    if (mounted) {
      SmartSilencerApp.setLocale(context, newLocale);
      await Future.delayed(const Duration(milliseconds: 50)); // Allow rebuild
      
      // Verify locale was applied
      final currentLocale = Localizations.localeOf(context).languageCode;
      print('Current locale after sync: $currentLocale');
      
      if (currentLocale == newLocale.languageCode) {
        // Reschedule alarms with new locale
        final currentMode = prefs.getString('silencer_mode') ?? 'gps';
        await saveModeAndReschedule(currentMode);
      } else {
        print('Locale mismatch! Expected ${newLocale.languageCode}, got $currentLocale');
      }
    }
  }


  // -------------------- Prayer Time Logic --------------------


  Future<void> _calculatePrayerTimes({bool forceUpdate = false}) async {
    if (coordinates == null) return;

    final params = CalculationMethod.muslim_world_league.getParameters();
    params.madhab = Madhab.shafi;

    final now = DateTime.now();
    final date = DateComponents.from(now);

    setState(() {
      prayerTimes = PrayerTimes(coordinates!, date, params);
    });

    await _storePrayerTimesToPrefs(prayerTimes!);
    
    // Convert to map of milliseconds since epoch
    final prayerTimesMap = {
      'fajr': prayerTimes!.fajr.millisecondsSinceEpoch,
      'dhuhr': prayerTimes!.dhuhr.millisecondsSinceEpoch,
      'asr': prayerTimes!.asr.millisecondsSinceEpoch,
      'maghrib': prayerTimes!.maghrib.millisecondsSinceEpoch,
      'isha': prayerTimes!.isha.millisecondsSinceEpoch,
    };

    // Save to native side
    final prefs = await SharedPreferences.getInstance();
    final mode = prefs.getString('silencer_mode') ?? 'gps';
    final locale = Localizations.localeOf(context).languageCode;
    
    try {
      await MethodChannel('com.example.smartsilencerapp_fixed/alarms').invokeMethod(
        'savePrayerTimesToNative',
        {
          'prayerTimes': prayerTimesMap,
          'mode': mode,
          'locale': locale,
        },
      );
    } catch (e) {
      print('Error saving times to native: $e');
    }

    await startNativeSilencerService(prayerTimes!, mode, locale);
  }
  Future<void> _storePrayerTimesToPrefs(PrayerTimes pt) async {
    final prefs = await SharedPreferences.getInstance();
    final now = DateTime.now().millisecondsSinceEpoch;

    final prayers = {
      'fajr': pt.fajr,
      'dhuhr': pt.dhuhr,
      'asr': pt.asr,
      'maghrib': pt.maghrib,
      'isha': pt.isha,
    };

    for (final entry in prayers.entries) {
      if (entry.value != null) {
        final timestamp = entry.value!.millisecondsSinceEpoch;
        if (timestamp > now - 86400000 && timestamp < now + 86400000) {
          await prefs.setInt('prayerTime_${entry.key}', timestamp);
        }
      }
    }

    await prefs.setString('prayerTimesDate', DateTime.now().toIso8601String());
  }

  bool _loadPrayerTimesFromPrefs(SharedPreferences prefs) {
    final prayers = <Prayer, DateTime>{};
    final storedDateStr = prefs.getString('prayerTimesDate');

    if (storedDateStr == null) return false;

    final storedDate = DateTime.tryParse(storedDateStr);
    final today = DateTime.now();

    if (storedDate == null ||
        storedDate.year != today.year ||
        storedDate.month != today.month ||
        storedDate.day != today.day) {
      return false;
    }

    final keys = {
      Prayer.fajr: 'prayerTime_fajr',
      Prayer.dhuhr: 'prayerTime_dhuhr',
      Prayer.asr: 'prayerTime_asr',
      Prayer.maghrib: 'prayerTime_maghrib',
      Prayer.isha: 'prayerTime_isha',
    };

    for (var entry in keys.entries) {
      final ts = prefs.getInt(entry.value);
      if (ts == null) return false;
      prayers[entry.key] = DateTime.fromMillisecondsSinceEpoch(ts);
    }

    if (coordinates != null) {
      final params = CalculationMethod.muslim_world_league.getParameters();
      params.madhab = Madhab.shafi;

      final date = DateComponents.from(today);
      prayerTimes = PrayerTimes(coordinates!, date, params);

      

      return true;
    }

    return false;
  }

  Future<void> startServiceWithPermissions(String mode, PrayerTimes pt) async {
    final location = await Permission.location.request();
    final notification = await Permission.notification.request();

    if (location.isGranted && notification.isGranted) {
      final locale = Localizations.localeOf(context).languageCode;
      await platform.invokeMethod('startService', {
        'mode': mode,
        'prayerTimes': {
          'fajr': pt.fajr.millisecondsSinceEpoch,
          'dhuhr': pt.dhuhr.millisecondsSinceEpoch,
          'asr': pt.asr.millisecondsSinceEpoch,
          'maghrib': pt.maghrib.millisecondsSinceEpoch,
          'isha': pt.isha.millisecondsSinceEpoch,
        },
        'locale': locale,
      });
    } else {
      print("Permissions denied.");
      openAppSettings(); // Optional fallback
    }
  }

  Future<void> startNativeSilencerService(PrayerTimes pt, String mode, String locale) async {
    try {
      final prayers = {
        'fajr': pt.fajr,
        'dhuhr': pt.dhuhr,
        'asr': pt.asr,
        'maghrib': pt.maghrib,
        'isha': pt.isha,
      };

      final prayerTimesMap = Map<String, int>.fromEntries(
        prayers.entries.where((e) => e.value != null).map(
              (e) => MapEntry(e.key, e.value!.millisecondsSinceEpoch),
            ),
      );

      print('Starting service with locale: $locale'); // Debug log

      await platform.invokeMethod('startService', {
        'mode': mode,
        'prayerTimes': prayerTimesMap,
        'locale': locale,
      });
    } on PlatformException catch (e) {
      print("Native call failed: ${e.message}\n${e.details}");
    }
  }

  Future<void> saveModeAndReschedule(String mode) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('silencer_mode', mode);
    
    // Get current locale
    final locale = Localizations.localeOf(context).languageCode;
    
    if (mounted) {
      setState(() {
        silencerMode = mode;
      });
    }

    try {
      final Map<String, int> prayerTimesMap = {};
      final keys = ['fajr', 'dhuhr', 'asr', 'maghrib', 'isha'];
      for (var key in keys) {
        final ts = prefs.getInt('prayerTime_$key');
        if (ts != null) {
          prayerTimesMap[key] = ts;
        }
      }

      if (prayerTimesMap.length != 5) throw Exception('Incomplete cached prayer times');

      const MethodChannel alarmChannel = MethodChannel('com.example.smartsilencerapp_fixed/alarms');

      // Save to native with locale
      await alarmChannel.invokeMethod('savePrayerTimesToNative', {
        'prayerTimes': prayerTimesMap,
        'mode': mode,
        'locale': locale,
      });

      // Schedule alarms with locale
      await alarmChannel.invokeMethod('schedulePrayerAlarms', {
        'prayerTimes': prayerTimesMap,
        'mode': mode,
        'locale': locale,
      });

      print('✅ Alarms rescheduled with updated mode: $mode and locale: $locale');
    } catch (e) {
      print('❌ Failed to reschedule alarms: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text("Failed to fetch cached prayer times.")),
        );
      }
    }
  }


  // -------------------- UI Helpers --------------------

  String _formatTime(DateTime? dt) {
    if (dt == null) return '--:--';
    return '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }

  Prayer? _nextPrayer() {
    if (prayerTimes == null || currentTime == null) return null;

    final now = currentTime!;
    for (final prayer in [
      Prayer.fajr,
      Prayer.dhuhr,
      Prayer.asr,
      Prayer.maghrib,
      Prayer.isha
    ]) {
      final time = prayerTimes!.timeForPrayer(prayer);
      if (time != null && now.isBefore(time)) {
        return prayer;
      }
    }

    return Prayer.fajr; // fallback
  }

  Duration _timeUntilNextPrayer() {
    final next = _nextPrayer();
    if (prayerTimes == null || currentTime == null || next == null) {
      return Duration.zero;
    }

    final nextTime = prayerTimes!.timeForPrayer(next);
    return nextTime?.difference(currentTime!) ?? Duration.zero;
  }

  // -------------------- UI --------------------

  @override
  Widget build(BuildContext context) {
    final prayers = [
      Prayer.fajr,
      Prayer.dhuhr,
      Prayer.asr,
      Prayer.maghrib,
      Prayer.isha
    ];

    final hijriDate = HijriCalendar.now();
    final tealColor = const Color.fromARGB(255, 13, 41, 40);
    final lightTealColor = const Color.fromARGB(255, 38, 65, 71);
    final nextPrayerColor = const Color.fromARGB(255, 255, 215, 0); // Gold color for next prayer
    
    // Determine if dark mode is active
    final bool isDarkMode = Theme.of(context).brightness == Brightness.dark;
    final Color defaultTextColor = isDarkMode ? Colors.white : Colors.black87;

    return Scaffold(
      appBar: AppBar(
        title: Text(
          AppLocalizations.translate(context, 'prayerTimes'),
          style: TextStyle(color: Colors.white),
        ),
        flexibleSpace: Container(
          decoration: BoxDecoration(
            gradient: LinearGradient(
              colors: [lightTealColor, tealColor],
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
            ),
          ),
        ),
        actions: [
                  DropdownButton<Locale>(
                    underline: SizedBox(),
                    icon: Icon(Icons.language, color: Colors.white),
                    value: Localizations.localeOf(context),
                    items: AppLocalizations.supportedLocales.map((Locale locale) {
                      return DropdownMenuItem<Locale>(
                        value: locale,
                        child: Text(
                          locale.languageCode == 'en' ? 'English' : 'العربية',
                          style: TextStyle(color: Colors.white),
                        ),
                      );
                    }).toList(),
                        onChanged: (Locale? newLocale) async {
                          if (newLocale != null) {
                            await _syncLanguageAndAlarms(newLocale);
                          }
                        },
                  ),
        ],
      ),
      body: coordinates == null
          ? Center(
              child: Text(
                AppLocalizations.translate(context, 'waitingForLocation'),
                style: TextStyle(color: defaultTextColor),
              ),
            )
          : Column(
              children: [
                Container(
                  padding: EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [lightTealColor, lightTealColor.withOpacity(0.8)],
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                  ),
                  child: Center(
                    child: Text(
                      "${AppLocalizations.translate(context, 'hijriDate')}: ${hijriDate.hYear}-${hijriDate.hMonth}-${hijriDate.hDay}",
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ),
                if (prayerTimes == null)
                  CircularProgressIndicator()
                else
                  Expanded(
                    child: ListView.builder(
                      itemCount: prayers.length,
                      itemBuilder: (context, index) {
                        final prayer = prayers[index];
                        final time = prayerTimes!.timeForPrayer(prayer);
                        final isNext = prayer == _nextPrayer();

                        return Container(
                          decoration: BoxDecoration(
                            border: Border(bottom: BorderSide(color: tealColor.withOpacity(0.2))),
                          ),
                          child: ListTile(
                            title: Text(
                              AppLocalizations.translate(context, prayer.name),
                              style: TextStyle(
                                fontWeight: isNext ? FontWeight.bold : FontWeight.normal,
                                color: isNext ? nextPrayerColor : defaultTextColor,
                              ),
                            ),
                            trailing: Text(
                              _formatTime(time),
                              style: TextStyle(
                                fontWeight: isNext ? FontWeight.bold : FontWeight.normal,
                                color: isNext ? nextPrayerColor : defaultTextColor,
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                Container(
                  padding: EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      colors: [lightTealColor, lightTealColor.withOpacity(0.8)],
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                    ),
                  ),
                  child: Center(
                    child: Text(
                      "${AppLocalizations.translate(context, 'nextPrayerIn')}: "
                      "${_timeUntilNextPrayer().inHours.toString().padLeft(2, '0')}:"
                      "${(_timeUntilNextPrayer().inMinutes % 60).toString().padLeft(2, '0')}:"
                      "${(_timeUntilNextPrayer().inSeconds % 60).toString().padLeft(2, '0')}",
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ),
              ],
            ),
    );
  }

}



class NativeAlarms {
  static const platform = MethodChannel('com.example.smartsilencerapp_fixed/alarms');

  static Future<void> schedulePrayerAlarms() async {
    try {
      final locale = PlatformDispatcher.instance.locale.languageCode;
      await platform.invokeMethod('schedulePrayerAlarms', {'locale': locale});
    } on PlatformException catch (e) {
      print("Failed to schedule alarms: '${e.message}'.");
    }
  }
}


