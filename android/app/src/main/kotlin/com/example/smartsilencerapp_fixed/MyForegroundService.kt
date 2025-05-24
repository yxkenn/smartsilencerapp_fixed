package com.example.smartsilencerapp_fixed

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Date
import android.Manifest
import java.text.SimpleDateFormat



class MyForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "ForegroundServiceChannel"
        const val NOTIF_ID_FOREGROUND = 1
        const val NOTIF_ID_SILENCE_PROMPT = 2
        const val ACTION_ALARM_TRIGGER = "com.example.smartsilencerapp_fixed.ACTION_ALARM_TRIGGER"
        
        // Direct service actions (replaced broadcast actions)
        const val ACTION_USER_CONFIRMED = "user_confirmed_silence"
        const val ACTION_USER_DECLINED = "user_declined_silence"
    }

    private var prayerTimesMap: Map<String, Long> = emptyMap()
    private var mode: String = "notification"

    private val mosqueLat = 33.815115
    private val mosqueLon = 2.864548
    private val mosqueRadiusMeters = 100.0

    private lateinit var alarmManager: AlarmManager
    private lateinit var pendingAlarmIntent: PendingIntent

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: intent?.getStringExtra("action")) {
            // Handle direct user actions
            ACTION_USER_CONFIRMED -> {
                val prayer = intent?.getStringExtra("prayer") ?: return START_STICKY
                handleUserConfirmation(prayer)
            }
            
            ACTION_USER_DECLINED -> {
                handleUserDecline()
            }
            
            // Existing service commands
            "silence_now" -> {
                val prayer = intent?.getStringExtra("prayer") ?: return START_STICKY
                activateDnd(prayer)
            }
            
            "restore_sound" -> {
                restoreNormalSoundMode()
            }
            
            // Initial setup
            else -> {
                mode = intent?.getStringExtra("mode") ?: "notification"
                intent?.getBundleExtra("prayerTimesBundle")?.keySet()?.forEach { key ->
                    prayerTimesMap = prayerTimesMap.toMutableMap().apply {
                        put(key, intent.getBundleExtra("prayerTimesBundle")?.getLong(key) ?: 0L)
                    }
                }
                startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification("Smart Silencer Active"))
                scheduleNextSilencerCheck()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Prayer Alerts",
                NotificationManager.IMPORTANCE_HIGH // Changed to HIGH for reliability
            ).apply {
                description = "Prayer time notifications"
            }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Silencer")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )
            )
            .setOngoing(true)
            .build()
    }

    // Modified to use direct service intents
    private fun sendSilencePromptNotification(prayer: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Silence for $prayer prayer?")
            .setContentText("Are you going to the mosque?")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .addAction(
                android.R.drawable.ic_lock_silent_mode, 
                "Yes",
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, MyForegroundService::class.java).apply {
                        action = ACTION_USER_CONFIRMED
                        putExtra("prayer", prayer)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel, 
                "No",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, MyForegroundService::class.java).apply {
                        action = ACTION_USER_DECLINED
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )
            )
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_SILENCE_PROMPT, notification)
    }

    private fun handleUserConfirmation(prayer: String) {
        Log.d(TAG, "User confirmed silence for $prayer")
        when (mode) {
            "gps" -> if (isInMosqueZone()) activateDnd(prayer)
            "auto", "notification" -> activateDnd(prayer)
        }
    }

    private fun handleUserDecline() {
        Log.d(TAG, "User declined silence")
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
            cancel(NOTIF_ID_SILENCE_PROMPT)
            // Optional: Ensure sound isn't silenced
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isNotificationPolicyAccessGranted) {
                setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
    }

    private fun activateDnd(prayer: String) {
        Log.d(TAG, "Activating DND for $prayer")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).takeIf { 
                it.isNotificationPolicyAccessGranted 
            }?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }
        scheduleRestoreSound(prayer, 40 * 60 * 1000) // 40 minute buffer
    }

    private fun scheduleNextSilencerCheck() {
        val now = System.currentTimeMillis()
        val upcomingPrayers = prayerTimesMap.filterValues { it > now }

        if (upcomingPrayers.isEmpty()) {
            Log.d(TAG, "No upcoming prayers to schedule")
            return
        }

        val (nextPrayer, nextTime) = upcomingPrayers.minByOrNull { it.value }!!
        val triggerAt = nextTime - 3 * 60 * 1000 // 3 minutes before prayer

        if (triggerAt <= now) {
            Log.d(TAG, "Prayer time close, running silencer immediately")
            runSilencerLogic(nextPrayer)
            scheduleAfterPrayer(nextPrayer)
            return
        }

        Log.d(TAG, "Scheduling silencer for $nextPrayer at ${Date(triggerAt)}")
        pendingAlarmIntent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGER
            putExtra("prayer", nextPrayer)
            putExtra("mode", mode)
        }.let { intent ->
            PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingAlarmIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingAlarmIntent)
        }
    }

    private fun runSilencerLogic(prayer: String) {
        Log.d(TAG, "Running silencer for $prayer in $mode mode")
        when (mode) {
            "gps" -> {
                if (isInMosqueZone()) activateDnd(prayer)
                else Log.d(TAG, "Not in mosque zone - not silencing")
            }
            "notification" -> sendSilencePromptNotification(prayer)
            "auto" -> activateDnd(prayer)
        }
    }

    private fun isInMosqueZone(): Boolean {
        val location = getLastKnownLocation() ?: return false
        val distance = location.distanceTo(Location("").apply {
            latitude = mosqueLat
            longitude = mosqueLon
        })
        Log.d(TAG, "Mosque distance: ${"%.1f".format(distance)} meters")
        return distance <= mosqueRadiusMeters
    }

    private fun getLastKnownLocation(): Location? {
        return (getSystemService(Context.LOCATION_SERVICE) as LocationManager).run {
            getProviders(true).mapNotNull { provider ->
                try {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        getLastKnownLocation(provider)
                    } else null
                } catch (e: SecurityException) {
                    Log.e(TAG, "Location permission error", e)
                    null
                }
            }.maxByOrNull { it.time }
        }
    }

    private fun scheduleRestoreSound(prayer: String, bufferMillis: Long) {
        prayerTimesMap[prayer]?.plus(bufferMillis)?.let { restoreTime ->
            Log.d(TAG, "Scheduling sound restore at ${Date(restoreTime)}")
            PendingIntent.getBroadcast(
                this,
                1,
                Intent(this, RestoreSoundReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
            ).also { pendingIntent ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, restoreTime, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, restoreTime, pendingIntent)
                }
            }
        }
    }

    private fun restoreNormalSoundMode() {
        Log.d(TAG, "Restoring normal sound mode")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).takeIf {
                it.isNotificationPolicyAccessGranted
            }?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }

    private fun scheduleAfterPrayer(prayer: String) {
        prayerTimesMap = prayerTimesMap.toMutableMap().apply { remove(prayer) }
        scheduleNextSilencerCheck()
    }

    private fun getImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    override fun onDestroy() {
        cancelAlarm()
        restoreNormalSoundMode()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    private fun cancelAlarm() {
        if (::pendingAlarmIntent.isInitialized) {
            alarmManager.cancel(pendingAlarmIntent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val TAG = "MyForegroundService"
}