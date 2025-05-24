import 'dart:async';
import 'package:flutter/material.dart';
import 'package:adhan/adhan.dart';
import 'package:geolocator/geolocator.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:hijri/hijri_calendar.dart'; // Add this package for Hijri date

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
  String silencerMode = "gps"; // Keep for internal use, no UI here

  static const platform = MethodChannel('com.yourapp/foreground');

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

  Future<void> _initLocationAndPrayerTimes() async {
    final prefs = await SharedPreferences.getInstance();
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

    if (coordinates != null) {
      _calculatePrayerTimes();
    } else {
      print("No location available for prayer times calculation.");
    }
  }

  Future<bool> _requestLocationPermission() async {
    LocationPermission permission = await Geolocator.checkPermission();

    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.deniedForever) {
      return false;
    }
    return permission == LocationPermission.whileInUse ||
        permission == LocationPermission.always;
  }

  Future<Position?> _getCurrentLocation() async {
    try {
      return await Geolocator.getCurrentPosition(
          desiredAccuracy: LocationAccuracy.high);
    } catch (e) {
      print("Error getting location: $e");
      return null;
    }
  }

  Future<void> _calculatePrayerTimes() async {
    if (coordinates == null) return;

    final params = CalculationMethod.muslim_world_league.getParameters();
    params.madhab = Madhab.shafi;

    final date = DateComponents.from(DateTime.now());
    prayerTimes = PrayerTimes(coordinates!, date, params);

    await _storePrayerTimesToPrefs(prayerTimes!);

    startNativeSilencerService(prayerTimes!, silencerMode);
    setState(() {}); // Refresh UI with new prayer times
  }

  Future<void> _storePrayerTimesToPrefs(PrayerTimes pt) async {
    final prefs = await SharedPreferences.getInstance();

    final prayers = [
      Prayer.fajr,
      Prayer.dhuhr,
      Prayer.asr,
      Prayer.maghrib,
      Prayer.isha
    ];

    Map<String, int> prayerTimesMap = {};
    for (final prayer in prayers) {
      final time = pt.timeForPrayer(prayer);
      if (time != null) {
        prayerTimesMap[prayer.name.toLowerCase()] = time.millisecondsSinceEpoch;
      }
    }

    // Store as JSON string or individual keys
    // Here, storing individual keys for simplicity
    for (final entry in prayerTimesMap.entries) {
      await prefs.setInt('prayerTime_${entry.key}', entry.value);
    }
  }

  Future<void> startNativeSilencerService(PrayerTimes pt, String mode) async {
    try {
      final prayers = [
        Prayer.fajr,
        Prayer.dhuhr,
        Prayer.asr,
        Prayer.maghrib,
        Prayer.isha
      ];

      Map<String, int> prayerTimesMap = {};
      for (final prayer in prayers) {
        final time = pt.timeForPrayer(prayer);
        if (time != null) {
          prayerTimesMap[prayer.name.toLowerCase()] = time.millisecondsSinceEpoch;
        }
      }

      await platform.invokeMethod('startService', {
        'mode': mode,
        'prayerTimes': prayerTimesMap,
      });
    } on PlatformException catch (e) {
      print("Failed to start native service: '${e.message}'.");
    }
  }

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
    return Prayer.fajr;
  }

  Duration _timeUntilNextPrayer() {
    final next = _nextPrayer();
    if (prayerTimes == null || currentTime == null || next == null) {
      return Duration.zero;
    }
    final nextTime = prayerTimes!.timeForPrayer(next);
    if (nextTime == null) return Duration.zero;
    return nextTime.difference(currentTime!);
  }

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
                // Display Hijri date instead of location
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
                              fontWeight:
                                  isNext ? FontWeight.bold : FontWeight.normal,
                              color: isNext ? Colors.green : null,
                            ),
                          ),
                          trailing: Text(_formatTime(time),
                              style: TextStyle(
                                  fontWeight:
                                      isNext ? FontWeight.bold : FontWeight.normal,
                                  color: isNext ? Colors.green : null)),
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
