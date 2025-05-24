package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val PREFS_NAME = "com.example.smartsilencerapp_fixed.PREFERENCE_FILE_KEY"
        private const val KEY_PRAYER_TIMES_PREFIX = "prayer_"
        private const val KEY_SILENCER_MODE = "silencer_mode"
        private val PRAYERS = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm received")

        val sharedPrefs: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Load silencer mode (default to notification if not set)
        val silencerMode = sharedPrefs.getString(KEY_SILENCER_MODE, "notification") ?: "notification"

        // Load prayer times map
        val prayerTimesMap = mutableMapOf<String, Long>()
        for (prayer in PRAYERS) {
            val timeMillis = sharedPrefs.getLong(KEY_PRAYER_TIMES_PREFIX + prayer, -1L)
            if (timeMillis != -1L) {
                prayerTimesMap[prayer] = timeMillis
            }
        }

        Log.d(TAG, "Loaded silencer mode: $silencerMode")
        Log.d(TAG, "Loaded prayer times: $prayerTimesMap")

        // Prepare intent to start the foreground service
        val serviceIntent = Intent(context, MyForegroundService::class.java).apply {
            putExtra("mode", silencerMode)
            // Pass prayer times as a Bundle extras
            val bundle = android.os.Bundle()
            for ((prayer, time) in prayerTimesMap) {
                bundle.putLong(prayer, time)
            }
            putExtra("prayerTimesBundle", bundle)
        }

        // Start foreground service appropriately depending on API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
