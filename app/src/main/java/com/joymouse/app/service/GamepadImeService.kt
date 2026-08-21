package com.joymouse.app.service

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import com.joymouse.app.config.Action
import com.joymouse.app.overlay.OverlayController

/**
 * 物理手柄输入法（免 root 手柄映射通道）。
 *
 * 原理：系统会把硬件按键事件优先路由给当前激活的输入法（IME）。
 * 在系统设置中启用并切换到本输入法后，蓝牙/USB 手柄的按键与方向键
 * 会先到达这里，再映射为鼠标动作（经由无障碍手势注入）。
 *
 * 已知限制：手柄摇杆的轴运动（MotionEvent）不会进入输入法通道，
 * 因此模拟摇杆/移动光标请使用悬浮控制台或手柄方向键。
 */
class GamepadImeService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        // 无界面输入法：onCreateInputView 返回 null + onEvaluateInputViewShown=false，
        // 系统不会显示软键盘
    }

    override fun onCreateInputView(): View? = null

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return false
    }

    /** 按键 → 动作 默认映射（后续版本支持自定义） */
    private val buttonMap: Map<Int, Action> = mapOf(
        KeyEvent.KEYCODE_BUTTON_A to Action.CLICK,
        KeyEvent.KEYCODE_BUTTON_B to Action.LONG_PRESS,
        KeyEvent.KEYCODE_BUTTON_X to Action.DOUBLE_CLICK,
        KeyEvent.KEYCODE_BUTTON_Y to Action.SCROLL_UP,
        KeyEvent.KEYCODE_BUTTON_L1 to Action.SCROLL_DOWN,
        KeyEvent.KEYCODE_BUTTON_R1 to Action.VOLUME_UP,
        KeyEvent.KEYCODE_BUTTON_L2 to Action.SWIPE_LEFT,
        KeyEvent.KEYCODE_BUTTON_R2 to Action.SWIPE_RIGHT,
        KeyEvent.KEYCODE_BUTTON_SELECT to Action.HOME,
        KeyEvent.KEYCODE_BUTTON_START to Action.RECENTS,
        KeyEvent.KEYCODE_BUTTON_MODE to Action.TOGGLE_PANEL,
        KeyEvent.KEYCODE_DPAD_CENTER to Action.CLICK,
    )

    /** 方向键每次按下的光标位移（dp） */
    private val dpadStepDp = 42f

    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val controller = OverlayController.instance ?: return false
        if (GestureAccessibilityService.instance == null) return false

        // 方向键：按住连续移动光标（repeatCount >= 0 都响应）
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                controller.moveCursorBy(0f, -controller.dp(dpadStepDp).toFloat()); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                controller.moveCursorBy(0f, controller.dp(dpadStepDp).toFloat()); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                controller.moveCursorBy(-controller.dp(dpadStepDp).toFloat(), 0f); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                controller.moveCursorBy(controller.dp(dpadStepDp).toFloat(), 0f); return true
            }
        }

        // 普通按键：只在按下瞬间触发一次（忽略长按重复）
        val action = buttonMap[keyCode] ?: return false
        if (event.repeatCount > 0) return true
        controller.execute(action)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKey(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // 按下已被消费的键，抬起也一并消费，避免应用收到半截事件
        if (buttonMap.containsKey(keyCode) || keyCode in dpadKeys) return true
        return super.onKeyUp(keyCode, event)
    }

    private val dpadKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER
    )
}
