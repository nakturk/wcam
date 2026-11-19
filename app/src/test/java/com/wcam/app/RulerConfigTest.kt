package com.wcam.app

import android.content.SharedPreferences
import com.wcam.app.model.RulerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulerConfigTest {

    @Test
    fun defaultConfig_hasValidClampedCoordinates() {
        val cfg = RulerConfig.defaultConfig()
        assertTrue(cfg.enabled)
        assertEquals(cfg, cfg.clamped())
        assertTrue(cfg.topLeftX < cfg.topRightX)
        assertTrue(cfg.bottomLeftX < cfg.bottomRightX)
        assertTrue(cfg.topLeftY < cfg.bottomLeftY)
    }

    @Test
    fun clamped_enforcesBoundsAndMinimumSpacing() {
        val invalid = RulerConfig(
            enabled = true,
            topLeftX = 0.90f,
            topLeftY = 0.95f,
            topRightX = 0.10f,
            topRightY = 0.01f,
            bottomLeftX = 0.90f,
            bottomLeftY = 0.20f,
            bottomRightX = 0.10f,
            bottomRightY = 0.10f
        )
        val clamped = invalid.clamped()
        assertTrue(clamped.topLeftX in 0.05f..0.85f)
        assertTrue(clamped.topRightX >= clamped.topLeftX + 0.08f - 1e-4f)
        assertTrue(clamped.bottomRightX >= clamped.bottomLeftX + 0.08f - 1e-4f)
        assertTrue(clamped.bottomLeftY >= clamped.topLeftY + 0.12f - 1e-4f)
        assertTrue(clamped.bottomRightY >= clamped.topRightY + 0.12f - 1e-4f)
    }

    @Test
    fun computeZoneGeometry_interpolatesGreenYellowRedProgressively() {
        val cfg = RulerConfig.defaultConfig()
        val geom = cfg.computeZoneGeometry()

        assertEquals(cfg.topLeftY, geom.leftFarTop.y, 1e-5f)
        assertEquals(cfg.bottomLeftY, geom.leftNearBottom.y, 1e-5f)
        assertTrue(geom.leftFarTop.y < geom.leftGreenYellow.y)
        assertTrue(geom.leftGreenYellow.y < geom.leftYellowRed.y)
        assertTrue(geom.leftYellowRed.y < geom.leftNearBottom.y)

        assertTrue(geom.leftFarTop.x > geom.leftGreenYellow.x)
        assertTrue(geom.leftGreenYellow.x > geom.leftYellowRed.x)
        assertTrue(geom.leftYellowRed.x > geom.leftNearBottom.x)
    }

    @Test
    fun toCameraRulerPoints_returnsSixOrderedPoints() {
        val cfg = RulerConfig.defaultConfig()
        val (xs, ys) = cfg.toCameraRulerPoints()

        assertEquals(6, xs.size)
        assertEquals(6, ys.size)
        assertEquals(cfg.bottomLeftX.toDouble(), xs[0], 1e-5)
        assertEquals(cfg.topLeftX.toDouble(), xs[2], 1e-5)
        assertEquals(cfg.topRightX.toDouble(), xs[3], 1e-5)
        assertEquals(cfg.bottomRightX.toDouble(), xs[5], 1e-5)
        assertEquals((cfg.topLeftX + cfg.bottomLeftX) / 2.0, xs[1], 1e-5)
        assertEquals((cfg.topRightY + cfg.bottomRightY) / 2.0, ys[4], 1e-5)
    }

    @Test
    fun saveAndLoad_persistsAllFieldsInSharedPreferences() {
        val prefs = InMemorySharedPreferences()
        val custom = RulerConfig(
            enabled = false,
            topLeftX = 0.30f,
            topLeftY = 0.45f,
            topRightX = 0.70f,
            topRightY = 0.45f,
            bottomLeftX = 0.15f,
            bottomLeftY = 0.90f,
            bottomRightX = 0.85f,
            bottomRightY = 0.90f
        )

        custom.save(prefs)
        val loaded = RulerConfig.load(prefs)

        assertFalse(loaded.enabled)
        assertEquals(custom.topLeftX, loaded.topLeftX, 1e-5f)
        assertEquals(custom.topLeftY, loaded.topLeftY, 1e-5f)
        assertEquals(custom.topRightX, loaded.topRightX, 1e-5f)
        assertEquals(custom.bottomLeftX, loaded.bottomLeftX, 1e-5f)
        assertEquals(custom.bottomRightY, loaded.bottomRightY, 1e-5f)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) map.clear()
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}
