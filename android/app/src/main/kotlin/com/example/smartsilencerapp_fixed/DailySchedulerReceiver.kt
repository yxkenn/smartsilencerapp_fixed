package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class DailySchedulerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DailySchedulerReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "📅 Daily reschedule triggered at ${System.currentTimeMillis()}")

        // Delay slightly to ensure SharedPreferences and prayer times are ready
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "🔁 Rescheduling today's prayer alarms")
            val prefs = context.getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val locale = prefs.getString("app_locale", "en") ?: "en"
            PrayerAlarmManager.schedulePrayerAlarms(context, locale)

        }, 2000L) // 2 second delay
    }
}
