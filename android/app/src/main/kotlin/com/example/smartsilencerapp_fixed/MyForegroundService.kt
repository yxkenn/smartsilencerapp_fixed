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
import android.content.SharedPreferences
import android.graphics.Color
import android.content.res.Configuration








class MyForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "ForegroundServiceChannel"
        const val ALERTS_CHANNEL_ID = "ALERTS_CHANNEL"

        const val NOTIF_ID_FOREGROUND = 1
        const val NOTIF_ID_SILENCE_PROMPT = 2
        const val NOTIF_ID_GPS_PROMPT = 3

        const val PERMISSION_REQUEST_CODE = 1004

        const val ACTION_ALARM_TRIGGER = "com.example.smartsilencerapp_fixed.ACTION_ALARM_TRIGGER"
        const val ACTION_USER_CONFIRMED = "user_confirmed_silence"
        const val ACTION_USER_DECLINED = "user_declined_silence"
        const val ACTION_GPS_TIMEOUT = "gps_wait_timeout"
        const val PREF_GPS_WAITING_ACTIVE = "gps_waiting_active"

        const val GPS_CHECK_INTERVAL = 30_000L // 30 seconds
        const val DND_DURATION = 25 * 60 * 1000L // 40 minutes
        const val GPS_WAIT_TIMEOUT = 15 * 60 * 1000L // 15 minutes


        const val PREF_SKIP_COUNT = "skip_count"
        const val PREF_MAX_SKIPS = "max_skips"
        const val PREF_REMINDER_ENABLED = "reminder_enabled"
        const val ACTION_SKIP_LIMIT_REACHED = "skip_limit_reached"


        
        // Constants for foreground service types
        const val FOREGROUND_SERVICE_TYPE_LOCATION = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        const val DND_ACTIVATION_DELAY = 5 * 60 * 1000L // 5 minutes
    }

    private var currentLocale: String = "en" // default to English

    private val TAG = "MyForegroundService"

    private val adhkarList = listOf(
        "سبحان الله",
        "الحمد لله",
        "الله أكبر",
        "لا إله إلا الله",
        "أستغفر الله",
        "اللهم صل على محمد",
        "سبحان الله وبحمده سبحان الله العظيم"
    )


    private var prayerTimesMap: Map<String, Long> = emptyMap()
    private var mode: String = "notification"

    private var dndWasAlreadyEnabled = false


    private lateinit var locationHandler: Handler
    private lateinit var locationRunnable: Runnable
    private var gpsWaitStartTime = 0L

    private val mosqueLat = 33.815077007411155
    private val mosqueLon = 2.864483237898059 
    private val mosqueRadiusMeters = 150

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
             currentLocale = intent?.getStringExtra("locale") 
            ?: getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                .getString("current_locale", "en") 
            ?: "en"

            // Update prefs if we got a new locale from intent
            intent?.getStringExtra("locale")?.let { newLocale ->
                if (newLocale != currentLocale) {
                    currentLocale = newLocale
                    getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString("current_locale", currentLocale)
                        .apply()
                }
            }
            // ✅ Extract mode and prayer before starting anything
            // Avoid fallback to potentially incorrect value
            val newMode = intent?.getStringExtra("mode")
            if (newMode != null) {
                mode = newMode
            }

            currentPrayer = intent?.getStringExtra("prayer") ?: currentPrayer

            val prefs = getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            currentLocale = prefs.getString("current_locale", "en") ?: "en"
            
            // Then process the intent which might contain a newer locale
            val newLocale = intent?.getStringExtra("locale") ?: currentLocale
            if (newLocale != currentLocale) {
                currentLocale = newLocale
                prefs.edit().putString("current_locale", currentLocale).apply()
                Log.d(TAG, "Locale updated to: $currentLocale")
            }

            Log.d(TAG, "Service started with action=${intent?.action}, mode=$mode")

            // ✅ Build notification after mode is known
            val adhkar = getRandomAdhkar()
            val notification = buildForegroundNotification(adhkar)


            // ✅ Handle foreground service with location type if needed
            startForegroundSafe(notification)

            


            // ✅ Process the intent actions
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
                    Log.d(TAG, "Received restore_sound command")
                    restoreNormalSoundMode()
                    stopSelf()  // ✅ Stop service if work is done
                }

                ACTION_ALARM_TRIGGER -> {
                    val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
                    val actionMode = intent.getStringExtra("mode")
                    if (actionMode != null) {
                        mode = actionMode
                    }

                    Log.d(TAG, "Alarm triggered for $prayer in $mode mode")
                    runSilencerLogic(prayer)
                }

                "GPS_NOTIFICATION_DISMISSED" -> {
                    val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
                    Log.d(TAG, "GPS notification dismissed for $prayer")
                    // Optional: handle dismissal logic
                    getPrefs().edit().putBoolean(PREF_GPS_WAITING_ACTIVE, false).apply()
                }

                ACTION_SKIP_LIMIT_REACHED -> {
                    val prayer = intent.getStringExtra("prayer") ?: return START_STICKY
                    showSkipLimitNotification(prayer)
                }

                else -> {
                    val prayer = intent?.getStringExtra("prayer") ?: return START_STICKY
                    val actionMode = intent.getStringExtra("mode")
                    if (actionMode != null) {
                        mode = actionMode
                    }

                    Log.d(TAG, "Default case - running silencer for $prayer")
                    runSilencerLogic(prayer)
                }
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception in foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in foreground service", e)
            return START_REDELIVER_INTENT
        }

        return START_STICKY
    }

    private fun getRandomAdhkar(): String {
        return adhkarList.random()
    }






    private fun getPrefs(): SharedPreferences {
        return getSharedPreferences("silencer_prefs", Context.MODE_PRIVATE)
    }

    private fun getPrayerSkipKey(prayer: String): String {
        return "skip_count_$prayer"
    }


    private fun incrementSkipCount(prayer: String): Boolean {
        val prefs = getPrefs()
        val generalSkips = prefs.getInt(PREF_SKIP_COUNT, 0)
        val maxSkips = prefs.getInt(PREF_MAX_SKIPS, 3)
        val newGeneralSkips = generalSkips + 1

        // Update general skip count
        prefs.edit().putInt(PREF_SKIP_COUNT, newGeneralSkips).apply()

        Log.d(TAG, "General skips: $newGeneralSkips / $maxSkips")

        // Update prayer-specific skip count
        val prayerKey = getPrayerSkipKey(prayer)
        val prayerSkips = prefs.getInt(prayerKey, 0) + 1
        prefs.edit().putInt(prayerKey, prayerSkips).apply()

        Log.d(TAG, "Prayer-specific skips for $prayer: $prayerSkips / $maxSkips")

        // If either limit reached
        if (newGeneralSkips >= maxSkips) {
            resetSkipCount() // Reset general after reaching limit
            return true
        }

        if (prayerSkips >= maxSkips) {
            showPrayerSpecificSkipNotification(prayer)
        }

        return false
    }



    private fun resetSkipCount() {
        val prefs = getPrefs()
        val currentSkips = prefs.getInt(PREF_SKIP_COUNT, 0)
        Log.d(TAG, "Resetting skip count from $currentSkips to 0")
        prefs.edit().putInt(PREF_SKIP_COUNT, 0).apply()
    }

    private fun shouldShowReminder(): Boolean {
        val prefs = getPrefs()
        if (!prefs.getBoolean(PREF_REMINDER_ENABLED, true)) {
            Log.d(TAG, "Reminders are disabled in settings")
            return false
        }
        
        val currentSkips = prefs.getInt(PREF_SKIP_COUNT, 0)
        val maxSkips = prefs.getInt(PREF_MAX_SKIPS, 3)
        val shouldShow = currentSkips < maxSkips
        
        Log.d(TAG, "Checking if should show reminder. Current skips: $currentSkips, Max skips: $maxSkips, Should show: $shouldShow")
        return shouldShow
    }


    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Foreground service channel - set to minimum importance
            NotificationChannel(CHANNEL_ID, "Foreground Service", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Background service notifications"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET // Hide from lock screen
            }.also { channel ->
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }

            // Keep your existing alerts channel as is
            NotificationChannel(ALERTS_CHANNEL_ID, "Prayer Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Important prayer time notifications"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                setBypassDnd(true)
            }.also { channel ->
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
        }
    }

    private fun showSkipLimitNotification(prayer: String) {
        val localizedPrayer = getLocalizedPrayerName(this, prayer)

        val title = getLocalizedString(R.string.notification_skip_limit_title)
        val message = getLocalizedString(R.string.notification_skip_limit_message, localizedPrayer)

        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_SILENCE_PROMPT + 1000, notification)
    }

    private fun showPrayerSpecificSkipNotification(prayer: String) {
        val localizedPrayer = getLocalizedPrayerName(this, prayer)

        val title = getLocalizedString(R.string.notification_prayer_skip_title, localizedPrayer)
        val message = getLocalizedString(R.string.notification_prayer_skip_message, localizedPrayer)

        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_SILENCE_PROMPT + prayer.hashCode(), notification)
    }





    private fun buildForegroundNotification(adhkar: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID) // Use CHANNEL_ID instead of ALERTS_CHANNEL_ID
            .setContentTitle("📿 $adhkar")
            .setSmallIcon(R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_MIN) // Set to minimum priority
            .setOngoing(true)
            .setShowWhen(false) // Don't show time
            .setCategory(Notification.CATEGORY_SERVICE) // Mark as service notification
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Hide from lock screen
            .setSound(null) // No sound
            .setVibrate(null) // No vibration
            .build()
    }



   private fun handleGpsMode(prayer: String) {
        if (!checkLocationPermissions()) {
            Log.w(TAG, "Cannot start GPS mode - missing permissions")
            // Fallback to notification mode
            mode = "notification"
            sendSilencePromptNotification(prayer)
            return
        }

        if (!isGpsEnabled()) {
            Log.d(TAG, "GPS not enabled - starting waiting loop")
            // Always show notification when first detecting GPS is off
            showGpsWaitingNotification(prayer)
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
                
            
                val localizedPrayer = getLocalizedPrayerName(this, prayer)

                sendNotification(
                    getLocalizedString(R.string.notification_auto_silence_title),
                    getLocalizedString(R.string.notification_auto_silence_message, localizedPrayer),
                    NOTIF_ID_SILENCE_PROMPT,
                    autoCancel = true
                )
            }
        }
    }

  private fun startGpsWaitingLoop(prayer: String) {
        gpsWaitStartTime = System.currentTimeMillis()
        
        // Mark that we're actively waiting for GPS
        getPrefs().edit().putBoolean(PREF_GPS_WAITING_ACTIVE, true).apply()
        
        logEvent("gps_waiting_$prayer")

        val gpsCheckRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - gpsWaitStartTime
                
                if (elapsed > GPS_WAIT_TIMEOUT) {
                    Log.w(TAG, "GPS wait timeout reached for $prayer")
                    // Clear waiting state
                    getPrefs().edit().putBoolean(PREF_GPS_WAITING_ACTIVE, false).apply()
                    handleGpsTimeout(prayer)
                    return
                }

                if (isGpsEnabled()) {
                    Log.d(TAG, "GPS now enabled - proceeding with $prayer prayer")
                    // Clear waiting state
                    getPrefs().edit().putBoolean(PREF_GPS_WAITING_ACTIVE, false).apply()
                    startLocationChecks(prayer)
                } else {
                    Log.d(TAG, "Still waiting for GPS (${(GPS_WAIT_TIMEOUT - elapsed)/1000}s remaining)...")
                    // Update the existing notification
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
                            if (isInMosqueZone()) {
                                activateDnd(it)
                            } else {
                                restoreNormalSoundMode()
                            }
                            locationHandler.postDelayed(this, GPS_CHECK_INTERVAL)
                        } else {
                            Log.d(TAG, "GPS disabled during checks - stopping location monitoring")
                            restoreNormalSoundMode()
                            // Don't restart waiting loop - just stop monitoring
                            stopForeground(STOP_FOREGROUND_REMOVE)
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
        val hasFineLocation = checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasCoarseLocation = checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val hasFgsLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            checkSelfPermission(
                Manifest.permission.FOREGROUND_SERVICE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        
        // For GPS mode we need fine location, for others coarse is acceptable
        return if (mode == "gps") {
            hasFineLocation && hasFgsLocation
        } else {
            (hasFineLocation || hasCoarseLocation) && hasFgsLocation
        }
    }

    private fun showGpsWaitingNotification(prayer: String) {
        try {
            val localizedPrayer = getLocalizedPrayerName(this, prayer)

            // Intent to open location settings - improved version
            val gpsSettingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                // Add package for specific manufacturers if needed
                `package` = when {
                    Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) -> "com.android.settings"
                    Build.MANUFACTURER.equals("huawei", ignoreCase = true) -> "com.android.settings"
                    else -> null
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                gpsSettingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
            )

            // Intent for notification dismissal
            val deleteIntent = Intent(this, MyForegroundService::class.java).apply {
                action = "GPS_NOTIFICATION_DISMISSED"
                putExtra("prayer", prayer)
            }

            val deletePendingIntent = PendingIntent.getService(
                this,
                0,
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
            )

            // Notification content
            val title = getLocalizedString(R.string.notification_gps_enable_title, localizedPrayer)
            val message = getLocalizedString(R.string.notification_gps_enable_message)

            val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent) // This will open GPS settings when tapped
                .setDeleteIntent(deletePendingIntent)
                .setAutoCancel(true)
                .setOngoing(false)
                .build()

            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_GPS_PROMPT, notification)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show GPS notification", e)
            // Fallback to simpler intent if the specific one fails
            try {
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }

                val fallbackPendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    fallbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )

                val fallbackNotification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
                    .setContentTitle(getLocalizedString(R.string.fallback_gps_title))
                    .setContentText(getLocalizedString(R.string.fallback_gps_message))
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(fallbackPendingIntent)
                    .setAutoCancel(true)
                    .build()

                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID_GPS_PROMPT, fallbackNotification)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to show fallback GPS notification", e2)
            }
        }
    }


    private fun startForegroundSafe(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use the correct service type based on mode
                val serviceType = when {
                    mode == "gps" && AlarmReceiver.hasLocationPermissions(this) -> 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    else -> 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(NOTIF_ID_FOREGROUND, notification, serviceType)
            } else {
                startForeground(NOTIF_ID_FOREGROUND, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground", e)
            // Fallback to regular start if foreground fails
            try {
                startForeground(NOTIF_ID_FOREGROUND, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Couldn't start foreground at all", e2)
                stopSelf()
            }
        }
    }


    private fun sendSilencePromptNotification(prayer: String) {
        // Store the time when notification was shown
        getPrefs().edit().putLong("notification_time_$prayer", System.currentTimeMillis()).apply()
        
        val localizedPrayer = getLocalizedPrayerName(this, prayer)
        val title = getLocalizedString(R.string.notification_silence_prompt_title, localizedPrayer)
        val message = getLocalizedString(R.string.notification_silence_prompt_message)
        val yesText = getLocalizedString(R.string.notification_action_yes)
        val noText = getLocalizedString(R.string.notification_action_no)

        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(
                android.R.drawable.ic_lock_silent_mode,
                yesText,
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
                noText,
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
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                val currentFilter = notificationManager.currentInterruptionFilter
                if (currentFilter == NotificationManager.INTERRUPTION_FILTER_NONE) {
                    Log.d(TAG, "DND is already enabled - skipping activation")
                    setAppEnabledDnd(false)  // mark that we didn't touch it
                    return
                }

                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                setAppEnabledDnd(true)
            } else {
                Log.w(TAG, "DND access NOT granted. Prompting user.")
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        }

        scheduleSoundRestore(prayer, DND_DURATION)
    }




    // Add this helper method in MyForegroundService
  



    private fun restoreNormalSoundMode() {
        Log.d(TAG, "Restoring normal sound mode")
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                if (!wasAppEnabledDnd()) {
                    Log.d(TAG, "Skipping restore — DND was already enabled before app ran")
                    return
                }

                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                setAppEnabledDnd(false)  // clear flag after restoring
            } else {
                Log.w(TAG, "Cannot restore sound — no DND access")
            }
        }
    }


    private fun setAppEnabledDnd(enabled: Boolean) {
        getPrefs().edit().putBoolean("app_enabled_dnd", enabled).apply()
    }

    private fun wasAppEnabledDnd(): Boolean {
        return getPrefs().getBoolean("app_enabled_dnd", false)
    }




    private fun getLocalizedString(resId: Int, vararg formatArgs: Any): String {
        Log.d(TAG, "Getting string for resId: $resId with locale: $currentLocale")
        try {
            val configuration = Configuration(resources.configuration)
            configuration.setLocale(Locale(currentLocale))
            val localizedContext = createConfigurationContext(configuration)
            val result = localizedContext.resources.getString(resId, *formatArgs)
            Log.d(TAG, "Localized string result: $result")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error in getLocalizedString: ", e)
            return resources.getString(resId, *formatArgs) // fallback
        }
    }
    
    private fun getLocalizedPrayerName(context: Context, prayer: String): String {
        return try {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale(currentLocale))
            val localizedContext = context.createConfigurationContext(configuration)
            
            when (prayer.lowercase()) {
                "fajr" -> localizedContext.getString(R.string.prayer_fajr)
                "dhuhr" -> localizedContext.getString(R.string.prayer_dhuhr)
                "asr" -> localizedContext.getString(R.string.prayer_asr)
                "maghrib" -> localizedContext.getString(R.string.prayer_maghrib)
                "isha" -> localizedContext.getString(R.string.prayer_isha)
                else -> prayer
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getLocalizedPrayerName: ", e)
            // Fallback to English if localization fails
            when (prayer.lowercase()) {
                "fajr" -> "Fajr"
                "dhuhr" -> "Dhuhr"
                "asr" -> "Asr"
                "maghrib" -> "Maghrib"
                "isha" -> "Isha"
                else -> prayer
            }
        }
    }


    
    private fun handleGpsTimeout(prayer: String) {
        Log.d(TAG, "Handling GPS timeout for prayer: $prayer")
        val limitReached = incrementSkipCount(currentPrayer ?: return)

        if (limitReached) {
            Log.w(TAG, "Skip limit reached after GPS timeout - showing notification")
            showSkipLimitNotification(prayer)
        }
        logEvent("gps_timeout_$prayer")
        stopForeground(true)
          
        val localizedPrayer = getLocalizedPrayerName(this, prayer)
  
        sendNotification(
            getLocalizedString(R.string.notification_gps_timeout_title),
            getLocalizedString(R.string.notification_gps_timeout_message, localizedPrayer),
            NOTIF_ID_GPS_PROMPT,
            autoCancel = true
        )
    }



     // In MyForegroundService
    fun updateSettingsFromFlutter(prefs: SharedPreferences, reminderEnabled: Boolean, maxSkips: Int) {
        prefs.edit()
            .putBoolean(PREF_REMINDER_ENABLED, reminderEnabled)
            .putInt(PREF_MAX_SKIPS, maxSkips)
            .apply()
        Log.d(TAG, "Updated settings: reminderEnabled=$reminderEnabled, maxSkips=$maxSkips")
    }


    


    
    private fun updateGpsWaitingNotification(prayer: String, elapsed: Long) {
        val timeLeft = (GPS_WAIT_TIMEOUT - elapsed) / 1000
        val mins = timeLeft / 60
        val secs = timeLeft % 60

        val localizedPrayer = getLocalizedPrayerName(this, prayer)
        val title = getLocalizedString(R.string.notification_gps_enable_title, localizedPrayer)
        val message = getLocalizedString(
            R.string.notification_gps_enable_message_with_timer,
            String.format("%dm %ds", mins, secs)
        )

        val notification = NotificationCompat.Builder(this, ALERTS_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
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
        if (!checkLocationPermissions()) return false
        val location = getLastKnownLocation() ?: return false
        val distance = location.distanceTo(Location("").apply {
            latitude = mosqueLat
            longitude = mosqueLon
        })
        Log.d(TAG, "Mosque distance: ${"%.1f".format(distance)} meters")
        return distance <= mosqueRadiusMeters
    }

    private fun getLastKnownLocation(): Location? {
        if (!checkLocationPermissions()) return null

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val hasFine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "Permissions: fine=$hasFine, coarse=$hasCoarse")

        val providers = locationManager.getProviders(true)
        val locations = providers.mapNotNull { provider ->
            try {
                val location = locationManager.getLastKnownLocation(provider)
                if (location != null) {
                    Log.d(TAG, "✅ Location from $provider: $location")
                } else {
                    Log.w(TAG, "⚠️ No location from $provider")
                }
                location
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException accessing $provider", e)
                null
            }
        }

        val bestLocation = locations.maxByOrNull { it.time }

        if (bestLocation == null && hasCoarse) {
            // Fallback: try NETWORK_PROVIDER directly if no recent locations were found
            try {
                val fallbackLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                Log.w(TAG, "⚠️ Fallback to NETWORK_PROVIDER: $fallbackLocation")
                return fallbackLocation
            } catch (e: Exception) {
                Log.e(TAG, "Error accessing NETWORK_PROVIDER fallback", e)
            }
        }

        Log.d(TAG, "📍 Best location selected: $bestLocation")
        return bestLocation
    }


   private fun handleUserConfirmation(prayer: String) {
        Log.d(TAG, "User confirmed silence for $prayer")
        resetSkipCount()
        
        val prefs = getPrefs()
        val confirmationTime = System.currentTimeMillis()
        val notificationTime = prefs.getLong("notification_time_$prayer", 0L)
        
        // Check if 5 minutes have passed since notification was shown
        if (notificationTime > 0 && (confirmationTime - notificationTime) > DND_ACTIVATION_DELAY) {
            Log.d(TAG, "5 minutes have passed - activating DND immediately")
            activateDnd(prayer)
            
            // Show immediate activation notification
            val localizedPrayer = getLocalizedPrayerName(this, prayer)
            sendNotification(
                getLocalizedString(R.string.notification_silence_activated_title),
                getLocalizedString(R.string.notification_silence_activated_message, localizedPrayer),
                NOTIF_ID_SILENCE_PROMPT,
                autoCancel = true
            )
        } else {
            Log.d(TAG, "Within 5 minute window - will activate in ${DND_ACTIVATION_DELAY / (60 * 1000)} minutes")
            // Original logic - schedule for 5 minutes later
            val localizedPrayer = getLocalizedPrayerName(this, prayer)
            sendNotification(
                getLocalizedString(R.string.notification_silence_scheduled_title),
                getLocalizedString(R.string.notification_silence_scheduled_message, localizedPrayer),
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
        
        // Schedule sound restore after total duration (either immediate + DND_DURATION or 5 min delay + DND_DURATION)
        scheduleSoundRestore(prayer, DND_DURATION)
    }

    private fun handleUserDecline() {
        Log.d(TAG, "Handling user decline of prayer silence")
        val limitReached = incrementSkipCount(currentPrayer ?: return)


        if (limitReached) {
            Log.w(TAG, "Skip limit reached after user decline - showing notification")
            currentPrayer?.let { 
                
                showSkipLimitNotification(it) 
            } ?: run {
                Log.e(TAG, "Couldn't show skip limit notification - current prayer is null")
            }
        } else {
            Log.d(TAG, "Skip count incremented but limit not reached yet")
        }
        
        Log.d(TAG, "User declined silence - canceling notification")
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).apply {
            cancel(NOTIF_ID_SILENCE_PROMPT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isNotificationPolicyAccessGranted) {
                Log.d(TAG, "Notification policy access granted")
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
