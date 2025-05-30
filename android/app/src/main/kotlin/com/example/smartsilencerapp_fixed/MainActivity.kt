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

                    if (!hasLocationPermissions()) {
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

                    checkBatteryOptimization()
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

    private fun hasLocationPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
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

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = packageName
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
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
