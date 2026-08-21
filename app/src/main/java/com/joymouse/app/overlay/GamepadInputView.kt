package com.joymouse.app.overlay

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * 手柄输入捕获视图：以 1×1 可聚焦的无障碍悬浮窗挂在屏幕左上角 (0,0)。
 * 当鼠标激活时该窗口持有输入焦点，蓝牙/USB 手柄的按键与摇杆轴事件
 * 都会被路由到这里（免 root 捕获模拟摇杆的唯一途径，参考 gamepad mouse 方案）。
 *
 * 关键：本视图绝不自行派发任何手势/点击——唤出光标不会产生任何误点击。
 */
class GamepadInputView(context: Context, private val controller: OverlayController) : View(context) {

    /** 手柄/键盘/方向键/摇杆来源位掩码（与参考应用一致） */
    private fun isGamepadSource(event: MotionEvent): Boolean {
        val src = event.source
        return (src and 16777232) == 16777232 || (src and 1025) == 1025
    }

    private fun isGamepadSource(event: KeyEvent): Boolean {
        val src = event.source
        return (src and 16777232) == 16777232 || (src and 1025) == 1025
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_HOVER_MOVE) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isGamepadSource(event)) return super.onGenericMotionEvent(event)
        val consumed = controller.onGamepadMotion(event)
        return consumed || super.onGenericMotionEvent(event)
    }

    /** 按键主通道（焦点窗路径，本设备实测可靠）：
     *  映射到的键消费；未映射/未激活时的非唤出键返回 false，事件回落给应用。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepadSource(event) && controller.onGamepadKey(keyCode, true, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isGamepadSource(event) && controller.onGamepadKey(keyCode, false, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    /**
     * 触摸到屏幕其他位置（FLAG_WATCH_OUTSIDE_TOUCH）：
     * 上报给控制器——手指控制时自动收起鼠标（用户要求；也避免注入手势与真实触摸互相打断）。
     * 本视图不消费任何触摸，应用始终能正常收到触摸。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            controller.onOutsideTouch(event.rawX, event.rawY)
        }
        return false
    }

    // 按键统一由无障碍服务的全局按键通道处理（onKeyEvent），本视图只负责摇杆轴
}
