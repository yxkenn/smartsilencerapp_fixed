package com.example.smartsilencerapp_fixed

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.example.smartsilencerapp_fixed/foreground"
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 1002
    private val NOTIFICATION_POLICY_REQUEST_CODE = 1003

    private var pendingStartServiceCall: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
            MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
                when (call.method) {
                    "startService" -> {
                        val mode = call.argument<String>("mode") ?: "gps"
                        val prayerTimesMap = call.argument<Map<String, Long>>("prayerTimes") ?: emptyMap()

                        if (hasLocationPermissions()) {
                            if (hasNotificationPolicyAccess()) {
                                startForegroundService(mode, prayerTimesMap)
                                result.success("Service started")
                            } else {
                                requestNotificationPolicyAccess()
                                result.error("NOTIFICATION_POLICY_DENIED", "Notification Policy access is required", null)
                            }
                        } else {
                            requestLocationPermissions()
                            // Save this call to retry after permissions granted
                            pendingStartServiceCall = { startForegroundService(mode, prayerTimesMap) }
                            result.error("PERMISSION_DENIED", "Location permissions are required", null)
                        }
                    }
                    "stopService" -> {
                        stopForegroundService()
                        result.success("Service stopped")
                    }
                    "checkNotificationPolicy" -> {
                        result.success(hasNotificationPolicyAccess())
                    }
                    else -> {
                        result.notImplemented()
                    }
                }
            }
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fineLocationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        return fineLocationGranted && backgroundLocationGranted
    }

    private fun requestLocationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun hasNotificationPolicyAccess(): Boolean {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return notificationManager.isNotificationPolicyAccessGranted
    }

    private fun requestNotificationPolicyAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        startActivityForResult(intent, NOTIFICATION_POLICY_REQUEST_CODE)
    }

    private fun startForegroundService(mode: String, prayerTimesMap: Map<String, Long>) {
        val intent = Intent(this, MyForegroundService::class.java)
        intent.putExtra("mode", mode)
        intent.putExtra("prayerTimes", HashMap(prayerTimesMap))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, MyForegroundService::class.java)
        stopService(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                pendingStartServiceCall?.invoke()
                pendingStartServiceCall = null
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == NOTIFICATION_POLICY_REQUEST_CODE) {
            if (hasNotificationPolicyAccess()) {
                // Optionally notify Flutter that notification policy access granted
            }
        }
    }
}
