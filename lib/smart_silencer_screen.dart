import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'localization.dart';

const MethodChannel _alarmChannel = MethodChannel('com.example.smartsilencerapp_fixed/alarms');

class SmartSilencerScreen extends StatefulWidget {
  final Map<String, int> prayerTimes;

  const SmartSilencerScreen({super.key, required this.prayerTimes});

  @override
  State<SmartSilencerScreen> createState() => _SmartSilencerScreenState();
}

class _SmartSilencerScreenState extends State<SmartSilencerScreen> {
  final _platform = const MethodChannel('com.example.smartsilencerapp_fixed/foreground');
  bool _isToggleOn = false;
  String _currentMode = 'notification';
  late Map<String, int> _lastPrayerTimes;

  @override
  void initState() {
    super.initState();
    _lastPrayerTimes = widget.prayerTimes;
    _loadSettings();
     _loadPrayerTimes();
  }

    Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    final locale = getCurrentLocale(); // Get current locale
    
    // Load or default to 'notification'
    final mode = prefs.getString('silencer_mode') ?? 'notification';
    final enabled = prefs.getBool('silencer_enabled') ?? false;

    if (!prefs.containsKey('silencer_mode')) {
      await prefs.setString('silencer_mode', mode);
      await saveSilencerModeToNative(mode, locale); // Pass locale
      print('💾 Saved default mode to prefs on first launch: $mode');
    }

    setState(() {
      _currentMode = mode;
      _isToggleOn = enabled;
    });

    try {
      // Sync mode with native
      await saveSilencerModeToNative(mode, locale); // Pass locale

      // Schedule alarms
      if (_lastPrayerTimes.isNotEmpty) {
        await _alarmChannel.invokeMethod('schedulePrayerAlarms', {
          'prayerTimes': _lastPrayerTimes,
          'mode': mode,
          'locale': locale, // Add locale
        });
        print('✅ Initial alarm scheduling with mode: $mode and locale: $locale');
      }
    } catch (e) {
      print('❌ Error during initial settings load: $e');
    }
  }
  String getCurrentLocale() {
    return Localizations.localeOf(context).languageCode;
  }



  Future<void> _saveToggleStatus(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('silencer_enabled', value);
    setState(() => _isToggleOn = value);
  }

  Future<void> _saveMode(String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('silencer_mode', value);
    final locale = getCurrentLocale();
    await saveSilencerModeToNative(value, locale);  // Pass both mode and locale
    setState(() => _currentMode = value);
  }


  Future<void> _toggleDnd(bool enable) async {
    try {
      final String? result = await _platform.invokeMethod('toggleDnd', {'enable': enable});
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(result ?? (enable ? 'DND activated' : 'DND deactivated'))),
      );
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('DND Error: ${e.message}')),
      );
      if (e.code == 'DND_PERMISSION') {
        await _platform.invokeMethod('requestDndPermission');
      }
    }
  }
  Future<void> _loadPrayerTimes() async {
      try {
            final times = await _loadCachedPrayerTimes();
            if (times != null) {
              setState(() {
                _lastPrayerTimes = times;
              });
            }
      } catch (e) {
        print('Error loading prayer times: $e');
        // Keep the widget.prayerTimes if available
        if (widget.prayerTimes.isNotEmpty) {
          setState(() {
            _lastPrayerTimes = widget.prayerTimes;
          });
        }
      }
  }  

  Future<void> saveSilencerModeToNative(String mode, String locale) async {
    try {
      await _alarmChannel.invokeMethod('saveSilencerMode', {
        'mode': mode,
        'locale': locale, // Add locale
      });
      print('✅ Silencer mode saved to Android: $mode with locale: $locale');
    } catch (e) {
      print('❌ Error saving mode to Android: $e');
    }
  }

  Future<void> saveModeAndReschedule(String mode) async {
    final locale = getCurrentLocale(); // Get current locale
    
    // Always save the mode first
    await _saveMode(mode);
    await saveSilencerModeToNative(mode, locale); // Pass locale
    
    // Try to reschedule if we have prayer times
    if (_lastPrayerTimes.isNotEmpty) {
      try {
        await _alarmChannel.invokeMethod('schedulePrayerAlarms', {
          'prayerTimes': _lastPrayerTimes,
          'mode': mode,
          'locale': locale, // Add locale
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Mode changed to $mode')),
        );
      } catch (e) {
        print('Failed to reschedule alarms: $e');
      }
    } else {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Mode changed to $mode (prayer times will update later)')),
      );
    }
  }

  Future<Map<String, int>> _loadCachedPrayerTimes() async {
    final prefs = await SharedPreferences.getInstance();
    final dateStr = prefs.getString('prayerTimesDate');
    final today = DateTime.now();

    if (dateStr == null || DateTime.tryParse(dateStr)?.day != today.day) {
      throw Exception("Prayer times not available or outdated.");
    }

    final keys = ['fajr', 'dhuhr', 'asr', 'maghrib', 'isha'];
    final Map<String, int> times = {};

    for (final key in keys) {
      final ts = prefs.getInt('prayerTime_$key');
      if (ts == null) throw Exception("Prayer time for $key not found");
      times[key] = ts;
    }

    return times;
  }


  void updatePrayerTimes(Map<String, int> times) {
    setState(() {
      _lastPrayerTimes = times;
    });
  }

  @override
  Widget build(BuildContext context) {
    final tealColor = const Color.fromARGB(255, 13, 41, 40);
    final lightTealColor = const Color.fromARGB(255, 38, 65, 71);
    final goldColor = const Color.fromARGB(255, 120, 213, 230);
    final isDarkMode = Theme.of(context).brightness == Brightness.dark;
    final defaultTextColor = isDarkMode ? Colors.white : Colors.black87;

    return Scaffold(
      appBar: AppBar(
        title: Text(
          AppLocalizations.translate(context, 'silencer'),
          style: const TextStyle(color: Colors.white),
        ),
        backgroundColor: tealColor,
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 🔘 Silencer Toggle Panel
            Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(12),
                gradient: LinearGradient(
                  colors: [lightTealColor, tealColor],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
              ),
              child: SwitchListTile(
                contentPadding: const EdgeInsets.symmetric(horizontal: 16),
                title: Text(
                  AppLocalizations.translate(context, 'enableSmartSilencer'),
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                    color: Colors.white,
                  ),
                ),
                activeColor: goldColor,
                value: _isToggleOn,
                onChanged: _saveToggleStatus,
              ),
            ),

            const SizedBox(height: 24),

            // ⬇️ Silencing Mode Dropdown Panel
            Container(
              decoration: BoxDecoration(
                color: Colors.black,
                borderRadius: BorderRadius.circular(12),
              ),
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    AppLocalizations.translate(context, 'silencerSettings'),
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<String>(
                    dropdownColor: Colors.grey[900],
                    value: _currentMode,
                    decoration: InputDecoration(
                      filled: true,
                      fillColor: Colors.grey[900],
                      labelText: AppLocalizations.translate(context, 'silencingMode'),
                      labelStyle: const TextStyle(color: Colors.white),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(8),
                        borderSide: BorderSide.none,
                      ),
                    ),
                    iconEnabledColor: Colors.white,
                    style: const TextStyle(color: Colors.white),
                    items: [
                      DropdownMenuItem(
                        value: 'notification',
                        child: Text(
                          AppLocalizations.translate(context, 'notificationMode'),
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                      DropdownMenuItem(
                        value: 'gps',
                        child: Text(
                          AppLocalizations.translate(context, 'gpsMode'),
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                      DropdownMenuItem(
                        value: 'auto',
                        child: Text(
                          AppLocalizations.translate(context, 'autoMode'),
                          style: const TextStyle(color: Colors.white),
                        ),
                      ),
                    ],
                    onChanged: (value) {
                      if (value != null) {
                        saveModeAndReschedule(value);
                      }
                    },
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // ✋ Manual Control Panel
            Container(
              decoration: BoxDecoration(
                color: Colors.black,
                borderRadius: BorderRadius.circular(12),
              ),
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    AppLocalizations.translate(context, 'manualControl'),
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        child: ElevatedButton.icon(
                          icon: const Icon(Icons.volume_off, size: 20),
                          label: Text(AppLocalizations.translate(context, 'silenceNow')),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: tealColor,
                            foregroundColor: Colors.white,
                          ),
                          onPressed: () => _toggleDnd(true),
                        ),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: ElevatedButton.icon(
                          icon: const Icon(Icons.volume_up, size: 20),
                          label: Text(AppLocalizations.translate(context, 'restoreSound')),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: lightTealColor,
                            foregroundColor: Colors.white,
                          ),
                          onPressed: () => _toggleDnd(false),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // 📝 Auto Save Note
            Text(
              AppLocalizations.translate(context, 'autoSaveNote'),
              style: TextStyle(color: Colors.grey[600]),
            ),
          ],
        ),
      ),
    );
  }

}
