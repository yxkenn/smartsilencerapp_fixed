import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

class AppLocalizations {
  static const supportedLocales = [
    Locale('en', 'US'),
    Locale('ar', 'SA'),
  ];

  static Map<String, Map<String, String>> _localizedValues = {
    'en': {
      'prayerTimes': 'Prayer Times',
      'silencer': 'Silencer',
      'reminder': 'Reminder',
      'settings': 'Settings',
      'enableSmartSilencer': 'Enable Smart Silencer',
      'silencingMode': 'Silencing Mode',
      'notificationMode': 'Notification Mode',
      'gpsMode': 'GPS Mode',
      'autoMode': 'Auto Mode',
      'manualControl': 'Manual Control',
      'silenceNow': 'Silence Now',
      'restoreSound': 'Restore Sound',
      'weeklyPrayerPreferences': 'Weekly Prayer Preferences',
      'defaultMode': 'Default Mode',
      'certainlyGoing': 'Certainly Going',
      'excluded': 'Excluded',
      'enableReminder': 'Enable Reminder',
      'maxSkipsAllowed': 'Maximum Skips Allowed',
      'enterSkips': 'Enter number of skips',
      'nextPrayerIn': 'Next Prayer in',
      'hijriDate': 'Hijri Date',
      'silencerSettings': 'Silencer Settings',
      'enterSkipsHint': 'Enter number',
      'enterNumberError': 'Please enter a number',
      'enterValidNumberError': 'Please enter a valid number',
      'autoSaveNote': 'Changes are saved automatically',
      'waitingForLocation': 'Waiting for location...',
      'fajr': 'Fajr',
      'dhuhr': 'Dhuhr',
      'asr': 'Asr',
      'maghrib': 'Maghrib',
      'isha': 'Isha',
      'sunday': 'Sunday',
      'monday': 'Monday',
      'tuesday': 'Tuesday',
      'wednesday': 'Wednesday',
      'thursday': 'Thursday',
      'friday': 'Friday',
      'saturday': 'Saturday',

      // Add more translations as needed
    },
    'ar': {
      'prayerTimes': 'أوقات الصلاة',
      'silencer': 'كتم الصوت',
      'reminder': 'التذكير',
      'settings': 'الإعدادات',
      'enableSmartSilencer': 'تفعيل كتم الصوت الذكي',
      'silencingMode': 'وضع الكتم',
      'notificationMode': 'وضع الإشعار',
      'gpsMode': 'وضع GPS',
      'autoMode': 'وضع تلقائي',
      'manualControl': 'التحكم اليدوي',
      'silenceNow': 'كتم الصوت الآن',
      'restoreSound': 'استعادة الصوت',
      'weeklyPrayerPreferences': 'تفضيلات الصلاة الأسبوعية',
      'defaultMode': 'الوضع الافتراضي',
      'certainlyGoing': 'متأكد من الذهاب',
      'excluded': 'مستثنى',
      'enableReminder': 'تفعيل التذكير',
      'maxSkipsAllowed': 'الحد الأقصى للتجاوزات المسموحة',
      'enterSkips': 'أدخل عدد التجاوزات',
      'nextPrayerIn': 'الصلاة القادمة بعد',
      'hijriDate': 'التاريخ الهجري',
      'silencerSettings': 'إعدادات كتم الصوت',
      'enterSkipsHint': 'أدخل الرقم',
      'enterNumberError': 'الرجاء إدخال رقم',
      'enterValidNumberError': 'الرجاء إدخال رقم صحيح',
      'autoSaveNote': 'يتم حفظ التغييرات تلقائيًا',
      'waitingForLocation': 'جاري انتظار الموقع...',
      'fajr': 'الفجر',
      'dhuhr': 'الظهر',
      'asr': 'العصر',
      'maghrib': 'المغرب',
      'isha': 'العشاء',
      'sunday': 'الأحد',
      'monday': 'الاثنين',
      'tuesday': 'الثلاثاء',
      'wednesday': 'الأربعاء',
      'thursday': 'الخميس',
      'friday': 'الجمعة',
      'saturday': 'السبت',

      // Add more Arabic translations as needed
    },
  };

  static String translate(BuildContext context, String key) {
    final locale = Localizations.localeOf(context).languageCode;
    return _localizedValues[locale]?[key] ?? 
          _localizedValues['en']?[key] ?? 
          key; // Return the key itself as fallback
  }
}

class AppLocalizationsDelegate extends LocalizationsDelegate<AppLocalizations> {
  const AppLocalizationsDelegate();

  @override
  bool isSupported(Locale locale) => ['en', 'ar'].contains(locale.languageCode);

  @override
  Future<AppLocalizations> load(Locale locale) async {
    return SynchronousFuture<AppLocalizations>(AppLocalizations());
  }

  @override
  bool shouldReload(AppLocalizationsDelegate old) => false;
}