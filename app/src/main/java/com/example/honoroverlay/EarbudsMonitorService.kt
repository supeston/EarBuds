package com.example.honoroverlay

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class EarbudsMonitorService : Service() {

    private var isReceiverRegistered = false

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "Bluetooth broadcast received in EarbudsMonitorService: $action")

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> handleDeviceConnected(intent)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> handleDeviceDisconnected(intent)
                ACTION_BATTERY_LEVEL_CHANGED -> handleBatteryChanged(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "EarbudsMonitorService onCreate")
        createSilentNotificationChannel()
        startSilentForeground()
        registerBluetoothReceiver()
        OverlayService.initSoundPool(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "EarbudsMonitorService onStartCommand")
        startSilentForeground()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            OverlayService.dismissImmediately()
        }
    }

    private fun createSilentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фоновый мониторинг",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Фоновый мониторинг подключения Honor Earbuds X5 Pro"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startSilentForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Фоновый мониторинг")
            .setContentText("Ожидание подключения Honor Earbuds X5 Pro")
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    private fun registerBluetoothReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        try {
            ContextCompat.registerReceiver(
                this,
                bluetoothReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            isReceiverRegistered = true
            Log.i(TAG, "Bluetooth ACL receiver registered dynamically")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Bluetooth receiver", e)
        }
    }

    private fun handleDeviceConnected(intent: Intent) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        if (device == null) return

        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
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
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
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

        Log.d(TAG, "Device ACL Connected: Name='$deviceName', Address='$deviceAddress', matchesMac=$matchesMac, matchesName=$matchesName")

        if (matchesMac || matchesName) {
            if (!shouldShowOverlay(this)) {
                return
            }
            val batteryLevel = extractDeviceBattery(device, intent)
            val displayName = if (savedName.isNotEmpty()) savedName else "Honor Earbuds X5 Pro"
            Log.i(TAG, "Matching Honor Earbuds connected with battery $batteryLevel%! Showing overlay...")
            OverlayService.show(this, displayName, batteryLevel)
        }
    }

    private fun handleBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        if (level in 0..100) {
            Log.i(TAG, "Dynamic battery level received from Bluetooth: $level%")
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_CACHED_BATTERY, level).apply()
            OverlayService.updateBatteryLevel(level)
        }
    }

    private fun extractDeviceBattery(device: BluetoothDevice, intent: Intent): Int {

        var level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        if (level in 0..100) return level

        try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val result = method.invoke(device) as? Int ?: -1
            if (result in 0..100) return result
        } catch (_: Exception) {}

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
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

    private fun handleDeviceDisconnected(intent: Intent) {
        Log.i(TAG, "Device disconnected. Dismissing overlay if active.")
        OverlayService.dismiss()
    }

    override fun onDestroy() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(bluetoothReceiver)
                isReceiverRegistered = false
                Log.i(TAG, "Bluetooth receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver", e)
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EarbudsMonitorService"
        const val CHANNEL_ID = "monitor_silent"
        const val NOTIFICATION_ID = 101

        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        const val KEY_CACHED_BATTERY = "cached_battery_level"
    }
}
