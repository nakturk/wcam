package com.wcam.app.gl

import android.graphics.SurfaceTexture
import android.opengl.GLUtils
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import com.wcam.app.model.RulerConfig
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * Single-threaded EGL10 driver that binds to the currently active native window
 * (SurfaceTexture on phone fullscreen/overlay, or Surface on Android Auto head unit)
 * and renders incoming I420 frames without blocking the native decode thread.
 */
class EglSurfaceRenderer {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WCam-EGL").apply {
            isDaemon = true
        }
    }

    private val frameLock = Any()
    private var stagingBuffer: ByteBuffer? = null
    private var stagingWidth = 0
    private var stagingHeight = 0
    private val drawPending = AtomicBoolean(false)

    private var currentNativeWindow: Any? = null
    private var egl: EGL10? = null
    private var eglDisplay: EGLDisplay = EGL10.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL10.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL10.EGL_NO_SURFACE
    private var glRenderer: YuvGlRenderer? = null
    private var initialized = false
    private var prioritySet = false

    @Volatile
    private var hasAttachedSurface = false

    @Volatile
    private var mirrorHorizontal = false

    @Volatile
    private var fillViewport = false

    @Volatile
    private var glParkingLinesEnabled = false

    @Volatile
    private var parkingLinesConfig: RulerConfig = RulerConfig.defaultConfig()

    fun attachSurface(
        surfaceTexture: SurfaceTexture,
        width: Int,
        height: Int,
        enableGlParkingLines: Boolean = false
    ) {
        attachNativeWindow(surfaceTexture, width, height, enableGlParkingLines)
    }

    fun attachSurface(
        surface: Surface,
        width: Int,
        height: Int,
        enableGlParkingLines: Boolean = true
    ) {
        attachNativeWindow(surface, width, height, enableGlParkingLines)
    }

    private fun attachNativeWindow(
        nativeWindow: Any,
        width: Int,
        height: Int,
        enableGlParkingLines: Boolean
    ) {
        hasAttachedSurface = true
        glParkingLinesEnabled = enableGlParkingLines
        executor.execute {
            if (currentNativeWindow === nativeWindow && initialized) {
                glRenderer?.setSurfaceSize(width, height)
                glRenderer?.setGlParkingLinesEnabled(enableGlParkingLines)
                drawLatestCachedFrame()
                return@execute
            }
            disposeInternal()
            currentNativeWindow = nativeWindow
            initInternal(nativeWindow, width, height)
            hasAttachedSurface = initialized
            drawLatestCachedFrame()
        }
    }

    fun updateSurfaceSize(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        updateNativeWindowSize(surfaceTexture, width, height)
    }

    fun updateSurfaceSize(surface: Surface, width: Int, height: Int) {
        updateNativeWindowSize(surface, width, height)
    }

    private fun updateNativeWindowSize(nativeWindow: Any, width: Int, height: Int) {
        if (!hasAttachedSurface) return
        executor.execute {
            if (currentNativeWindow !== nativeWindow || !initialized) return@execute
            glRenderer?.setSurfaceSize(width, height)
            drawLatestCachedFrame()
        }
    }

    fun detachSurface(surfaceTexture: SurfaceTexture) {
        detachNativeWindow(surfaceTexture)
    }

    fun detachSurface(surface: Surface) {
        detachNativeWindow(surface)
    }

    private fun detachNativeWindow(nativeWindow: Any) {
        executor.execute {
            if (currentNativeWindow === nativeWindow) {
                disposeInternal()
                currentNativeWindow = null
                hasAttachedSurface = false
            }
        }
    }

    /**
     * Synchronously tears down EGL if [surface] is currently attached, ensuring
     * `eglDestroySurface` completes before Android Auto reclaims the Surface.
     */
    fun detachSurfaceBlocking(surface: Surface) {
        val future = executor.submit {
            if (currentNativeWindow === surface) {
                disposeInternal()
                currentNativeWindow = null
                hasAttachedSurface = false
            }
        }
        runCatching {
            future.get(DETACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.onFailure {
            Log.w(TAG, "Timed out waiting for blocking surface detach", it)
        }
    }

    fun setMirrorHorizontal(mirrored: Boolean) {
        mirrorHorizontal = mirrored
        if (!hasAttachedSurface) return
        executor.execute {
            glRenderer?.setMirrorHorizontal(mirrored)
            drawLatestCachedFrame()
        }
    }

    fun setFillViewport(fill: Boolean) {
        fillViewport = fill
        if (!hasAttachedSurface) return
        executor.execute {
            glRenderer?.setFillViewport(fill)
            drawLatestCachedFrame()
        }
    }

    fun setParkingLinesConfig(config: RulerConfig) {
        val clamped = config.clamped()
        parkingLinesConfig = clamped
        if (!hasAttachedSurface) return
        executor.execute {
            glRenderer?.setParkingLinesConfig(clamped)
            drawLatestCachedFrame()
        }
    }

    fun renderFrame(byteBuffer: ByteBuffer, width: Int, height: Int) {
        if (!hasAttachedSurface) return
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return
        val requiredBytes = width * height * 3 / 2
        if (byteBuffer.capacity() < requiredBytes) return

        synchronized(frameLock) {
            var target = stagingBuffer
            if (target == null || target.capacity() < requiredBytes) {
                target = ByteBuffer.allocateDirect(requiredBytes)
                stagingBuffer = target
            }
            val src = byteBuffer.duplicate()
            src.position(0)
            src.limit(requiredBytes)
            target.clear()
            target.put(src)
            target.flip()
            stagingWidth = width
            stagingHeight = height
        }

        if (drawPending.compareAndSet(false, true)) {
            executor.execute {
                drawPending.set(false)
                drawLatestCachedFrame()
            }
        }
    }

    fun clearCachedFrame() {
        synchronized(frameLock) {
            stagingWidth = 0
            stagingHeight = 0
        }
        if (hasAttachedSurface) {
            executor.execute {
                drawLatestCachedFrame()
            }
        }
    }

    private fun drawLatestCachedFrame() {
        if (!initialized) return
        val window = currentNativeWindow
        if (window is Surface && !window.isValid) return
        if (window is SurfaceTexture && Build.VERSION.SDK_INT >= 26 && window.isReleased) return

        if (!prioritySet) {
            prioritySet = true
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        }
        val renderer = glRenderer ?: return
        val eglInstance = egl ?: return
        synchronized(frameLock) {
            val cached = stagingBuffer
            if (cached == null || stagingWidth <= 0 || stagingHeight <= 0) {
                if (renderer.drawIdleFrame()) {
                    if (!eglInstance.eglSwapBuffers(eglDisplay, eglSurface)) {
                        val err = eglInstance.eglGetError()
                        Log.w(TAG, "eglSwapBuffers (idle) failed: ${GLUtils.getEGLErrorString(err)}")
                    }
                }
                return
            }
            if (renderer.drawI420(cached, stagingWidth, stagingHeight)) {
                if (!eglInstance.eglSwapBuffers(eglDisplay, eglSurface)) {
                    val err = eglInstance.eglGetError()
                    Log.w(TAG, "eglSwapBuffers failed: ${GLUtils.getEGLErrorString(err)}")
                }
            }
        }
    }

    private fun initInternal(nativeWindow: Any, width: Int, height: Int) {
        when (nativeWindow) {
            is SurfaceTexture -> {
                if (Build.VERSION.SDK_INT >= 26 && nativeWindow.isReleased) {
                    Log.w(TAG, "SurfaceTexture already released, skipping EGL init")
                    return
                }
            }
            is Surface -> {
                if (!nativeWindow.isValid) {
                    Log.w(TAG, "Surface is invalid, skipping EGL init")
                    return
                }
            }
            else -> {
                Log.w(TAG, "Unsupported native window type: ${nativeWindow.javaClass.name}")
                return
            }
        }

        try {
            val eglInstance = EGLContext.getEGL() as EGL10
            egl = eglInstance
            val display = eglInstance.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            if (display == EGL10.EGL_NO_DISPLAY) {
                throw IllegalStateException("eglGetDisplay failed")
            }
            eglDisplay = display

            val version = IntArray(2)
            if (!eglInstance.eglInitialize(display, version)) {
                throw IllegalStateException("eglInitialize failed")
            }

            val config = chooseConfig(eglInstance, display)
                ?: throw IllegalStateException("No matching EGLConfig found")

            val contextAttrs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
            val context = eglInstance.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttrs)
            if (context == null || context == EGL10.EGL_NO_CONTEXT) {
                throw IllegalStateException("eglCreateContext failed: ${GLUtils.getEGLErrorString(eglInstance.eglGetError())}")
            }
            eglContext = context

            val surface = eglInstance.eglCreateWindowSurface(display, config, nativeWindow, null)
            if (surface == null || surface == EGL10.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed: ${GLUtils.getEGLErrorString(eglInstance.eglGetError())}")
            }
            eglSurface = surface

            if (!eglInstance.eglMakeCurrent(display, surface, surface, context)) {
                throw IllegalStateException("eglMakeCurrent failed: ${GLUtils.getEGLErrorString(eglInstance.eglGetError())}")
            }

            val renderer = YuvGlRenderer().apply {
                onSurfaceCreated()
                setSurfaceSize(width, height)
                setMirrorHorizontal(mirrorHorizontal)
                setFillViewport(fillViewport)
                setGlParkingLinesEnabled(glParkingLinesEnabled)
                setParkingLinesConfig(parkingLinesConfig)
            }
            glRenderer = renderer
            initialized = true
            Log.d(TAG, "EGL initialized (${width}x$height, glLines=$glParkingLinesEnabled)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize EGL", t)
            disposeInternal()
        }
    }

    private fun disposeInternal() {
        initialized = false
        glRenderer?.onDispose()
        glRenderer = null

        val eglInstance = egl
        if (eglInstance != null && eglDisplay != EGL10.EGL_NO_DISPLAY) {
            eglInstance.eglMakeCurrent(
                eglDisplay,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL10.EGL_NO_SURFACE) {
                eglInstance.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL10.EGL_NO_CONTEXT) {
                eglInstance.eglDestroyContext(eglDisplay, eglContext)
            }
            eglInstance.eglTerminate(eglDisplay)
        }
        eglSurface = EGL10.EGL_NO_SURFACE
        eglContext = EGL10.EGL_NO_CONTEXT
        eglDisplay = EGL10.EGL_NO_DISPLAY
        egl = null
    }

    private fun chooseConfig(eglInstance: EGL10, display: EGLDisplay): EGLConfig? {
        val numConfigs = IntArray(1)
        val configs = arrayOfNulls<EGLConfig>(1)
        if (!eglInstance.eglChooseConfig(display, EGL_CONFIG_ATTRIBS, configs, 1, numConfigs)) {
            throw IllegalArgumentException("eglChooseConfig failed: ${GLUtils.getEGLErrorString(eglInstance.eglGetError())}")
        }
        return if (numConfigs[0] > 0) configs[0] else null
    }

    companion object {
        private const val TAG = "EglSurfaceRenderer"
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES2_BIT = 4
        private const val DETACH_TIMEOUT_MS = 1000L

        private val EGL_CONFIG_ATTRIBS = intArrayOf(
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_ALPHA_SIZE, 0,
            EGL10.EGL_DEPTH_SIZE, 0,
            EGL10.EGL_STENCIL_SIZE, 0,
            EGL10.EGL_NONE
        )
    }
}
