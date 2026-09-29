package com.example.honoroverlay

import android.Manifest
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

class BluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        Log.d(TAG, "BluetoothReceiver onReceive action: $action")

        try {
            val monitorIntent = Intent(context, EarbudsMonitorService::class.java)
            ContextCompat.startForegroundService(context, monitorIntent)
        } catch (_: Exception) {}

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> handleDeviceConnected(context, intent)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleDeviceDisconnected(context)
            ACTION_BATTERY_LEVEL_CHANGED -> handleBatteryChanged(context, intent)
        }
    }

    private fun handleDeviceConnected(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device == null) return

        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val deviceName: String? = if (hasBtPermission) {
            try {
                device.name
            } catch (e: SecurityException) {
                null
            }
        } else {
            null
        }

        val deviceAddress = device.address ?: ""
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(MainActivity.KEY_SAVED_MAC, "")?.trim() ?: ""
        val savedName = prefs.getString(MainActivity.KEY_SAVED_NAME, "")?.trim() ?: ""

        val matchesMac = savedMac.isNotEmpty() && deviceAddress.equals(savedMac, ignoreCase = true)
        val matchesName = if (savedName.isNotEmpty()) {
            deviceName?.contains(savedName, ignoreCase = true) == true
        } else {
            deviceName?.let {
                it.contains("Honor Earbuds X5 Pro", ignoreCase = true) ||
                        it.contains("X5 Pro", ignoreCase = true) ||
                        it.contains("Earbuds", ignoreCase = true) ||
                        it.contains("Honor", ignoreCase = true)
            } ?: false
        }

        if (matchesMac || matchesName) {
            if (!shouldShowOverlay(context)) {
                return
            }
            val batteryLevel = extractDeviceBattery(context, device, intent)
            val displayName = if (savedName.isNotEmpty()) savedName else "Honor Earbuds X5 Pro"
            Log.i(TAG, "Matching device connected with battery $batteryLevel%! Showing overlay via OverlayService.")
            OverlayService.show(context, displayName, batteryLevel)
        }
    }

    private fun handleBatteryChanged(context: Context, intent: Intent) {
        val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        if (level in 0..100) {
            Log.i(TAG, "BluetoothReceiver: battery level changed to $level%")
            val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_CACHED_BATTERY, level).apply()
            OverlayService.updateBatteryLevel(level)
        }
    }

    private fun extractDeviceBattery(context: Context, device: BluetoothDevice, intent: Intent): Int {
        var level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        if (level in 0..100) return level

        try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val result = method.invoke(device) as? Int ?: -1
            if (result in 0..100) return result
        } catch (_: Exception) {}

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        level = prefs.getInt(KEY_CACHED_BATTERY, -1)
        if (level in 0..100) return level

        return 100
    }

    private fun shouldShowOverlay(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        val isScreenOn = powerManager.isInteractive
        val isLocked = keyguardManager.isKeyguardLocked
        val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        return isScreenOn && !isLocked && isPortrait
    }

    private fun handleDeviceDisconnected(context: Context) {
        Log.i(TAG, "Device disconnected. Dismissing overlay.")
        OverlayService.dismiss()
    }

    companion object {
        private const val TAG = "BluetoothReceiver"
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val KEY_CACHED_BATTERY = "cached_battery_level"
    }
}
