package com.example.smartsilencerapp_fixed

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class AutoModeStarterService : Service() {
    private lateinit var alarmManager: AlarmManager

    override fun onCreate() {
        super.onCreate()
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("AutoModeStarter", "Service started")
        
        val prayer = intent?.getStringExtra("prayer") ?: run {
            Log.e("AutoModeStarter", "No prayer provided")
            stopSelf()
            return START_NOT_STICKY
        }

        // Schedule DND activation after delay
        scheduleDndActivation(prayer)
        
        // Schedule sound restore
        scheduleSoundRestore(prayer, DND_ACTIVATION_DELAY + DND_DURATION)
        
        // Immediately stop the service since we don't need to run in foreground
        stopSelf()

        return START_NOT_STICKY
    }

    private fun scheduleDndActivation(prayer: String) {
        val activateIntent = Intent(this, MyForegroundService::class.java).apply {
            action = "silence_now"
            putExtra("prayer", prayer)
            putExtra("mode", "auto")
        }
        
        val pendingActivateIntent = PendingIntent.getService(
            this, 
            prayer.hashCode() + 1,
            activateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )
        
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + DND_ACTIVATION_DELAY,
            pendingActivateIntent
        )
    }

    private fun scheduleSoundRestore(prayer: String, delayMillis: Long) {
        val intent = Intent(this, RestoreSoundReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, prayer.hashCode() + 2, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMillis,
                pendingIntent
            )
        }
    }

    private fun getImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val DND_ACTIVATION_DELAY = 5 * 60 * 1000L // 5 minutes
        const val DND_DURATION = 25 * 60 * 1000L // 40 minutes
    }
}