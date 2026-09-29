package com.example.honoroverlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            dismissImmediately()
        }
    }

    override fun onDestroy() {
        dismissImmediately()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_SHOW_OVERLAY = "com.example.honoroverlay.action.SHOW_OVERLAY"
        const val ACTION_DISMISS_OVERLAY = "com.example.honoroverlay.action.DISMISS_OVERLAY"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_BATTERY_LEVEL = "extra_battery_level"

        private const val SUCK_SHADER_SRC = """
            uniform shader content;
            uniform float2 resolution;
            uniform float progress;

            half4 main(float2 fragCoord) {
                if (progress <= 0.0005) {
                    return content.eval(fragCoord);
                }
                if (progress >= 0.9995) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                float p = progress;
                float px = 0.5;
                float py = 1.0;

                float top_y = pow(p, 1.8) * py;
                float bot_y = py;

                float v_top_orig = 0.0;
                float v_bot_orig = 1.0 - pow(p, 1.2);

                if (v_bot_orig <= 0.001) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                float span_y = bot_y - top_y;
                if (span_y <= 0.001) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                float v_screen = fragCoord.y / resolution.y;
                if (v_screen < top_y || v_screen > bot_y) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                float eta = (v_screen - top_y) / span_y;

                float top_scale = 1.0 - pow(p, 1.8) * 0.45;
                float bot_scale = max(0.02, 1.0 - pow(p, 0.75) * 0.98);
                float w_factor = top_scale + (bot_scale - top_scale) * pow(eta, 1.3);

                if (w_factor <= 0.001) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                float u_screen = fragCoord.x / resolution.x;
                float dx = u_screen - px;

                float dx_orig = dx / w_factor;
                float u_orig = 0.5 + dx_orig;

                float sag = p * 0.07 * (1.0 - clamp(dx_orig * dx_orig * 4.0, 0.0, 1.0)) * sin(eta * 3.1415926535);
                float v_orig_sag = v_top_orig + eta * (v_bot_orig - v_top_orig) - sag;

                if (u_orig < 0.0 || u_orig > 1.0 || v_orig_sag < 0.0 || v_orig_sag > 1.0) {
                    return half4(0.0, 0.0, 0.0, 0.0);
                }

                float edge_dist_x = min(u_orig, 1.0 - u_orig) * (w_factor * resolution.x);
                float edge_dist_y = min(v_orig_sag, 1.0 - v_orig_sag) * (span_y * resolution.y);
                float edge_dist = min(edge_dist_x, edge_dist_y);
                float alpha = clamp(edge_dist * 0.5, 0.0, 1.0);

                float2 sampleCoord = float2(u_orig * resolution.x, v_orig_sag * resolution.y);
                half4 color = content.eval(sampleCoord);

                float highlight = 1.0 + 0.15 * p * sin(eta * 3.1415926535) * (1.0 - abs(dx_orig));
                color.rgb *= highlight;

                return color * alpha;
            }
        """

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

        fun dismissImmediately() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanupAllViewsImmediate()
            } else {
                mainHandler.post {
                    cleanupAllViewsImmediate()
                }
            }
        }

        private fun showOverlayInternal(context: Context, deviceName: String, batteryLevel: Int = -1) {
            registerOrientationListener(context)

            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot show overlay: SYSTEM_ALERT_WINDOW permission not granted")
                return
            }

            if (!shouldShowOverlay(context)) {
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
            val baseElevation = if (isDarkTheme) 4f * density else 10f * density

            if (isDarkTheme) {
                cardRoot.setBackgroundResource(R.drawable.bg_card_dark)
                cardRoot.elevation = baseElevation
                tvDeviceName.setTextColor(Color.WHITE)
                tvBatteryPercent?.setTextColor(Color.WHITE)
                btnDone.setBackgroundResource(R.drawable.bg_btn_dark)
                btnDone.setTextColor(Color.WHITE)
            } else {
                cardRoot.setBackgroundResource(R.drawable.bg_card_light)
                cardRoot.elevation = baseElevation
                tvDeviceName.setTextColor(Color.BLACK)
                tvBatteryPercent?.setTextColor(Color.BLACK)
                btnDone.setBackgroundResource(R.drawable.bg_btn_light)
                btnDone.setTextColor(Color.WHITE)
            }

            var isThisViewDismissing = false
            fun dismissThisOverlayView() {
                if (isThisViewDismissing) return
                isThisViewDismissing = true
                btnDone.isClickable = false
                cardRoot.setOnTouchListener(null)

                startSuckDismissAnimation(overlayView) {
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
                            if (currentTransY > 120f) {
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
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 0
            }

            val displayMetrics = context.resources.displayMetrics
            val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(displayMetrics.widthPixels, View.MeasureSpec.EXACTLY)
            val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(displayMetrics.heightPixels, View.MeasureSpec.AT_MOST)
            overlayView.measure(widthMeasureSpec, heightMeasureSpec)
            val measuredW = overlayView.measuredWidth.toFloat().coerceAtLeast(displayMetrics.widthPixels.toFloat())
            val measuredH = overlayView.measuredHeight.toFloat().coerceAtLeast(360f * density)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val shader = RuntimeShader(SUCK_SHADER_SRC)
                shader.setFloatUniform("progress", 1.0f)
                shader.setFloatUniform("resolution", measuredW, measuredH)
                overlayView.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
                cardRoot.elevation = 0f
                overlayView.alpha = 1.0f
            } else {
                cardRoot.translationY = 800f
                cardRoot.alpha = 0f
            }

            try {
                windowManager?.addView(overlayView, params)
                attachedViews.add(overlayView)
                isOverlayShowing = true
                Log.i(TAG, "Overlay window added to WindowManager. Total active: ${attachedViews.size}")

                startSuckAppearanceAnimation(overlayView, cardRoot, measuredW, measuredH, baseElevation)
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
                    val cardRoot = overlayView.findViewById<View>(R.id.cardRoot)
                    val btnDone = overlayView.findViewById<View>(R.id.btnDone)
                    btnDone?.isClickable = false
                    cardRoot?.setOnTouchListener(null)

                    startSuckDismissAnimation(overlayView) {
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
                }
            }
        }

        private fun cleanupAllViewsImmediate() {
            synchronized(attachedViews) {
                val iterator = attachedViews.iterator()
                while (iterator.hasNext()) {
                    val view = iterator.next()
                    try {
                        val cardRoot = view.findViewById<View>(R.id.cardRoot)
                        (view.tag as? ValueAnimator)?.cancel()
                        (cardRoot?.tag as? ValueAnimator)?.cancel()
                        cardRoot?.animate()?.cancel()
                        view.setRenderEffect(null)
                        cardRoot?.setRenderEffect(null)
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

        private fun startSuckAppearanceAnimation(
            overlayView: View,
            cardRoot: View,
            initialW: Float,
            initialH: Float,
            baseElevation: Float
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                overlayView.post {
                    val w = if (overlayView.width > 0) overlayView.width.toFloat() else initialW
                    val h = if (overlayView.height > 0) overlayView.height.toFloat() else initialH
                    val shader = RuntimeShader(SUCK_SHADER_SRC)

                    val animator = ValueAnimator.ofFloat(1.0f, 0.0f).apply {
                        duration = 440
                        interpolator = PathInterpolator(0.16f, 1.0f, 0.3f, 1.0f)
                        addUpdateListener { va ->
                            val p = va.animatedValue as Float
                            shader.setFloatUniform("progress", p)
                            shader.setFloatUniform("resolution", w, h)
                            overlayView.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
                            cardRoot.elevation = (1.0f - p) * baseElevation
                            overlayView.invalidate()
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                overlayView.setRenderEffect(null)
                                cardRoot.elevation = baseElevation
                                overlayView.tag = null
                            }
                        })
                    }
                    overlayView.tag = animator
                    animator.start()
                }
            } else {
                cardRoot.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(PathInterpolator(0.2f, 1f, 0.2f, 1f))
                    .start()
            }
        }

        private fun startSuckDismissAnimation(overlayView: View, onFinished: () -> Unit) {
            val cardRoot = overlayView.findViewById<View>(R.id.cardRoot) ?: run {
                onFinished()
                return
            }

            (overlayView.tag as? ValueAnimator)?.cancel()
            (cardRoot.tag as? ValueAnimator)?.cancel()
            cardRoot.animate().cancel()
            cardRoot.translationY = 0f

            val w = if (overlayView.width > 0) overlayView.width.toFloat() else overlayView.measuredWidth.toFloat()
            val h = if (overlayView.height > 0) overlayView.height.toFloat() else overlayView.measuredHeight.toFloat()
            val currentElevation = cardRoot.elevation

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && w > 0f && h > 0f) {
                val shader = RuntimeShader(SUCK_SHADER_SRC)
                val animator = ValueAnimator.ofFloat(0.0f, 1.0f).apply {
                    duration = 380
                    interpolator = PathInterpolator(0.38f, 0.0f, 0.2f, 1.0f)
                    addUpdateListener { va ->
                        val p = va.animatedValue as Float
                        shader.setFloatUniform("progress", p)
                        shader.setFloatUniform("resolution", w, h)
                        overlayView.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
                        cardRoot.elevation = (1.0f - p) * currentElevation
                        overlayView.invalidate()
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            overlayView.setRenderEffect(null)
                            overlayView.tag = null
                            onFinished()
                        }
                    })
                }
                overlayView.tag = animator
                animator.start()
            } else {
                cardRoot.animate()
                    .translationY(900f)
                    .alpha(0f)
                    .setDuration(250)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction { onFinished() }
                    .start()
            }
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

        private var isOrientationListenerRegistered = false

        private fun registerOrientationListener(context: Context) {
            if (isOrientationListenerRegistered) return
            try {
                context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                            dismissImmediately()
                        }
                    }

                    override fun onLowMemory() {}
                })
                isOrientationListenerRegistered = true
            } catch (_: Exception) {}
        }

        private fun shouldShowOverlay(context: Context): Boolean {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            val isScreenOn = powerManager.isInteractive
            val isLocked = keyguardManager.isKeyguardLocked
            val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

            return isScreenOn && !isLocked && isPortrait
        }
    }
}
