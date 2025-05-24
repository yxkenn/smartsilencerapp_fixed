package com.example.smartsilencerapp_fixed

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class MyForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "ForegroundServiceChannel"
        const val NOTIF_ID_FOREGROUND = 1
        const val NOTIF_ID_SILENCE_PROMPT = 2
        const val ACTION_SILENCE_YES = "com.example.smartsilencerapp_fixed.ACTION_SILENCE_YES"
        const val ACTION_SILENCE_NO = "com.example.smartsilencerapp_fixed.ACTION_SILENCE_NO"
        const val ACTION_ALARM_TRIGGER = "com.example.smartsilencerapp_fixed.ACTION_ALARM_TRIGGER"
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
       when (intent?.getStringExtra("action")) {
           "silence_now" -> {
               val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
               silencePhone()
               scheduleRestoreSound(prayer, 40 * 60 * 1000)
               return START_STICKY
            }
            "restore_sound" -> {
               restoreNormalSoundMode()
               return START_STICKY
           }
           else -> {
               // Original initialization code
               mode = intent?.getStringExtra("mode") ?: "notification"
            
               val bundle = intent?.getBundleExtra("prayerTimesBundle")
               bundle?.keySet()?.forEach { key ->
                   prayerTimesMap = prayerTimesMap.toMutableMap().apply {
                       put(key, bundle.getLong(key))
                   }
               }
  
               Log.d("MyForegroundService", "Starting in mode $mode with prayers: $prayerTimesMap")
 
               startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification("Smart Silencer Active"))
               scheduleNextSilencerCheck()
               return START_STICKY
           }
        }
    }

    override fun onDestroy() {
        cancelAlarm()
        restoreNormalSoundMode()
        super.onDestroy()
        Log.d("MyForegroundService", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Silencer")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun scheduleNextSilencerCheck() {
        val now = System.currentTimeMillis()
        val upcomingPrayers = prayerTimesMap.filterValues { it > now }

        if (upcomingPrayers.isEmpty()) {
            Log.d("MyForegroundService", "No upcoming prayers to schedule")
            return
        }

        val (nextPrayer, nextTime) = upcomingPrayers.minByOrNull { it.value }!!

        val triggerAt = nextTime - 3 * 60 * 1000 // 3 minutes before prayer

        if (triggerAt <= now) {
            Log.d("MyForegroundService", "Prayer time passed or close, running silencer immediately")
            runSilencerLogic(nextPrayer)
            scheduleAfterPrayer(nextPrayer)
            return
        }

        Log.d("MyForegroundService", "Scheduling silencer for $nextPrayer at $triggerAt (in ${triggerAt - now} ms)")

        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_TRIGGER
            putExtra("prayer", nextPrayer)
            putExtra("mode", mode)
        }

        pendingAlarmIntent = PendingIntent.getBroadcast(
            this, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingAlarmIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingAlarmIntent)
        }
    }

    private fun cancelAlarm() {
        if (::pendingAlarmIntent.isInitialized) {
            alarmManager.cancel(pendingAlarmIntent)
        }
    }

    private fun runSilencerLogic(prayer: String) {
        Log.d("MyForegroundService", "Running silencer logic for prayer $prayer in mode $mode")

        when (mode) {
            "gps" -> {
                if (isInMosqueZone()) {
                    silencePhone()
                    scheduleRestoreSound(prayer, 40 * 60 * 1000)
                } else {
                    Log.d("MyForegroundService", "User not in mosque zone. Not silencing.")
                }
            }
            "notification" -> {
                sendSilencePromptNotification(prayer)
            }
            "auto" -> {
                silencePhone()
                scheduleRestoreSound(prayer, 40 * 60 * 1000)
            }
        }
    }

    private fun scheduleRestoreSound(prayer: String, bufferMillis: Long) {
        val restoreTime = prayerTimesMap[prayer]?.plus(bufferMillis) ?: return
        Log.d("MyForegroundService", "Scheduling restore sound mode at $restoreTime")

        val intent = Intent(this, RestoreSoundReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 
            1, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, restoreTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, restoreTime, pendingIntent)
        }
    }

    private fun scheduleAfterPrayer(prayer: String) {
        prayerTimesMap = prayerTimesMap.toMutableMap().apply { remove(prayer) }
        scheduleNextSilencerCheck()
    }

    private fun isInMosqueZone(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.getProviders(true)
        var lastKnownLocation: Location? = null

        for (provider in providers) {
            try {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.getLastKnownLocation(provider)?.let {
                        if (lastKnownLocation == null || it.time > lastKnownLocation?.time ?: 0) {
                            lastKnownLocation = it
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e("MyForegroundService", "Location permission missing")
            }
        }

        lastKnownLocation?.let {
            val distance = it.distanceTo(Location("").apply {
                latitude = mosqueLat
                longitude = mosqueLon
            })
            Log.d("MyForegroundService", "Distance to mosque: $distance meters")
            return distance <= mosqueRadiusMeters
        }

        Log.d("MyForegroundService", "No location available")
        return false
    }

    private fun silencePhone() {
        Log.d("MyForegroundService", "Silencing phone...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).let { nm ->
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                } else {
                    Log.w("MyForegroundService", "Notification policy access not granted")
                }
            }
        }
    }

    private fun restoreNormalSoundMode() {
        Log.d("MyForegroundService", "Restoring normal sound mode")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).let { nm ->
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                }
            }
        }
    }

    private fun sendSilencePromptNotification(prayer: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val silenceIntent = Intent(this, SilenceReceiver::class.java).apply {
            action = ACTION_SILENCE_YES
            putExtra("prayer", prayer)
        }

        val declineIntent = Intent(this, SilenceReceiver::class.java).apply {
            action = ACTION_SILENCE_NO
            putExtra("prayer", prayer)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Silence for $prayer prayer?")
            .setContentText("Are you going to the mosque?")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .addAction(android.R.drawable.ic_lock_silent_mode, "Yes", PendingIntent.getBroadcast(
                this,
                10,
                silenceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
            ))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "No", PendingIntent.getBroadcast(
                this,
                11,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
            ))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIF_ID_SILENCE_PROMPT, notification)
    }

    private fun getImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}