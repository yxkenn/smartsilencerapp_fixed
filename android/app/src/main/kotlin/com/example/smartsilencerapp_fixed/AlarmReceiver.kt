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
        val PRAYER_ORDER = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")

        fun logAllScheduledAlarms(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


            
            Log.d(TAG, "===== CURRENT TIME: ${Date()} =====")
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

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "══════════════════════════════════════")
        Log.d(TAG, "⏰ ALARM RECEIVED at ${Date()}")
        
        val prayerName = intent?.getStringExtra("prayer") ?: 
            intent?.action?.substringAfter("prayer_alarm_") ?: "unknown"
        
        Log.d(TAG, "Handling alarm for prayer: $prayerName")
        
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
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)


            val mode = prefs.getString(KEY_MODE, "notification") ?: "notification"
                Log.d(TAG, "═══════════════════════════════════════════════")
                Log.d(TAG, "📅 CURRENT TIME: ${Date()}")
                Log.d(TAG, "🎛️ ACTIVE SILENCER MODE: $mode")
                Log.d(TAG, "📋 LOADED SCHEDULED PRAYER ALARMS:")
            
            val prayerTime = prefs.getLong("$KEY_PRAYER_PREFIX$prayerName", -1L)
            if (prayerTime <= 0) {
                Log.e(TAG, "No valid time found for prayer: $prayerName")
                return
            }

            Log.d(TAG, "🕌 Prayer: $prayerName at ${Date(prayerTime)}")

            Intent(context, MyForegroundService::class.java).apply {
                putExtra("action", "handle_prayer")
                putExtra("prayer", prayerName)
                putExtra("time", prayerTime)
                putExtra("mode", mode)

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