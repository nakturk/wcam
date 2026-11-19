package com.wcam.app.model

import android.content.SharedPreferences

/**
 * Parking guide trapezoid configuration and camera protocol conversion helpers.
 * All coordinates are normalized in [0f..1f] relative to the video viewport.
 */
data class RulerConfig(
    val enabled: Boolean = true,
    val topLeftX: Float = DEFAULT_TOP_LEFT_X,
    val topLeftY: Float = DEFAULT_TOP_LEFT_Y,
    val topRightX: Float = DEFAULT_TOP_RIGHT_X,
    val topRightY: Float = DEFAULT_TOP_RIGHT_Y,
    val bottomLeftX: Float = DEFAULT_BOTTOM_LEFT_X,
    val bottomLeftY: Float = DEFAULT_BOTTOM_LEFT_Y,
    val bottomRightX: Float = DEFAULT_BOTTOM_RIGHT_X,
    val bottomRightY: Float = DEFAULT_BOTTOM_RIGHT_Y
) {

    data class Point2F(val x: Float, val y: Float)

    data class ZoneGeometry(
        val leftFarTop: Point2F,
        val leftGreenYellow: Point2F,
        val leftYellowRed: Point2F,
        val leftNearBottom: Point2F,
        val rightFarTop: Point2F,
        val rightGreenYellow: Point2F,
        val rightYellowRed: Point2F,
        val rightNearBottom: Point2F
    )

    fun clamped(): RulerConfig {
        val tlY = topLeftY.coerceIn(0.15f, 0.85f)
        val trY = topRightY.coerceIn(0.15f, 0.85f)
        val blY = bottomLeftY.coerceIn((tlY + 0.12f).coerceAtMost(0.98f), 0.98f)
        val brY = bottomRightY.coerceIn((trY + 0.12f).coerceAtMost(0.98f), 0.98f)

        val tlX = topLeftX.coerceIn(0.05f, 0.85f)
        val trX = topRightX.coerceIn((tlX + 0.08f).coerceAtMost(0.95f), 0.95f)
        val blX = bottomLeftX.coerceIn(0.02f, 0.85f)
        val brX = bottomRightX.coerceIn((blX + 0.08f).coerceAtMost(0.98f), 0.98f)

        return copy(
            topLeftX = tlX,
            topLeftY = tlY,
            topRightX = trX,
            topRightY = trY,
            bottomLeftX = blX,
            bottomLeftY = blY,
            bottomRightX = brX,
            bottomRightY = brY
        )
    }

    fun computeZoneGeometry(): ZoneGeometry {
        val leftTop = Point2F(topLeftX, topLeftY)
        val leftBottom = Point2F(bottomLeftX, bottomLeftY)
        val rightTop = Point2F(topRightX, topRightY)
        val rightBottom = Point2F(bottomRightX, bottomRightY)

        return ZoneGeometry(
            leftFarTop = leftTop,
            leftGreenYellow = lerp(leftTop, leftBottom, GREEN_END_T),
            leftYellowRed = lerp(leftTop, leftBottom, YELLOW_END_T),
            leftNearBottom = leftBottom,
            rightFarTop = rightTop,
            rightGreenYellow = lerp(rightTop, rightBottom, GREEN_END_T),
            rightYellowRed = lerp(rightTop, rightBottom, YELLOW_END_T),
            rightNearBottom = rightBottom
        )
    }

    /**
     * Converts the trapezoid into the 6-point array expected by XMLinkDevice.setRuler():
     * [bottom-left, mid-left, top-left, top-right, mid-right, bottom-right].
     */
    fun toCameraRulerPoints(): Pair<DoubleArray, DoubleArray> {
        val leftMid = lerp(Point2F(topLeftX, topLeftY), Point2F(bottomLeftX, bottomLeftY), 0.5f)
        val rightMid = lerp(Point2F(topRightX, topRightY), Point2F(bottomRightX, bottomRightY), 0.5f)
        val xs = doubleArrayOf(
            bottomLeftX.toDouble(),
            leftMid.x.toDouble(),
            topLeftX.toDouble(),
            topRightX.toDouble(),
            rightMid.x.toDouble(),
            bottomRightX.toDouble()
        )
        val ys = doubleArrayOf(
            bottomLeftY.toDouble(),
            leftMid.y.toDouble(),
            topLeftY.toDouble(),
            topRightY.toDouble(),
            rightMid.y.toDouble(),
            bottomRightY.toDouble()
        )
        return xs to ys
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putFloat(KEY_TL_X, topLeftX)
            .putFloat(KEY_TL_Y, topLeftY)
            .putFloat(KEY_TR_X, topRightX)
            .putFloat(KEY_TR_Y, topRightY)
            .putFloat(KEY_BL_X, bottomLeftX)
            .putFloat(KEY_BL_Y, bottomLeftY)
            .putFloat(KEY_BR_X, bottomRightX)
            .putFloat(KEY_BR_Y, bottomRightY)
            .apply()
    }

    companion object {
        const val CAMERA_RULER_TYPE = "horizontal_trapezoid"
        const val GREEN_END_T = 0.38f
        const val YELLOW_END_T = 0.70f

        const val DEFAULT_TOP_LEFT_X = 0.34f
        const val DEFAULT_TOP_LEFT_Y = 0.42f
        const val DEFAULT_TOP_RIGHT_X = 0.66f
        const val DEFAULT_TOP_RIGHT_Y = 0.42f
        const val DEFAULT_BOTTOM_LEFT_X = 0.12f
        const val DEFAULT_BOTTOM_LEFT_Y = 0.92f
        const val DEFAULT_BOTTOM_RIGHT_X = 0.88f
        const val DEFAULT_BOTTOM_RIGHT_Y = 0.92f

        private const val KEY_ENABLED = "ruler_enabled"
        private const val KEY_TL_X = "ruler_tl_x"
        private const val KEY_TL_Y = "ruler_tl_y"
        private const val KEY_TR_X = "ruler_tr_x"
        private const val KEY_TR_Y = "ruler_tr_y"
        private const val KEY_BL_X = "ruler_bl_x"
        private const val KEY_BL_Y = "ruler_bl_y"
        private const val KEY_BR_X = "ruler_br_x"
        private const val KEY_BR_Y = "ruler_br_y"

        fun defaultConfig(enabled: Boolean = true): RulerConfig = RulerConfig(enabled = enabled)

        fun load(prefs: SharedPreferences): RulerConfig {
            return RulerConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, true),
                topLeftX = prefs.getFloat(KEY_TL_X, DEFAULT_TOP_LEFT_X),
                topLeftY = prefs.getFloat(KEY_TL_Y, DEFAULT_TOP_LEFT_Y),
                topRightX = prefs.getFloat(KEY_TR_X, DEFAULT_TOP_RIGHT_X),
                topRightY = prefs.getFloat(KEY_TR_Y, DEFAULT_TOP_RIGHT_Y),
                bottomLeftX = prefs.getFloat(KEY_BL_X, DEFAULT_BOTTOM_LEFT_X),
                bottomLeftY = prefs.getFloat(KEY_BL_Y, DEFAULT_BOTTOM_LEFT_Y),
                bottomRightX = prefs.getFloat(KEY_BR_X, DEFAULT_BOTTOM_RIGHT_X),
                bottomRightY = prefs.getFloat(KEY_BR_Y, DEFAULT_BOTTOM_RIGHT_Y)
            ).clamped()
        }

        private fun lerp(a: Point2F, b: Point2F, t: Float): Point2F {
            return Point2F(
                x = a.x + (b.x - a.x) * t,
                y = a.y + (b.y - a.y) * t
            )
        }
    }
}
