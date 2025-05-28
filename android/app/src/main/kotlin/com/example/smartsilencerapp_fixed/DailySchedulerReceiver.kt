package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DailySchedulerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("DailyScheduler", "🕛 Midnight reached — Rescheduling all prayer alarms")
        PrayerAlarmManager.schedulePrayerAlarms(context)
    }
}

