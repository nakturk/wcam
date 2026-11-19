package com.wcam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.wcam.app.MainActivity
import com.wcam.app.R
import com.wcam.app.auto.WCamCameraScreen
import com.wcam.app.camera.CameraSessionManager
import com.wcam.app.ui.ParkingLinesView
import com.wcam.app.util.AppLanguageManager
import java.util.Locale
import kotlin.math.abs

class OverlayCameraService : Service(), CameraSessionManager.Listener {

    private lateinit var session: CameraSessionManager
    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var textureView: TextureView? = null
    private var parkingLinesView: ParkingLinesView? = null
    private var statusBadge: TextView? = null
    private var controlsBar: View? = null
    private var btnLines: ImageButton? = null
    private var btnRecord: ImageButton? = null

    private val hideControlsRunnable = Runnable {
        controlsBar?.visibility = View.GONE
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        session = CameraSessionManager.getInstance(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_OVERLAY -> {
                hideOverlay()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_CLOSE_ALL -> {
                hideOverlay()
                if (!WCamCameraScreen.isCarScreenActive) {
                    session.stopSession()
                }
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_SHOW_OVERLAY, null -> {
                startForegroundWithNotification()
                session.ensureStarted()
                showOverlay()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val locCtx = AppLanguageManager.localizedContext(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                locCtx.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val contentPending = PendingIntent.getActivity(
            this,
            101,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeIntent = Intent(this, OverlayCameraService::class.java).apply {
            action = ACTION_CLOSE_ALL
        }
        val closePending = PendingIntent.getService(
            this,
            102,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setContentTitle(locCtx.getString(R.string.notification_title))
            .setContentText(locCtx.getString(R.string.notification_content))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPending)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    R.drawable.ic_close,
                    locCtx.getString(R.string.btn_close),
                    closePending
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun showOverlay() {
        if (overlayView != null) {
            scheduleHideControls()
            return
        }

        val density = resources.displayMetrics.density
        val (screenW, screenH) = getScreenBounds()
        val prefs = getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE)

        val minW = (200 * density).toInt()
        val minH = (125 * density).toInt()
        val defaultW = (300 * density).toInt().coerceAtMost((screenW * 0.85f).toInt())
        val defaultH = (180 * density).toInt().coerceAtMost((screenH * 0.6f).toInt())

        val savedW = prefs.getInt(KEY_W, defaultW).coerceIn(minW, (screenW - (16 * density).toInt()).coerceAtLeast(minW))
        val savedH = prefs.getInt(KEY_H, defaultH).coerceIn(minH, (screenH - (32 * density).toInt()).coerceAtLeast(minH))
        val savedX = prefs.getInt(KEY_X, (20 * density).toInt()).coerceIn(0, (screenW - savedW).coerceAtLeast(0))
        val savedY = prefs.getInt(KEY_Y, (90 * density).toInt()).coerceIn(0, (screenH - savedH).coerceAtLeast(0))

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            savedW,
            savedH,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX
            y = savedY
        }

        val root = LayoutInflater.from(AppLanguageManager.localizedContext(this))
            .inflate(R.layout.layout_overlay_camera, null)
        val videoContainer = root.findViewById<View>(R.id.overlayVideoContainer)
        videoContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 12f * density)
            }
        }
        videoContainer.clipToOutline = true

        val tex = root.findViewById<TextureView>(R.id.overlayTextureView)
        val lines = root.findViewById<ParkingLinesView>(R.id.overlayParkingLinesView)
        val badge = root.findViewById<TextView>(R.id.overlayStatusBadge)
        val controls = root.findViewById<View>(R.id.overlayControlsBar)
        val btnFull = root.findViewById<ImageButton>(R.id.btnOverlayFullscreen)
        val btnL = root.findViewById<ImageButton>(R.id.btnOverlayLines)
        val btnRec = root.findViewById<ImageButton>(R.id.btnOverlayRecord)
        val btnClose = root.findViewById<ImageButton>(R.id.btnOverlayClose)
        val resizeHandle = root.findViewById<View>(R.id.overlayResizeHandle)

        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                session.eglRenderer.attachSurface(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                session.eglRenderer.updateSurfaceSize(surface, width, height)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                session.eglRenderer.detachSurface(surface)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        setupDragAndTap(root, params, prefs)
        setupResizeHandle(resizeHandle, root, params, minW, minH, prefs)

        btnFull.setOnClickListener {
            openFullscreen()
        }
        btnL.setOnClickListener {
            session.toggleParkingLines()
            scheduleHideControls()
        }
        btnRec.setOnClickListener {
            session.toggleRecording()
            scheduleHideControls()
        }
        btnClose.setOnClickListener {
            hideOverlay()
            if (!WCamCameraScreen.isCarScreenActive) {
                session.stopSession()
            }
            stopForegroundCompat()
            stopSelf()
        }

        overlayView = root
        layoutParams = params
        textureView = tex
        parkingLinesView = lines
        statusBadge = badge
        controlsBar = controls
        btnLines = btnL
        btnRecord = btnRec

        try {
            windowManager.addView(root, params)
            isOverlayVisible = true
            session.addListener(this)
            scheduleHideControls()
        } catch (t: Throwable) {
            overlayView = null
            isOverlayVisible = false
            Toast.makeText(
                this,
                AppLanguageManager.localizedContext(this).getString(R.string.toast_overlay_open_failed, t.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun hideOverlay() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        session.removeListener(this)
        val view = overlayView
        if (view != null) {
            textureView?.surfaceTexture?.let { session.eglRenderer.detachSurface(it) }
            runCatching { windowManager.removeViewImmediate(view) }
        }
        overlayView = null
        layoutParams = null
        textureView = null
        parkingLinesView = null
        statusBadge = null
        controlsBar = null
        btnLines = null
        btnRecord = null
        isOverlayVisible = false
    }

    private fun openFullscreen() {
        hideOverlay()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        stopForegroundCompat()
        stopSelf()
    }

    private fun setupDragAndTap(
        root: View,
        params: WindowManager.LayoutParams,
        prefs: android.content.SharedPreferences
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val (screenW, screenH) = getScreenBounds()
                        params.x = (startX + dx.toInt()).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        toggleControlsVisibility()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun setupResizeHandle(
        handle: View,
        root: View,
        params: WindowManager.LayoutParams,
        minW: Int,
        minH: Int,
        prefs: android.content.SharedPreferences
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startW = 0
        var startH = 0

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startW = params.width
                    startH = params.height
                    mainHandler.removeCallbacks(hideControlsRunnable)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val (screenW, screenH) = getScreenBounds()
                    val maxW = (screenW - params.x).coerceAtLeast(minW)
                    val maxH = (screenH - params.y).coerceAtLeast(minH)
                    params.width = (startW + (event.rawX - downRawX).toInt()).coerceIn(minW, maxW)
                    params.height = (startH + (event.rawY - downRawY).toInt()).coerceIn(minH, maxH)
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt(KEY_W, params.width).putInt(KEY_H, params.height).apply()
                    scheduleHideControls()
                    true
                }

                else -> false
            }
        }
    }

    private fun toggleControlsVisibility() {
        val bar = controlsBar ?: return
        if (bar.visibility == View.VISIBLE) {
            mainHandler.removeCallbacks(hideControlsRunnable)
            bar.visibility = View.GONE
        } else {
            bar.visibility = View.VISIBLE
            scheduleHideControls()
        }
    }

    private fun scheduleHideControls() {
        val bar = controlsBar ?: return
        bar.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
    }

    override fun onStateChanged(state: CameraSessionManager.UiState) {
        val lines = parkingLinesView ?: return
        lines.config = state.rulerConfig
        lines.videoAspectRatio = if (state.videoWidth > 0 && state.videoHeight > 0) {
            state.videoWidth.toFloat() / state.videoHeight.toFloat()
        } else {
            0f
        }

        btnLines?.setColorFilter(
            if (state.rulerConfig.enabled) Color.rgb(46, 213, 115) else Color.WHITE,
            PorterDuff.Mode.SRC_IN
        )

        btnRecord?.apply {
            setImageResource(if (state.isRecording) R.drawable.ic_stop else R.drawable.ic_record)
            setColorFilter(
                if (state.isRecording) Color.rgb(255, 71, 87) else Color.WHITE,
                PorterDuff.Mode.SRC_IN
            )
        }

        val badge = statusBadge ?: return
        when {
            state.isRecording -> {
                val mins = state.recordSeconds / 60
                val secs = state.recordSeconds % 60
                badge.text = String.format(Locale.US, "● REC %02d:%02d", mins, secs)
                badge.setTextColor(Color.rgb(255, 90, 95))
                badge.visibility = View.VISIBLE
            }
            state.connectionState == CameraSessionManager.ConnectionState.STREAMING -> {
                badge.visibility = View.GONE
            }
            else -> {
                val locCtx = AppLanguageManager.localizedContext(this)
                badge.text = when (state.connectionState) {
                    CameraSessionManager.ConnectionState.CONNECTING -> locCtx.getString(R.string.status_connecting)
                    CameraSessionManager.ConnectionState.CONNECTED -> locCtx.getString(R.string.status_waiting_video_short)
                    else -> locCtx.getString(R.string.status_waiting_camera_short)
                }
                badge.setTextColor(Color.WHITE)
                badge.visibility = View.VISIBLE
            }
        }
    }

    override fun onToastMessage(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun getScreenBounds(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.wcam.app.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.wcam.app.action.HIDE_OVERLAY"
        const val ACTION_CLOSE_ALL = "com.wcam.app.action.CLOSE_ALL"

        private const val CHANNEL_ID = "wcam_camera_channel"
        private const val NOTIFICATION_ID = 2001
        private const val CONTROLS_AUTO_HIDE_MS = 4000L

        private const val PREFS_OVERLAY = "wcam_overlay_prefs"
        private const val KEY_X = "ov_x"
        private const val KEY_Y = "ov_y"
        private const val KEY_W = "ov_w"
        private const val KEY_H = "ov_h"

        @Volatile
        var isOverlayVisible: Boolean = false
            private set
    }
}
