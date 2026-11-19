package com.wcam.app

import com.wcam.app.gl.GlParkingLinesGeometry
import com.wcam.app.model.RulerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlParkingLinesGeometryTest {

    @Test
    fun disabledOrInvalidDimensions_returnsZeroVertices() {
        val out = FloatArray(GlParkingLinesGeometry.MAX_FLOATS)
        val disabled = RulerConfig.defaultConfig(enabled = false)

        assertEquals(
            0,
            GlParkingLinesGeometry.buildVertices(disabled, 1280, 720, 1f, 1f, out)
        )
        assertEquals(
            0,
            GlParkingLinesGeometry.buildVertices(RulerConfig.defaultConfig(), 0, 720, 1f, 1f, out)
        )
        assertEquals(
            0,
            GlParkingLinesGeometry.buildVertices(RulerConfig.defaultConfig(), 1280, 0, 1f, 1f, out)
        )
    }

    @Test
    fun enabledDefaultConfig_producesExpectedShadowAndZoneVerticesInNdcBounds() {
        val out = FloatArray(GlParkingLinesGeometry.MAX_FLOATS)
        val count = GlParkingLinesGeometry.buildVertices(
            config = RulerConfig.defaultConfig(),
            surfaceWidth = 1280,
            surfaceHeight = 720,
            videoScaleX = 1f,
            videoScaleY = 1f,
            out = out
        )

        assertEquals(GlParkingLinesGeometry.MAX_VERTICES, count)

        val floatsPerVertex = GlParkingLinesGeometry.FLOATS_PER_VERTEX
        for (i in 0 until count) {
            val base = i * floatsPerVertex
            val x = out[base]
            val y = out[base + 1]
            assertTrue("x out of bounds at vertex $i: $x", x.isFinite() && x in -1.05f..1.05f)
            assertTrue("y out of bounds at vertex $i: $y", y.isFinite() && y in -1.05f..1.05f)
        }

        // First half is shadow pass (RGB = 0,0,0)
        val half = count / 2
        for (i in 0 until half) {
            val base = i * floatsPerVertex
            assertEquals(0f, out[base + 2], 1e-5f)
            assertEquals(0f, out[base + 3], 1e-5f)
            assertEquals(0f, out[base + 4], 1e-5f)
        }

        // Second half splits cleanly into 4 green, 4 yellow, 5 red segments (6 vertices each)
        val firstGreenBase = half * floatsPerVertex
        assertTrue(out[firstGreenBase + 3] > out[firstGreenBase + 2]) // G > R for green

        val firstYellowBase = (half + 4 * GlParkingLinesGeometry.VERTICES_PER_SEGMENT) * floatsPerVertex
        assertTrue(out[firstYellowBase + 2] > 0.9f && out[firstYellowBase + 3] > 0.7f) // yellow

        val firstRedBase = (half + 8 * GlParkingLinesGeometry.VERTICES_PER_SEGMENT) * floatsPerVertex
        assertTrue(out[firstRedBase + 2] > 0.9f && out[firstRedBase + 3] < 0.4f) // red
    }

    @Test
    fun aspectRatioFitScaling_keepsParkingLinesWithinVideoQuadBounds() {
        val out = FloatArray(GlParkingLinesGeometry.MAX_FLOATS)
        val count = GlParkingLinesGeometry.buildVertices(
            config = RulerConfig.defaultConfig(),
            surfaceWidth = 1600,
            surfaceHeight = 600,
            videoScaleX = 0.75f,
            videoScaleY = 1.0f,
            out = out
        )

        assertEquals(GlParkingLinesGeometry.MAX_VERTICES, count)
        for (i in 0 until count) {
            val x = out[i * GlParkingLinesGeometry.FLOATS_PER_VERTEX]
            assertTrue("x=$x exceeded fitted video quad bounds [-0.80, 0.80]", x in -0.80f..0.80f)
        }
    }
}
