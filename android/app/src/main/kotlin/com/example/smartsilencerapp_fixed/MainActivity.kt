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
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import java.util.*

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.smartsilencerapp_fixed/foreground"
    private val ALARMS_CHANNEL = "com.example.smartsilencerapp_fixed/alarms"
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
    private val EXACT_ALARM_PERMISSION_REQUEST_CODE = 1003

    companion object {
        private const val TAG = "MainActivity"
    }

    private var pendingStartServiceCall: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        pendingStartServiceCall = { startForegroundService(mode, prayerTimes) }
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
                    val intent = Intent(this, MyForegroundService::class.java)
                    intent.action = if (enable) "silence_now" else "restore_sound"
                    intent.putExtra("prayer", "manual_toggle")
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

        MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, ALARMS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "schedulePrayerAlarms" -> {
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

                    Log.d(TAG, "✅ Received prayer times: $prayerTimes")

                    val prefs = getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().apply {
                        prayerTimes.forEach { (prayer, time) ->
                            putLong("${AlarmReceiver.KEY_PRAYER_PREFIX}$prayer", time)
                        }
                        apply()
                    }

                    schedulePrayerAlarms(this)
                    result.success(true)
                }

                "cancelAllAlarms" -> {
                    cancelAllAlarms(this)
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        }

        setDailyMidnightAlarm(this)
    }

    // ----------------- HELPER METHODS -----------------

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
        } else {
            true
        }
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
        val intent = Intent(this, MyForegroundService::class.java)
        intent.putExtra("mode", mode)
        intent.putExtra("prayerTimes", HashMap(prayerTimes))
        ContextCompat.startForegroundService(this, intent)
    }

    private fun isMyServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun cancelAllAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)

        AlarmReceiver.PRAYER_ORDER.forEach { prayer ->
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = "prayer_alarm_$prayer"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                AlarmReceiver.PRAYER_ORDER.indexOf(prayer),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }

        prefs.edit().clear().apply()
        Log.d(TAG, "All alarms cancelled and preferences cleared")
    }

    private fun setDailyMidnightAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, DailySchedulerReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pendingIntent
        )

        Log.d(TAG, "🗓️ Daily midnight alarm set for: ${calendar.time}")
    }

    fun schedulePrayerAlarms(context: Context) {
        Log.d(TAG, "⏳ Scheduling prayer alarms...")
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val prefs = context.getSharedPreferences(AlarmReceiver.PREFS_NAME, Context.MODE_PRIVATE)

        AlarmReceiver.PRAYER_ORDER.forEach { prayer ->
            val prayerTime = prefs.getLong("${AlarmReceiver.KEY_PRAYER_PREFIX}$prayer", -1L)
            if (prayerTime > 0) {
                val alarmTime = prayerTime - (3 * 60 * 1000)
                val now = System.currentTimeMillis()

                Log.d(TAG, "🕒 Processing $prayer:")
                Log.d(TAG, "  - Prayer time: ${Date(prayerTime)}")
                Log.d(TAG, "  - Alarm time: ${Date(alarmTime)}")
                Log.d(TAG, "  - Time until alarm: ${(alarmTime - now) / 1000} seconds")

                if (alarmTime < now) {
                    Log.w(TAG, "⚠️ Skipping $prayer - alarm time has passed")
                    return@forEach
                }

                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("prayer", prayer)
                    putExtra("time", prayerTime)
                    action = "prayer_alarm_$prayer"
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    AlarmReceiver.PRAYER_ORDER.indexOf(prayer),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                alarmManager.cancel(pendingIntent)

                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarmTime,
                    pendingIntent
                )

                Log.d(TAG, "✅ Scheduled alarm for $prayer at ${Date(alarmTime)}")
            } else {
                Log.w(TAG, "⚠️ No time set for $prayer")
            }
        }
        Log.d(TAG, "Finished scheduling alarms")
    }
}
