import 'dart:async';
import 'package:flutter/material.dart';
import 'package:adhan/adhan.dart';
import 'package:geolocator/geolocator.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:hijri/hijri_calendar.dart';


class PrayerTimesPage extends StatefulWidget {
  @override
  _PrayerTimesPageState createState() => _PrayerTimesPageState();
}

class _PrayerTimesPageState extends State<PrayerTimesPage> {
  PrayerTimes? prayerTimes;
  DateTime? currentTime;
  Timer? timer;
  Coordinates? coordinates;
  bool locationPermissionGranted = false;

  static const platform = MethodChannel('com.example.smartsilencerapp_fixed/foreground');
  String silencerMode = "gps"; // default fallback

  @override
  void initState() {
    super.initState();
    _initLocationAndPrayerTimes();

    timer = Timer.periodic(Duration(seconds: 1), (_) {
      setState(() {
        currentTime = DateTime.now();
      });
    });
  }

  @override
  void dispose() {
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

  if (_loadPrayerTimesFromPrefs(prefs)) {
    setState(() {});
    await schedulePrayerAlarms({
      'fajr': prayerTimes!.fajr,
      'dhuhr': prayerTimes!.dhuhr,
      'asr': prayerTimes!.asr,
      'maghrib': prayerTimes!.maghrib,
      'isha': prayerTimes!.isha,
    });
    await startNativeSilencerService(prayerTimes!, silencerMode);
    
    
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

      // Include the mode when scheduling alarms
      final result = await MethodChannel('com.example.smartsilencerapp_fixed/alarms')
          .invokeMethod('schedulePrayerAlarms', {
            'prayerTimes': timesInMillis,
            'mode': silencerMode, // <-- use loaded mode
          });

      print('✅ Alarms scheduled successfully: $result');
    } on PlatformException catch (e) {
      print('❌ Failed to schedule alarms: ${e.message}');
    }
  }


  // -------------------- Prayer Time Logic --------------------

  Future<void> _calculatePrayerTimes() async {
    if (coordinates == null) return;

    final params = CalculationMethod.muslim_world_league.getParameters();
    params.madhab = Madhab.shafi;

    final now = DateTime.now();
    final date = DateComponents.from(now);

    prayerTimes = PrayerTimes(coordinates!, date, params);

    await _storePrayerTimesToPrefs(prayerTimes!);
    await startNativeSilencerService(prayerTimes!, silencerMode);
    setState(() {});
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

  Future<void> startNativeSilencerService(PrayerTimes pt, String mode) async {
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

      await platform.invokeMethod('startService', {
        'mode': mode,
        'prayerTimes': prayerTimesMap,
      });
    } on PlatformException catch (e) {
      print("Native call failed: ${e.message}\n${e.details}");
    }
  }


  Future<void> saveModeAndReschedule(String mode) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('silencerMode', mode); // Save mode locally
    setState(() {
      silencerMode = mode;
    });

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

      // Save to native
      await alarmChannel.invokeMethod('savePrayerTimesToNative', {
        'prayerTimes': prayerTimesMap,
      });

      // Schedule alarms
      await alarmChannel.invokeMethod('schedulePrayerAlarms', {
        'prayerTimes': prayerTimesMap,
        'mode': mode,
      });

      print('✅ Alarms rescheduled with updated mode: $mode');
    } catch (e) {
      print('❌ Failed to reschedule alarms: $e');
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Failed to fetch cached prayer times.")),
      );
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

    return Scaffold(
      appBar: AppBar(title: Text("Prayer Times")),
      body: coordinates == null
          ? Center(child: Text("Waiting for location..."))
          : Column(
              children: [
                SizedBox(height: 16),
                Text(
                  "Hijri Date: ${hijriDate.hYear}-${hijriDate.hMonth}-${hijriDate.hDay}",
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                SizedBox(height: 16),
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

                        return ListTile(
                          title: Text(
                            prayer.name.toUpperCase(),
                            style: TextStyle(
                              fontWeight: isNext ? FontWeight.bold : FontWeight.normal,
                              color: isNext ? Colors.green : null,
                            ),
                          ),
                          trailing: Text(
                            _formatTime(time),
                            style: TextStyle(
                              fontWeight: isNext ? FontWeight.bold : FontWeight.normal,
                              color: isNext ? Colors.green : null,
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                Divider(),
                Text(
                  "Next Prayer in: ${_timeUntilNextPrayer().inHours.toString().padLeft(2, '0')}:${(_timeUntilNextPrayer().inMinutes % 60).toString().padLeft(2, '0')}:${(_timeUntilNextPrayer().inSeconds % 60).toString().padLeft(2, '0')}",
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                SizedBox(height: 16),
              ],
            ),
    );
  }
}

class NativeAlarms {
  static const platform = MethodChannel('com.example.smartsilencerapp_fixed/alarms');

  static Future<void> schedulePrayerAlarms() async {
    try {
      await platform.invokeMethod('schedulePrayerAlarms');
    } on PlatformException catch (e) {
      print("Failed to schedule alarms: '${e.message}'.");
    }
  }
}


