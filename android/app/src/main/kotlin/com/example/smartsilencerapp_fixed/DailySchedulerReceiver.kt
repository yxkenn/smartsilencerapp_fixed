package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class DailySchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("DailyScheduler", "🕛 Midnight reached — Rescheduling all prayer alarms")
        
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                // Force fresh prayer time calculation
                triggerPrayerTimeUpdate(context)
                
                // Then reschedule alarms
                PrayerAlarmManager.schedulePrayerAlarms(context)
                Log.d("DailyScheduler", "✅ Alarms rescheduled")
            } catch (e: Exception) {
                Log.e("DailyScheduler", "❌ Rescheduling failed", e)
            }
        }, 30_000L) // 30-second delay
    }

    private fun triggerPrayerTimeUpdate(context: Context) {
        // Start your foreground service to trigger new prayer time calculation
        val intent = Intent(context, MyForegroundService::class.java).apply {
            action = "update_prayer_times"
        }
        ContextCompat.startForegroundService(context, intent)
    }
}