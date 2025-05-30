package com.example.smartsilencerapp_fixed

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import java.util.*

object PrayerAlarmManager {
    private const val TAG = "PrayerAlarmManager"

    fun schedulePrayerAlarms(context: Context) {
        Log.d(TAG, "⏳ Scheduling prayer alarms...")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString("silencer_mode", "notification") ?: "notification"

        cancelAllAlarms(context)

        AlarmReceiver.PRAYER_ORDER.forEach { prayer ->
            val prayerTime = prefs.getLong("${AlarmReceiver.KEY_PRAYER_PREFIX}$prayer", -1L)
            if (prayerTime > 0) {
                val alarmTime = prayerTime - (3 * 60 * 1000)
                val now = System.currentTimeMillis()

                if (alarmTime < now) {
                    Log.w(TAG, "⚠️ Skipping $prayer - alarm time has passed")
                    return@forEach
                }

                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("prayer", prayer)
                    putExtra("time", prayerTime)
                    putExtra("mode", mode)
                    action = "prayer_alarm_$prayer"
                }

                val requestCode = (prayer + prayerTime.toString()).hashCode()
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    pendingIntent
                )

                Log.d(TAG, "✅ Scheduled alarm for $prayer at ${Date(alarmTime)}")
            } else {
                Log.w(TAG, "⚠️ No time set for $prayer")
            }
        }
    }
    fun setDailyMidnightAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailySchedulerReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    fun cancelAllAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)

        AlarmReceiver.PRAYER_ORDER.forEach { prayer ->
            val prayerTime = prefs.getLong("${AlarmReceiver.KEY_PRAYER_PREFIX}$prayer", -1L)

            if (prayerTime > 0) {
                val requestCode = (prayer + prayerTime.toString()).hashCode()
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    action = "prayer_alarm_$prayer"
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.cancel(pendingIntent)
                Log.d(TAG, "❌ Canceled alarm for $prayer")
            }
        }

        Log.d(TAG, "✅ All alarms cancelled")
    }
}