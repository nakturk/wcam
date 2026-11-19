package com.wcam.app.auto

import android.util.Log
import android.view.Surface
import androidx.annotation.DrawableRes
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.wcam.app.MainActivity
import com.wcam.app.R
import com.wcam.app.camera.CameraSessionManager
import com.wcam.app.service.OverlayCameraService
import com.wcam.app.util.AppLanguageManager

/**
 * Android Auto NavigationTemplate screen that renders the live backup camera stream
 * and OpenGL 3-zone parking lines onto the car head unit's Surface while exposing
 * concise action-strip controls (parking lines, record, snapshot, mirror).
 */
class WCamCameraScreen(carContext: CarContext) :
    Screen(carContext),
    DefaultLifecycleObserver,
    SurfaceCallback,
    CameraSessionManager.Listener {

    private data class TemplateSnapshot(
        val connectionState: CameraSessionManager.ConnectionState,
        val rulerEnabled: Boolean,
        val isRecording: Boolean,
        val isMirrored: Boolean,
        val nonStreamingStatus: String?,
        val currentSsid: String?,
        val languageTag: String
    )

    private val session = CameraSessionManager.getInstance(carContext.applicationContext)
    private var activeSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var lastTemplateSnapshot: TemplateSnapshot? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        isCarScreenActive = true
        session.addListener(this)
        session.ensureStarted()

        val surface = activeSurface
        if (surface != null && surface.isValid && surfaceWidth > 0 && surfaceHeight > 0) {
            session.carEglRenderer.attachSurface(
                surface = surface,
                width = surfaceWidth,
                height = surfaceHeight,
                enableGlParkingLines = true
            )
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        isCarScreenActive = false
        session.removeListener(this)
        activeSurface?.let { session.carEglRenderer.detachSurfaceBlocking(it) }
        if (!MainActivity.isForegroundVisible &&
            !OverlayCameraService.isOverlayVisible &&
            !session.state.isRecording
        ) {
            session.stopSession()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        isCarScreenActive = false
        session.removeListener(this)
        activeSurface?.let { session.carEglRenderer.detachSurfaceBlocking(it) }
        activeSurface = null
        surfaceWidth = 0
        surfaceHeight = 0
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
        if (!MainActivity.isForegroundVisible &&
            !OverlayCameraService.isOverlayVisible &&
            !session.state.isRecording
        ) {
            session.stopSession()
        }
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface
        val width = surfaceContainer.width
        val height = surfaceContainer.height
        if (surface == null || !surface.isValid || width <= 0 || height <= 0) {
            Log.w(TAG, "Ignoring invalid Android Auto surface (${width}x$height, valid=${surface?.isValid})")
            return
        }
        Log.i(TAG, "onSurfaceAvailable: ${width}x$height dpi=${surfaceContainer.dpi}")
        activeSurface = surface
        surfaceWidth = width
        surfaceHeight = height
        session.carEglRenderer.attachSurface(
            surface = surface,
            width = width,
            height = height,
            enableGlParkingLines = true
        )
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface
        Log.i(TAG, "onSurfaceDestroyed (hasSurface=${surface != null})")
        if (surface != null) {
            session.carEglRenderer.detachSurfaceBlocking(surface)
            if (activeSurface === surface) {
                activeSurface = null
                surfaceWidth = 0
                surfaceHeight = 0
            }
        } else {
            activeSurface?.let { session.carEglRenderer.detachSurfaceBlocking(it) }
            activeSurface = null
            surfaceWidth = 0
            surfaceHeight = 0
        }
    }

    override fun onGetTemplate(): Template {
        val state = session.state
        lastTemplateSnapshot = snapshotFrom(state)
        val locCtx = AppLanguageManager.localizedContext(carContext)

        val builder = NavigationTemplate.Builder()

        val isStreaming = state.connectionState == CameraSessionManager.ConnectionState.STREAMING
        if (!isStreaming) {
            val title = when (state.connectionState) {
                CameraSessionManager.ConnectionState.CONNECTING ->
                    locCtx.getString(R.string.car_title_connecting)
                CameraSessionManager.ConnectionState.CONNECTED ->
                    locCtx.getString(R.string.car_title_waiting_video)
                else ->
                    locCtx.getString(R.string.car_title_waiting_camera)
            }
            val detail = if (state.currentSsid != null) {
                locCtx.getString(R.string.car_detail_with_ssid, state.currentSsid, state.statusText)
            } else {
                locCtx.getString(R.string.car_detail_connect_wifi, state.statusText)
            }
            val wifiIcon = tintedIcon(R.drawable.ic_wifi, CarColor.YELLOW)
            builder.setNavigationInfo(
                MessageInfo.Builder(title)
                    .setText(detail)
                    .setImage(wifiIcon)
                    .build()
            )
        }

        val actionStripBuilder = ActionStrip.Builder()
        if (!isStreaming) {
            actionStripBuilder.addAction(
                buildAction(
                    title = locCtx.getString(R.string.car_action_connect),
                    iconRes = R.drawable.ic_wifi,
                    tint = CarColor.YELLOW
                ) {
                    session.triggerReconnectNow()
                }
            )
        }

        actionStripBuilder.addAction(
            buildAction(
                title = locCtx.getString(R.string.btn_lines),
                iconRes = R.drawable.ic_lines,
                tint = if (state.rulerConfig.enabled) CarColor.GREEN else CarColor.DEFAULT
            ) {
                session.toggleParkingLines()
            }
        )

        if (isStreaming) {
            actionStripBuilder.addAction(
                buildAction(
                    title = locCtx.getString(
                        if (state.isRecording) R.string.btn_stop_record else R.string.btn_record
                    ),
                    iconRes = if (state.isRecording) R.drawable.ic_stop else R.drawable.ic_record,
                    tint = if (state.isRecording) CarColor.RED else CarColor.DEFAULT
                ) {
                    session.toggleRecording()
                }
            )
            actionStripBuilder.addAction(
                buildAction(
                    title = locCtx.getString(R.string.car_action_photo),
                    iconRes = R.drawable.ic_camera,
                    tint = CarColor.DEFAULT
                ) {
                    session.captureSnapshot()
                }
            )
        }

        actionStripBuilder.addAction(
            buildAction(
                title = locCtx.getString(R.string.btn_mirror),
                iconRes = R.drawable.ic_mirror,
                tint = if (state.isMirrored) CarColor.BLUE else CarColor.DEFAULT
            ) {
                session.toggleMirror()
            }
        )

        return builder
            .setActionStrip(actionStripBuilder.build())
            .build()
    }

    override fun onStateChanged(state: CameraSessionManager.UiState) {
        val next = snapshotFrom(state)
        if (next != lastTemplateSnapshot && isCarScreenActive) {
            lastTemplateSnapshot = next
            invalidate()
        }
    }

    override fun onToastMessage(message: String) {
        if (isCarScreenActive) {
            CarToast.makeText(carContext, message, CarToast.LENGTH_SHORT).show()
        }
    }

    private fun buildAction(
        title: String,
        @DrawableRes iconRes: Int,
        tint: CarColor,
        onClick: () -> Unit
    ): Action {
        return Action.Builder()
            .setTitle(title)
            .setIcon(tintedIcon(iconRes, tint))
            .setOnClickListener(onClick)
            .build()
    }

    private fun tintedIcon(@DrawableRes iconRes: Int, tint: CarColor): CarIcon {
        val iconCompat = IconCompat.createWithResource(carContext, iconRes)
        return CarIcon.Builder(iconCompat)
            .setTint(tint)
            .build()
    }

    private fun snapshotFrom(state: CameraSessionManager.UiState): TemplateSnapshot {
        val nonStreamingStatus = if (state.connectionState == CameraSessionManager.ConnectionState.STREAMING) {
            null
        } else {
            state.statusText
        }
        return TemplateSnapshot(
            connectionState = state.connectionState,
            rulerEnabled = state.rulerConfig.enabled,
            isRecording = state.isRecording,
            isMirrored = state.isMirrored,
            nonStreamingStatus = nonStreamingStatus,
            currentSsid = state.currentSsid,
            languageTag = AppLanguageManager.getEffectiveLanguage(carContext)
        )
    }

    companion object {
        private const val TAG = "WCamCameraScreen"

        @Volatile
        var isCarScreenActive: Boolean = false
            private set
    }
}
