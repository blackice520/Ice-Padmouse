package com.joymouse.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.joymouse.app.AppLog
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

    override fun onCreate() {
        super.onCreate()
        AppLog.writeSync(this, "events.log",
            "${System.currentTimeMillis()} [service] onCreate pid=${android.os.Process.myPid()}\n")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.write(this, "events.log", "${System.currentTimeMillis()} [service] onServiceConnected")
        // filterKeyEvents 改为运行时启用（对齐参考应用 Gamepad Mouse 的 XML：不静态声明
        // accessibilityFlags=flagRequestFilterKeyEvents）。静态全局按键拦截标记会被
        // MagicOS 视为高危服务，与"注入时高频焦点振荡"叠加触发 AppEyeFocusWindow 强停。
        // flags=98 完全对齐参考应用（同设备从未被 ZRHungService 强停）：
        //   FLAG_INCLUDE_NOT_IMPORTANT_VIEWS(0x2) | FLAG_REQUEST_FILTER_KEY_EVENTS(0x20)
        //   | FLAG_REQUEST_TOUCH_EXPLORATION_MODE(0x40)
        // 0x40 让本服务进入"触摸探索类（读屏类）"服务分类——荣耀探针对该分类有豁免。
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = 98
            setServiceInfo(info)
            AppLog.write(this, "events.log",
                "${System.currentTimeMillis()} [service] setServiceInfo flags=0x${info.flags.toString(16)}")
        } catch (t: Throwable) {
            AppLog.write(this, "events.log",
                "${System.currentTimeMillis()} [service] setServiceInfo failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        AppLog.write(this, "keys.log", "${System.currentTimeMillis()} service connected")
        // 无障碍服务启动后自动拉起悬浮控制台（无障碍窗口类型，无需悬浮窗权限）
        if (OverlayController.instance == null) {
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
        logKeyEvent(event)
        val c = OverlayController.instance
        if (c == null) return super.onKeyEvent(event)
        val src = event.source
        val isGamepad = (src and 1025) == 1025 || (src and 16777232) == 16777232 ||
            (src and android.view.InputDevice.SOURCE_DPAD) == android.view.InputDevice.SOURCE_DPAD
        if (!isGamepad) return super.onKeyEvent(event)
        val name = ConfigStore.keyNameOf(event.keyCode) ?: return super.onKeyEvent(event)
        // 游戏模式：所有手柄按键直接交给控制器做点位映射，与鼠标激活状态无关、绝不抢焦点
        if (c.gameMode()) {
            return c.onGamepadKey(event.keyCode, event.action != KeyEvent.ACTION_UP, event)
        }
        if (!c.mouseActive) {
            // 未激活：响应用户配置的唤出键（L3 等），以及任何映射为"唤出/隐藏光标"的键（如 X）
            val cfg = ConfigStore.load(this)
            val isToggle = name == cfg.toggleKey || cfg.gamepadMap[name] == Action.TOGGLE_MOUSE.id
            if (isToggle && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                c.toggleMouse()
                return true
            }
            return super.onKeyEvent(event)
        }
        // 激活：统一由服务处理全部手柄按键（不依赖焦点窗焦点状态），
        // 映射到的键消费掉（返回 true），未映射的放行给应用
        return c.onGamepadKey(event.keyCode, event.action != KeyEvent.ACTION_UP, event)
    }

    /** 按键事件落盘（诊断按键是否到达服务；后台线程写入，不拖慢主线程） */
    private fun logKeyEvent(event: KeyEvent) {
        try {
            val line = "${System.currentTimeMillis()} code=${event.keyCode} " +
                "name=${ConfigStore.keyNameOf(event.keyCode) ?: "?"} " +
                "src=${event.source} act=${event.action}"
            AppLog.write(this, "keys.log", line)
        } catch (_: Throwable) {
        }
    }

    /** 手势注入/系统动作落盘：用于诊断看门狗强杀与注入行为的时序关联 */
    private fun logGesture(tag: String, detail: String) {
        try {
            val line = "${System.currentTimeMillis()} [$tag] $detail"
            AppLog.write(this, "gestures.log", line)
        } catch (_: Throwable) {
        }
    }

    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        OverlayController.instance?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.writeSync(this, "events.log",
            "${System.currentTimeMillis()} [service] onUnbind intent=${intent?.action}\n")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AppLog.writeSync(this, "events.log",
            "${System.currentTimeMillis()} [service] onDestroy\n")
        OverlayController.instance?.hide()
        instance = null
        super.onDestroy()
    }

    // ---------------- 基础手势 ----------------

    private fun pointPath(x: Float, y: Float): Path =
        Path().apply { moveTo(x, y) }

    private fun linePath(x1: Float, y1: Float, x2: Float, y2: Float): Path =
        Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

    private fun dispatch(builder: GestureDescription.Builder, onResult: ((Boolean) -> Unit)? = null): Boolean {
        logGesture("dispatch", "send")
        return try {
            dispatchGesture(
                builder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        logGesture("dispatch", "completed")
                        onResult?.invoke(true)
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        logGesture("dispatch", "cancelled")
                        onResult?.invoke(false)
                    }
                },
                handler
            )
        } catch (t: Throwable) {
            logGesture("dispatch", "exception=${t.javaClass.simpleName}")
            onResult?.invoke(false)
            false
        }
    }

    /** 单击（默认 80ms 点击），返回是否成功派发 */
    fun tap(x: Float, y: Float, durationMs: Long = 80, onResult: ((Boolean) -> Unit)? = null): Boolean =
        dispatch(GestureDescription.Builder().addStroke(StrokeDescription(pointPath(x, y), 0, durationMs)), onResult)

    /** 双击（两次点击，结果取第二次） */
    fun doubleTap(x: Float, y: Float, onResult: ((Boolean) -> Unit)? = null) {
        tap(x, y, 70) { ok1 ->
            if (ok1) {
                tap(x, y, 70) { ok2 -> onResult?.invoke(ok2) }
            } else {
                onResult?.invoke(false)
            }
        }
    }

    /** 长按（作为右键的模拟） */
    fun longPress(x: Float, y: Float, durationMs: Long = 600, onResult: ((Boolean) -> Unit)? = null): Boolean =
        dispatch(GestureDescription.Builder().addStroke(StrokeDescription(pointPath(x, y), 0, durationMs)), onResult)

    /** 单次滑动（down->move->up 一个手势完成）。端点限制在屏幕内（Path 不允许负坐标） */
    fun swipe(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 280,
        onResult: ((Boolean) -> Unit)? = null
    ): Boolean {
        val fx1 = x1.coerceAtLeast(0f)
        val fy1 = y1.coerceAtLeast(0f)
        val fx2 = x2.coerceAtLeast(0f)
        val fy2 = y2.coerceAtLeast(0f)
        return dispatch(GestureDescription.Builder().addStroke(StrokeDescription(linePath(fx1, fy1, fx2, fy2), 0, durationMs)), onResult)
    }

    /** 向下滚动 = 手指向上滑 */
    fun scrollAt(x: Float, y: Float, distancePx: Int, up: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        val d = distancePx.toFloat()
        val sx = x.coerceAtLeast(0f)
        val sy = y.coerceAtLeast(0f)
        if (up) swipe(sx, sy, sx, sy - d, 260, onResult)
        else swipe(sx, sy, sx, sy + d, 260, onResult)
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
        logGesture("global", action.id)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ok = when (action) {
            Action.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            Action.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            Action.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            Action.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            Action.QUICK_SETTINGS -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            Action.SCREENSHOT ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                } else {
                    false
                }
            Action.VOLUME_UP -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                true
            }
            Action.VOLUME_DOWN -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                true
            }
            else -> false // 非全局动作
        }
        // 结果落盘：result=false 说明系统拒绝了该全局动作，便于定位"映射不生效"
        logGesture("global", "${action.id} result=$ok")
    }
}
