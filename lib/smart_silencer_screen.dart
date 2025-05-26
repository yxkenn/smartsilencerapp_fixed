import 'dart:io';
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
  final _platform = const MethodChannel('com.example.smartsilencerapp_fixed/foreground');
  bool _isToggleOn = false;
  String _currentMode = 'notification';

  @override
  void initState() {
    super.initState();
    _loadSettings();
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Smart Silencer'),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
        child: Column(
          children: [
            // Settings Card
            Card(
              margin: const EdgeInsets.only(bottom: 20),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text(
                      'Silencer Settings',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                    ),
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
                        contentPadding: EdgeInsets.symmetric(horizontal: 12, vertical: 16),
                      ),
                      items: const [
                        DropdownMenuItem(
                          value: 'notification',
                          child: Text('Notification Mode'),
                        ),
                        DropdownMenuItem(
                          value: 'gps',
                          child: Text('GPS Mode'),
                        ),
                        DropdownMenuItem(
                          value: 'auto',
                          child: Text('Auto Mode'),
                        ),
                      ],
                      onChanged: (value) {
                        if (value != null) {
                          _saveMode(value);
                        }
                      },
                    ),
                  ],
                ),
              ),
            ),

            // Manual Controls
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text(
                      'Manual Control',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                    ),
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
