import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class WeeklyPreferencesScreen extends StatefulWidget {
  @override
  _WeeklyPreferencesScreenState createState() => _WeeklyPreferencesScreenState();
}

class _WeeklyPreferencesScreenState extends State<WeeklyPreferencesScreen> {
  final Map<String, Map<String, String>> preferences = {};
  final List<String> days = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
  final List<String> prayers = ['fajr', 'dhuhr', 'asr', 'maghrib', 'isha'];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadPreferences();
  }

  Future<void> _loadPreferences() async {
    try {
      final channel = MethodChannel('com.example.smartsilencerapp_fixed/alarms');
      final Map<dynamic, dynamic>? loadedPrefs = 
          await channel.invokeMethod('loadWeeklyPreferences');
      
      if (loadedPrefs != null) {
        setState(() {
          // Convert the loaded preferences to our format
          loadedPrefs.forEach((day, prayerPrefs) {
            preferences[day as String] = {};
            (prayerPrefs as Map<dynamic, dynamic>).forEach((prayer, pref) {
              preferences[day]![prayer as String] = pref as String;
            });
          });
          _isLoading = false;
        });
      } else {
        // Initialize with defaults if nothing loaded
        _initializeDefaults();
      }
    } catch (e) {
      print('Error loading preferences: $e');
      // Initialize with defaults on error
      _initializeDefaults();
    }
  }

  void _initializeDefaults() {
    setState(() {
      for (var day in days) {
        preferences[day] = {};
        for (var prayer in prayers) {
          preferences[day]![prayer] = 'DEFAULT';
        }
      }
      _isLoading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(title: Text('Weekly Prayer Preferences')),
        body: Center(child: CircularProgressIndicator()),
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text('Weekly Prayer Preferences')),
      body: ListView.builder(
        itemCount: days.length,
        itemBuilder: (context, dayIndex) {
          final day = days[dayIndex];
          return ExpansionTile(
            title: Text(day.toUpperCase()),
            children: prayers.map((prayer) {
              return ListTile(
                title: Text(prayer.toUpperCase()),
                trailing: DropdownButton<String>(
                  value: preferences[day]![prayer],
                  items: [
                    DropdownMenuItem(
                      value: 'DEFAULT',
                      child: Text('Default Mode'),
                    ),
                    DropdownMenuItem(
                      value: 'CERTAIN',
                      child: Text('Certainly Going'),
                    ),
                    DropdownMenuItem(
                      value: 'EXCLUDED',
                      child: Text('Excluded'),
                    ),
                  ],
                  onChanged: (value) {
                    setState(() {
                      preferences[day]![prayer] = value!;
                    });
                  },
                ),
              );
            }).toList(),
          );
        },
      ),
      floatingActionButton: FloatingActionButton(
        child: Icon(Icons.save),
        onPressed: () async {
          print('Saving preferences: $preferences');
          try {
            final channel = MethodChannel('com.example.smartsilencerapp_fixed/alarms');
            await channel.invokeMethod('saveWeeklyPreferences', {'preferences': preferences});
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('Preferences saved successfully!')),
            );
          } catch (e) {
            print('Error saving preferences: $e');
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('Failed to save preferences: $e')),
            );
          }
        },
      ),
    );
  }
}