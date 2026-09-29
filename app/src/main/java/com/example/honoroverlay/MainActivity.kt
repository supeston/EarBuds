package com.example.honoroverlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.honoroverlay.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateSwitchStates()
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateSwitchStates()
        startMonitorService()
    }

    private val pickAnimationLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            handleSelectedMedia(uri)
        }
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
        updateSwitchStates()
    }

    private fun initViews() {
        binding.switchOverlay.setOnClickListener {
            val hasOverlay = Settings.canDrawOverlays(this)
            if (!hasOverlay) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
        binding.rowOverlay.setOnClickListener {
            binding.switchOverlay.performClick()
        }

        binding.switchBluetooth.setOnClickListener {
            requestRuntimePermissions()
        }
        binding.rowBluetooth.setOnClickListener {
            binding.switchBluetooth.performClick()
        }

        binding.switchBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
        binding.rowBattery.setOnClickListener {
            binding.switchBattery.performClick()
        }

        binding.rowNotifications.setOnClickListener {
            openSilentChannelSettings()
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        binding.etDeviceName.doAfterTextChanged { text ->
            prefs.edit().putString(KEY_SAVED_NAME, text?.toString()?.trim() ?: "").apply()
        }

        binding.etMacAddress.doAfterTextChanged { text ->
            prefs.edit().putString(KEY_SAVED_MAC, text?.toString()?.trim() ?: "").apply()
        }

        binding.btnChooseAnimation.setOnClickListener {
            try {
                pickAnimationLauncher.launch(arrayOf("image/*", "video/*", "*/*"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch document picker", e)
                Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResetAnimation.setOnClickListener {
            resetCustomAnimation()
        }

        binding.btnTestOverlay.setOnClickListener {
            testOverlay()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedName = prefs.getString(KEY_SAVED_NAME, "")
        if (!savedName.isNullOrEmpty()) {
            binding.etDeviceName.setText(savedName)
        }

        val savedMac = prefs.getString(KEY_SAVED_MAC, "")
        if (!savedMac.isNullOrEmpty()) {
            binding.etMacAddress.setText(savedMac)
        }

        updateAnimationDisplay()
    }

    private fun updateAnimationDisplay() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customName = prefs.getString(KEY_CUSTOM_ANIMATION_NAME, null)
        val customPath = prefs.getString(KEY_CUSTOM_ANIMATION_PATH, null)
        val fileExists = customPath?.let { File(it).exists() } == true

        if (!customName.isNullOrEmpty() && fileExists) {
            binding.tvAnimationFileName.text = customName
            binding.btnResetAnimation.visibility = View.VISIBLE
        } else {
            binding.tvAnimationFileName.text = getString(R.string.animation_default)
            binding.btnResetAnimation.visibility = View.GONE
        }
    }

    private fun handleSelectedMedia(uri: Uri) {
        try {
            var fileName = "custom_animation.webp"
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = it.getString(nameIndex) ?: fileName
                    }
                }
            }

            val ext = if (fileName.contains('.')) fileName.substringAfterLast('.') else "webp"
            val targetFile = File(filesDir, "custom_animation.$ext")

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CUSTOM_ANIMATION_PATH, targetFile.absolutePath)
                .putString(KEY_CUSTOM_ANIMATION_NAME, fileName)
                .apply()

            updateAnimationDisplay()
            Toast.makeText(this, "Анимация успешно сохранена", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom media", e)
            Toast.makeText(this, "Ошибка при сохранении файла", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetCustomAnimation() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customPath = prefs.getString(KEY_CUSTOM_ANIMATION_PATH, null)
        if (!customPath.isNullOrEmpty()) {
            try {
                File(customPath).delete()
            } catch (_: Exception) {}
        }
        prefs.edit()
            .remove(KEY_CUSTOM_ANIMATION_PATH)
            .remove(KEY_CUSTOM_ANIMATION_NAME)
            .apply()

        updateAnimationDisplay()
        Toast.makeText(this, "Восстановлена стандартная анимация", Toast.LENGTH_SHORT).show()
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

    private fun updateSwitchStates() {
        val hasOverlay = Settings.canDrawOverlays(this)
        binding.switchOverlay.isChecked = hasOverlay

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

        binding.switchBluetooth.isChecked = hasBtConnect && hasPostNotifications

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isIgnoringBattery = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: false
        binding.switchBattery.isChecked = isIgnoringBattery
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
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
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
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (_: Exception) {}
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

    private fun testOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Включите разрешение «Окно поверх приложений»",
                Toast.LENGTH_SHORT
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedName = prefs.getString(KEY_SAVED_NAME, "")?.trim() ?: ""
        val displayName = if (savedName.isNotEmpty()) savedName else "Honor Earbuds X5 Pro"

        OverlayService.show(this, displayName, 85)
    }

    companion object {
        private const val TAG = "MainActivity"
        const val PREFS_NAME = "honor_overlay_prefs"
        const val KEY_SAVED_NAME = "saved_name"
        const val KEY_SAVED_MAC = "saved_mac"
        const val KEY_CUSTOM_ANIMATION_PATH = "custom_animation_path"
        const val KEY_CUSTOM_ANIMATION_NAME = "custom_animation_name"
    }
}
