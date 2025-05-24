package com.example.smartsilencerapp_fixed

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class SilenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            MyForegroundService.ACTION_SILENCE_YES -> {
                val prayer = intent.getStringExtra("prayer") ?: return
                Log.d("SilenceReceiver", "User confirmed silence for $prayer")
                
                Intent(context, MyForegroundService::class.java).apply {
                    putExtra("action", "silence_now")
                    putExtra("prayer", prayer)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(this)
                    } else {
                        context.startService(this)
                    }
                }
            }
            MyForegroundService.ACTION_SILENCE_NO -> {
                Log.d("SilenceReceiver", "User declined silence")
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(MyForegroundService.NOTIF_ID_SILENCE_PROMPT)
            }
        }
    }
}