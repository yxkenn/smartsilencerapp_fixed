package com.example.smartsilencerapp_fixed

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.smartsilencerapp_fixed.PrayerDayPreference
import java.util.Calendar
import java.util.Date
import android.net.Uri
import androidx.core.app.NotificationCompat
import android.app.NotificationManager

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "AlarmReceiver"
        const val KEY_PRAYER_PREFIX = "prayerTime_"
        const val KEY_MODE = "silencer_mode"
        const val PREFS_NAME = "SmartSilencerPrefs"
        const val MODE_NOTIFICATION = "notification"
        const val MODE_GPS = "gps"
        const val MODE_AUTO = "auto"
        const val ACTION_SKIP_LIMIT_REACHED = "skip_limit_reached"

        val PRAYER_ORDER = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")

        fun getCurrentMode(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_MODE, MODE_NOTIFICATION) ?: MODE_NOTIFICATION
        }

        private fun getImmutableFlag(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        }


        fun logAllScheduledAlarms(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentMode = getCurrentMode(context)

            Log.d(TAG, "===== CURRENT TIME: ${Date()} =====")
            Log.d(TAG, "===== ACTIVE MODE: $currentMode =====")
            Log.d(TAG, "===== SCHEDULED ALARMS =====")

            PRAYER_ORDER.forEach { prayer ->
                val prayerTime = prefs.getLong("$KEY_PRAYER_PREFIX$prayer", -1L)
                if (prayerTime > 0) {
                    val alarmTime = prayerTime - (3 * 60 * 1000)
                    val timeUntil = alarmTime - System.currentTimeMillis()

                    Log.d(TAG, "Prayer: $prayer")
                    Log.d(TAG, "  - Prayer time: ${Date(prayerTime)}")
                    Log.d(TAG, "  - Alarm scheduled: ${Date(alarmTime)}")
                    Log.d(TAG, "  - Time until alarm: ${timeUntil / 1000} seconds")
                    Log.d(TAG, "  - Alarm passed: ${timeUntil < 0}")
                } else {
                    Log.d(TAG, "Prayer: $prayer - NOT SCHEDULED (invalid time)")
                }
            }
        }

        fun hasLocationPermissions(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED && 
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || 
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED)
        }

        fun hasRequiredPermissions(context: Context): Boolean {
            // Location permissions (either fine or coarse is sufficient)
            val hasFineLoc = ContextCompat.checkSelfPermission(context, 
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasCoarseLoc = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            // Foreground service location (required for Android 10+)
            val hasFgsLoc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context,
                    Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else true
            
            // Exact alarms (required for Android 12+)
            val hasExactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false)
            } else true

            Log.d(TAG, "Permission Check - FineLoc: $hasFineLoc, CoarseLoc: $hasCoarseLoc, " +
                    "FgsLoc: $hasFgsLoc, ExactAlarms: $hasExactAlarms")
            
            return (hasFineLoc || hasCoarseLoc) && hasFgsLoc && hasExactAlarms
        }


    }

    private fun isValidMode(mode: String): Boolean {
        return mode in listOf(MODE_NOTIFICATION, MODE_GPS, MODE_AUTO)
    }

    private fun getPrayerPreference(context: Context, prayer: String, dayOfWeek: Int): PrayerPreference {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "pref_${dayOfWeek}_$prayer"

        return try {
            val prefValue = prefs.getString(key, "DEFAULT") ?: "DEFAULT"
            Log.d(TAG, "Loading prayer preference - key: $key, value: $prefValue")
            PrayerPreference.valueOf(prefValue)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading prayer preference for key: $key", e)
            PrayerPreference.DEFAULT
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "══════════════════════════════════════")
        Log.d(TAG, "⏰ ALARM RECEIVED at ${Date()}")

        // Unified locale fallback: intent -> prefs -> default
        val locale = intent?.getStringExtra("locale")?.takeIf { it.isNotEmpty() }
            ?: context.getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                .getString("current_locale", "en")
            ?: "en"
        
        Log.d(TAG, "Using locale: $locale")
        Log.d(TAG, "Using locale: $locale (source: ${
            when {
                intent?.hasExtra("locale") == true -> "intent"
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .contains("current_locale") -> "prefs"
                else -> "default"
            }
        })")
        

        val prayerName = intent?.getStringExtra("prayer")
            ?: intent?.action?.substringAfter("prayer_alarm_")
            ?: "unknown"
        Log.d(TAG, "🔔 Handling alarm for prayer: $prayerName")

        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prayerPref = getPrayerPreference(context, prayerName, currentDay)

        var currentMode = prefs.getString(KEY_MODE, MODE_NOTIFICATION) ?: MODE_NOTIFICATION

        if (!isValidMode(currentMode)) {
            Log.w(TAG, "⚠️ Invalid mode detected: $currentMode. Defaulting to notification")
            currentMode = MODE_NOTIFICATION
            prefs.edit().putString(KEY_MODE, currentMode).apply()
        }

        when (prayerPref) {
            PrayerPreference.EXCLUDED -> {
                Log.d(TAG, "⏭️ Prayer $prayerName is excluded today - skipping")
                return
            }

            PrayerPreference.CERTAIN -> {
                val prayerTime = intent?.getLongExtra("time", -1L) 
                    ?: prefs.getLong("${KEY_PRAYER_PREFIX}$prayerName", -1L)

                if (prayerTime <= 0) {
                    Log.e(TAG, "❌ No valid prayer time found for $prayerName")
                    return
                }

                Log.d(TAG, "🕌 Prayer $prayerName is certain today - scheduling auto mode")

                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                // DND activation
                val activateIntent = Intent(context, MyForegroundService::class.java).apply {
                    action = "silence_now"
                    putExtra("prayer", prayerName)
                    putExtra("mode", "auto")
                }

                val pendingActivate = PendingIntent.getService(
                    context,
                    prayerName.hashCode() + 1,
                    activateIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )

                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + AutoModeStarterService.DND_ACTIVATION_DELAY,
                    pendingActivate
                )

                // Restore sound
                val restoreIntent = Intent(context, RestoreSoundReceiver::class.java)
                val pendingRestore = PendingIntent.getBroadcast(
                    context,
                    prayerName.hashCode() + 2,
                    restoreIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or getImmutableFlag()
                )

                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + AutoModeStarterService.DND_ACTIVATION_DELAY +
                            AutoModeStarterService.DND_DURATION,
                    pendingRestore
                )

                return
            }

            PrayerPreference.DEFAULT -> {
                Log.d(TAG, "🎛️ Prayer $prayerName uses default mode: $currentMode")

                val serviceIntent = Intent(context, MyForegroundService::class.java).apply {
                    action = "run_silencer_logic"
                    putExtra("prayer", prayerName)
                    putExtra("mode", currentMode)
                    putExtra("locale", locale)  // Ensure locale is passed
                }

                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Log.d(TAG, "🚀 Started MyForegroundService with mode: $currentMode and locale: $locale")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to start foreground service", e)
                }
            }
        }

        // Wake lock & permission logic
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).run {
            newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "SmartSilencer::$prayerName"
            ).apply { acquire(30_000) }
        }

        try {
            var prayerTime = intent?.getLongExtra("time", -1L) ?: -1L
            if (prayerTime <= 0) {
                prayerTime = prefs.getLong("$KEY_PRAYER_PREFIX$prayerName", -1L)
            }

            if (prayerTime <= 0) {
                Log.e(TAG, "❌ No valid prayer time found for $prayerName (intent and prefs failed)")
                return
            }

            Log.d(TAG, "🔄 Using mode: $currentMode (final)")
            Log.d(TAG, "🕒 Prayer time: ${Date(prayerTime)}")

            if (!hasRequiredPermissions(context)) {
                Log.w(TAG, "⛔ Missing required permissions - cannot start service")
                showPermissionNotification(context)
                return
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "🔒 Security exception starting service", e)
        } finally {
            wakeLock.release()
        }
    }


    private fun showPermissionNotification(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        
        val notification = NotificationCompat.Builder(context, MyForegroundService.ALERTS_CHANNEL_ID)
            .setContentTitle("Permissions Required")
            .setContentText("Tap to grant location permissions")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setAutoCancel(true)
            .build()
        
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(9999, notification)
    }
}
