import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

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
    setState(() {
      _currentMode = prefs.getString('silencer_mode') ?? 'notification';
      _isToggleOn = prefs.getBool('silencer_enabled') ?? false;
    });
  }

  Future<void> _saveToggleStatus(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('silencer_enabled', value);
    setState(() => _isToggleOn = value);
  }

  Future<void> _saveMode(String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('silencer_mode', value);
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
        setState(() {
          _lastPrayerTimes = times;
        });
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

  Future<void> saveSilencerModeToNative(String mode) async {
    try {
      await _alarmChannel.invokeMethod('saveSilencerMode', {'mode': mode});
      print('✅ Silencer mode saved to Android: $mode');
    } catch (e) {
      print('❌ Error saving mode to Android: $e');
    }
  }

  Future<void> saveModeAndReschedule(String mode) async {
    // Always save the mode first
    await _saveMode(mode);
    await saveSilencerModeToNative(mode);
    
    // Try to reschedule if we have prayer times
    if (_lastPrayerTimes.isNotEmpty) {
      try {
        await _alarmChannel.invokeMethod('schedulePrayerAlarms', {
          'prayerTimes': _lastPrayerTimes,
          'mode': mode,
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
    return Scaffold(
      appBar: AppBar(title: const Text('Smart Silencer'), centerTitle: true),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
        child: Column(
          children: [
            Card(
              margin: const EdgeInsets.only(bottom: 20),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text('Silencer Settings', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 16),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text('Enable Smart Silencer'),
                      value: _isToggleOn,
                      onChanged: _saveToggleStatus,
                    ),
                    const SizedBox(height: 16),
                      DropdownButtonFormField<String>(
                          value: _currentMode,
                          decoration: const InputDecoration(
                            labelText: 'Silencing Mode',
                            border: OutlineInputBorder(),
                          ),
                          items: const [
                            DropdownMenuItem(value: 'notification', child: Text('Notification Mode')),
                            DropdownMenuItem(value: 'gps', child: Text('GPS Mode')),
                            DropdownMenuItem(value: 'auto', child: Text('Auto Mode')),
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
            ),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text('Manual Control', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        Expanded(
                          child: ElevatedButton.icon(
                            icon: const Icon(Icons.volume_off, size: 20),
                            label: const Text('Silence Now'),
                            onPressed: () => _toggleDnd(true),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.red[400],
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(vertical: 16),
                            ),
                          ),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: ElevatedButton.icon(
                            icon: const Icon(Icons.volume_up, size: 20),
                            label: const Text('Restore Sound'),
                            onPressed: () => _toggleDnd(false),
                            style: ElevatedButton.styleFrom(
                              padding: const EdgeInsets.symmetric(vertical: 16),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
