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
            mouseSpeed = 60,
            sensitivity = 30,
            deadzone = 20,
            scrollSpeed = 80,
            mouseTimeout = 90,
            cursorStyle = "red",
            swapAB = true,
            hapticEnabled = false,
            vibrationIntensity = 128,
            toggleKey = "r3",
            gamepadMap = mutableMapOf("a" to "click", "lb" to "mute"),
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
        assertEquals(60, round.mouseSpeed)
        assertEquals(30, round.sensitivity)
        assertEquals(20, round.deadzone)
        assertEquals(80, round.scrollSpeed)
        assertEquals(90, round.mouseTimeout)
        assertEquals("red", round.cursorStyle)
        assertTrue(round.swapAB)
        assertFalse(round.hapticEnabled)
        assertEquals(128, round.vibrationIntensity)
        assertEquals("r3", round.toggleKey)
        assertEquals("click", round.gamepadMap["a"])
        assertEquals("mute", round.gamepadMap["lb"])

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

    @Test
    fun defaultGamepadMapIsCompleteAndValid() {
        val map = ConfigStore.defaultGamepadMap()
        // 全部键名都可解析为 keyCode
        map.keys.forEach { k -> assertTrue("unknown key $k", ConfigStore.keyCodeOf(k) != -1) }
        // 全部动作 id 有效
        map.values.forEach { v -> assertEquals(v, Action.fromId(v).id) }
        // 关键默认值（对齐参考应用）
        assertEquals("click", map["a"])
        assertEquals("longpress", map["b"])
        assertEquals("vol_up", map["up"])
        assertEquals("mute", map["lb"])
        assertEquals("screenshot", map["rt"])
        // keyNameOf 与 keyCodeOf 互逆
        map.keys.forEach { k -> assertEquals(k, ConfigStore.keyNameOf(ConfigStore.keyCodeOf(k))) }
    }

    @Test
    fun newGamepadFieldsClampedOnLoad() {
        val json = """{"mouseSpeed":500,"sensitivity":0,"deadzone":99,"vibrationIntensity":9999}"""
        val cfg = AppConfig.fromJson(json)
        assertEquals(100, cfg.mouseSpeed)
        assertEquals(1, cfg.sensitivity)
        assertEquals(50, cfg.deadzone)
        assertEquals(255, cfg.vibrationIntensity)
    }
}
