import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'localization.dart'; // <-- Make sure this is imported

class WeeklyPreferencesScreen extends StatefulWidget {
  @override
  _WeeklyPreferencesScreenState createState() => _WeeklyPreferencesScreenState();
}

class _WeeklyPreferencesScreenState extends State<WeeklyPreferencesScreen> {
  final Map<String, Map<String, String>> preferences = {};
  final List<String> days = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
  final List<String> prayers = ['fajr', 'dhuhr', 'asr', 'maghrib', 'isha'];
  bool _isLoading = true;
  int? _expandedDayIndex;

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
          loadedPrefs.forEach((day, prayerPrefs) {
            preferences[day as String] = {};
            (prayerPrefs as Map<dynamic, dynamic>).forEach((prayer, pref) {
              preferences[day]![prayer as String] = pref as String;
            });
          });
          _isLoading = false;
        });
      } else {
        _initializeDefaults();
      }
    } catch (e) {
      print('Error loading preferences: $e');
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
    final tr = AppLocalizations.translate;
    final tealColor = const Color.fromARGB(255, 13, 41, 40);
    final lightTealColor = const Color.fromARGB(255, 38, 65, 71);
    final goldColor = const Color.fromARGB(255, 120, 213, 230);
    final isDarkMode = Theme.of(context).brightness == Brightness.dark;

    if (_isLoading) {
      return Scaffold(
        appBar: AppBar(
          title: Text(
            tr(context, 'weeklyPrayerPreferences'),
            style: const TextStyle(color: Colors.white),
          ),
          backgroundColor: tealColor,
        ),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    return Scaffold(
      appBar: AppBar(
        title: Text(
          tr(context, 'weeklyPrayerPreferences'),
          style: const TextStyle(color: Colors.white),
        ),
        backgroundColor: tealColor,
      ),
      body: ListView.builder(
        itemCount: days.length,
        itemBuilder: (context, dayIndex) {
          final day = days[dayIndex];
          final isExpanded = _expandedDayIndex == dayIndex;

          return Container(
            margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              gradient: LinearGradient(
                colors: [lightTealColor, tealColor],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
            ),
            child: Theme(
              data: Theme.of(context).copyWith(
                dividerColor: Colors.transparent,
                unselectedWidgetColor: Colors.white70,
              ),
              child: ExpansionTile(
                key: isExpanded ? UniqueKey() : Key(day),
                initiallyExpanded: isExpanded,
                onExpansionChanged: (expanded) {
                  setState(() {
                    _expandedDayIndex = expanded ? dayIndex : null;
                  });
                },

                title: Text(
                  tr(context, day),
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                children: prayers.map((prayer) {
                  return Container(
                    color: Colors.black,
                    child: ListTile(
                      title: Text(
                        tr(context, prayer),
                        style: const TextStyle(color: Colors.white),
                      ),
                      trailing: DropdownButton<String>(
                        dropdownColor: Colors.grey[900],
                        value: preferences[day]![prayer],
                        underline: const SizedBox(),
                        iconEnabledColor: Colors.white,
                        style: const TextStyle(color: Colors.white),
                        items: [
                          DropdownMenuItem(
                            value: 'DEFAULT',
                            child: Text(tr(context, 'defaultMode')),
                          ),
                          DropdownMenuItem(
                            value: 'CERTAIN',
                            child: Text(tr(context, 'certainlyGoing')),
                          ),
                          DropdownMenuItem(
                            value: 'EXCLUDED',
                            child: Text(tr(context, 'excluded')),
                          ),
                        ],
                        onChanged: (value) {
                          setState(() {
                            preferences[day]![prayer] = value!;
                          });
                        },
                      ),
                    ),
                  );
                }).toList(),
              ),
            ),
          );
        },
      ),
      floatingActionButton: FloatingActionButton(
        backgroundColor: goldColor,
        child: const Icon(Icons.save, color: Colors.black),
        onPressed: () async {
          try {
            final channel = MethodChannel('com.example.smartsilencerapp_fixed/alarms');
            await channel.invokeMethod('saveWeeklyPreferences', {'preferences': preferences});
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text(tr(context, 'autoSaveNote'))),
            );
          } catch (e) {
            print('Error saving preferences: $e');
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(content: Text('${tr(context, 'failedToSave')} $e')),
            );
          }
        },
      ),
    );
  }

}
