package com.wcam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.wcam.app.model.RulerConfig
import kotlin.math.hypot

/**
 * Lightweight Canvas overlay that draws 3-zone perspective backup parking guide lines
 * (Green / Yellow / Red) and optionally lets the user drag the 4 trapezoid corners
 * in calibration mode.
 */
class ParkingLinesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Corner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    var config: RulerConfig = RulerConfig.defaultConfig()
        set(value) {
            field = value.clamped()
            invalidate()
        }

    var isCalibrationMode: Boolean = false
        set(value) {
            field = value
            activeCorner = null
            invalidate()
        }

    var videoAspectRatio: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onConfigChanged: ((RulerConfig) -> Unit)? = null
    var onCalibrationCommit: ((RulerConfig) -> Unit)? = null

    private var activeCorner: Corner? = null
    private val density = resources.displayMetrics.density
    private val videoRect = android.graphics.RectF()

    private fun updateVideoRect(viewW: Float, viewH: Float) {
        if (videoAspectRatio <= 0f || viewW <= 0f || viewH <= 0f) {
            videoRect.set(0f, 0f, viewW, viewH)
            return
        }
        val viewAspect = viewW / viewH
        if (videoAspectRatio > viewAspect) {
            val scaledH = viewW / videoAspectRatio
            val top = (viewH - scaledH) / 2f
            videoRect.set(0f, top, viewW, top + scaledH)
        } else {
            val scaledW = viewH * videoAspectRatio
            val left = (viewW - scaledW) / 2f
            videoRect.set(left, 0f, left + scaledW, viewH)
        }
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(170, 0, 0, 0)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 255, 255, 255)
    }

    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(0, 180, 255)
        strokeWidth = 2.5f * density
    }

    private val handleActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0, 180, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!config.enabled && !isCalibrationMode) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        updateVideoRect(w, h)
        val rw = videoRect.width()
        val rh = videoRect.height()

        val strokeScale = (minOf(rw, rh) / (360f * density)).coerceIn(0.45f, 1.3f)
        val mainWidth = 4.0f * density * strokeScale
        val barWidth = 2.6f * density * strokeScale
        val shadowExtra = 2.2f * density * strokeScale

        shadowPaint.strokeWidth = mainWidth + shadowExtra
        linePaint.strokeWidth = mainWidth
        dashedPaint.strokeWidth = barWidth
        dashedPaint.pathEffect = DashPathEffect(
            floatArrayOf(10f * density * strokeScale, 7f * density * strokeScale),
            0f
        )

        val zone = config.computeZoneGeometry()

        fun px(p: RulerConfig.Point2F) = videoRect.left + p.x * rw
        fun py(p: RulerConfig.Point2F) = videoRect.top + p.y * rh

        // Draw far green segment
        drawSegment(
            canvas,
            px(zone.leftFarTop), py(zone.leftFarTop),
            px(zone.leftGreenYellow), py(zone.leftGreenYellow),
            COLOR_GREEN
        )
        drawSegment(
            canvas,
            px(zone.rightFarTop), py(zone.rightFarTop),
            px(zone.rightGreenYellow), py(zone.rightGreenYellow),
            COLOR_GREEN
        )
        drawCrossbar(
            canvas,
            px(zone.leftFarTop), py(zone.leftFarTop),
            px(zone.rightFarTop), py(zone.rightFarTop),
            COLOR_GREEN,
            dashed = true,
            shadowExtra = shadowExtra
        )

        // Draw middle yellow segment
        drawSegment(
            canvas,
            px(zone.leftGreenYellow), py(zone.leftGreenYellow),
            px(zone.leftYellowRed), py(zone.leftYellowRed),
            COLOR_YELLOW
        )
        drawSegment(
            canvas,
            px(zone.rightGreenYellow), py(zone.rightGreenYellow),
            px(zone.rightYellowRed), py(zone.rightYellowRed),
            COLOR_YELLOW
        )
        drawCrossbar(
            canvas,
            px(zone.leftGreenYellow), py(zone.leftGreenYellow),
            px(zone.rightGreenYellow), py(zone.rightGreenYellow),
            COLOR_YELLOW,
            dashed = true,
            shadowExtra = shadowExtra
        )

        // Draw near red segment
        drawSegment(
            canvas,
            px(zone.leftYellowRed), py(zone.leftYellowRed),
            px(zone.leftNearBottom), py(zone.leftNearBottom),
            COLOR_RED
        )
        drawSegment(
            canvas,
            px(zone.rightYellowRed), py(zone.rightYellowRed),
            px(zone.rightNearBottom), py(zone.rightNearBottom),
            COLOR_RED
        )
        drawCrossbar(
            canvas,
            px(zone.leftYellowRed), py(zone.leftYellowRed),
            px(zone.rightYellowRed), py(zone.rightYellowRed),
            COLOR_RED,
            dashed = false,
            shadowExtra = shadowExtra
        )

        // Draw bumper reference ticks on bottom
        drawBumperTicks(
            canvas,
            px(zone.leftNearBottom), py(zone.leftNearBottom),
            px(zone.rightNearBottom), py(zone.rightNearBottom),
            12f * density * strokeScale
        )

        if (isCalibrationMode) {
            val radius = 14f * density
            drawCornerHandle(canvas, videoRect.left + config.topLeftX * rw, videoRect.top + config.topLeftY * rh, radius, activeCorner == Corner.TOP_LEFT)
            drawCornerHandle(canvas, videoRect.left + config.topRightX * rw, videoRect.top + config.topRightY * rh, radius, activeCorner == Corner.TOP_RIGHT)
            drawCornerHandle(canvas, videoRect.left + config.bottomLeftX * rw, videoRect.top + config.bottomLeftY * rh, radius, activeCorner == Corner.BOTTOM_LEFT)
            drawCornerHandle(canvas, videoRect.left + config.bottomRightX * rw, videoRect.top + config.bottomRightY * rh, radius, activeCorner == Corner.BOTTOM_RIGHT)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isCalibrationMode || width <= 0 || height <= 0) {
            return false
        }
        val w = width.toFloat()
        val h = height.toFloat()
        updateVideoRect(w, h)
        val rw = videoRect.width()
        val rh = videoRect.height()
        val x = event.x
        val y = event.y
        val hitRadius = 36f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val corners = listOf(
                    Corner.TOP_LEFT to (videoRect.left + config.topLeftX * rw to videoRect.top + config.topLeftY * rh),
                    Corner.TOP_RIGHT to (videoRect.left + config.topRightX * rw to videoRect.top + config.topRightY * rh),
                    Corner.BOTTOM_LEFT to (videoRect.left + config.bottomLeftX * rw to videoRect.top + config.bottomLeftY * rh),
                    Corner.BOTTOM_RIGHT to (videoRect.left + config.bottomRightX * rw to videoRect.top + config.bottomRightY * rh)
                )
                val nearest = corners.minByOrNull { (_, pt) -> hypot(x - pt.first, y - pt.second) }
                if (nearest != null && hypot(x - nearest.second.first, y - nearest.second.second) <= hitRadius) {
                    activeCorner = nearest.first
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val corner = activeCorner ?: return false
                val nx = ((x - videoRect.left) / rw).coerceIn(0.02f, 0.98f)
                val ny = ((y - videoRect.top) / rh).coerceIn(0.15f, 0.98f)

                // Mirror left/right edits symmetrically around center (0.5) for clean alignment
                val updated = when (corner) {
                    Corner.TOP_LEFT -> {
                        val mirroredRight = (1f - nx).coerceIn(0.54f, 0.95f)
                        config.copy(
                            topLeftX = nx.coerceAtMost(0.46f),
                            topLeftY = ny,
                            topRightX = mirroredRight,
                            topRightY = ny
                        )
                    }
                    Corner.TOP_RIGHT -> {
                        val mirroredLeft = (1f - nx).coerceIn(0.05f, 0.46f)
                        config.copy(
                            topRightX = nx.coerceAtLeast(0.54f),
                            topRightY = ny,
                            topLeftX = mirroredLeft,
                            topLeftY = ny
                        )
                    }
                    Corner.BOTTOM_LEFT -> {
                        val mirroredRight = (1f - nx).coerceIn(0.54f, 0.98f)
                        config.copy(
                            bottomLeftX = nx.coerceAtMost(0.46f),
                            bottomLeftY = ny,
                            bottomRightX = mirroredRight,
                            bottomRightY = ny
                        )
                    }
                    Corner.BOTTOM_RIGHT -> {
                        val mirroredLeft = (1f - nx).coerceIn(0.02f, 0.46f)
                        config.copy(
                            bottomRightX = nx.coerceAtLeast(0.54f),
                            bottomRightY = ny,
                            bottomLeftX = mirroredLeft,
                            bottomLeftY = ny
                        )
                    }
                }.clamped()

                config = updated
                onConfigChanged?.invoke(updated)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeCorner != null) {
                    activeCorner = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    onCalibrationCommit?.invoke(config)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun drawSegment(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        canvas.drawLine(x1, y1, x2, y2, shadowPaint)
        linePaint.color = color
        canvas.drawLine(x1, y1, x2, y2, linePaint)
    }

    private fun drawCrossbar(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Int,
        dashed: Boolean,
        shadowExtra: Float
    ) {
        val gap = (x2 - x1) * 0.12f
        val leftInnerX = x1 + gap
        val rightInnerX = x2 - gap
        val leftEndX = x1 + (x2 - x1) * 0.34f
        val rightStartX = x2 - (x2 - x1) * 0.34f
        val paint = if (dashed) dashedPaint else linePaint
        paint.color = color

        val oldShadow = shadowPaint.strokeWidth
        shadowPaint.strokeWidth = paint.strokeWidth + shadowExtra

        if (dashed) {
            canvas.drawLine(x1, y1, leftEndX, y1, shadowPaint)
            canvas.drawLine(x1, y1, leftEndX, y1, paint)
            canvas.drawLine(rightStartX, y2, x2, y2, shadowPaint)
            canvas.drawLine(rightStartX, y2, x2, y2, paint)
        } else {
            canvas.drawLine(leftInnerX, y1, rightInnerX, y2, shadowPaint)
            canvas.drawLine(leftInnerX, y1, rightInnerX, y2, paint)
        }
        shadowPaint.strokeWidth = oldShadow
    }

    private fun drawBumperTicks(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, tickLen: Float) {
        linePaint.color = COLOR_RED
        canvas.drawLine(x1, y1, x1 + tickLen, y1, shadowPaint)
        canvas.drawLine(x1, y1, x1 + tickLen, y1, linePaint)
        canvas.drawLine(x2 - tickLen, y2, x2, y2, shadowPaint)
        canvas.drawLine(x2 - tickLen, y2, x2, y2, linePaint)
    }

    private fun drawCornerHandle(canvas: Canvas, cx: Float, cy: Float, radius: Float, active: Boolean) {
        canvas.drawCircle(cx, cy, radius, if (active) handleActivePaint else handleFillPaint)
        canvas.drawCircle(cx, cy, radius, handleStrokePaint)
    }

    companion object {
        private val COLOR_GREEN = Color.argb(235, 46, 213, 115)
        private val COLOR_YELLOW = Color.argb(235, 255, 195, 18)
        private val COLOR_RED = Color.argb(240, 255, 71, 87)
    }
}
