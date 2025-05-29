package com.example.smartsilencerapp_fixed

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*
import android.content.pm.ServiceInfo



class MyForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "ForegroundServiceChannel"
        const val ALERTS_CHANNEL_ID = "ALERTS_CHANNEL"

        const val NOTIF_ID_FOREGROUND = 1
        const val NOTIF_ID_SILENCE_PROMPT = 2
        const val NOTIF_ID_GPS_PROMPT = 3

        const val ACTION_ALARM_TRIGGER = "com.example.smartsilencerapp_fixed.ACTION_ALARM_TRIGGER"
        const val ACTION_USER_CONFIRMED = "user_confirmed_silence"
        const val ACTION_USER_DECLINED = "user_declined_silence"
        const val ACTION_GPS_TIMEOUT = "gps_wait_timeout"

        const val GPS_CHECK_INTERVAL = 30_000L // 30 seconds
        const val DND_DURATION = 40 * 60 * 1000L // 40 minutes
        const val GPS_WAIT_TIMEOUT = 15 * 60 * 1000L // 15 minutes
        
        // Constants for foreground service types
        const val FOREGROUND_SERVICE_TYPE_LOCATION = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        const val DND_ACTIVATION_DELAY = 5 * 60 * 1000L // 5 minutes
    }

    private val TAG = "MyForegroundService"

    private var prayerTimesMap: Map<String, Long> = emptyMap()
    private var mode: String = "notification"

    private lateinit var locationHandler: Handler
    private lateinit var locationRunnable: Runnable
    private var gpsWaitStartTime = 0L

    private val mosqueLat = 33.815115
    private val mosqueLon = 2.864548
    private val mosqueRadiusMeters = 100.0

    private lateinit var alarmManager: AlarmManager
    private lateinit var pendingAlarmIntent: PendingIntent

    private var currentPrayer: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        locationHandler = Handler(Looper.getMainLooper())
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Build the foreground notification
            val notification = buildForegroundNotification("Initializing silencer...")
            
            // Start as foreground service with proper type
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIF_ID_FOREGROUND, 
                        notification, 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } catch (e: IllegalArgumentException) {
                    // Fallback for manifest mismatch
                    Log.w(TAG, "Using fallback foreground service start", e)
                    startForeground(NOTIF_ID_FOREGROUND, notification)
                }
            } else {
                startForeground(NOTIF_ID_FOREGROUND, notification)
            }

            // Process the incoming intent
            mode = intent?.getStringExtra("mode") ?: mode
            Log.d(TAG, "Service started with action=${intent?.action}, mode=$mode")

            when (intent?.action) {
                ACTION_USER_CONFIRMED -> {
                    val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
                    Log.d(TAG, "User confirmed silence for $prayer")
                    handleUserConfirmation(prayer)
                }
                
                ACTION_USER_DECLINED -> {
                    Log.d(TAG, "User declined silence")
                    handleUserDecline()
                }
                
                "silence_now" -> {
                    val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
                    Log.d(TAG, "Immediate silence requested for $prayer")
                    activateDnd(prayer)
                }
                
                "restore_sound" -> {
                    Log.d(TAG, "Restoring normal sound mode")
                    restoreNormalSoundMode()
                }
                
                ACTION_ALARM_TRIGGER -> {
                    val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
                    mode = intent.getStringExtra("mode") ?: mode
                    Log.d(TAG, "Alarm triggered for $prayer in $mode mode")
                    runSilencerLogic(prayer)
                }

                "GPS_NOTIFICATION_DISMISSED" -> {
                    val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
                    Log.d(TAG, "GPS notification dismissed for $prayer")
                    // You could add additional handling here if needed
                }
            
                
                else -> {
                    val prayer = intent?.getStringExtra("prayer") ?: return START_STICKY
                    mode = intent?.getStringExtra("mode") ?: mode
                    Log.d(TAG, "Default case - running silencer for $prayer")
                    runSilencerLogic(prayer)
                }
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in foreground service", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in foreground service", e)
            // Try to restart if possible
            return START_REDELIVER_INTENT
        }
        
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Service Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Background service notifications"
                getSystemService(NotificationManager::class.java).createNotificationChannel(this)
            }

            NotificationChannel(ALERTS_CHANNEL_ID, "Prayer Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Prayer time alerts and prompts"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                getSystemService(NotificationManager::class.java).createNotificationChannel(this)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )
            )
            .setOngoing(true)
            .build()
    }

    private fun handleGpsMode(prayer: String) {
        Log.d(TAG, "Handling GPS mode for $prayer")
        if (!isGpsEnabled()) {
            Log.d(TAG, "GPS not enabled - starting waiting loop")
            startGpsWaitingLoop(prayer)
        } else {
            Log.d(TAG, "GPS enabled - starting location checks")
            startLocationChecks(prayer)
        }
    }



    private fun runSilencerLogic(prayer: String) {
        Log.d(TAG, "Running silencer for $prayer in $mode mode")
        when (mode) {
            "gps" -> handleGpsMode(prayer)
            "notification" -> sendSilencePromptNotification(prayer)
            "auto" -> {
                // For auto mode, schedule activation after 5 minutes
                val activateIntent = Intent(this, MyForegroundService::class.java).apply {
                    action = "silence_now"
                    putExtra("prayer", prayer)
                }
                
                val pendingActivateIntent = PendingIntent.getService(
                    this, 
                    prayer.hashCode() + 2,
                    activateIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )
                
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + DND_ACTIVATION_DELAY,
                    pendingActivateIntent
                )
                
                scheduleSoundRestore(prayer, DND_ACTIVATION_DELAY + DND_DURATION)
                
                sendNotification(
                    "Auto Silence Scheduled",
                    "Your phone will be silenced in 5 minutes for $prayer prayer",
                    NOTIF_ID_SILENCE_PROMPT,
                    autoCancel = true
                )
            }
        }
    }

    private fun startGpsWaitingLoop(prayer: String) {
        gpsWaitStartTime = System.currentTimeMillis()
        locationHandler.removeCallbacksAndMessages(null)
        showGpsWaitingNotification(prayer)
        logEvent("gps_waiting_$prayer")

        val gpsCheckRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - gpsWaitStartTime
                
                if (elapsed > GPS_WAIT_TIMEOUT) {
                    Log.w(TAG, "GPS wait timeout reached for $prayer")
                    handleGpsTimeout(prayer)
                    return
                }

                if (isGpsEnabled()) {
                    Log.d(TAG, "GPS now enabled - proceeding with $prayer prayer")
                    startLocationChecks(prayer)
                } else {
                    Log.d(TAG, "Still waiting for GPS (${(GPS_WAIT_TIMEOUT - elapsed)/1000}s remaining)...")
                    updateGpsWaitingNotification(prayer, elapsed)
                    locationHandler.postDelayed(this, GPS_CHECK_INTERVAL)
                }
            }
        }
        locationHandler.post(gpsCheckRunnable)
    }

    private fun startLocationChecks(prayer: String) {
        if (!checkLocationPermissions()) {
            Log.w(TAG, "Location permissions not granted")
            sendNotification(
                "Permissions Required",
                "Please grant location permissions for mosque detection",
                NOTIF_ID_GPS_PROMPT,
                autoCancel = true
            )
            return
        }

        locationHandler.removeCallbacksAndMessages(null)
        currentPrayer = prayer

        // Dismiss the GPS prompt notification since we're now checking location
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID_GPS_PROMPT)

        locationRunnable = object : Runnable {
            override fun run() {
                try {
                    currentPrayer?.let {
                        if (isGpsEnabled()) {
                            // Dismiss the notification when GPS is enabled
                            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                                .cancel(NOTIF_ID_GPS_PROMPT)
                                
                            if (isInMosqueZone()) {
                                activateDnd(it)
                            } else {
                                restoreNormalSoundMode()
                            }
                            locationHandler.postDelayed(this, GPS_CHECK_INTERVAL)
                        } else {
                            Log.d(TAG, "GPS disabled during checks - restarting waiting loop")
                            restoreNormalSoundMode()
                            startGpsWaitingLoop(prayer)
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Location permission error", e)
                    restoreNormalSoundMode()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
        locationHandler.post(locationRunnable)
    }

    private fun checkLocationPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun showGpsWaitingNotification(prayer: String) {
        try {
            // Create intent to open location settings - using the correct action
            val gpsSettingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                // Add package name for some devices
                `package` = when {
                    Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) -> "com.android.settings"
                    else -> null
                }
            }
            
            // Create pending intent with proper flags
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                gpsSettingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Create delete intent that will be triggered when notification is dismissed
            val deleteIntent = Intent(this, MyForegroundService::class.java).apply {
                action = "GPS_NOTIFICATION_DISMISSED"
                putExtra("prayer", prayer)
            }
            val deletePendingIntent = PendingIntent.getService(
                this,
                0,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                .setContentTitle("📍 Enable Location for $prayer")
                .setContentText("Tap to enable location services")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setDeleteIntent(deletePendingIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()

            // Show as regular notification
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_GPS_PROMPT, notification)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show GPS notification", e)
            // Fallback with simpler intent
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val fallbackNotification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                    .setContentTitle("Enable Location")
                    .setContentText("Please enable location services")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(PendingIntent.getActivity(
                        this, 0, fallbackIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ))
                    .setAutoCancel(true)
                    .build()
                
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID_GPS_PROMPT, fallbackNotification)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show fallback notification", e)
            }
        }
    }




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
                    this, prayer.hashCode(),
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
                    this, 1,
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
                    this, 0,
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )
            )
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_GPS_PROMPT, notification)
    }

    private fun sendNotification(
        title: String,
        text: String,
        id: Int,
        ongoing: Boolean = false,
        autoCancel: Boolean = false,
        intent: Intent? = null
    ) {
        val builder = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)

        intent?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )
            )
        }

        if (ongoing) {
            startForeground(id, builder.build())
        } else {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, builder.build())
        }
    }

    // ==================== DND + LOGIC ====================

    private fun activateDnd(prayer: String) {
        Log.d(TAG, "Activating DND for $prayer")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).takeIf {
                it.isNotificationPolicyAccessGranted
            }?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }
        scheduleSoundRestore(prayer, DND_DURATION)
    }

    private fun restoreNormalSoundMode() {
        Log.d(TAG, "Restoring normal sound mode")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).takeIf {
                it.isNotificationPolicyAccessGranted
            }?.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }
    }
    
    private fun handleGpsTimeout(prayer: String) {
        logEvent("gps_timeout_$prayer")
        stopForeground(true)
        sendNotification(
            "GPS Timeout",
            "Couldn't detect location for $prayer prayer",
            NOTIF_ID_GPS_PROMPT,
            autoCancel = true
        )
    }


    
    private fun updateGpsWaitingNotification(prayer: String, elapsed: Long) {
        val timeLeft = (GPS_WAIT_TIMEOUT - elapsed) / 1000
        val mins = timeLeft / 60
        val secs = timeLeft % 60
        
        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle("📍 Enable GPS for $prayer")
            .setContentText("Time remaining: ${mins}m ${secs}s")
            .setSmallIcon(R.drawable.ic_lock_silent_mode)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_GPS_PROMPT, notification)
    }

    private fun scheduleSoundRestore(prayer: String, delayMillis: Long) {
        val restoreTime = System.currentTimeMillis() + delayMillis
        Log.d(TAG, "Scheduling sound restore at ${Date(restoreTime)}")

        val intent = Intent(this, RestoreSoundReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, restoreTime, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, restoreTime, pendingIntent)
        }
    }

    // ==================== GPS UTILITIES ====================
    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || 
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
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

    private fun handleUserConfirmation(prayer: String) {
        Log.d(TAG, "User confirmed silence for $prayer - will activate in 5 minutes")
        
        // Show a notification that DND will be activated soon
        sendNotification(
            "Silence Scheduled",
            "Your phone will be silenced in 5 minutes for $prayer prayer",
            NOTIF_ID_SILENCE_PROMPT,
            autoCancel = true
        )
        
        // Schedule DND activation after 5 minutes
        val activateIntent = Intent(this, MyForegroundService::class.java).apply {
            action = "silence_now"
            putExtra("prayer", prayer)
        }
        
        val pendingActivateIntent = PendingIntent.getService(
            this, 
            prayer.hashCode() + 1, // Different ID from confirmation
            activateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
        )
        
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + DND_ACTIVATION_DELAY,
            pendingActivateIntent
        )
        
        // Schedule sound restore after total duration (5 min delay + DND duration)
        scheduleSoundRestore(prayer, DND_ACTIVATION_DELAY + DND_DURATION)
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

    // ==================== UTILITIES ====================

    private fun getImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    private fun logEvent(event: String) {
        Log.d(TAG, "Event logged: $event")
    }

    private fun cancelAlarm() {
        if (::pendingAlarmIntent.isInitialized) {
            alarmManager.cancel(pendingAlarmIntent)
        }
    }

    override fun onDestroy() {
        locationHandler.removeCallbacksAndMessages(null)
        cancelAlarm()
        restoreNormalSoundMode()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).apply {
        cancel(NOTIF_ID_FOREGROUND)
        cancel(NOTIF_ID_SILENCE_PROMPT)
        cancel(NOTIF_ID_GPS_PROMPT)
    }
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
