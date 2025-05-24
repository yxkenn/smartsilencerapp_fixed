package com.example.smartsilencerapp_fixed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class RestoreSoundReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("RestoreSoundReceiver", "Restoring normal sound mode")
        
        Intent(context, MyForegroundService::class.java).apply {
            putExtra("action", "restore_sound")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(this)
            } else {
                context.startService(this)
            }
        }
    }
}