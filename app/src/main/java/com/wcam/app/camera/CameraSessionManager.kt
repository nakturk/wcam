package com.wcam.app.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.StringRes
import com.sw.driveeye.linkdevice.XMLinkDevice
import com.wcam.app.R
import com.wcam.app.gl.EglSurfaceRenderer
import com.wcam.app.model.RulerConfig
import com.wcam.app.net.WifiNetworkHelper
import com.wcam.app.util.AppLanguageManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Process-wide camera controller shared across MainActivity (fullscreen),
 * OverlayCameraService (floating window), and WCamCarAppService (Android Auto)
 * so switching or mirroring display modes never drops the stream or active video recording.
 */
class CameraSessionManager private constructor(context: Context) {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        STREAMING
    }

    data class UiState(
        val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val isRecording: Boolean = false,
        val recordSeconds: Int = 0,
        val isMirrored: Boolean = false,
        val rulerConfig: RulerConfig = RulerConfig.defaultConfig(),
        val currentSsid: String? = null,
        val statusText: String = ""
    )

    private sealed interface StatusDescriptor {
        object WaitingInitial : StatusDescriptor
        data class Live(val width: Int, val height: Int) : StatusDescriptor
        object Stopped : StatusDescriptor
        object ConnectingCamera : StatusDescriptor
        object ConnectedWaitingVideo : StatusDescriptor
        data class CameraNoResponse(val ssid: String) : StatusDescriptor
        object ConnectCameraWifi : StatusDescriptor
        object DisconnectedRetrying : StatusDescriptor
    }

    interface Listener {
        fun onStateChanged(state: UiState)
        fun onToastMessage(message: String)
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val controlExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "WCam-Control").apply { isDaemon = true }
    }

    val eglRenderer = EglSurfaceRenderer()
    val carEglRenderer = EglSurfaceRenderer()
    val wifiHelper = WifiNetworkHelper(appContext)

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var activeRecordingFile: File? = null
    private var preparedMaxPixels = 0
    private var hardwareMirrorSupported: Boolean? = null
    private var isActive = false
    private var hasOpenedStream = false
    private var connectedSinceMs = 0L
    @Volatile
    private var frameCount = 0L
    private var reconnectTask: ScheduledFuture<*>? = null
    private var recordTimerTask: ScheduledFuture<*>? = null
    private var lastReconnectAttemptMs = 0L
    @Volatile
    private var currentStatusDescriptor: StatusDescriptor = StatusDescriptor.WaitingInitial

    @Volatile
    var state: UiState = UiState(
        isMirrored = prefs.getBoolean(KEY_MIRRORED, false),
        rulerConfig = RulerConfig.load(prefs),
        statusText = formatStatus(StatusDescriptor.WaitingInitial)
    )
        private set

    private val deviceCallback: XMLinkDevice.IXMDeviceEventCallback = object : XMLinkDevice.IXMDeviceEventCallback {
        override fun yuvCallBack(byteBuffer: ByteBuffer?, width: Int, height: Int, timestamp: Long) {
            if (byteBuffer == null || width <= 0 || height <= 0) return
            val nextFrame = frameCount + 1L
            frameCount = nextFrame
            if (nextFrame == 1L || nextFrame == 30L) {
                Log.i(TAG, "yuvCallBack frame #$nextFrame (${width}x$height)")
            }
            eglRenderer.renderFrame(byteBuffer, width, height)
            carEglRenderer.renderFrame(byteBuffer, width, height)
            val current = state
            if (current.connectionState != ConnectionState.STREAMING ||
                current.videoWidth != width ||
                current.videoHeight != height
            ) {
                val descriptor = StatusDescriptor.Live(width, height)
                currentStatusDescriptor = descriptor
                updateState {
                    it.copy(
                        connectionState = ConnectionState.STREAMING,
                        videoWidth = width,
                        videoHeight = height,
                        statusText = formatStatus(descriptor)
                    )
                }
            }
        }

        override fun onFirstYUVFrame(width: Int, height: Int) {
            Log.i(TAG, "onFirstYUVFrame: ${width}x$height")
            controlExecutor.execute {
                if (width * height > preparedMaxPixels && width > 0 && height > 0) {
                    runCatching { xmlinkDevice.closeVideo() }
                    preparedMaxPixels = width * height
                    xmlinkDevice.preparePlay(width, height)
                    hasOpenedStream = true
                }
                syncCameraSettingsAfterStreamStart()
            }
            val descriptor = StatusDescriptor.Live(width, height)
            currentStatusDescriptor = descriptor
            updateState {
                it.copy(
                    connectionState = ConnectionState.STREAMING,
                    videoWidth = width,
                    videoHeight = height,
                    statusText = formatStatus(descriptor)
                )
            }
        }

        override fun onDisconnectEvent() {
            Log.w(TAG, "onDisconnectEvent")
            controlExecutor.execute {
                handleDisconnectOnControlThread()
            }
        }

        override fun onAlarmRsg(list: MutableList<*>?) = Unit
        override fun onAlarmRsgAdas(list: MutableList<*>?) = Unit
        override fun onAudioData(data: ByteArray?, pts: Long): Boolean = true
        override fun onAudioHeader(a: Int, b: Int, c: Int) = Unit
        override fun onFlashEncryption(status: Int) = Unit

        // Intentionally ignored: Fullscreen <-> Overlay transitions are strictly user-controlled.
        override fun onDriveCar() = Unit
        override fun onReturnCar() = Unit
    }

    private val xmlinkDevice: XMLinkDevice = XMLinkDevice(deviceCallback)

    init {
        xmlinkDevice.initSDK()
        carEglRenderer.setParkingLinesConfig(state.rulerConfig)
        wifiHelper.onWifiAvailabilityChanged = { available ->
            val ssid = wifiHelper.getCurrentSsid()
            updateState { it.copy(currentSsid = ssid) }
            if (available) {
                if (isActive && state.connectionState == ConnectionState.DISCONNECTED) {
                    triggerReconnectNow()
                }
            } else {
                if (isActive &&
                    (state.connectionState == ConnectionState.CONNECTED ||
                        state.connectionState == ConnectionState.STREAMING)
                ) {
                    controlExecutor.execute { handleDisconnectOnControlThread() }
                }
            }
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        mainHandler.post { listener.onStateChanged(state) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun ensureStarted() {
        controlExecutor.execute {
            if (isActive) return@execute
            isActive = true
            wifiHelper.startPassiveBinding()
            updateState { it.copy(currentSsid = wifiHelper.getCurrentSsid()) }
            startReconnectLoop()
            if (wifiHelper.isWifiBound) {
                attemptConnectInternal()
            } else {
                controlExecutor.schedule({
                    if (isActive && state.connectionState == ConnectionState.DISCONNECTED) {
                        attemptConnectInternal()
                    }
                }, INITIAL_WIFI_BIND_WAIT_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun triggerReconnectNow() {
        controlExecutor.execute {
            if (!isActive) {
                isActive = true
                wifiHelper.startPassiveBinding()
                startReconnectLoop()
            }
            lastReconnectAttemptMs = 0L
            if (state.connectionState != ConnectionState.STREAMING &&
                state.connectionState != ConnectionState.CONNECTING
            ) {
                attemptConnectInternal()
            }
        }
    }

    fun refreshLocalizedState() {
        val descriptor = currentStatusDescriptor
        updateState {
            it.copy(statusText = formatStatus(descriptor))
        }
    }

    fun stopSession() {
        controlExecutor.execute {
            isActive = false
            reconnectTask?.cancel(false)
            reconnectTask = null
            stopRecordingInternal(notifyUser = false)
            runCatching { xmlinkDevice.closeVideo() }
            hasOpenedStream = false
            connectedSinceMs = 0L
            frameCount = 0L
            eglRenderer.clearCachedFrame()
            carEglRenderer.clearCachedFrame()
            preparedMaxPixels = 0
            hardwareMirrorSupported = null
            currentStatusDescriptor = StatusDescriptor.Stopped
            updateState {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    videoWidth = 0,
                    videoHeight = 0,
                    isRecording = false,
                    recordSeconds = 0,
                    statusText = formatStatus(StatusDescriptor.Stopped)
                )
            }
        }
    }

    fun toggleParkingLines() {
        val nextConfig = state.rulerConfig.copy(enabled = !state.rulerConfig.enabled)
        nextConfig.save(prefs)
        carEglRenderer.setParkingLinesConfig(nextConfig)
        updateState { it.copy(rulerConfig = nextConfig) }
        controlExecutor.execute {
            if (state.connectionState == ConnectionState.STREAMING ||
                state.connectionState == ConnectionState.CONNECTED
            ) {
                runCatching {
                    xmlinkDevice.setRulerSwitch(if (nextConfig.enabled) 1 else 0)
                }
            }
        }
    }

    fun updateParkingLinesPreview(config: RulerConfig) {
        val clamped = config.clamped()
        carEglRenderer.setParkingLinesConfig(clamped)
        updateState { it.copy(rulerConfig = clamped) }
    }

    fun commitParkingLinesCalibration(config: RulerConfig) {
        val clamped = config.clamped()
        clamped.save(prefs)
        carEglRenderer.setParkingLinesConfig(clamped)
        updateState { it.copy(rulerConfig = clamped) }
        controlExecutor.execute {
            if (state.connectionState == ConnectionState.STREAMING ||
                state.connectionState == ConnectionState.CONNECTED
            ) {
                val (xs, ys) = clamped.toCameraRulerPoints()
                runCatching {
                    xmlinkDevice.setRuler(xs, ys, xs.size, RulerConfig.CAMERA_RULER_TYPE)
                }
            }
        }
        emitToast(str(R.string.toast_lines_saved))
    }

    fun resetParkingLines() {
        val defaults = RulerConfig.defaultConfig(enabled = state.rulerConfig.enabled)
        commitParkingLinesCalibration(defaults)
    }

    fun toggleMirror() {
        val nextMirror = !state.isMirrored
        prefs.edit().putBoolean(KEY_MIRRORED, nextMirror).apply()
        updateState { it.copy(isMirrored = nextMirror) }
        applyMirrorMode(nextMirror)
    }

    fun toggleRecording() {
        controlExecutor.execute {
            if (state.isRecording) {
                stopRecordingInternal(notifyUser = true)
            } else {
                startRecordingInternal()
            }
        }
    }

    fun captureSnapshot() {
        controlExecutor.execute {
            if (state.connectionState != ConnectionState.STREAMING) {
                emitToast(str(R.string.toast_photo_needs_stream))
                return@execute
            }
            val tmpFile = File(appContext.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
            val captured = runCatching { xmlinkDevice.captureJpgImage(tmpFile.absolutePath) }.getOrDefault(false)
            if (captured && tmpFile.exists() && tmpFile.length() > 0L) {
                val savedLocation = saveImageToGallery(tmpFile)
                tmpFile.delete()
                if (savedLocation != null) {
                    emitToast(str(R.string.toast_photo_saved, savedLocation))
                } else {
                    emitToast(str(R.string.toast_photo_gallery_failed))
                }
            } else {
                tmpFile.delete()
                emitToast(str(R.string.toast_photo_capture_failed))
            }
        }
    }

    fun connectWifiAp(ssid: String, password: String, onResult: (Boolean) -> Unit) {
        prefs.edit()
            .putString(KEY_LAST_SSID, ssid.trim())
            .putString(KEY_LAST_PASS, password)
            .apply()
        wifiHelper.connectToCameraAp(ssid, password) { success ->
            updateState { it.copy(currentSsid = wifiHelper.getCurrentSsid()) }
            if (success) {
                triggerReconnectNow()
            }
            onResult(success)
        }
    }

    fun getLastSavedWifiCredentials(): Pair<String, String> {
        val ssid = prefs.getString(KEY_LAST_SSID, "") ?: ""
        val pass = prefs.getString(KEY_LAST_PASS, "") ?: ""
        return ssid to pass
    }

    private fun startReconnectLoop() {
        reconnectTask?.cancel(false)
        reconnectTask = controlExecutor.scheduleWithFixedDelay({
            if (!isActive) return@scheduleWithFixedDelay
            val now = System.currentTimeMillis()
            if (state.connectionState == ConnectionState.CONNECTED &&
                connectedSinceMs > 0L &&
                now - connectedSinceMs >= STALLED_CONNECTED_TIMEOUT_MS
            ) {
                Log.w(TAG, "Connected without video frames for ${now - connectedSinceMs}ms, retrying...")
                handleDisconnectOnControlThread()
                attemptConnectInternal()
                return@scheduleWithFixedDelay
            }
            if (state.connectionState == ConnectionState.DISCONNECTED &&
                now - lastReconnectAttemptMs >= RECONNECT_INTERVAL_MS
            ) {
                attemptConnectInternal()
            }
        }, RECONNECT_INTERVAL_MS, RECONNECT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun attemptConnectInternal() {
        if (!isActive) return
        lastReconnectAttemptMs = System.currentTimeMillis()
        if (hasOpenedStream) {
            runCatching { xmlinkDevice.closeVideo() }
            hasOpenedStream = false
        }
        connectedSinceMs = 0L
        frameCount = 0L

        val ssid = wifiHelper.getCurrentSsid()
        currentStatusDescriptor = StatusDescriptor.ConnectingCamera
        updateState {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                currentSsid = ssid,
                statusText = formatStatus(StatusDescriptor.ConnectingCamera)
            )
        }

        val connected = runCatching { xmlinkDevice.startConnect() }.getOrDefault(false)
        if (!isActive) return

        if (connected) {
            Log.i(TAG, "startConnect succeeded -> preparePlay(${DEFAULT_PREPARE_W}x$DEFAULT_PREPARE_H)")
            runCatching { xmlinkDevice.closeVideo() }
            preparedMaxPixels = DEFAULT_PREPARE_W * DEFAULT_PREPARE_H
            xmlinkDevice.preparePlay(DEFAULT_PREPARE_W, DEFAULT_PREPARE_H)
            hasOpenedStream = true
            connectedSinceMs = System.currentTimeMillis()
            currentStatusDescriptor = StatusDescriptor.ConnectedWaitingVideo
            updateState {
                it.copy(
                    connectionState = ConnectionState.CONNECTED,
                    statusText = formatStatus(StatusDescriptor.ConnectedWaitingVideo)
                )
            }
        } else {
            val descriptor = if (ssid != null) {
                StatusDescriptor.CameraNoResponse(ssid)
            } else {
                StatusDescriptor.ConnectCameraWifi
            }
            currentStatusDescriptor = descriptor
            updateState {
                it.copy(
                    connectionState = ConnectionState.DISCONNECTED,
                    statusText = formatStatus(descriptor)
                )
            }
        }
    }

    private fun handleDisconnectOnControlThread() {
        stopRecordingInternal(notifyUser = true)
        runCatching { xmlinkDevice.closeVideo() }
        hasOpenedStream = false
        connectedSinceMs = 0L
        frameCount = 0L
        eglRenderer.clearCachedFrame()
        carEglRenderer.clearCachedFrame()
        preparedMaxPixels = 0
        hardwareMirrorSupported = null
        currentStatusDescriptor = StatusDescriptor.DisconnectedRetrying
        updateState {
            it.copy(
                connectionState = ConnectionState.DISCONNECTED,
                videoWidth = 0,
                videoHeight = 0,
                isRecording = false,
                recordSeconds = 0,
                currentSsid = wifiHelper.getCurrentSsid(),
                statusText = formatStatus(StatusDescriptor.DisconnectedRetrying)
            )
        }
    }

    private fun syncCameraSettingsAfterStreamStart() {
        val current = state
        runCatching {
            xmlinkDevice.setRulerSwitch(if (current.rulerConfig.enabled) 1 else 0)
        }
        val (xs, ys) = current.rulerConfig.toCameraRulerPoints()
        runCatching {
            xmlinkDevice.setRuler(xs, ys, xs.size, RulerConfig.CAMERA_RULER_TYPE)
        }

        // Probe whether firmware implements mirror control; fallback to OpenGL flip otherwise.
        runCatching {
            xmlinkDevice.getMirror { map ->
                val supported = map != null && map.containsKey("mirrorValue")
                hardwareMirrorSupported = supported
                Log.i(TAG, "Camera getMirror supported=$supported map=$map")
                if (supported) {
                    setGlMirrorHorizontal(false)
                    controlExecutor.execute {
                        runCatching { xmlinkDevice.setMirror(if (state.isMirrored) 1 else 0) }
                    }
                } else {
                    setGlMirrorHorizontal(state.isMirrored)
                }
            }
        }
        if (hardwareMirrorSupported == false) {
            setGlMirrorHorizontal(current.isMirrored)
        } else if (hardwareMirrorSupported == true) {
            setGlMirrorHorizontal(false)
            runCatching { xmlinkDevice.setMirror(if (current.isMirrored) 1 else 0) }
        }
    }

    private fun applyMirrorMode(mirrored: Boolean) {
        when (hardwareMirrorSupported) {
            false -> {
                setGlMirrorHorizontal(mirrored)
            }
            true -> {
                setGlMirrorHorizontal(false)
                controlExecutor.execute {
                    runCatching { xmlinkDevice.setMirror(if (mirrored) 1 else 0) }
                }
            }
            null -> {
                // Unknown yet: if connected, send hardware command; if offline, preview via GL
                if (state.connectionState == ConnectionState.STREAMING ||
                    state.connectionState == ConnectionState.CONNECTED
                ) {
                    controlExecutor.execute {
                        val sent = runCatching { xmlinkDevice.setMirror(if (mirrored) 1 else 0) }
                            .getOrDefault(false)
                        if (!sent) {
                            setGlMirrorHorizontal(mirrored)
                        }
                    }
                } else {
                    setGlMirrorHorizontal(mirrored)
                }
            }
        }
    }

    private fun setGlMirrorHorizontal(mirrored: Boolean) {
        eglRenderer.setMirrorHorizontal(mirrored)
        carEglRenderer.setMirrorHorizontal(mirrored)
    }

    private fun startRecordingInternal() {
        if (state.connectionState != ConnectionState.STREAMING) {
            emitToast(str(R.string.toast_record_needs_stream))
            return
        }
        if (state.isRecording) return

        val tmpFile = File(appContext.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val started = runCatching { xmlinkDevice.startSaveVideo(tmpFile.absolutePath) }.getOrDefault(false)
        if (!started) {
            tmpFile.delete()
            emitToast(str(R.string.toast_record_start_failed))
            return
        }

        activeRecordingFile = tmpFile
        updateState { it.copy(isRecording = true, recordSeconds = 0) }
        startRecordDurationTimer()
        emitToast(str(R.string.toast_record_started))
    }

    private fun stopRecordingInternal(notifyUser: Boolean) {
        recordTimerTask?.cancel(false)
        recordTimerTask = null

        val recFile = activeRecordingFile
        if (!state.isRecording && recFile == null) return

        runCatching { xmlinkDevice.stopSaveVideo() }
        activeRecordingFile = null
        updateState { it.copy(isRecording = false, recordSeconds = 0) }

        if (recFile != null && recFile.exists() && recFile.length() > 0L) {
            val savedPath = saveVideoToGallery(recFile)
            recFile.delete()
            if (notifyUser) {
                if (savedPath != null) {
                    emitToast(str(R.string.toast_video_saved, savedPath))
                } else {
                    emitToast(str(R.string.toast_video_gallery_failed))
                }
            }
        } else {
            recFile?.delete()
            if (notifyUser) {
                emitToast(str(R.string.toast_video_empty_deleted))
            }
        }
    }

    private fun formatStatus(descriptor: StatusDescriptor): String {
        return when (descriptor) {
            StatusDescriptor.WaitingInitial -> str(R.string.status_waiting_camera)
            is StatusDescriptor.Live -> str(R.string.status_live_res, descriptor.width, descriptor.height)
            StatusDescriptor.Stopped -> str(R.string.status_stopped)
            StatusDescriptor.ConnectingCamera -> str(R.string.status_connecting_camera)
            StatusDescriptor.ConnectedWaitingVideo -> str(R.string.status_connected_waiting_video)
            is StatusDescriptor.CameraNoResponse -> str(R.string.status_camera_no_response, descriptor.ssid)
            StatusDescriptor.ConnectCameraWifi -> str(R.string.status_connect_camera_wifi)
            StatusDescriptor.DisconnectedRetrying -> str(R.string.status_disconnected_retrying)
        }
    }

    private fun str(@StringRes resId: Int, vararg args: Any): String {
        val locCtx = AppLanguageManager.localizedContext(appContext)
        return if (args.isEmpty()) locCtx.getString(resId) else locCtx.getString(resId, *args)
    }

    private fun startRecordDurationTimer() {
        recordTimerTask?.cancel(false)
        recordTimerTask = controlExecutor.scheduleWithFixedDelay({
            if (!state.isRecording) return@scheduleWithFixedDelay
            val nativeSecs = runCatching { xmlinkDevice.getRecordMp4Duration() }.getOrDefault(-1)
            val nextSecs = if (nativeSecs >= 0) nativeSecs else state.recordSeconds + 1
            updateState { it.copy(recordSeconds = nextSecs) }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun saveVideoToGallery(sourceFile: File): String? {
        val fileName = "WCam_${timestampString()}.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/WCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            copyToMediaStore(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values, sourceFile)
                ?.let { "Movies/WCam/$fileName" }
        } else {
            copyToLegacyPublicDir(Environment.DIRECTORY_MOVIES, fileName, "video/mp4", sourceFile)
        }
    }

    private fun saveImageToGallery(sourceFile: File): String? {
        val fileName = "WCam_${timestampString()}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/WCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            copyToMediaStore(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values, sourceFile)
                ?.let { "Pictures/WCam/$fileName" }
        } else {
            copyToLegacyPublicDir(Environment.DIRECTORY_PICTURES, fileName, "image/jpeg", sourceFile)
        }
    }

    private fun copyToMediaStore(
        collectionUri: android.net.Uri,
        values: ContentValues,
        sourceFile: File
    ): android.net.Uri? {
        val resolver = appContext.contentResolver
        val itemUri = resolver.insert(collectionUri, values) ?: return null
        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            } ?: return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalizeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(itemUri, finalizeValues, null, null)
            }
            itemUri
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing to MediaStore", t)
            runCatching { resolver.delete(itemUri, null, null) }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun copyToLegacyPublicDir(
        envDir: String,
        fileName: String,
        mimeType: String,
        sourceFile: File
    ): String? {
        val hasStoragePerm = appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val targetDir = if (hasStoragePerm) {
            File(Environment.getExternalStoragePublicDirectory(envDir), "WCam")
        } else {
            File(appContext.getExternalFilesDir(envDir), "WCam")
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) return null
        val destFile = File(targetDir, fileName)
        return try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { out ->
                    input.copyTo(out)
                }
            }
            MediaScannerConnection.scanFile(appContext, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
            destFile.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "Failed writing legacy file", t)
            null
        }
    }

    private fun timestampString(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private inline fun updateState(transform: (UiState) -> UiState) {
        val updated = transform(state)
        state = updated
        mainHandler.post {
            listeners.forEach { it.onStateChanged(updated) }
        }
    }

    private fun emitToast(message: String) {
        mainHandler.post {
            listeners.forEach { it.onToastMessage(message) }
        }
    }

    companion object {
        private const val TAG = "CameraSessionManager"
        private const val PREFS_NAME = "wcam_prefs"
        private const val KEY_MIRRORED = "mirror_enabled"
        private const val KEY_LAST_SSID = "last_wifi_ssid"
        private const val KEY_LAST_PASS = "last_wifi_pass"
        private const val DEFAULT_PREPARE_W = 1280
        private const val DEFAULT_PREPARE_H = 720
        private const val RECONNECT_INTERVAL_MS = 2000L
        private const val STALLED_CONNECTED_TIMEOUT_MS = 6000L
        private const val INITIAL_WIFI_BIND_WAIT_MS = 250L

        @Volatile
        private var instance: CameraSessionManager? = null

        fun getInstance(context: Context): CameraSessionManager {
            return instance ?: synchronized(this) {
                instance ?: CameraSessionManager(context).also { instance = it }
            }
        }
    }
}
