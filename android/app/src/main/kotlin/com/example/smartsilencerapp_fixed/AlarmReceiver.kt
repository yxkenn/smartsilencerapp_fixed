package com.example.smartsilencerapp_fixed

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.Calendar
import java.util.Date

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val PREFS_NAME = "com.example.smartsilencerapp_fixed.PREFERENCE_FILE_KEY"
        private const val KEY_PRAYER_PREFIX = "prayerTime_"
        private const val KEY_MODE = "silencer_mode"
        private val PRAYER_ORDER = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm triggered at ${Date()}")

        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "SmartSilencer::AlarmLock"
            ).apply {
                acquire(60_000)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    Log.w(TAG, "Exact alarm permission missing")
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                    )
                    return
                }
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_MODE, "notification") ?: "notification"

            val (nextPrayer, nextPrayerTime) = getNextPrayerTime(prefs) ?: run {
                Log.e(TAG, "No valid upcoming prayer found")
                return
            }

            Log.d(TAG, "Next prayer: $nextPrayer at ${Date(nextPrayerTime)}")

            Intent(context, MyForegroundService::class.java).apply {
                putExtra("action", "handle_prayer")
                putExtra("prayer", nextPrayer)
                putExtra("time", nextPrayerTime)
                putExtra("mode", mode)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(this)
                } else {
                    context.startService(this)
                }
                Log.d(TAG, "Foreground service started for $nextPrayer")
            }

            // 💡 Schedule the daily rescheduler
            scheduleDailyPrayerRefresh(context)

        } finally {
            wakeLock.release()
            Log.d(TAG, "Wake lock released")
        }
    }

    private fun getNextPrayerTime(prefs: SharedPreferences): Pair<String, Long>? {
        val now = System.currentTimeMillis()

        return PRAYER_ORDER.mapNotNull { prayer ->
            prefs.getLong("$KEY_PRAYER_PREFIX$prayer", -1L)
                .takeIf { timestamp ->
                    timestamp > 1_600_000_000_000L &&
                            timestamp in (now - 86_400_000)..(now + 86_400_000)
                }
                ?.let { prayer to it }
        }.minByOrNull { (_, time) ->
            if (time > now) time - now else Long.MAX_VALUE
        }?.also { (prayer, time) ->
            Log.d(TAG, "Selected $prayer at ${Date(time)} (in ${(time - now) / 1000}s)")
        }
    }

    // ✅ Adds daily midnight rescheduling
    private fun scheduleDailyPrayerRefresh(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java) // reuse same receiver
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DATE, 1)
            }
        }

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pendingIntent
        )

        Log.d(TAG, "Scheduled daily prayer refresh at ${calendar.time}")
    }
}
