  import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
  import 'package:shared_preferences/shared_preferences.dart';

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
        _maxSkipsAllowed = prefs.getInt('max_skips') ?? 3;
        _skipController.text = _maxSkipsAllowed.toString();
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
      return Scaffold(
        appBar: AppBar(
          title: Text('Reminder Settings'),
          actions: [
            IconButton(
              icon: Icon(Icons.save),
              onPressed: _saveSettings,
            ),
          ],
        ),
        body: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Form(
            key: _formKey,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                SwitchListTile(
                  title: Text('Enable Reminder'),
                  value: _isReminderEnabled,
                  onChanged: (bool value) {
                    setState(() {
                      _isReminderEnabled = value;
                    });
                    _sendSettingsToAndroid(); // Notify Android immediately
                  },

                ),
                SizedBox(height: 20),
                Text(
                  'Maximum Skips Allowed',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
                ),
                SizedBox(height: 10),
                Row(
                  children: [
                    Expanded(
                      child: TextFormField(
                        controller: _skipController,
                        keyboardType: TextInputType.number,
                        decoration: InputDecoration(
                          border: OutlineInputBorder(),
                          hintText: 'Enter number of skips',
                        ),
                        validator: (value) {
                          if (value == null || value.isEmpty) {
                            return 'Please enter a number';
                          }
                          final num = int.tryParse(value);
                          if (num == null || num < 0) {
                            return 'Please enter a valid positive number';
                          }
                          return null;
                        },
                        onChanged: (value) {
                          final num = int.tryParse(value);
                          if (num != null && num >= 0) {
                            setState(() {
                              _maxSkipsAllowed = num;
                            });
                            _sendSettingsToAndroid(); // Notify Android immediately
                          }
                        },
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                        ],      

                      ),
                    ),
                    SizedBox(width: 10),
                    DropdownButton<int>(
                      value: _maxSkipsAllowed,
                      items: [1, 2, 3, 5, 10].map((int value) {
                        return DropdownMenuItem<int>(
                          value: value,
                          child: Text('$value'),
                        );
                      }).toList(),
                      onChanged: (int? newValue) {
                        if (newValue != null) {
                          setState(() {
                            _maxSkipsAllowed = newValue;
                            _skipController.text = newValue.toString();
                          });
                          _sendSettingsToAndroid(); // Notify Android immediately
                        }
                      },

                    ),
                  ],
                ),
                SizedBox(height: 20),
                Text(
                  'Settings are saved automatically when you toggle the switch '
                  'or change the skip value. You can also manually save using '
                  'the save icon in the app bar.',
                  style: TextStyle(color: Colors.grey),
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