package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d("BootReceiver", "✅ Device rebooted — Initializing")
                
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // 1. Reset daily scheduler
                        PrayerAlarmManager.setDailyMidnightAlarm(context)
                        
                        // 2. Start service to calculate fresh times
                        val serviceIntent = Intent(context, MyForegroundService::class.java).apply {
                            action = "boot_complete"
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                        
                        Log.d("BootReceiver", "✅ Initialization complete")
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "❌ Initialization failed", e)
                    }
                }, 60_000L) // 60-second delay for more stability
            }
        }
    }
}