package com.example.smartsilencerapp_fixed

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.util.*

object PrayerAlarmManager {
    private const val TAG = "PrayerAlarmManager"
    private const val PRE_ALARM_OFFSET = 3 * 60 * 1000 // 3 minutes before prayer

    fun schedulePrayerAlarms(context: Context) {
        Log.d(TAG, "⏳ Scheduling prayer alarms...")

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString("silencer_mode", "notification") ?: "notification"

        cancelAllAlarms(context)

        AlarmReceiver.PRAYER_ORDER.forEach { prayer ->
            var prayerTime = prefs.getLong("${AlarmReceiver.KEY_PRAYER_PREFIX}$prayer", -1L)
            if (prayerTime > 0) {
                // Adjust for next day if time has passed
                if (prayerTime < System.currentTimeMillis()) {
                    prayerTime += AlarmManager.INTERVAL_DAY // Add 24 hours
                    Log.d(TAG, "⏩ Adjusted $prayer time to next day")
                }

                val alarmTime = prayerTime - PRE_ALARM_OFFSET
                val now = System.currentTimeMillis()

                if (alarmTime < now) {
                    Log.w(TAG, "⚠️ Skipping $prayer - adjusted alarm time still in past")
                    return@forEach
                }

                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("prayer", prayer)
                    putExtra("time", prayerTime)
                    putExtra("mode", mode)
                    action = "prayer_alarm_$prayer"
                    flags = Intent.FLAG_RECEIVER_FOREGROUND
                }

                val requestCode = (prayer + prayerTime.toString()).hashCode()
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or 
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            alarmTime,
                            pendingIntent
                        )
                    } else {
                        alarmManager.set(
                            AlarmManager.RTC_WAKEUP,
                            alarmTime,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        alarmTime,
                        pendingIntent
                    )
                }

                Log.d(TAG, "✅ Scheduled alarm for $prayer at ${Date(alarmTime)} (Original: ${Date(prayerTime)})")
            } else {
                Log.w(TAG, "⚠️ No time set for $prayer")
            }
        }
    }

    fun setDailyMidnightAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailySchedulerReceiver::class.java).apply {
            flags = Intent.FLAG_RECEIVER_FOREGROUND
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5) // 12:05 AM to avoid midnight congestion
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }

        Log.d(TAG, "⏰ Set daily rescheduler for ${Date(calendar.timeInMillis)}")
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

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    flags
                )

                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d(TAG, "❌ Canceled alarm for $prayer")
            }
        }

        // Also cancel the daily scheduler
        val dailyIntent = Intent(context, DailySchedulerReceiver::class.java)
        val dailyPendingIntent = PendingIntent.getBroadcast(
            context,
            9999,
            dailyIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )
        alarmManager.cancel(dailyPendingIntent)
        dailyPendingIntent.cancel()

        Log.d(TAG, "✅ All alarms cancelled")
    }
}