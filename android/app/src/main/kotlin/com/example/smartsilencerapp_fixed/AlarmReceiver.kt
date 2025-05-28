package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.Date
import java.util.Calendar
import androidx.preference.PreferenceManager

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "AlarmReceiver"
        const val KEY_PRAYER_PREFIX = "prayerTime_"
        const val KEY_MODE = "silencer_mode"
        const val PREFS_NAME = "SmartSilencerPrefs"
        const val MODE_NOTIFICATION = "notification"
        const val MODE_GPS = "gps"
        const val MODE_AUTO = "auto"
        
        val PRAYER_ORDER = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")

        fun getCurrentMode(context: Context): String {
            return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(KEY_MODE, MODE_NOTIFICATION) ?: MODE_NOTIFICATION
        }

        fun logAllScheduledAlarms(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentMode = getCurrentMode(context)

            Log.d(TAG, "===== CURRENT TIME: ${Date()} =====")
            Log.d(TAG, "===== ACTIVE MODE: $currentMode =====")
            Log.d(TAG, "===== SCHEDULED ALARMS =====")

            PRAYER_ORDER.forEach { prayer ->
                val prayerTime = prefs.getLong("$KEY_PRAYER_PREFIX$prayer", -1L)
                if (prayerTime > 0) {
                    val alarmTime = prayerTime - (3 * 60 * 1000)
                    val timeUntil = alarmTime - System.currentTimeMillis()

                    Log.d(TAG, "Prayer: $prayer")
                    Log.d(TAG, "  - Prayer time: ${Date(prayerTime)}")
                    Log.d(TAG, "  - Alarm scheduled: ${Date(alarmTime)}")
                    Log.d(TAG, "  - Time until alarm: ${timeUntil / 1000} seconds")
                    Log.d(TAG, "  - Alarm passed: ${timeUntil < 0}")
                } else {
                    Log.d(TAG, "Prayer: $prayer - NOT SCHEDULED (invalid time)")
                }
            }
        }
    }

    private fun isValidMode(mode: String): Boolean {
        return mode in listOf(MODE_NOTIFICATION, MODE_GPS, MODE_AUTO)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "══════════════════════════════════════")
        Log.d(TAG, "⏰ ALARM RECEIVED at ${Date()}")

        // Get the current mode from SharedPreferences
        val modePrefs = PreferenceManager.getDefaultSharedPreferences(context)
        var currentMode = modePrefs.getString(KEY_MODE, MODE_NOTIFICATION) ?: MODE_NOTIFICATION
        
        // Validate the mode
        if (!isValidMode(currentMode)) {
            Log.w(TAG, "⚠️ Invalid mode detected: $currentMode. Defaulting to notification")
            currentMode = MODE_NOTIFICATION
            modePrefs.edit().putString(KEY_MODE, currentMode).apply()
        }

        // Get prayer times from shared preferences
        val prayerPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Try to get prayer name
        val prayerName = intent?.getStringExtra("prayer")
            ?: intent?.action?.substringAfter("prayer_alarm_")
            ?: "unknown"

        Log.d(TAG, "🔔 Handling alarm for prayer: $prayerName")
        Log.d(TAG, "🎛️ Current system mode: $currentMode")

        // Acquire wakelock
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "SmartSilencer::${prayerName}AlarmLock"
            ).apply {
                acquire(30_000)
            }
        }

        try {
            // Get prayerTime from Intent first, fallback to prefs
            var prayerTime = intent?.getLongExtra("time", -1L) ?: -1L
            if (prayerTime <= 0) {
                prayerTime = prayerPrefs.getLong("$KEY_PRAYER_PREFIX$prayerName", -1L)
            }

            if (prayerTime <= 0) {
                Log.e(TAG, "❌ No valid prayer time found for $prayerName (intent and prefs failed)")
                return
            }

            Log.d(TAG, "🔄 Using mode: $currentMode (from preferences)")
            Log.d(TAG, "🕌 Prayer: $prayerName")
            Log.d(TAG, "🕒 Prayer time: ${Date(prayerTime)}")

            // Trigger foreground service
            Intent(context, MyForegroundService::class.java).apply {
                action = MyForegroundService.ACTION_ALARM_TRIGGER
                putExtra("prayer", prayerName)
                putExtra("time", prayerTime)
                putExtra("mode", currentMode)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(this)
                } else {
                    context.startService(this)
                }
            }

        } finally {
            wakeLock.release()
        }
    }
}