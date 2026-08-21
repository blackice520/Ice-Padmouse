package com.joymouse.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.joymouse.app.config.Action
import com.joymouse.app.config.ConfigStore
import com.joymouse.app.overlay.OverlayController

/**
 * 无障碍手势服务：通过 dispatchGesture 在系统层面模拟触摸手势。
 * 这是免 root 模拟鼠标点击/拖拽的标准途径（Android 16 仍然支持）。
 */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 无障碍服务启动后自动拉起悬浮控制台（需已授予悬浮窗权限）
        if (OverlayController.instance == null && Settings.canDrawOverlays(this)) {
            OverlayController(this).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不需要读取窗口内容 */ }

    override fun onInterrupt() { /* 忽略 */ }

    /**
     * 全局按键（canRequestFilterKeyEvents）：
     * - 鼠标未激活时：仅响应唤出键（默认 L3），其余放行给应用
     * - 鼠标激活时：按键已由焦点捕获视图接收，这里不再重复处理
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val c = OverlayController.instance
        if (c == null) return super.onKeyEvent(event)
        val src = event.source
        val isGamepad = (src and 1025) == 1025 || (src and 16777232) == 16777232
        if (!isGamepad) return super.onKeyEvent(event)
        if (c.mouseActive) return super.onKeyEvent(event)
        // 未激活：仅响应用户配置的唤出键（按下瞬间）
        val name = ConfigStore.keyNameOf(event.keyCode) ?: return super.onKeyEvent(event)
        if (name == ConfigStore.load(this).toggleKey &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            c.toggleMouse()
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        OverlayController.instance?.onConfigurationChanged()
    }

    override fun onDestroy() {
        OverlayController.instance?.hide()
        instance = null
        super.onDestroy()
    }

    // ---------------- 基础手势 ----------------

    private fun pointPath(x: Float, y: Float): Path =
        Path().apply { moveTo(x, y) }

    private fun linePath(x1: Float, y1: Float, x2: Float, y2: Float): Path =
        Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

    private fun dispatch(builder: GestureDescription.Builder, onResult: ((Boolean) -> Unit)? = null) {
        try {
            dispatchGesture(
                builder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { onResult?.invoke(true) }
                    override fun onCancelled(g: GestureDescription?) { onResult?.invoke(false) }
                },
                handler
            )
        } catch (t: Throwable) {
            onResult?.invoke(false)
        }
    }

    /** 单击（默认 80ms 点击） */
    fun tap(x: Float, y: Float, durationMs: Long = 80) {
        dispatch(GestureDescription.Builder().addStroke(StrokeDescription(pointPath(x, y), 0, durationMs)))
    }

    /** 双击 */
    fun doubleTap(x: Float, y: Float) {
        tap(x, y, 70)
        handler.postDelayed({ tap(x, y, 70) }, 130)
    }

    /** 长按（作为右键的模拟） */
    fun longPress(x: Float, y: Float, durationMs: Long = 600) {
        dispatch(GestureDescription.Builder().addStroke(StrokeDescription(pointPath(x, y), 0, durationMs)))
    }

    /** 单次滑动（down->move->up 一个手势完成）。端点限制在屏幕内（Path 不允许负坐标） */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 280) {
        val fx1 = x1.coerceAtLeast(0f)
        val fy1 = y1.coerceAtLeast(0f)
        val fx2 = x2.coerceAtLeast(0f)
        val fy2 = y2.coerceAtLeast(0f)
        dispatch(GestureDescription.Builder().addStroke(StrokeDescription(linePath(fx1, fy1, fx2, fy2), 0, durationMs)))
    }

    /** 向下滚动 = 手指向上滑 */
    fun scrollAt(x: Float, y: Float, distancePx: Int, up: Boolean) {
        val d = distancePx.toFloat()
        val sx = x.coerceAtLeast(0f)
        val sy = y.coerceAtLeast(0f)
        if (up) swipe(sx, sy, sx, sy - d, 260)
        else swipe(sx, sy, sx, sy + d, 260)
    }

    // ---------------- 连续拖拽（按住-移动-松开） ----------------
    // 实现原理（已核对 Android 16 AOSP 源码）：
    //   StrokeDescription(..., willContinue=true) 的笔划结束时手指保持按下；
    //   用 prev.continueStroke(path, ...) 生成续接笔划加入下一个手势，
    //   最后一个 willContinue=false 的笔划结束时手指抬起。

    private var chainStroke: StrokeDescription? = null
    private var chainX = 0f
    private var chainY = 0f
    private var chainActive = false
    private var chainBroken = false

    /** 按下：在 (x,y) 落下手指并保持 */
    fun dragStart(x: Float, y: Float) {
        if (chainActive) dragEnd(x, y)
        val s = StrokeDescription(pointPath(x, y), 0, 50, true)
        chainStroke = s
        chainX = x
        chainY = y
        chainActive = true
        chainBroken = false
        dispatch(GestureDescription.Builder().addStroke(s)) { ok -> if (!ok) chainBroken = true }
    }

    /** 移动：续接笔划把手指从旧位置拖到新位置 */
    fun dragMove(x: Float, y: Float) {
        if (!chainActive) return
        if (x == chainX && y == chainY) return
        val prev = chainStroke ?: return
        val s = prev.continueStroke(linePath(chainX, chainY, x, y), 0, 60, true)
        chainStroke = s
        chainX = x
        chainY = y
        dispatch(GestureDescription.Builder().addStroke(s)) { ok -> if (!ok) chainBroken = true }
    }

    /** 松开：终笔划结束，手指抬起 */
    fun dragEnd(x: Float, y: Float) {
        if (!chainActive) return
        val prev = chainStroke
        val wasBroken = chainBroken
        // 捕获本次拖拽的起点/终点，避免回调时字段已被新的拖拽改写
        val fromX = chainX
        val fromY = chainY
        val toX = x
        val toY = y
        chainActive = false
        chainStroke = null
        chainBroken = false
        if (prev == null) return
        val s = prev.continueStroke(linePath(fromX, fromY, toX, toY), 0, 120, false)
        dispatch(GestureDescription.Builder().addStroke(s)) { ok ->
            // 链断了：回退为一次完整拖拽手势，保证“手指”一定抬起
            if (!ok && !wasBroken && !chainActive) {
                swipe(fromX, fromY, toX, toY, 200)
            }
        }
    }

    /** 是否正在拖拽（供 UI 显示状态） */
    fun isDragging(): Boolean = chainActive

    // ---------------- 全局系统动作 ----------------

    fun performGlobal(action: Action) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (action) {
            Action.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Action.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Action.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            Action.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            Action.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            Action.SCREENSHOT ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                }
            Action.VOLUME_UP ->
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            Action.VOLUME_DOWN ->
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            else -> { /* 非全局动作 */ }
        }
    }
}
