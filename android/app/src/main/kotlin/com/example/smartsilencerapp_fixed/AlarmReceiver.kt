package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.*

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val PREFS_NAME = "com.example.smartsilencerapp_fixed.PREFERENCE_FILE_KEY"
        private const val KEY_PRAYER_PREFIX = "prayerTime_" // 🔥 Matches Flutter's key format
        private const val KEY_MODE = "silencer_mode"
        private val PRAYER_ORDER = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm triggered at ${Date()}")

        // 1. Acquire wake lock to prevent CPU sleep
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartSilencer::AlarmLock").apply {
                acquire(60_000) // Hold for 1 minute max
            }

        try {
            // 2. Load data from SharedPreferences
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mode = prefs.getString(KEY_MODE, "notification") ?: "notification"

            // 3. Debug: Log all stored prayer keys
            Log.d(TAG, "Stored prayer keys: ${prefs.all.keys.filter { it.startsWith(KEY_PRAYER_PREFIX) }}")

            // 4. Find next prayer time
            val (nextPrayer, nextPrayerTime) = getNextPrayerTime(prefs).also {
                Log.d(TAG, "Next prayer: ${it?.first} at ${Date(it?.second ?: 0)}")
            } ?: run {
                Log.e(TAG, "No upcoming prayer found in: ${PRAYER_ORDER.joinToString()}")
                return
            }

            // 5. Verify exact alarm permission (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    Log.w(TAG, "Exact alarms permission missing")
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    return
                }
            }

            // 6. Start foreground service with next prayer info
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
                Log.d(TAG, "Started service for $nextPrayer at ${Date(nextPrayerTime)}")
            }
        } finally {
            wakeLock.release()
        }
    }

    private fun getNextPrayerTime(prefs: SharedPreferences): Pair<String, Long>? {
        val now = System.currentTimeMillis()
        
        return PRAYER_ORDER.mapNotNull { prayer ->
            // 🔥 Critical: Match Flutter's exact key format and handle milliseconds
            val storedTime = prefs.getLong("$KEY_PRAYER_PREFIX$prayer", -1L).takeIf { 
                it > 1_000_000_000_000L // Basic timestamp validation (after year 2001)
            }
            storedTime?.let { prayer to it }
        }.filter { (_, time) ->
            // Only consider future prayers or those within last 24h
            time > now - 86_400_000 
        }.minByOrNull { (_, time) ->
            // Find nearest future prayer
            if (time > now) time - now else Long.MAX_VALUE
        }?.also { (prayer, time) ->
            Log.d(TAG, "Selected $prayer at ${Date(time)} (in ${(time - now) / 1000}s)")
        }
    }
}