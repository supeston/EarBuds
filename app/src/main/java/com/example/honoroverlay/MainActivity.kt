package com.example.honoroverlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.honoroverlay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatuses()
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionStatuses()
        startMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initViews()
        loadPreferences()
        startMonitorService()
        OverlayService.initSoundPool(this)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    private fun initViews() {

        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }

        binding.btnGrantRuntime.setOnClickListener {
            requestRuntimePermissions()
        }

        binding.btnHideNotification.setOnClickListener {
            openSilentChannelSettings()
        }

        binding.btnBatteryOptimization.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        binding.btnOriginOsAutostart.setOnClickListener {
            openOriginOsAutostartMenu()
        }

        binding.btnSaveMac.setOnClickListener {
            val mac = binding.etMacAddress.text?.toString()?.trim()?.uppercase() ?: ""
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SAVED_MAC, mac).apply()

            if (mac.isNotEmpty()) {
                Toast.makeText(this, "MAC-адрес сохранен: $mac", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "MAC очищен. Фильтрация работает по имени устройства.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearMac.setOnClickListener {
            binding.etMacAddress.setText("")
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_SAVED_MAC).apply()
            Toast.makeText(this, "Фильтр сброшен. По умолчанию ищутся «Earbuds» / «X5 Pro».", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestOverlay.setOnClickListener {
            testOverlay()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMac = prefs.getString(KEY_SAVED_MAC, "")
        if (!savedMac.isNullOrEmpty()) {
            binding.etMacAddress.setText(savedMac)
        }
    }

    private fun startMonitorService() {
        try {
            val serviceIntent = Intent(this, EarbudsMonitorService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.d(TAG, "EarbudsMonitorService started from MainActivity")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start EarbudsMonitorService", e)
        }
    }

    private fun updatePermissionStatuses() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvOverlayStatus.text = getString(R.string.status_granted)
            binding.tvOverlayStatus.setTextColor(Color.parseColor("#00E676"))
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.text = getString(R.string.btn_overlay_active)
        } else {
            binding.tvOverlayStatus.text = getString(R.string.status_not_granted)
            binding.tvOverlayStatus.setTextColor(Color.parseColor("#F87171"))
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.text = getString(R.string.btn_grant_overlay)
        }

        val hasBtConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val hasPostNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasBtConnect && hasPostNotifications) {
            binding.tvRuntimeStatus.text = getString(R.string.status_granted)
            binding.tvRuntimeStatus.setTextColor(Color.parseColor("#00E676"))
            binding.btnGrantRuntime.isEnabled = false
            binding.btnGrantRuntime.text = getString(R.string.btn_runtime_active)
        } else {
            binding.tvRuntimeStatus.text = getString(R.string.status_action_required)
            binding.tvRuntimeStatus.setTextColor(Color.parseColor("#F87171"))
            binding.btnGrantRuntime.isEnabled = true
            binding.btnGrantRuntime.text = getString(R.string.btn_grant_runtime)
        }
    }

    private fun requestRuntimePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            runtimePermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Toast.makeText(this, "Все системные разрешения уже выданы", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSilentChannelSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, EarbudsMonitorService.CHANNEL_ID)
                }
                startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open channel notification settings", e)
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Ограничения батареи уже отключены", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallbackIntent)
            } catch (ex: Exception) {
                Toast.makeText(this, "Откройте настройки батареи вручную", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openOriginOsAutostartMenu() {
        val targets = listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.MainGuideActivity")
        )

        var launched = false
        for (target in targets) {
            try {
                val intent = Intent().apply {
                    component = target
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                launched = true
                break
            } catch (_: Exception) {

            }
        }

        if (!launched) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть меню OriginOS", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Сначала включите разрешение «Отображение поверх других приложений»!",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        OverlayService.show(this, "Honor Earbuds X5 Pro", 85)
        Toast.makeText(this, "Запуск оверлея...", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "MainActivity"
        const val PREFS_NAME = "honor_overlay_prefs"
        const val KEY_SAVED_MAC = "saved_mac"
    }
}
