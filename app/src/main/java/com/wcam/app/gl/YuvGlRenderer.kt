package com.wcam.app.gl

import android.opengl.GLES20
import android.util.Log
import com.wcam.app.model.RulerConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 2.0 I420 (YUV420P) planar renderer.
 * Supports aspect-ratio preserving FIT/FILL scaling, horizontal mirroring,
 * and optional OpenGL ES 2.0 3-zone parking guide lines (used on Android Auto Surface).
 */
class YuvGlRenderer {

    private var program = 0
    private var positionHandle = -1
    private var texCoordHandle = -1
    private var yTexLocation = -1
    private var uTexLocation = -1
    private var vTexLocation = -1

    private var lineProgram = 0
    private var linePositionHandle = -1
    private var lineColorHandle = -1

    private val textures = IntArray(3)
    private val texWidth = IntArray(3)
    private val texHeight = IntArray(3)
    private val texInitialized = BooleanArray(3)

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var frameWidth = 0
    private var frameHeight = 0
    private var currentScaleX = 1f
    private var currentScaleY = 1f
    private var mirrorHorizontal = false
    private var fillViewport = false

    private var glParkingLinesEnabled = false
    private var parkingLinesConfig: RulerConfig = RulerConfig.defaultConfig()
    private var linesDirty = true
    private var lineVertexCount = 0
    private val lineFloatArray = FloatArray(GlParkingLinesGeometry.MAX_FLOATS)
    private val lineVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(GlParkingLinesGeometry.MAX_FLOATS * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var vertexDirty = true
    private val vertexData = FloatArray(16)
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun onSurfaceCreated() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_I420)
        if (program == 0) {
            Log.e(TAG, "Failed to create GL program")
            return
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        yTexLocation = GLES20.glGetUniformLocation(program, "yTexture")
        uTexLocation = GLES20.glGetUniformLocation(program, "uTexture")
        vTexLocation = GLES20.glGetUniformLocation(program, "vTexture")

        lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
        if (lineProgram != 0) {
            linePositionHandle = GLES20.glGetAttribLocation(lineProgram, "aPosition")
            lineColorHandle = GLES20.glGetAttribLocation(lineProgram, "aColor")
        } else {
            Log.w(TAG, "Failed to create parking line GL program")
        }

        GLES20.glGenTextures(3, textures, 0)
        for (i in textures.indices) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texInitialized[i] = false
            texWidth[i] = 0
            texHeight[i] = 0
        }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        vertexDirty = true
        linesDirty = true
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (width != surfaceWidth || height != surfaceHeight) {
            surfaceWidth = width
            surfaceHeight = height
            if (width > 0 && height > 0) {
                GLES20.glViewport(0, 0, width, height)
            }
            vertexDirty = true
            linesDirty = true
        }
    }

    fun setMirrorHorizontal(mirrored: Boolean) {
        if (mirrorHorizontal != mirrored) {
            mirrorHorizontal = mirrored
            vertexDirty = true
        }
    }

    fun setFillViewport(fill: Boolean) {
        if (fillViewport != fill) {
            fillViewport = fill
            vertexDirty = true
            linesDirty = true
        }
    }

    fun setGlParkingLinesEnabled(enabled: Boolean) {
        if (glParkingLinesEnabled != enabled) {
            glParkingLinesEnabled = enabled
            linesDirty = true
        }
    }

    fun setParkingLinesConfig(config: RulerConfig) {
        val clamped = config.clamped()
        if (parkingLinesConfig != clamped) {
            parkingLinesConfig = clamped
            linesDirty = true
        }
    }

    fun drawIdleFrame(): Boolean {
        if (program == 0) return false
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }
        GLES20.glClearColor(0.051f, 0.059f, 0.078f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        return true
    }

    fun drawI420(yuvBuffer: ByteBuffer, width: Int, height: Int): Boolean {
        if (program == 0 || width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            return false
        }
        val ySize = width * height
        val uvWidth = width / 2
        val uvHeight = height / 2
        val uvSize = uvWidth * uvHeight
        val requiredBytes = ySize + uvSize * 2
        if (yuvBuffer.capacity() < requiredBytes) {
            Log.e(TAG, "Invalid I420 buffer capacity=${yuvBuffer.capacity()} required=$requiredBytes")
            return false
        }

        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            vertexDirty = true
            linesDirty = true
        }

        if (vertexDirty) {
            rebuildVertices()
        }

        if (surfaceWidth > 0 && surfaceHeight > 0) {
            GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        }
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        val dup = yuvBuffer.duplicate()
        dup.position(0)
        val ySlice = dup.slice().apply { limit(ySize) }
        dup.position(ySize)
        val uSlice = dup.slice().apply { limit(uvSize) }
        dup.position(ySize + uvSize)
        val vSlice = dup.slice().apply { limit(uvSize) }

        uploadPlane(0, GLES20.GL_TEXTURE0, ySlice, width, height)
        uploadPlane(1, GLES20.GL_TEXTURE1, uSlice, uvWidth, uvHeight)
        uploadPlane(2, GLES20.GL_TEXTURE2, vSlice, uvWidth, uvHeight)

        GLES20.glUniform1i(yTexLocation, 0)
        GLES20.glUniform1i(uTexLocation, 1)
        GLES20.glUniform1i(vTexLocation, 2)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        drawParkingLinesIfEnabled()
        return true
    }

    private fun drawParkingLinesIfEnabled() {
        if (!glParkingLinesEnabled || !parkingLinesConfig.enabled || lineProgram == 0) return
        if (linesDirty) {
            val count = GlParkingLinesGeometry.buildVertices(
                config = parkingLinesConfig,
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                videoScaleX = currentScaleX,
                videoScaleY = currentScaleY,
                out = lineFloatArray
            )
            lineVertexCount = count
            if (count > 0) {
                lineVertexBuffer.clear()
                lineVertexBuffer.put(lineFloatArray, 0, count * GlParkingLinesGeometry.FLOATS_PER_VERTEX)
                lineVertexBuffer.flip()
            }
            linesDirty = false
        }
        if (lineVertexCount <= 0) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(lineProgram)

        val strideBytes = GlParkingLinesGeometry.FLOATS_PER_VERTEX * 4
        lineVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(linePositionHandle, 2, GLES20.GL_FLOAT, false, strideBytes, lineVertexBuffer)
        GLES20.glEnableVertexAttribArray(linePositionHandle)

        lineVertexBuffer.position(2)
        GLES20.glVertexAttribPointer(lineColorHandle, 4, GLES20.GL_FLOAT, false, strideBytes, lineVertexBuffer)
        GLES20.glEnableVertexAttribArray(lineColorHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, lineVertexCount)

        GLES20.glDisableVertexAttribArray(linePositionHandle)
        GLES20.glDisableVertexAttribArray(lineColorHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun onDispose() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (lineProgram != 0) {
            GLES20.glDeleteProgram(lineProgram)
            lineProgram = 0
        }
        GLES20.glDeleteTextures(textures.size, textures, 0)
        textures.fill(0)
        texInitialized.fill(false)
        texWidth.fill(0)
        texHeight.fill(0)
    }

    private fun uploadPlane(index: Int, texUnit: Int, data: ByteBuffer, width: Int, height: Int) {
        GLES20.glActiveTexture(texUnit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[index])
        if (!texInitialized[index] || texWidth[index] != width || texHeight[index] != height) {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_LUMINANCE,
                width,
                height,
                0,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                data
            )
            texInitialized[index] = true
            texWidth[index] = width
            texHeight[index] = height
        } else {
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                0,
                0,
                width,
                height,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                data
            )
        }
    }

    private fun rebuildVertices() {
        var scaleX = 1f
        var scaleY = 1f
        if (surfaceWidth > 0 && surfaceHeight > 0 && frameWidth > 0 && frameHeight > 0) {
            val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight.toFloat()
            val frameAspect = frameWidth.toFloat() / frameHeight.toFloat()
            if (fillViewport) {
                if (frameAspect > surfaceAspect) {
                    scaleX = frameAspect / surfaceAspect
                } else {
                    scaleY = surfaceAspect / frameAspect
                }
            } else {
                if (frameAspect > surfaceAspect) {
                    scaleY = surfaceAspect / frameAspect
                } else {
                    scaleX = frameAspect / surfaceAspect
                }
            }
        }

        val uLeft = if (mirrorHorizontal) 1f else 0f
        val uRight = if (mirrorHorizontal) 0f else 1f
        currentScaleX = scaleX
        currentScaleY = scaleY

        // Triangle strip: bottom-left, bottom-right, top-left, top-right
        // Texture V=1 is bottom, V=0 is top (matching standard camera buffer orientation)
        vertexData[0] = -scaleX; vertexData[1] = -scaleY; vertexData[2] = uLeft;  vertexData[3] = 1f
        vertexData[4] =  scaleX; vertexData[5] = -scaleY; vertexData[6] = uRight; vertexData[7] = 1f
        vertexData[8] = -scaleX; vertexData[9] =  scaleY; vertexData[10] = uLeft; vertexData[11] = 0f
        vertexData[12] = scaleX; vertexData[13] =  scaleY; vertexData[14] = uRight; vertexData[15] = 0f

        vertexBuffer.clear()
        vertexBuffer.put(vertexData)
        vertexBuffer.position(0)
        vertexDirty = false
        linesDirty = true
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return 0
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader)
            return 0
        }
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vertexShader)
        GLES20.glAttachShader(prog, fragmentShader)
        GLES20.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
            GLES20.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile failed ($type): ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "YuvGlRenderer"

        private const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTextureCoord = aTextureCoord;
            }
        """

        private const val FRAGMENT_SHADER_I420 = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D yTexture;
            uniform sampler2D uTexture;
            uniform sampler2D vTexture;
            void main() {
                float y = texture2D(yTexture, vTextureCoord).r;
                float u = texture2D(uTexture, vTextureCoord).r - 0.5;
                float v = texture2D(vTexture, vTextureCoord).r - 0.5;
                float r = y + 1.402 * v;
                float g = y - 0.344 * u - 0.714 * v;
                float b = y + 1.772 * u;
                gl_FragColor = vec4(r, g, b, 1.0);
            }
        """

        private const val LINE_VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vColor = aColor;
            }
        """

        private const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """
    }
}
