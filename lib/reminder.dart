import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

class SkipSettingsPage extends StatefulWidget {
  const SkipSettingsPage({Key? key}) : super(key: key);

  @override
  State<SkipSettingsPage> createState() => _SkipSettingsPageState();
}

class _SkipSettingsPageState extends State<SkipSettingsPage> {
  bool skipEnabled = false;
  int skipThreshold = 3;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      skipEnabled = prefs.getBool('skip_enabled') ?? false;
      skipThreshold = prefs.getInt('skip_threshold') ?? 3;
    });
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('skip_enabled', skipEnabled);
    await prefs.setInt('skip_threshold', skipThreshold);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("Skip Settings")),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            SwitchListTile(
              title: const Text("Enable Skip Counter"),
              value: skipEnabled,
              onChanged: (val) {
                setState(() {
                  skipEnabled = val;
                });
                _saveSettings();
              },
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text("Skip Threshold"),
                SizedBox(
                  width: 60,
                  child: TextFormField(
                    initialValue: skipThreshold.toString(),
                    keyboardType: TextInputType.number,
                    onChanged: (value) {
                      final parsed = int.tryParse(value);
                      if (parsed != null) {
                        skipThreshold = parsed;
                        _saveSettings();
                      }
                    },
                  ),
                )
              ],
            )
          ],
        ),
      ),
    );
  }
}
