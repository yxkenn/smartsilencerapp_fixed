package com.example.smartsilencerapp_fixed

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import java.util.Calendar

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.example.smartsilencerapp_fixed/foreground"
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
    private val EXACT_ALARM_PERMISSION_REQUEST_CODE = 1003

    private var pendingStartServiceCall: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger!!, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    val mode = call.argument<String>("mode") ?: "gps"
                    val prayerTimes = call.argument<Map<String, Long>>("prayerTimes") ?: emptyMap()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
    }

    private fun isMyServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
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
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED && coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun hasNotificationPolicyAccess(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.isNotificationPolicyAccessGranted
    }

    private fun requestNotificationPolicyAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        }
    }

    private fun startForegroundService(mode: String, prayerTimes: Map<String, Long>) {
        val intent = Intent(this, MyForegroundService::class.java)
        intent.putExtra("mode", mode)
        intent.putExtra("prayerTimes", HashMap(prayerTimes))
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    pendingStartServiceCall?.invoke()
                    pendingStartServiceCall = null
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                // No-op, could show feedback if needed
            }
            EXACT_ALARM_PERMISSION_REQUEST_CODE -> {
                // No-op, could show feedback if needed
            }
        }
    }
}