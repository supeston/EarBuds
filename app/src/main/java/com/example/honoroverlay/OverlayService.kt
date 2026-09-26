package com.example.honoroverlay

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.decode.ImageDecoderDecoder
import coil.load
import java.util.Collections

open class OverlayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OverlayService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundNotification()

        val action = intent?.action
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "Honor Earbuds X5 Pro"
        val batteryLevel = intent?.getIntExtra(EXTRA_BATTERY_LEVEL, -1) ?: -1
        Log.d(TAG, "OverlayService onStartCommand with action: $action, battery: $batteryLevel")

        when (action) {
            ACTION_DISMISS_OVERLAY -> {
                dismiss()
            }
            else -> {
                show(this, deviceName, batteryLevel)
            }
        }

        return START_NOT_STICKY
    }

    private fun ensureForegroundNotification() {
        try {
            val notification = NotificationCompat.Builder(this, EarbudsMonitorService.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setContentTitle("Honor Overlay")
                .setContentText("Отображение всплывающего окна")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOngoing(false)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    102,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(102, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground notification start exception in OverlayService", e)
        }
    }

    override fun onDestroy() {
        dismiss()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_SHOW_OVERLAY = "com.example.honoroverlay.action.SHOW_OVERLAY"
        const val ACTION_DISMISS_OVERLAY = "com.example.honoroverlay.action.DISMISS_OVERLAY"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_BATTERY_LEVEL = "extra_battery_level"

        private val mainHandler = Handler(Looper.getMainLooper())
        private var windowManager: WindowManager? = null

        private val attachedViews = Collections.synchronizedSet(mutableSetOf<View>())
        private var isOverlayShowing = false
        private var lastShowTimestamp = 0L
        private var currentBatteryLevel: Int = 100

        fun show(context: Context, deviceName: String = "Honor Earbuds X5 Pro", batteryLevel: Int = -1) {
            mainHandler.post {
                showOverlayInternal(context.applicationContext, deviceName, batteryLevel)
            }
        }

        fun updateBatteryLevel(level: Int) {
            if (level !in 0..100) return
            currentBatteryLevel = level
            mainHandler.post {
                synchronized(attachedViews) {
                    for (view in attachedViews) {
                        val batteryView = view.findViewById<BatteryView>(R.id.batteryView)
                        val tvBatteryPercent = view.findViewById<TextView>(R.id.tvBatteryPercent)
                        batteryView?.setBatteryLevel(level)
                        tvBatteryPercent?.text = "$level%"
                    }
                }
            }
        }

        fun dismiss() {
            mainHandler.post {
                dismissAllAnimated()
            }
        }

        private fun showOverlayInternal(context: Context, deviceName: String, batteryLevel: Int = -1) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
                return
            }

            if (!isDeviceUnlockedAndActive(context)) {
                Log.i(TAG, "Device is locked or display is not active. Skipping overlay display.")
                return
            }

            val now = SystemClock.elapsedRealtime()

            if (now - lastShowTimestamp < 2500L) {
                Log.d(TAG, "Debounce: ignoring show request within 2500ms")
                return
            }
            lastShowTimestamp = now

            if (isOverlayShowing && attachedViews.isNotEmpty()) {
                Log.d(TAG, "Overlay is already active. Ignoring duplicate show request.")
                return
            }

            if (windowManager == null) {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            cleanupAllViewsImmediate()

            val inflater = LayoutInflater.from(context)
            val overlayView = inflater.inflate(R.layout.view_earbuds_popup, null)
            val cardRoot = overlayView.findViewById<View>(R.id.cardRoot)
            val tvDeviceName = overlayView.findViewById<TextView>(R.id.tvDeviceName)
            val ivEarbuds = overlayView.findViewById<ImageView>(R.id.ivEarbuds)
            val batteryView = overlayView.findViewById<BatteryView>(R.id.batteryView)
            val tvBatteryPercent = overlayView.findViewById<TextView>(R.id.tvBatteryPercent)
            val btnDone = overlayView.findViewById<TextView>(R.id.btnDone)

            tvDeviceName.text = "Honor Earbuds X5 Pro"

            val initialBattery = if (batteryLevel in 0..100) {
                currentBatteryLevel = batteryLevel
                batteryLevel
            } else {
                currentBatteryLevel
            }
            batteryView?.setBatteryLevel(initialBattery)
            tvBatteryPercent?.text = "$initialBattery%"

            val isDarkTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val density = context.resources.displayMetrics.density

            if (isDarkTheme) {

                cardRoot.setBackgroundResource(R.drawable.bg_card_dark)
                cardRoot.elevation = 4f * density
                tvDeviceName.setTextColor(Color.WHITE)
                tvBatteryPercent?.setTextColor(Color.WHITE)
                btnDone.setBackgroundResource(R.drawable.bg_btn_dark)
                btnDone.setTextColor(Color.WHITE)
            } else {

                cardRoot.setBackgroundResource(R.drawable.bg_card_light)
                cardRoot.elevation = 10f * density
                tvDeviceName.setTextColor(Color.BLACK)
                tvBatteryPercent?.setTextColor(Color.BLACK)
                btnDone.setBackgroundResource(R.drawable.bg_btn_light)
                btnDone.setTextColor(Color.WHITE)
            }

            var isThisViewDismissing = false
            fun dismissThisOverlayView() {
                if (isThisViewDismissing) return
                isThisViewDismissing = true

                cardRoot.animate()
                    .translationY(900f)
                    .alpha(0f)
                    .setDuration(250)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        try {
                            if (overlayView.isAttachedToWindow) {
                                windowManager?.removeView(overlayView)
                            }
                        } catch (e: Exception) {
                            try {
                                windowManager?.removeViewImmediate(overlayView)
                            } catch (_: Exception) {}
                        } finally {
                            attachedViews.remove(overlayView)
                            if (attachedViews.isEmpty()) {
                                isOverlayShowing = false
                            }
                        }
                    }
                    .start()
            }

            btnDone.setOnClickListener {
                triggerSingleClickHaptic(context)
                dismissThisOverlayView()
            }

            var startY = 0f
            var isDragging = false
            cardRoot.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        isDragging = true
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging) {
                            val deltaY = event.rawY - startY
                            val transY = deltaY.coerceAtLeast(0f)
                            view.translationY = transY
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            isDragging = false
                            val currentTransY = view.translationY
                            if (currentTransY > 160f) {
                                dismissThisOverlayView()
                            } else {

                                view.animate()
                                    .translationY(0f)
                                    .setDuration(200)
                                    .setInterpolator(PathInterpolator(0.2f, 1f, 0.2f, 1f))
                                    .start()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            loadWebPAnimation(context, ivEarbuds)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = 0
            }

            cardRoot.translationY = 800f
            cardRoot.alpha = 0f

            try {
                windowManager?.addView(overlayView, params)
                attachedViews.add(overlayView)
                isOverlayShowing = true
                Log.i(TAG, "Overlay window added to WindowManager. Total active: ${attachedViews.size}")

                cardRoot.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(PathInterpolator(0.2f, 1f, 0.2f, 1f))
                    .start()

                triggerTapticEngineHaptic(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add overlay to WindowManager", e)
                attachedViews.remove(overlayView)
                isOverlayShowing = false
            }
        }

        private fun loadWebPAnimation(context: Context, ivEarbuds: ImageView) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val source = ImageDecoder.createSource(context.resources, R.raw.earbuds)
                    val drawable = ImageDecoder.decodeDrawable(source)
                    ivEarbuds.setImageDrawable(drawable)

                    ivEarbuds.post {
                        try {
                            if (drawable is Animatable) {
                                drawable.start()
                                Log.i(TAG, "Native WebP animation started via Animatable.start()")
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error starting native Animatable", e)
                        }
                    }
                    return
                } catch (e: Throwable) {
                    Log.w(TAG, "Native ImageDecoder failed, attempting Coil fallback", e)
                }
            }

            try {
                val imageLoader = ImageLoader.Builder(context)
                    .components {
                        if (Build.VERSION.SDK_INT >= 28) {
                            add(ImageDecoderDecoder.Factory())
                        }
                    }
                    .build()

                ivEarbuds.load(R.raw.earbuds, imageLoader) {
                    listener(
                        onSuccess = { _, result ->
                            val d = result.drawable
                            if (d is Animatable) {
                                ivEarbuds.post {
                                    try {
                                        d.start()
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        onError = { _, _ ->
                            ivEarbuds.setImageResource(R.drawable.ic_bluetooth)
                        }
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Coil fallback error", e)
                ivEarbuds.setImageResource(R.drawable.ic_bluetooth)
            }
        }

        private fun dismissAllAnimated() {
            synchronized(attachedViews) {
                val viewsToDismiss = attachedViews.toList()
                if (viewsToDismiss.isEmpty()) {
                    isOverlayShowing = false
                    return
                }

                for (overlayView in viewsToDismiss) {
                    val cardRoot = overlayView.findViewById<View>(R.id.cardRoot) ?: overlayView
                    cardRoot.animate()
                        .translationY(900f)
                        .alpha(0f)
                        .setDuration(250)
                        .setInterpolator(AccelerateInterpolator())
                        .withEndAction {
                            try {
                                if (overlayView.isAttachedToWindow) {
                                    windowManager?.removeView(overlayView)
                                    Log.i(TAG, "Overlay view removed from WindowManager")
                                }
                            } catch (e: Exception) {
                                try {
                                    windowManager?.removeViewImmediate(overlayView)
                                } catch (_: Exception) {}
                            } finally {
                                attachedViews.remove(overlayView)
                                if (attachedViews.isEmpty()) {
                                    isOverlayShowing = false
                                }
                            }
                        }
                        .start()
                }
            }
        }

        private fun cleanupAllViewsImmediate() {
            synchronized(attachedViews) {
                val iterator = attachedViews.iterator()
                while (iterator.hasNext()) {
                    val view = iterator.next()
                    try {
                        windowManager?.removeViewImmediate(view)
                    } catch (e: Exception) {
                        try {
                            windowManager?.removeView(view)
                        } catch (_: Exception) {}
                    }
                    iterator.remove()
                }
            }
            isOverlayShowing = false
        }

        private fun triggerTapticEngineHaptic(context: Context) {
            try {
                val vibrator = getVibrator(context) ?: return
                if (!vibrator.hasVibrator()) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val effect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 65)
                        .compose()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val attributes = VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
                            .build()
                        vibrator.vibrate(effect, attributes)
                    } else {
                        vibrator.vibrate(effect)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = longArrayOf(0, 30, 65, 15)
                    val amplitudes = intArrayOf(0, 220, 0, 120)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(35)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Taptic Engine haptic error", e)
            }
        }

        private fun triggerSingleClickHaptic(context: Context) {
            try {
                val vibrator = getVibrator(context) ?: return
                if (!vibrator.hasVibrator()) return

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val effect = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                        .compose()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val attributes = VibrationAttributes.Builder()
                            .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
                            .build()
                        vibrator.vibrate(effect, attributes)
                    } else {
                        vibrator.vibrate(effect)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(25, 220))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(25)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Single click haptic error", e)
            }
        }

        private fun getVibrator(context: Context): Vibrator? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }

        private fun isDeviceUnlockedAndActive(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            val isScreenOn = powerManager.isInteractive

            val isLocked = keyguardManager.isKeyguardLocked

            return isScreenOn && !isLocked
        }
    }
}
