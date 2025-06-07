package com.example.smartsilencerapp_fixed

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import java.util.*


class MainActivity : FlutterActivity() {


    companion object {
        private const val TAG = "MainActivity"
    }

    private val CHANNEL = "com.example.smartsilencerapp_fixed/foreground"
    private val ALARMS_CHANNEL = "com.example.smartsilencerapp_fixed/alarms"
    private val SETTINGS_CHANNEL = "com.example.smartsilencerapp_fixed/settings"

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
    private val EXACT_ALARM_PERMISSION_REQUEST_CODE = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Foreground channel method handling
        MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    val mode = call.argument<String>("mode") ?: "gps"
                    val prayerTimes = call.argument<Map<String, Long>>("prayerTimes") ?: emptyMap()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
                        result.error("NOTIFICATION_PERMISSION", "Notification permission is required", null)
                        return@setMethodCallHandler
                    }

                    if (!AlarmReceiver.hasRequiredPermissions(this)) {
                        requestLocationPermissions()
                        result.error("LOCATION_PERMISSION", "Location permissions are required", null)
                        return@setMethodCallHandler
                    }

                    if (!hasNotificationPolicyAccess()) {
                        requestNotificationPolicyAccess()
                        result.error("DND_PERMISSION", "Do Not Disturb access is required", null)
                        return@setMethodCallHandler
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()) {
                        requestExactAlarmPermission()
                        result.error("EXACT_ALARM_PERMISSION", "Exact alarm permission is required", null)
                        return@setMethodCallHandler
                    }

                    checkBatteryOptimizationOnce()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                        val hasFgsLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val hasFineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

                        if (!hasFgsLoc || !hasFineLoc) {
                            result.error("PERMISSION_DENIED", "Missing required location permissions", null)
                            return@setMethodCallHandler
                        }
                    }

                    // In the startService method handler
                    if (!verifyAllPermissions()) {
                        requestAllNeededPermissions()
                        result.error("PERMISSION_DENIED", "Required permissions not granted", null)
                        return@setMethodCallHandler
                    }


                    startForegroundService(mode, prayerTimes)
                    result.success("Service started")
                }

                "toggleDnd" -> {
                    val enable = call.argument<Boolean>("enable") ?: false
                    val intent = Intent(this, MyForegroundService::class.java).apply {
                        action = if (enable) "silence_now" else "restore_sound"
                        putExtra("prayer", "manual_toggle")
                    }
                    ContextCompat.startForegroundService(this, intent)
                    result.success("DND toggled to $enable")
                }

                "isServiceRunning" -> {
                    result.success(isMyServiceRunning(MyForegroundService::class.java))
                }

                "requestDndPermission" -> {
                    requestNotificationPolicyAccess()
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }

        // Alarm channel method handling
        MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, ALARMS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "schedulePrayerAlarms" -> {
                    try {
                        val mode = call.argument<String>("mode") ?: "notification"
                        val rawPrayerTimes = call.argument<Map<String, Any>>("prayerTimes") ?: emptyMap()

                        // Convert prayer times
                        val prayerTimes = rawPrayerTimes.mapValues { (_, v) ->
                            when (v) {
                                is Int -> v.toLong()
                                is Long -> v
                                is Double -> v.toLong()
                                is String -> v.toLongOrNull() ?: 0L
                                else -> 0L
                            }.also { time ->
                                if (time <= 0) throw IllegalArgumentException("Invalid time value")
                            }
                        }

                        // Save to prefs
                        val prefs = getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putString("silencer_mode", mode)
                            prayerTimes.forEach { (prayer, time) ->
                                putLong("${AlarmReceiver.KEY_PRAYER_PREFIX}$prayer", time)
                            }
                            apply()
                        }

                        // Schedule alarms
                        PrayerAlarmManager.schedulePrayerAlarms(this)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Alarm scheduling failed", e)
                        result.error("SCHEDULING_FAILED", e.message, null)
                    }
                }
                "saveSilencerMode" -> {
                    val mode = call.argument<String>("mode")
                    if (mode != null) {
                        val prefs = context.getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putString("silencer_mode", mode).apply()

                        Log.d("PrayerAlarmManager", "✅ Saved silencer mode in native preferences: $mode")
                        result.success("Mode saved: $mode")
                    } else {
                        result.error("INVALID_MODE", "Mode was null", null)
                    }

                }

                "cancelAllAlarms" -> {
                    PrayerAlarmManager.cancelAllAlarms(this)
                    result.success(true)
                }

                "savePrayerTimesToNative" -> {
                    val rawPrayerTimes = call.argument<Map<String, Any>>("prayerTimes") ?: emptyMap()
                    val prayerTimes = rawPrayerTimes.mapValues { (_, v) ->
                        when (v) {
                            is Int -> v.toLong()
                            is Long -> v
                            is Double -> v.toLong()
                            is String -> v.toLongOrNull() ?: 0L
                            else -> 0L
                        }
                    }

                    val prefs = getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        prayerTimes.forEach { (prayer, time) ->
                            putLong("${AlarmReceiver.KEY_PRAYER_PREFIX}$prayer", time)
                        }
                        apply()
                    }

                    Log.d("MainActivity", "✅ Prayer times saved natively: $prayerTimes")
                    result.success(true)
                }


                "saveWeeklyPreferences" -> {
                    try {
                        val preferences = call.argument<Map<String, Map<String, String>>>("preferences")
                        if (preferences != null) {
                             Log.d("MainActivity", "Received weekly preferences from Flutter: $preferences")
                            saveWeeklyPreferences(preferences)
                            result.success(true)
                        } else {
                            Log.e("MainActivity", "Received null preferences data")
                            result.error("INVALID_DATA", "Preferences data was null", null)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error saving weekly preferences", e)
                        result.error("SAVE_FAILED", e.message, null)
                    }
                }

                "loadWeeklyPreferences" -> {
                    try {
                        val prefs = getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                        val allPrefs = prefs.all.filter { it.key.startsWith("pref_") }
                        
                        // Convert to Flutter-friendly format
                        val resultMap = mutableMapOf<String, MutableMap<String, String>>()
                        
                        // Initialize all days
                        val days = listOf(
                            "sunday" to Calendar.SUNDAY,
                            "monday" to Calendar.MONDAY,
                            "tuesday" to Calendar.TUESDAY,
                            "wednesday" to Calendar.WEDNESDAY,
                            "thursday" to Calendar.THURSDAY,
                            "friday" to Calendar.FRIDAY,
                            "saturday" to Calendar.SATURDAY
                        )
                        
                        val prayers = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
                        
                        days.forEach { (dayName, dayValue) ->
                            val dayMap = mutableMapOf<String, String>()
                            prayers.forEach { prayer ->
                                val key = "pref_${dayValue}_$prayer"
                                dayMap[prayer] = prefs.getString(key, "DEFAULT") ?: "DEFAULT"
                            }
                            resultMap[dayName] = dayMap
                        }
                        
                        result.success(resultMap)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error loading weekly preferences", e)
                        result.error("LOAD_FAILED", e.message, null)
                    }
                }

                else -> result.notImplemented()
            }
        }

        MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, SETTINGS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "updateSettings" -> {
                    val reminderEnabled = call.argument<Boolean>("reminderEnabled") ?: false
                    val maxSkips = call.argument<Int>("maxSkips") ?: 3

                    val prefs = getSharedPreferences("silencer_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putBoolean("reminder_enabled", reminderEnabled)
                        .putInt("max_skips", maxSkips)
                        .apply()

                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }


        // Schedule daily rescheduler
        PrayerAlarmManager.setDailyMidnightAlarm(this)
    }

    // ------------------ Helper Methods ------------------
    // In MainActivity.kt

   
    private fun verifyAllPermissions(): Boolean {
        val hasLocation = hasLocationPermissions()
        val canScheduleAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            canScheduleExactAlarms()
        } else true
        
        Log.d(TAG, "Permission Verification - Location: $hasLocation, ExactAlarms: $canScheduleAlarms")
        
        return hasLocation && canScheduleAlarms
    }

    private fun requestAllNeededPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        
        // Check location permissions
        if (!hasLocationPermissions()) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissionsNeeded.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            }
        }

        // Request permissions if needed
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }

        // Handle exact alarms for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms()) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch exact alarm settings", e)
                // Fallback for devices that don't support the exact alarm intent
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.d(TAG, "Location permissions granted")
                } else {
                    Log.w(TAG, "Some location permissions denied")
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
        
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }



    private fun hasLocationPermissions(): Boolean {
        val hasStandardLocation = ContextCompat.checkSelfPermission(this, 
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, 
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        
        val hasFgsLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, 
                Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        
        return hasStandardLocation && hasFgsLocation
    }

    private fun requestLocationPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
        
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }


    private fun saveWeeklyPreferences(preferences: Map<String, Map<String, String>>) {
        val prefs = getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
        Log.d("MainActivity", "Saving weekly preferences - input data: $preferences")
        
        prefs.edit().apply {
            preferences.forEach { (day, prayerPrefs) ->
                val dayInt = when (day) {
                    "sunday" -> Calendar.SUNDAY
                    "monday" -> Calendar.MONDAY
                    "tuesday" -> Calendar.TUESDAY
                    "wednesday" -> Calendar.WEDNESDAY
                    "thursday" -> Calendar.THURSDAY
                    "friday" -> Calendar.FRIDAY
                    "saturday" -> Calendar.SATURDAY
                    else -> {
                        Log.w("MainActivity", "Invalid day name: $day")
                        return@forEach
                    }
                }
                
                Log.d("MainActivity", "Processing day: $day (int value: $dayInt)")
                
                prayerPrefs.forEach { (prayer, pref) ->
                    val key = "pref_${dayInt}_$prayer"
                    Log.d("MainActivity", "Saving preference - key: $key, value: $pref")
                    putString(key, pref)
                }
            }
            apply()
        }
        Log.d("MainActivity", "Weekly preferences saved successfully")
        
        // Verify saved preferences
        val allPrefs = prefs.all.filter { it.key.startsWith("pref_") }
        Log.d("MainActivity", "Current saved preferences: $allPrefs")
    }

    private fun hasNotificationPolicyAccess(): Boolean {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }

    private fun requestNotificationPolicyAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            startActivity(intent)
        }
    }

    private fun checkBatteryOptimizationOnce() {
        val prefs = getSharedPreferences("silencer_prefs", Context.MODE_PRIVATE)
        val alreadyPrompted = prefs.getBoolean("battery_opt_prompted", false)

        if (!alreadyPrompted) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")

                startActivity(intent)

                prefs.edit().putBoolean("battery_opt_prompted", true).apply()
            }
        }
    }


    private fun startForegroundService(mode: String, prayerTimes: Map<String, Long>) {
        val intent = Intent(this, MyForegroundService::class.java).apply {
            putExtra("mode", mode)
            putExtra("prayerTimes", HashMap(prayerTimes))
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun isMyServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == serviceClass.name
        }
    }
}
