package com.wcam.app.gl

import com.wcam.app.model.RulerConfig
import kotlin.math.hypot

/**
 * Pure-Kotlin geometry builder that tessellates the 3-zone parking guide trapezoid into
 * screen-space triangle quads in OpenGL NDC space (`[-1, 1]`), avoiding GPU `glLineWidth`
 * 1px clamping on embedded GPUs.
 *
 * Output vertex layout: `[ndcX, ndcY, r, g, b, a]` (6 floats per vertex, 6 vertices per segment).
 */
object GlParkingLinesGeometry {

    const val FLOATS_PER_VERTEX = 6
    const val VERTICES_PER_SEGMENT = 6
    private const val SEGMENT_COUNT = 13
    const val MAX_VERTICES = SEGMENT_COUNT * 2 * VERTICES_PER_SEGMENT // 156 (shadow + color pass)
    const val MAX_FLOATS = MAX_VERTICES * FLOATS_PER_VERTEX // 936

    private const val CROSSBAR_DASH_RATIO = 0.34f
    private const val SOLID_BAR_INSET_RATIO = 0.12f

    private const val SHADOW_R = 0f
    private const val SHADOW_G = 0f
    private const val SHADOW_B = 0f
    private const val SHADOW_A = 0.67f

    private const val GREEN_R = 46f / 255f
    private const val GREEN_G = 213f / 255f
    private const val GREEN_B = 115f / 255f
    private const val GREEN_A = 0.92f

    private const val YELLOW_R = 1f
    private const val YELLOW_G = 195f / 255f
    private const val YELLOW_B = 18f / 255f
    private const val YELLOW_A = 0.92f

    private const val RED_R = 1f
    private const val RED_G = 71f / 255f
    private const val RED_B = 87f / 255f
    private const val RED_A = 0.94f

    private data class Segment(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val widthPx: Float,
        val r: Float,
        val g: Float,
        val b: Float,
        val a: Float
    )

    /**
     * Builds shadow + colored line quads into [out]. Returns the number of vertices written.
     */
    fun buildVertices(
        config: RulerConfig,
        surfaceWidth: Int,
        surfaceHeight: Int,
        videoScaleX: Float,
        videoScaleY: Float,
        out: FloatArray
    ): Int {
        if (!config.enabled || surfaceWidth <= 0 || surfaceHeight <= 0) return 0
        if (videoScaleX <= 0f || videoScaleY <= 0f) return 0
        if (out.size < MAX_FLOATS) return 0

        val clamped = config.clamped()
        val zone = clamped.computeZoneGeometry()

        val sw = surfaceWidth.toFloat()
        val sh = surfaceHeight.toFloat()
        val videoLeft = (1f - videoScaleX) * 0.5f * sw
        val videoTop = (1f - videoScaleY) * 0.5f * sh
        val videoW = videoScaleX * sw
        val videoH = videoScaleY * sh

        val minDim = minOf(videoW, videoH)
        val mainWidthPx = (minDim * 0.011f).coerceIn(3.5f, 12f)
        val barWidthPx = mainWidthPx * 0.72f
        val shadowExtraPx = mainWidthPx * 0.55f
        val tickLenPx = minDim * 0.035f

        fun px(p: RulerConfig.Point2F): Float = videoLeft + p.x * videoW
        fun py(p: RulerConfig.Point2F): Float = videoTop + p.y * videoH
        fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        val lftX = px(zone.leftFarTop); val lftY = py(zone.leftFarTop)
        val rftX = px(zone.rightFarTop); val rftY = py(zone.rightFarTop)
        val lgyX = px(zone.leftGreenYellow); val lgyY = py(zone.leftGreenYellow)
        val rgyX = px(zone.rightGreenYellow); val rgyY = py(zone.rightGreenYellow)
        val lyrX = px(zone.leftYellowRed); val lyrY = py(zone.leftYellowRed)
        val ryrX = px(zone.rightYellowRed); val ryrY = py(zone.rightYellowRed)
        val lnbX = px(zone.leftNearBottom); val lnbY = py(zone.leftNearBottom)
        val rnbX = px(zone.rightNearBottom); val rnbY = py(zone.rightNearBottom)

        // Bottom inward tick direction follows the bottom baseline unit vector
        val botDx = rnbX - lnbX
        val botDy = rnbY - lnbY
        val botLen = hypot(botDx, botDy).coerceAtLeast(1f)
        val tickUx = (botDx / botLen) * tickLenPx
        val tickUy = (botDy / botLen) * tickLenPx

        val segments = arrayOf(
            // Green far zone (rails + dashed outer crossbars)
            Segment(lftX, lftY, lgyX, lgyY, mainWidthPx, GREEN_R, GREEN_G, GREEN_B, GREEN_A),
            Segment(rftX, rftY, rgyX, rgyY, mainWidthPx, GREEN_R, GREEN_G, GREEN_B, GREEN_A),
            Segment(
                lftX, lftY,
                lerpF(lftX, rftX, CROSSBAR_DASH_RATIO), lerpF(lftY, rftY, CROSSBAR_DASH_RATIO),
                barWidthPx, GREEN_R, GREEN_G, GREEN_B, GREEN_A
            ),
            Segment(
                lerpF(rftX, lftX, CROSSBAR_DASH_RATIO), lerpF(rftY, lftY, CROSSBAR_DASH_RATIO),
                rftX, rftY,
                barWidthPx, GREEN_R, GREEN_G, GREEN_B, GREEN_A
            ),
            // Yellow middle zone (rails + dashed outer crossbars)
            Segment(lgyX, lgyY, lyrX, lyrY, mainWidthPx, YELLOW_R, YELLOW_G, YELLOW_B, YELLOW_A),
            Segment(rgyX, rgyY, ryrX, ryrY, mainWidthPx, YELLOW_R, YELLOW_G, YELLOW_B, YELLOW_A),
            Segment(
                lgyX, lgyY,
                lerpF(lgyX, rgyX, CROSSBAR_DASH_RATIO), lerpF(lgyY, rgyY, CROSSBAR_DASH_RATIO),
                barWidthPx, YELLOW_R, YELLOW_G, YELLOW_B, YELLOW_A
            ),
            Segment(
                lerpF(rgyX, lgyX, CROSSBAR_DASH_RATIO), lerpF(rgyY, lgyY, CROSSBAR_DASH_RATIO),
                rgyX, rgyY,
                barWidthPx, YELLOW_R, YELLOW_G, YELLOW_B, YELLOW_A
            ),
            // Red near zone (rails + solid inner crossbar + bottom bumper ticks)
            Segment(lyrX, lyrY, lnbX, lnbY, mainWidthPx, RED_R, RED_G, RED_B, RED_A),
            Segment(ryrX, ryrY, rnbX, rnbY, mainWidthPx, RED_R, RED_G, RED_B, RED_A),
            Segment(
                lerpF(lyrX, ryrX, SOLID_BAR_INSET_RATIO), lerpF(lyrY, ryrY, SOLID_BAR_INSET_RATIO),
                lerpF(lyrX, ryrX, 1f - SOLID_BAR_INSET_RATIO), lerpF(lyrY, ryrY, 1f - SOLID_BAR_INSET_RATIO),
                mainWidthPx, RED_R, RED_G, RED_B, RED_A
            ),
            Segment(lnbX, lnbY, lnbX + tickUx, lnbY + tickUy, mainWidthPx, RED_R, RED_G, RED_B, RED_A),
            Segment(rnbX - tickUx, rnbY - tickUy, rnbX, rnbY, mainWidthPx, RED_R, RED_G, RED_B, RED_A)
        )

        var floatIdx = 0
        // Pass 1: dark halo shadow quads behind all guide lines
        for (seg in segments) {
            floatIdx = emitQuad(
                seg.x1, seg.y1, seg.x2, seg.y2,
                seg.widthPx + shadowExtraPx,
                sw, sh,
                SHADOW_R, SHADOW_G, SHADOW_B, SHADOW_A,
                out, floatIdx
            )
        }
        // Pass 2: colored foreground quads
        for (seg in segments) {
            floatIdx = emitQuad(
                seg.x1, seg.y1, seg.x2, seg.y2,
                seg.widthPx,
                sw, sh,
                seg.r, seg.g, seg.b, seg.a,
                out, floatIdx
            )
        }

        return floatIdx / FLOATS_PER_VERTEX
    }

    private fun emitQuad(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        widthPx: Float,
        surfaceW: Float,
        surfaceH: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        out: FloatArray,
        startIdx: Int
    ): Int {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len < 1e-3f) return startIdx

        val ux = dx / len
        val uy = dy / len
        val halfPx = widthPx * 0.5f
        val capPx = halfPx * 0.35f

        val sx1 = x1 - ux * capPx
        val sy1 = y1 - uy * capPx
        val sx2 = x2 + ux * capPx
        val sy2 = y2 + uy * capPx

        val nx = -uy * halfPx
        val ny = ux * halfPx

        val ax = toNdcX(sx1 - nx, surfaceW); val ay = toNdcY(sy1 - ny, surfaceH)
        val bx = toNdcX(sx2 - nx, surfaceW); val by = toNdcY(sy2 - ny, surfaceH)
        val cx = toNdcX(sx2 + nx, surfaceW); val cy = toNdcY(sy2 + ny, surfaceH)
        val dxN = toNdcX(sx1 + nx, surfaceW); val dyN = toNdcY(sy1 + ny, surfaceH)

        var idx = startIdx
        // Triangle 1: A, B, C
        idx = putVertex(out, idx, ax, ay, r, g, b, a)
        idx = putVertex(out, idx, bx, by, r, g, b, a)
        idx = putVertex(out, idx, cx, cy, r, g, b, a)
        // Triangle 2: A, C, D
        idx = putVertex(out, idx, ax, ay, r, g, b, a)
        idx = putVertex(out, idx, cx, cy, r, g, b, a)
        idx = putVertex(out, idx, dxN, dyN, r, g, b, a)
        return idx
    }

    private fun toNdcX(px: Float, surfaceW: Float): Float = (px / surfaceW) * 2f - 1f

    private fun toNdcY(py: Float, surfaceH: Float): Float = 1f - (py / surfaceH) * 2f

    private fun putVertex(
        out: FloatArray,
        startIdx: Int,
        x: Float,
        y: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float
    ): Int {
        out[startIdx] = x
        out[startIdx + 1] = y
        out[startIdx + 2] = r
        out[startIdx + 3] = g
        out[startIdx + 4] = b
        out[startIdx + 5] = a
        return startIdx + 6
    }
}
