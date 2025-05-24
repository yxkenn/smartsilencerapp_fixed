import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

class SmartSilencerScreen extends StatefulWidget {
  const SmartSilencerScreen({super.key});

  @override
  State<SmartSilencerScreen> createState() => _SmartSilencerScreenState();
}

class _SmartSilencerScreenState extends State<SmartSilencerScreen> {
  bool _isActive = false;
  String _mode = 'notification';
  final _platform = const MethodChannel('com.smartsilencerapp/silencer');

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _isActive = prefs.getBool('silencer_active') ?? false;
      _mode = prefs.getString('selectedMode') ?? 'notification';
    });
  }

  Future<bool> _checkPermissions() async {
    final status = await [
      Permission.location,
      Permission.notification,
      Permission.ignoreBatteryOptimizations,
    ].request();

    return status.values.every((s) => s.isGranted);
  }

  Future<void> _toggleActive(bool value) async {
    if (value && !await _checkPermissions()) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Required permissions not granted')));
      return;
    }

    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('silencer_active', value);
    
    try {
      if (value) {
        await _platform.invokeMethod('startSilencerService');
      } else {
        await _platform.invokeMethod('stopSilencerService');
      }
      if (!mounted) return;
      setState(() => _isActive = value);
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error: ${e.message}')));
    }
  }

  Future<void> _changeMode(String? mode) async {
    if (mode == null) return;
    await (await SharedPreferences.getInstance())
        .setString('selectedMode', mode);
    if (!mounted) return;
    setState(() => _mode = mode);
  }

  Future<void> _testDndMode(bool enable) async {
    try {
      await _platform.invokeMethod('setDndMode', {'enable': enable});
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(enable ? 'DND Enabled' : 'DND Disabled')));
    } on PlatformException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('DND Error: ${e.message}')));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Smart Silencer')),
      body: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          children: [
            SwitchListTile(
              title: const Text('Enable Smart Silencer'),
              value: _isActive,
              onChanged: _toggleActive,
            ),
            const SizedBox(height: 20),
            const Text('Silencing Mode:', style: TextStyle(fontSize: 16)),
            DropdownButton<String>(
              value: _mode,
              items: const [
                DropdownMenuItem(value: 'notification', child: Text('Notification Mode')),
                DropdownMenuItem(value: 'gps', child: Text('GPS Mode')),
                DropdownMenuItem(value: 'auto', child: Text('Auto Mode')),
              ],
              onChanged: _changeMode,
            ),
            const SizedBox(height: 30),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton(
                  onPressed: () => _testDndMode(true),
                  child: const Text('Test DND ON'),
                ),
                ElevatedButton(
                  onPressed: () => _testDndMode(false),
                  child: const Text('Test DND OFF'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}