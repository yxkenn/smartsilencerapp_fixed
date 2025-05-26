package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

import android.app.AlarmManager
import android.app.PendingIntent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val mainActivity = MainActivity()
            mainActivity.schedulePrayerAlarms(context)
            Log.d("BootReceiver", "✅ Re-scheduled alarms after boot")
        }
    }
}
