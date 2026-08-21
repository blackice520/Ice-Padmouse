package com.joymouse.app.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AppConfig / Action 的纯 JVM 单元测试（无需设备/模拟器） */
class AppConfigTest {

    @Test
    fun jsonRoundTripPreservesAllFields() {
        val cfg = AppConfig(
            cursorSpeed = 12f,
            scrollStep = 320,
            panelOpacity = 0.6f,
            dragMode = true,
            cursorAutoHide = false,
            panelX = 0.33f,
            panelY = 0.66f,
            buttons = mutableListOf(
                MappedButton(42, "攻击", Action.CLICK, 0.8f, 0.7f, 64),
                MappedButton(43, "返回", Action.BACK, 0.2f, 0.9f, 48)
            )
        )
        val round = AppConfig.fromJson(cfg.toJson().toString())

        assertEquals(12f, round.cursorSpeed)
        assertEquals(320, round.scrollStep)
        assertEquals(0.6f, round.panelOpacity)
        assertTrue(round.dragMode)
        assertFalse(round.cursorAutoHide)
        assertEquals(0.33f, round.panelX)
        assertEquals(0.66f, round.panelY)

        assertEquals(2, round.buttons.size)
        assertEquals(42L, round.buttons[0].id)
        assertEquals("攻击", round.buttons[0].label)
        assertEquals(Action.CLICK, round.buttons[0].action)
        assertEquals(0.8f, round.buttons[0].x)
        assertEquals(0.7f, round.buttons[0].y)
        assertEquals(64, round.buttons[0].sizeDp)
        assertEquals(Action.BACK, round.buttons[1].action)
    }

    @Test
    fun fromJsonHandlesNullBlankAndGarbage() {
        assertNotNull(AppConfig.fromJson(null))
        assertNotNull(AppConfig.fromJson(""))
        val garbage = AppConfig.fromJson("{not valid json")
        assertEquals(6f, garbage.cursorSpeed)
        assertEquals(0, garbage.buttons.size)
    }

    @Test
    fun invalidButtonValuesAreClampedOrDefaulted() {
        val json = """{"buttons":[
            {"id":1,"label":"x","action":"click","x":5.0,"y":-2.0,"size":200},
            {"id":2}
        ]}"""
        val cfg = AppConfig.fromJson(json)
        assertEquals(1f, cfg.buttons[0].x)
        assertEquals(0f, cfg.buttons[0].y)
        assertEquals(200, cfg.buttons[0].sizeDp)
        assertEquals("键", cfg.buttons[1].label)
        assertEquals(Action.NOOP, cfg.buttons[1].action)
        assertEquals(52, cfg.buttons[1].sizeDp)
    }

    @Test
    fun actionIdLookupFallsBackToNoop() {
        assertEquals(Action.CLICK, Action.fromId("click"))
        assertEquals(Action.NOOP, Action.fromId("nonexistent"))
        assertEquals(Action.NOOP, Action.fromId(null))
    }

    @Test
    fun actionIdsAreUnique() {
        val ids = Action.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun defaultButtonsAreSane() {
        val defaults = ConfigStore.defaultButtons()
        assertTrue(defaults.size in 1..12)
        defaults.forEach { b ->
            assertTrue(b.x in 0f..1f)
            assertTrue(b.y in 0f..1f)
            assertTrue(b.sizeDp in 36..110)
        }
    }
}
