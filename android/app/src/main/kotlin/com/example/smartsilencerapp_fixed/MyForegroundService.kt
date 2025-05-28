package com.example.smartsilencerapp_fixed

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*
import android.Manifest
import android.os.Looper

import android.provider.Settings

class MyForegroundService : Service() {
    private val TAG = "MyForegroundService"


    companion object {
        const val CHANNEL_ID = "ForegroundServiceChannel"
        const val ALERTS_CHANNEL_ID = "ALERTS_CHANNEL"
        const val NOTIF_ID_FOREGROUND = 1
        const val NOTIF_ID_SILENCE_PROMPT = 2
        const val NOTIF_ID_GPS_PROMPT = 3
        const val ACTION_ALARM_TRIGGER = "com.example.smartsilencerapp_fixed.ACTION_ALARM_TRIGGER"
        const val ACTION_USER_CONFIRMED = "user_confirmed_silence"
        const val ACTION_USER_DECLINED = "user_declined_silence"
        const val GPS_CHECK_INTERVAL = 30_000L // 30 seconds
        const val DND_DURATION = 40 * 60 * 1000L // 40 minutes
    }

    private var prayerTimesMap: Map<String, Long> = emptyMap()
    private var mode: String = "notification"
    private lateinit var locationHandler: Handler
    private lateinit var locationRunnable: Runnable

    // Mosque coordinates
    private val mosqueLat = 33.815115
    private val mosqueLon = 2.864548
    private val mosqueRadiusMeters = 100.0

    private lateinit var alarmManager: AlarmManager
    private lateinit var pendingAlarmIntent: PendingIntent

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        locationHandler = Handler(Looper.getMainLooper())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        
        startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification("Initializing silencer..."))
        mode = intent?.getStringExtra("mode") ?: mode
        Log.d(TAG, "📥 onStartCommand called with action=${intent?.action}")

        Log.d(TAG, "🔍 Intent received: action=${intent?.action}, extras=${intent?.extras}")

        Log.d(TAG, "🚀 Starting foreground service with mode: $mode")
        when (intent?.action) {


            // User actions
            ACTION_USER_CONFIRMED -> {
                val prayer = intent?.getStringExtra("prayer") ?: return START_STICKY
                Log.d(TAG, "✅ ACTION_USER_CONFIRMED triggered with prayer=$prayer")
                if (prayer == null) {
                    Log.e(TAG, "❌ Missing prayer extra in confirmation intent")
                    return START_STICKY
                }
                handleUserConfirmation(prayer)
            }
            
            ACTION_USER_DECLINED -> {
                handleUserDecline()
            }
            
            // Service commands
            "silence_now" -> {
                val prayer = intent?.getStringExtra("prayer") ?: return START_STICKY
                activateDnd(prayer)
            }
            
            "restore_sound" -> {
                restoreNormalSoundMode()
            }
            
            // Initial setup
           
            ACTION_ALARM_TRIGGER -> {
                val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
                mode = intent.getStringExtra("mode") ?: mode // <-- ADD THIS LINE
                Log.d(TAG, "🚦 Mode received from alarm: $mode")

                runSilencerLogic(prayer)
            }

            else -> {
                

                val prayer = intent?.getStringExtra("prayer") ?: return START_STICKY
                mode = intent.getStringExtra("mode") ?: mode

                
    
                runSilencerLogic(prayer) // ✅ This is the missing piece
            }


        }
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Foreground service channel
            NotificationChannel(
                CHANNEL_ID,
                "Service Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service notifications"
                getSystemService(NotificationManager::class.java).createNotificationChannel(this)
            }

            // High-priority alerts channel
            NotificationChannel(
                ALERTS_CHANNEL_ID,
                "Prayer Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Prayer time alerts and prompts"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                getSystemService(NotificationManager::class.java).createNotificationChannel(this)
            }
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

    // ==================== MODE HANDLERS ====================
    private fun runSilencerLogic(prayer: String) {
        Log.d(TAG, "Running silencer for $prayer in $mode mode")
        Log.d(TAG, "Mode is $mode, executing logic for $prayer")

        when (mode) {
            "gps" -> handleGpsMode(prayer)
            "notification" -> sendSilencePromptNotification(prayer)
            "auto" -> activateDnd(prayer)
        }
    }

    // GPS MODE IMPLEMENTATION
    private fun handleGpsMode(prayer: String) {
        if (!isGpsEnabled()) {
            sendGpsEnableNotification()
            logEvent("gps_skipped_$prayer")
            return
        }

        startLocationChecks(prayer)
        scheduleSoundRestore(prayer, DND_DURATION)
    }

    private fun startLocationChecks(prayer: String) {
        // Cancel any existing checks
        locationHandler.removeCallbacksAndMessages(null)

        locationRunnable = object : Runnable {
            override fun run() {
                if (isInMosqueZone()) {
                    activateDnd(prayer)
                } else {
                    restoreNormalSoundMode()
                }
                locationHandler.postDelayed(this, GPS_CHECK_INTERVAL)
            }
        }
        locationHandler.post(locationRunnable)
    }

    // NOTIFICATION MODE IMPLEMENTATION
    private fun sendSilencePromptNotification(prayer: String) {
        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle("Silence for $prayer prayer?")
            .setContentText("Are you going to the mosque?")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(
                android.R.drawable.ic_lock_silent_mode, 
                "Yes",
                PendingIntent.getService(
                    this,
                    prayer.hashCode(),
                    Intent(this, MyForegroundService::class.java).apply {
                        action = ACTION_USER_CONFIRMED
                        putExtra("prayer", prayer)
                        putExtra("mode", mode)
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

    private fun sendGpsEnableNotification() {
        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle("GPS Required")
            .setContentText("Enable GPS for mosque detection")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )
            )
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_GPS_PROMPT, notification)
    }

    // ==================== CORE FUNCTIONALITY ====================
    private fun handleUserConfirmation(prayer: String) {
        Log.d(TAG, "User confirmed silence for $prayer")
        Log.d(TAG, "Mode = $mode")
        when (mode) {
            "gps" -> if (isInMosqueZone()) activateDnd(prayer)
            "auto", "notification" -> activateDnd(prayer)
        }
    }

    private fun handleUserDecline() {
        Log.d(TAG, "User declined silence")
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
            cancel(NOTIF_ID_SILENCE_PROMPT)
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
        scheduleSoundRestore(prayer, DND_DURATION)
    }



    // ==================== UTILITIES ====================
    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
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

    private fun scheduleSoundRestore(prayer: String, delayMillis: Long) {
        val restoreTime = System.currentTimeMillis() + delayMillis
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
        
    }

    private fun getImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun logEvent(event: String) {
        // Implement your analytics/logging here
        Log.d(TAG, "Event logged: $event")
    }

    override fun onDestroy() {
        locationHandler.removeCallbacksAndMessages(null)
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

    
}