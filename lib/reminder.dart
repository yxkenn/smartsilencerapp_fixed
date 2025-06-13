import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'localization.dart';

  class ReminderSettingsScreen extends StatefulWidget {
    @override
    _ReminderSettingsScreenState createState() => _ReminderSettingsScreenState();
  }

  class _ReminderSettingsScreenState extends State<ReminderSettingsScreen> {
    static const platform = MethodChannel('com.example.smartsilencerapp_fixed/settings');

    bool _isReminderEnabled = false;
    int _maxSkipsAllowed = 3;
    final TextEditingController _skipController = TextEditingController();
    final _formKey = GlobalKey<FormState>();

    @override
    void initState() {
      super.initState();
      _loadSettings();
    }

// In Flutter code
    Future<void> _loadSettings() async {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _isReminderEnabled = prefs.getBool('reminder_enabled') ?? false;

        final validValues = [1, 2, 3, 4, 5, 10, 15, 20];
        int savedSkips = prefs.getInt('max_skips') ?? 4;
        if (!validValues.contains(savedSkips)) {
          savedSkips = 3; // fallback to default
        }

        _maxSkipsAllowed = savedSkips;
        _skipController.text = savedSkips.toString();
      });

    }

    Future<void> _saveSettings() async {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('reminder_enabled', _isReminderEnabled);
      await prefs.setInt('max_skips', _maxSkipsAllowed);
      await _sendSettingsToAndroid();
      // ...

      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Settings saved successfully!')),
      );
    }

    Future<void> _sendSettingsToAndroid() async {
      try {
        await platform.invokeMethod('updateSettings', {
          'reminderEnabled': _isReminderEnabled,
          'maxSkips': _maxSkipsAllowed,
        });
      } catch (e) {
        print("⚠️ Failed to send to Android: $e");
      }
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
            AppLocalizations.translate(context, 'reminder'),
            style: const TextStyle(color: Colors.white),
          ),
          backgroundColor: tealColor,
          actions: [
            IconButton(
              icon: const Icon(Icons.save, color: Colors.white),
              onPressed: _saveSettings,
            ),
          ],
        ),
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 🔘 Toggle Panel
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
                      AppLocalizations.translate(context, 'enableReminder'),
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                        color: Colors.white,
                      ),
                    ),
                    activeColor: goldColor,
                    value: _isReminderEnabled,
                    onChanged: (bool value) {
                      setState(() {
                        _isReminderEnabled = value;
                      });
                      _sendSettingsToAndroid();
                    },
                  ),
                ),
                const SizedBox(height: 24),

                // 🔲 Max Skips Panel
                Container(
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const SizedBox(height: 4),
                      Text(
                        AppLocalizations.translate(context, 'maxSkipsAllowed'),
                        style: const TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                          color: Colors.white,
                        ),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: TextFormField(
                              controller: _skipController,
                              keyboardType: TextInputType.number,
                              style: const TextStyle(color: Colors.white),
                              decoration: InputDecoration(
                                filled: true,
                                fillColor: Colors.grey[900],
                                hintText: AppLocalizations.translate(context, 'enterSkipsHint'),
                                hintStyle: const TextStyle(color: Colors.white54),
                                border: OutlineInputBorder(
                                  borderRadius: BorderRadius.circular(8),
                                  borderSide: BorderSide.none,
                                ),
                              ),
                              validator: (value) {
                                if (value == null || value.isEmpty) {
                                  return AppLocalizations.translate(context, 'enterNumberError');
                                }
                                final num = int.tryParse(value);
                                if (num == null || num < 0) {
                                  return AppLocalizations.translate(context, 'enterValidNumberError');
                                }
                                return null;
                              },
                              onChanged: (value) {
                                final num = int.tryParse(value);
                                if (num != null && num >= 0) {
                                  setState(() {
                                    _maxSkipsAllowed = num;
                                  });
                                  _sendSettingsToAndroid();
                                }
                              },
                              inputFormatters: [
                                FilteringTextInputFormatter.digitsOnly,
                              ],
                            ),
                          ),
                          const SizedBox(width: 12),
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 8),
                            decoration: BoxDecoration(
                              color: Colors.grey[900],
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: DropdownButton<int>(
                              dropdownColor: Colors.grey[900],
                              value: [1, 2, 3, 4, 5, 10, 15, 20].contains(_maxSkipsAllowed)
                                  ? _maxSkipsAllowed
                                  : 4,
                              underline: const SizedBox(),
                              iconEnabledColor: Colors.white,
                              items: [1, 2, 3, 4, 5, 10, 15, 20].map((int value) {
                                return DropdownMenuItem<int>(
                                  value: value,
                                  child: Text(
                                    '$value',
                                    style: const TextStyle(color: Colors.white),
                                  ),
                                );
                              }).toList(),
                              onChanged: (int? newValue) {
                                if (newValue != null) {
                                  setState(() {
                                    _maxSkipsAllowed = newValue;
                                    _skipController.text = newValue.toString();
                                  });
                                  _sendSettingsToAndroid();
                                }
                              },
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
        ),
      );
    }





    @override
    void dispose() {
      _skipController.dispose();
      super.dispose();
    }
  }