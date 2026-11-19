package com.wcam.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.wcam.app.auto.WCamCameraScreen
import com.wcam.app.camera.CameraSessionManager
import com.wcam.app.service.OverlayCameraService
import com.wcam.app.ui.ParkingLinesView
import com.wcam.app.util.AppLanguageManager
import java.util.Locale

class MainActivity : Activity(), CameraSessionManager.Listener {

    private lateinit var session: CameraSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootContainer: View
    private lateinit var textureView: TextureView
    private lateinit var parkingLinesView: ParkingLinesView
    private lateinit var waitingOverlay: View
    private lateinit var waitingStatusText: TextView
    private lateinit var waitingSubText: TextView
    private lateinit var recordingBadge: View
    private lateinit var recordingTimerText: TextView
    private lateinit var topHudBar: View
    private lateinit var bottomHudBar: View
    private lateinit var calibrationBar: View
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var btnCalibrate: View
    private lateinit var btnLines: View
    private lateinit var iconLines: ImageView
    private lateinit var labelLines: TextView
    private lateinit var btnRecord: View
    private lateinit var iconRecord: ImageView
    private lateinit var labelRecord: TextView
    private lateinit var btnMirror: View
    private lateinit var iconMirror: ImageView
    private lateinit var labelMirror: TextView

    private var hudVisible = true
    private var pendingOverlayAfterPermission = false
    private var isRecreatingForLocale = false
    private var pendingStorageAction: (() -> Unit)? = null

    private val hideHudRunnable = Runnable {
        if (!parkingLinesView.isCalibrationMode &&
            session.state.connectionState == CameraSessionManager.ConnectionState.STREAMING
        ) {
            setHudVisible(false)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        session = CameraSessionManager.getInstance(applicationContext)
        bindViews()
        setupListeners()
        applyImmersiveMode()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()

        if (pendingOverlayAfterPermission) {
            pendingOverlayAfterPermission = false
            if (Settings.canDrawOverlays(this)) {
                launchOverlayAndMinimize()
                return
            } else {
                Toast.makeText(this, getString(R.string.toast_overlay_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }

        if (OverlayCameraService.isOverlayVisible) {
            sendOverlayServiceAction(OverlayCameraService.ACTION_HIDE_OVERLAY)
        }

        session.addListener(this)
        session.ensureStarted()

        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let { surface ->
                session.eglRenderer.attachSurface(surface, textureView.width, textureView.height)
            }
        }
        scheduleHudAutoHide()
    }

    override fun onStart() {
        super.onStart()
        isForegroundVisible = true
    }

    override fun onPause() {
        mainHandler.removeCallbacks(hideHudRunnable)
        session.removeListener(this)
        super.onPause()
    }

    override fun onStop() {
        isForegroundVisible = false
        super.onStop()
        if (!isChangingConfigurations &&
            !isRecreatingForLocale &&
            !OverlayCameraService.isOverlayVisible &&
            !WCamCameraScreen.isCarScreenActive &&
            !session.state.isRecording
        ) {
            session.stopSession()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(hideHudRunnable)
        if (!isChangingConfigurations &&
            !isRecreatingForLocale &&
            !OverlayCameraService.isOverlayVisible &&
            !WCamCameraScreen.isCarScreenActive &&
            !session.state.isRecording
        ) {
            session.stopSession()
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveMode()
        if (textureView.isAvailable) {
            textureView.surfaceTexture?.let { surface ->
                session.eglRenderer.updateSurfaceSize(surface, textureView.width, textureView.height)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (parkingLinesView.isCalibrationMode) {
            exitCalibrationMode(commit = true)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE) {
            val action = pendingStorageAction
            pendingStorageAction = null
            action?.invoke()
        } else if (requestCode == REQ_POST_NOTIFICATIONS) {
            launchOverlayAndMinimize()
        }
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        textureView = findViewById(R.id.cameraTextureView)
        parkingLinesView = findViewById(R.id.parkingLinesView)
        waitingOverlay = findViewById(R.id.waitingOverlay)
        waitingStatusText = findViewById(R.id.waitingStatusText)
        waitingSubText = findViewById(R.id.waitingSubText)
        recordingBadge = findViewById(R.id.recordingBadge)
        recordingTimerText = findViewById(R.id.recordingTimerText)
        topHudBar = findViewById(R.id.topHudBar)
        bottomHudBar = findViewById(R.id.bottomHudBar)
        calibrationBar = findViewById(R.id.calibrationBar)
        statusDot = findViewById(R.id.statusDot)
        statusLabel = findViewById(R.id.statusLabel)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnLines = findViewById(R.id.btnLines)
        iconLines = findViewById(R.id.iconLines)
        labelLines = findViewById(R.id.labelLines)
        btnRecord = findViewById(R.id.btnRecord)
        iconRecord = findViewById(R.id.iconRecord)
        labelRecord = findViewById(R.id.labelRecord)
        btnMirror = findViewById(R.id.btnMirror)
        iconMirror = findViewById(R.id.iconMirror)
        labelMirror = findViewById(R.id.labelMirror)
    }

    private fun setupListeners() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
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

        rootContainer.setOnClickListener {
            if (!parkingLinesView.isCalibrationMode) {
                setHudVisible(!hudVisible)
                if (hudVisible) {
                    scheduleHudAutoHide()
                }
            }
        }

        parkingLinesView.onConfigChanged = { updated ->
            session.updateParkingLinesPreview(updated)
        }
        parkingLinesView.onCalibrationCommit = { committed ->
            session.commitParkingLinesCalibration(committed)
        }

        findViewById<Button>(R.id.btnWaitingWifi).setOnClickListener {
            showWifiDialog()
        }
        findViewById<Button>(R.id.btnWaitingRetry).setOnClickListener {
            session.triggerReconnectNow()
        }
        findViewById<View>(R.id.btnWifi).setOnClickListener {
            scheduleHudAutoHide()
            showWifiDialog()
        }
        findViewById<View>(R.id.btnLanguage).setOnClickListener {
            scheduleHudAutoHide()
            showLanguageDialog()
        }

        btnCalibrate.setOnClickListener {
            if (parkingLinesView.isCalibrationMode) {
                exitCalibrationMode(commit = true)
            } else {
                enterCalibrationMode()
            }
        }

        findViewById<Button>(R.id.btnCalibReset).setOnClickListener {
            session.resetParkingLines()
        }
        findViewById<Button>(R.id.btnCalibDone).setOnClickListener {
            exitCalibrationMode(commit = true)
        }

        btnLines.setOnClickListener {
            session.toggleParkingLines()
            scheduleHudAutoHide()
        }

        btnRecord.setOnClickListener {
            scheduleHudAutoHide()
            runWithLegacyStoragePermissionIfNeeded {
                session.toggleRecording()
            }
        }

        findViewById<View>(R.id.btnPhoto).setOnClickListener {
            scheduleHudAutoHide()
            runWithLegacyStoragePermissionIfNeeded {
                session.captureSnapshot()
            }
        }

        btnMirror.setOnClickListener {
            session.toggleMirror()
            scheduleHudAutoHide()
        }

        findViewById<View>(R.id.btnOverlay).setOnClickListener {
            requestAndSwitchToOverlay()
        }
    }

    private fun enterCalibrationMode() {
        mainHandler.removeCallbacks(hideHudRunnable)
        parkingLinesView.isCalibrationMode = true
        topHudBar.visibility = View.GONE
        bottomHudBar.visibility = View.GONE
        calibrationBar.visibility = View.VISIBLE
    }

    private fun exitCalibrationMode(commit: Boolean) {
        if (commit) {
            session.commitParkingLinesCalibration(parkingLinesView.config)
        }
        parkingLinesView.isCalibrationMode = false
        calibrationBar.visibility = View.GONE
        setHudVisible(true)
        scheduleHudAutoHide()
    }

    private fun setHudVisible(visible: Boolean) {
        hudVisible = visible
        val visibility = if (visible) View.VISIBLE else View.GONE
        topHudBar.visibility = visibility
        bottomHudBar.visibility = visibility
    }

    private fun scheduleHudAutoHide() {
        mainHandler.removeCallbacks(hideHudRunnable)
        if (!parkingLinesView.isCalibrationMode &&
            session.state.connectionState == CameraSessionManager.ConnectionState.STREAMING
        ) {
            mainHandler.postDelayed(hideHudRunnable, HUD_AUTO_HIDE_MS)
        }
    }

    private fun runWithLegacyStoragePermissionIfNeeded(action: () -> Unit) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStorageAction = action
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
        } else {
            action()
        }
    }

    private fun requestAndSwitchToOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_overlay_permission_title)
                .setMessage(R.string.dialog_overlay_permission_message)
                .setPositiveButton(R.string.btn_grant_permission) { _, _ ->
                    pendingOverlayAfterPermission = true
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
            return
        }

        launchOverlayAndMinimize()
    }

    private fun launchOverlayAndMinimize() {
        if (parkingLinesView.isCalibrationMode) {
            exitCalibrationMode(commit = true)
        }
        textureView.surfaceTexture?.let { session.eglRenderer.detachSurface(it) }
        sendOverlayServiceAction(OverlayCameraService.ACTION_SHOW_OVERLAY)
        moveTaskToBack(true)
    }

    private fun sendOverlayServiceAction(action: String) {
        val intent = Intent(this, OverlayCameraService::class.java).apply {
            this.action = action
        }
        if (action == OverlayCameraService.ACTION_SHOW_OVERLAY &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showLanguageDialog() {
        val modes = arrayOf(
            AppLanguageManager.MODE_SYSTEM,
            AppLanguageManager.MODE_TR,
            AppLanguageManager.MODE_EN
        )
        val labels = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_turkish),
            getString(R.string.lang_english)
        )
        val currentMode = AppLanguageManager.getSavedMode(this)
        val checkedIndex = modes.indexOf(currentMode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_language_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                dialog.dismiss()
                val chosenMode = modes[which]
                val needsRecreate = AppLanguageManager.setLanguageMode(this, chosenMode)
                session.refreshLocalizedState()
                if (needsRecreate) {
                    isRecreatingForLocale = true
                    recreate()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showWifiDialog() {
        val (savedSsid, savedPass) = session.getLastSavedWifiCredentials()
        val currentSsid = session.wifiHelper.getCurrentSsid()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val infoText = TextView(this).apply {
            text = if (currentSsid != null) {
                getString(R.string.wifi_dialog_current, currentSsid)
            } else if (!session.wifiHelper.isWifiEnabled()) {
                getString(R.string.wifi_dialog_disabled)
            } else {
                getString(R.string.wifi_dialog_camera_ip)
            }
            textSize = 13f
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        container.addView(infoText)

        val ssidInput = EditText(this).apply {
            setHint(R.string.wifi_dialog_ssid_hint)
            setText(if (savedSsid.isNotEmpty()) savedSsid else currentSsid ?: "")
            isSingleLine = true
        }
        container.addView(ssidInput)

        val passInput = EditText(this).apply {
            setHint(R.string.wifi_dialog_password_hint)
            setText(savedPass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        container.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.wifi_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.wifi_dialog_btn_connect) { _, _ ->
                val ssid = ssidInput.text.toString().trim()
                val pass = passInput.text.toString()
                if (ssid.isEmpty()) {
                    Toast.makeText(this, getString(R.string.toast_enter_ssid), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_wifi_connecting, ssid), Toast.LENGTH_SHORT).show()
                    session.connectWifiAp(ssid, pass) { ok ->
                        val msg = if (ok) {
                            getString(R.string.toast_wifi_connected, ssid)
                        } else {
                            getString(R.string.toast_wifi_failed, ssid)
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton(R.string.wifi_dialog_btn_system_settings) { _, _ ->
                session.wifiHelper.openSystemWifiSettings(this)
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    override fun onStateChanged(state: CameraSessionManager.UiState) {
        parkingLinesView.config = state.rulerConfig
        parkingLinesView.videoAspectRatio = if (state.videoWidth > 0 && state.videoHeight > 0) {
            state.videoWidth.toFloat() / state.videoHeight.toFloat()
        } else {
            0f
        }

        val isStreaming = state.connectionState == CameraSessionManager.ConnectionState.STREAMING
        waitingOverlay.visibility = if (isStreaming) View.GONE else View.VISIBLE
        waitingStatusText.text = state.statusText
        waitingSubText.text = if (state.currentSsid != null) {
            getString(R.string.waiting_sub_connected_ssid, state.currentSsid)
        } else {
            getString(R.string.waiting_sub_hint)
        }

        statusLabel.text = when (state.connectionState) {
            CameraSessionManager.ConnectionState.STREAMING ->
                getString(R.string.status_live_res, state.videoWidth, state.videoHeight)
            CameraSessionManager.ConnectionState.CONNECTED ->
                getString(R.string.status_connected)
            CameraSessionManager.ConnectionState.CONNECTING ->
                getString(R.string.status_connecting)
            CameraSessionManager.ConnectionState.DISCONNECTED ->
                state.currentSsid?.let { getString(R.string.status_ssid_waiting, it) }
                    ?: getString(R.string.status_waiting_wifi)
        }

        val dotColor = when (state.connectionState) {
            CameraSessionManager.ConnectionState.STREAMING -> Color.rgb(46, 213, 115)
            CameraSessionManager.ConnectionState.CONNECTED,
            CameraSessionManager.ConnectionState.CONNECTING -> Color.rgb(255, 195, 18)
            CameraSessionManager.ConnectionState.DISCONNECTED -> Color.rgb(255, 107, 129)
        }
        (statusDot.background.mutate() as? GradientDrawable)?.setColor(dotColor)

        // Update Parking Lines button highlight
        val linesColor = if (state.rulerConfig.enabled) Color.rgb(46, 213, 115) else Color.WHITE
        iconLines.setColorFilter(linesColor, PorterDuff.Mode.SRC_IN)
        labelLines.setTextColor(linesColor)
        setButtonBgTint(btnLines, if (state.rulerConfig.enabled) Color.argb(70, 46, 213, 115) else Color.argb(38, 255, 255, 255))

        // Update Record button and top recording badge
        if (state.isRecording) {
            recordingBadge.visibility = View.VISIBLE
            val mins = state.recordSeconds / 60
            val secs = state.recordSeconds % 60
            recordingTimerText.text = String.format(Locale.US, "REC %02d:%02d", mins, secs)
            iconRecord.setImageResource(R.drawable.ic_stop)
            iconRecord.setColorFilter(Color.rgb(255, 71, 87), PorterDuff.Mode.SRC_IN)
            labelRecord.text = getString(R.string.btn_stop_record)
            labelRecord.setTextColor(Color.rgb(255, 107, 129))
            setButtonBgTint(btnRecord, Color.argb(95, 255, 71, 87))
        } else {
            recordingBadge.visibility = View.GONE
            iconRecord.setImageResource(R.drawable.ic_record)
            iconRecord.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            labelRecord.text = getString(R.string.btn_record)
            labelRecord.setTextColor(Color.WHITE)
            setButtonBgTint(btnRecord, Color.argb(38, 255, 255, 255))
        }

        // Update Mirror button highlight
        val mirrorColor = if (state.isMirrored) Color.rgb(56, 189, 248) else Color.WHITE
        iconMirror.setColorFilter(mirrorColor, PorterDuff.Mode.SRC_IN)
        labelMirror.setTextColor(mirrorColor)
        setButtonBgTint(btnMirror, if (state.isMirrored) Color.argb(75, 56, 189, 248) else Color.argb(38, 255, 255, 255))

        if (isStreaming) {
            scheduleHudAutoHide()
        } else if (!parkingLinesView.isCalibrationMode) {
            mainHandler.removeCallbacks(hideHudRunnable)
            setHudVisible(true)
        }
    }

    override fun onToastMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setButtonBgTint(view: View, argb: Int) {
        (view.background.mutate() as? GradientDrawable)?.setColor(argb)
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    companion object {
        private const val HUD_AUTO_HIDE_MS = 5000L
        private const val REQ_WRITE_STORAGE = 1001
        private const val REQ_POST_NOTIFICATIONS = 1002

        @Volatile
        var isForegroundVisible: Boolean = false
            private set
    }
}
