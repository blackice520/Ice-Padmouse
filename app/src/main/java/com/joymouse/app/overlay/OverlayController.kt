package com.joymouse.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.joymouse.app.R
import com.joymouse.app.config.Action
import com.joymouse.app.config.AppConfig
import com.joymouse.app.config.ConfigStore
import com.joymouse.app.config.MappedButton
import com.joymouse.app.service.GestureAccessibilityService
import kotlin.math.hypot
import kotlin.math.pow

/**
 * 悬浮窗控制器：负责控制台（摇杆+面板键）、鼠标光标、自定义按键、编辑模式、
 * 动作执行（点击/拖拽/滑动/滚动/系统动作）与物理手柄输入（摇杆/按键）。
 *
 * 光标与手柄捕获窗口均使用 TYPE_ACCESSIBILITY_OVERLAY（无障碍专用窗口类型），
 * 不需要 SYSTEM_ALERT_WINDOW 悬浮窗权限。
 *
 * 与参考应用（gamepad mouse）不同：唤出/移动光标绝不派发任何额外手势，
 * 不存在"唤出鼠标自动点击左上角"的问题。
 */
class OverlayController(private val service: GestureAccessibilityService) {

    companion object {
        @Volatile
        var instance: OverlayController? = null
    }

    enum class Mode { MOVE, DRAG, SCROLL }

    internal val ctx: Context get() = service
    internal val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    internal val density = service.resources.displayMetrics.density
    private val config: AppConfig get() = ConfigStore.load(ctx)

    /** 屏幕尺寸（逻辑像素） */
    val screenW: Int get() = service.resources.displayMetrics.widthPixels
    val screenH: Int get() = service.resources.displayMetrics.heightPixels

    /** 真实显示边界（maximumWindowMetrics，API30+；服务早期 displayMetrics 可能异常） */
    private fun realScreenSize(): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            try {
                val b = wm.maximumWindowMetrics.bounds
                b.width() to b.height()
            } catch (t: Throwable) {
                screenW to screenH
            }
        } else {
            screenW to screenH
        }

    // ---- 窗口与视图 ----
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelGrabState: GrabState? = null
    private var joystick: JoystickView? = null
    private var cursor: ImageView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var editBar: LinearLayout? = null
    private var editBarParams: WindowManager.LayoutParams? = null
    internal val buttonViews = LinkedHashMap<Long, MappedButtonView>()
    private val modeButtons = mutableMapOf<Mode, TextView>()
    private var modeLabel: TextView? = null
    private var editKeyView: TextView? = null
    private var buttonsKeyView: TextView? = null
    private var mouseKeyView: TextView? = null
    internal val picker = ActionPicker(this)

    /** 手柄输入焦点捕获窗（1×1 可聚焦无障碍窗口，位于 (0,0)） */
    private var gamepadView: GamepadInputView? = null
    private var gamepadParams: WindowManager.LayoutParams? = null

    private class GrabState(var startWx: Int, var startWy: Int, var downRawX: Float, var downRawY: Float)

    // ---- 状态 ----
    var cursorX = 0f
    var cursorY = 0f
    var editMode = false
    var mode = Mode.MOVE
    private var panelVisible = true
    private var cursorVisible = true
    private var dragging = false
    private var lastConfigSave = 0L

    // ---- 鼠标（手柄）状态 ----
    /** 鼠标是否激活：激活时显示光标并捕获手柄输入 */
    var mouseActive = true
        private set

    /** 左摇杆速度向量（物理手柄，归一化，由 tick 处理） */
    private var stickX = 0f
    private var stickY = 0f
    /** 虚拟摇杆偏转向量（面板摇杆，-1..1，由 tick 处理） */
    private var vJoyX = 0f
    private var vJoyY = 0f
    /** 虚拟摇杆当前速度系数 0..1（加速度模型，指数逼近目标） */
    private var vJoySpeed = 0f
    /** 右摇杆滚动方向向量 */
    private var scrollX = 0f
    private var scrollY = 0f
    /** 滚动手势链是否进行中（参考应用方案：手势完成回调后立即派发下一个，形成连续滚动流） */
    private var scrollGestureBusy = false
    /** 滚动被取消/拒绝的时间（退避用） */
    private var lastScrollCancel = 0L
    /** 触摸休眠冷却：鼠标刚关闭 1.5 秒内忽略触摸，避免反复开关 */
    private var lastOutsideDismiss = 0L
    /** 左键按下状态机：held=按下，dragging=已转拖拽 */
    private var leftHeld = false
    private var leftDragging = false
    private var pressX = 0f
    private var pressY = 0f
    private var pressTime = 0L
    private var lastScrollTime = 0L
    private var lastActivity = 0L
    private var lastDragMoveTime = 0L
    private var lastMotionTime = 0L
    /** 连续拖拽链（复用服务的链式拖拽） */
    private var chainActive = false

    /** 手指是否按在虚拟摇杆上（真实触摸会打断注入手势，需等抬起后再注入点击） */
    private var joystickTouched = false
    private var pendingClickX = 0f
    private var pendingClickY = 0f
    private var pendingClickActive = false

    /** 按在我们任一交互窗口上的手指数（控制台/编辑栏/自定义按键/摇杆） */
    private var ourTouchCount = 0

    /** 我们自己的窗口被触摸/抬起时回调（由各视图上报） */
    fun onOurTouch(down: Boolean) {
        if (down) {
            ourTouchCount++
        } else {
            ourTouchCount = (ourTouchCount - 1).coerceAtLeast(0)
            if (ourTouchCount == 0) flushPendingClick()
        }
    }

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            tickHandler.postDelayed(this, 16)
        }
    }

    // 光标空闲自动隐藏
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        if (!editMode && !dragging && !picker.isVisible() && cursor != null && config.cursorAutoHide) {
            hideCursor()
        }
    }

    // ================= 显示 / 隐藏 =================

    /** 无障碍窗口类型：无需悬浮窗权限 */
    fun canOverlay(): Boolean = true

    fun show() {
        if (!canOverlay()) return
        instance = this
        mode = if (config.dragMode) Mode.DRAG else Mode.MOVE
        try {
            val (rw, rh) = realScreenSize()
            cursorX = rw / 2f
            cursorY = rh / 2f
            showCursor()
            // 控制台按持久化状态恢复（默认不自动弹出）
            if (config.panelVisible) showPanel()
            rebuildButtons()
            if (editMode) showEditBar()
            // 焦点窗常驻：无论鼠标是否激活都创建（未激活时仅响应唤出键，其余按键回落给应用）
            activateGamepadFocus()
            tickHandler.removeCallbacks(tickRunnable)
            tickHandler.post(tickRunnable)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun hide() {
        instance = null
        idleHandler.removeCallbacks(idleRunnable)
        tickHandler.removeCallbacks(tickRunnable)
        try {
            hideCursor()
            hidePanel()
            hideEditBar()
            picker.hide()
            deactivateGamepadFocus()
            buttonViews.keys.toList().forEach { removeButtonWindow(it) }
            buttonViews.clear()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    /** 屏幕方向/尺寸变化时按百分比坐标重建按键位置 */
    fun onConfigurationChanged() {
        if (!canOverlay()) return
        try {
            cursorX = cursorX.coerceIn(0f, screenW.toFloat())
            cursorY = cursorY.coerceIn(0f, screenH.toFloat())
            updateCursor()
            buttonViews.values.forEach { refreshButton(it) }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    // ================= 光标 =================

    private fun showCursor() {
        if (cursor != null) return
        val size = (22 * density).toInt()
        val iv = ImageView(ctx)
        iv.setImageResource(R.drawable.ic_cursor_dot)
        applyCursorStyle(iv)
        // 关键：光标窗口必须 FLAG_NOT_TOUCHABLE（否则注入点击被自己吞掉）
        // 且必须 FLAG_LAYOUT_IN_SCREEN（否则窗口坐标原点在状态栏下方，
        // 与注入手势的屏幕坐标系错位——点击会偏上"一个状态栏高度"）
        cursorParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (cursorX - size / 2f).toInt()
            y = (cursorY - size / 2f).toInt()
        }
        wm.addView(iv, cursorParams)
        cursor = iv
        cursorVisible = true
        scheduleCursorHide()
    }

    /** 播放/暂停：优先通过活跃媒体会话控制（QQ音乐/视频 App 等）；
     *  无媒体会话时回退为双击屏幕中央（参考应用的"点按区"思路，适配视频 App 的中央暂停键）。 */
    fun toggleMediaPlayPause() {
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager
            var handled = false
            if (msm != null) {
                val sessions = msm.getActiveSessions(null)
                val playing = sessions.firstOrNull {
                    it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                }
                val session = playing ?: sessions.firstOrNull {
                    it.playbackState?.state?.let { s ->
                        s != android.media.session.PlaybackState.STATE_NONE
                    } == true
                }
                if (session != null) {
                    val isPlaying = session.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                    if (isPlaying) session.transportControls.pause()
                    else session.transportControls.play()
                    handled = true
                }
            }
            if (!handled) {
                // 兜底：双击屏幕中央（视频 App 暂停区）
                android.util.Log.w("JoyMouse", "播放/暂停：无活跃媒体会话，回退为双击屏幕中央")
                GestureAccessibilityService.instance?.doubleTap(screenW / 2f, screenH / 2f)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    /** 光标样式着色（orange/white/red/green/blue/black） */
    private fun applyCursorStyle(iv: ImageView) {
        val tint = when (config.cursorStyle) {
            "white" -> Color.WHITE
            "red" -> Color.rgb(244, 67, 54)
            "green" -> Color.rgb(76, 175, 80)
            "blue" -> Color.rgb(33, 150, 243)
            "black" -> Color.BLACK
            else -> Color.rgb(255, 152, 0) // orange
        }
        iv.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
    }

    /** 主界面切换光标样式后调用 */
    fun onCursorStyleChanged() {
        cursor?.let { applyCursorStyle(it) }
    }

    private fun hideCursor() {
        cursor?.let { runCatching { wm.removeView(it) } }
        cursor = null
        cursorParams = null
        idleHandler.removeCallbacks(idleRunnable)
    }

    /** 摇杆活动时：唤出光标并重置自动隐藏计时 */
    private fun nudgeCursor() {
        if (config.cursorAutoHide) showCursor()
        scheduleCursorHide()
    }

    private fun scheduleCursorHide() {
        idleHandler.removeCallbacks(idleRunnable)
        if (config.cursorAutoHide) idleHandler.postDelayed(idleRunnable, 6000)
    }

    /** 设置变化时同步光标状态（主界面开关调用） */
    fun onCursorSettingsChanged() {
        if (config.cursorAutoHide) {
            showCursor()
            scheduleCursorHide()
        } else {
            idleHandler.removeCallbacks(idleRunnable)
            if (cursor == null) showCursor()
        }
    }

    /** 光标显示/隐藏：与鼠标激活状态联动（光标可见 = 鼠标激活 = 摇杆可用） */
    fun toggleCursor() {
        if (cursorVisible) {
            hideCursor()
            cursorVisible = false
        } else {
            if (!mouseActive) {
                mouseActive = true
                activateGamepadFocus()
            }
            showCursor()
            cursorVisible = true
            refreshModeButtons()
        }
    }

    private fun updateCursor() {
        val c = cursor ?: return
        val p = cursorParams ?: return
        val size = (22 * density).toInt()
        val (rw, rh) = realScreenSize()
        cursorX = cursorX.coerceIn(0f, rw.toFloat())
        cursorY = cursorY.coerceIn(0f, rh.toFloat())
        val nx = (cursorX - size / 2f).toInt()
        val ny = (cursorY - size / 2f).toInt()
        // 位置未变时跳过（减少 binder 调用，防止高频 updateViewLayout 压力）
        if (p.x == nx && p.y == ny) return
        p.x = nx
        p.y = ny
        runCatching { wm.updateViewLayout(c, p) }
    }

    // ================= 控制台面板 =================

    private fun showPanel() {
        // 每次全新重建，避免复用已移除的窗口视图（防止"隐藏后重新显示"崩溃）
        buildPanel()
        addPanelWindow()
        config.panelVisible = true
        saveConfig()
    }

    private fun addPanelWindow() {
        val p = panel ?: return
        val w = (118 * density).toInt()
        val defaultX = dp(10)
        val defaultY = (screenH / 2 - 150 * density).toInt().coerceAtLeast(dp(10))
        val useX = if (config.panelX >= 0f) (config.panelX * screenW).toInt() else defaultX
        val useY = if (config.panelY >= 0f) (config.panelY * screenH).toInt() else defaultY
        if (config.panelX < 0f || config.panelY < 0f) {
            config.panelX = useX.toFloat() / screenW
            config.panelY = useY.toFloat() / screenH
            saveConfig()
        }
        panelParams = baseParams(w, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = useX
            y = useY
            alpha = config.panelOpacity
        }
        wm.addView(p, panelParams)
        panelVisible = true
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        // 彻底清理视图引用，下次显示时全新重建
        panel = null
        panelParams = null
        panelGrabState = null
        joystick = null
        modeButtons.clear()
        editKeyView = null
        buttonsKeyView = null
        mouseKeyView = null
        modeLabel = null
        panelVisible = false
        config.panelVisible = false
        saveConfig()
    }

    fun togglePanel() {
        if (panelVisible) hidePanel() else showPanel()
    }

    fun panelVisible(): Boolean = panelVisible

    fun applyPanelOpacity() {
        val p = panel ?: return
        val pp = panelParams ?: return
        pp.alpha = config.panelOpacity
        runCatching { wm.updateViewLayout(p, pp) }
    }

    private fun buildPanel() {
        val p = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(Color.argb(235, 18, 18, 18), 16f)
            setPadding(dp(6), dp(4), dp(6), dp(8))
            elevation = 10f * density
        }

        // 拖动把手
        val handle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedDrawable(Color.argb(60, 255, 255, 255), 10f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            setOnTouchListener { _, e -> onPanelGrab(e) }
            addView(TextView(ctx).apply {
                text = "≡ JoyMouse"
                setTextColor(Color.argb(190, 255, 255, 255))
                textSize = 10f
            })
        }
        p.addView(handle)

        // 模式状态文字
        val label = TextView(ctx).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(230, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
        }
        modeLabel = label
        p.addView(label)

        // 摇杆
        val stick = JoystickView(ctx)
        stick.layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
        // 速度模型：摇杆偏转(-1..1)由 60fps 主循环持续移动光标
        stick.onMove = { dx, dy ->
            vJoyX = dx
            vJoyY = dy
        }
        stick.onTap = { if (mode == Mode.MOVE) execute(Action.CLICK) }
        stick.onPress = {
            joystickTouched = true
            onOurTouch(true)
            // 鼠标未激活时按摇杆自动唤醒（保证摇杆永远可用）
            if (!mouseActive) toggleMouse()
            if (mode == Mode.DRAG) startDrag()
        }
        stick.onRelease = {
            joystickTouched = false
            onOurTouch(false)
            vJoyX = 0f
            vJoyY = 0f
            if (mode == Mode.DRAG) endDrag()
        }
        joystick = stick
        p.addView(stick)

        // 按键网格 3x2
        p.addView(rowOf(
            panelKey("左键", Action.CLICK),
            panelKey("双击", Action.DOUBLE_CLICK),
            panelKey("长按", Action.LONG_PRESS),
        ))
        val dragKey = modeKey("拖拽", Mode.DRAG)
        val scrollKey = modeKey("滚轮", Mode.SCROLL)
        modeButtons[Mode.DRAG] = dragKey
        modeButtons[Mode.SCROLL] = scrollKey
        val editKey = panelKey("编辑") { setEditing(!editMode) }
        editKeyView = editKey
        p.addView(rowOf(
            dragKey,
            scrollKey,
            editKey,
        ))
        // 第三行：鼠标开关 / 自定义按键显隐 / 光标开关 / 隐藏控制台
        val mouseKey = panelKey("鼠标") { toggleMouse() }
        mouseKeyView = mouseKey
        val buttonsKey = panelKey("按键") { setButtonsVisible(!config.buttonsVisible) }.apply { textSize = 9.5f }
        buttonsKeyView = buttonsKey
        val cursorKey = panelKey("光标") { toggleCursor() }.apply { textSize = 9.5f }
        val hideKey = panelKey("隐藏") { hidePanel() }.apply { textSize = 9.5f }
        p.addView(rowOf(mouseKey, buttonsKey, cursorKey, hideKey))

        panel = p
        refreshModeButtons()
    }

    private fun rowOf(vararg views: View): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
            views.forEach { v ->
                addView(v)
                (v.layoutParams as LinearLayout.LayoutParams).setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        }

    private fun panelKey(label: String, action: Action): TextView =
        panelKey(label) { execute(action) }

    private fun panelKey(label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.argb(80, 255, 255, 255), 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onOurTouch(true)
                    MotionEvent.ACTION_UP -> {
                        onOurTouch(false)
                        onClick()
                        haptic()
                    }
                    MotionEvent.ACTION_CANCEL -> onOurTouch(false)
                }
                true
            }
        }

    private fun modeKey(label: String, m: Mode): TextView =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.argb(80, 255, 255, 255), 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onOurTouch(true)
                    MotionEvent.ACTION_UP -> {
                        onOurTouch(false)
                        mode = if (mode == m) Mode.MOVE else m
                        if (mode == Mode.DRAG) config.dragMode = true
                        if (mode != Mode.DRAG) config.dragMode = false
                        saveConfig()
                        refreshModeButtons()
                        haptic()
                    }
                    MotionEvent.ACTION_CANCEL -> onOurTouch(false)
                }
                true
            }
        }

    private fun refreshModeButtons() {
        modeButtons.forEach { (m, v) ->
            v.background = roundedDrawable(
                if (mode == m) Color.argb(255, 0, 180, 110) else Color.argb(80, 255, 255, 255), 10f
            )
        }
        // 模式状态文字：一眼看清当前模式与取消方式
        modeLabel?.text = when {
            editMode -> "编辑中·点[完成]退出"
            mode == Mode.DRAG -> "拖拽中·再点[拖拽]取消"
            mode == Mode.SCROLL -> "滚轮中·再点[滚轮]取消"
            else -> "移动·轻点摇杆=左键"
        }
        setKeyState(editKeyView, editMode)
        setKeyState(buttonsKeyView, config.buttonsVisible)
        setKeyState(mouseKeyView, mouseActive)
    }

    private fun setKeyState(v: TextView?, on: Boolean) {
        v?.background = roundedDrawable(
            if (on) Color.argb(255, 0, 180, 110) else Color.argb(80, 255, 255, 255), 10f
        )
    }

    private fun onPanelGrab(e: MotionEvent): Boolean {
        val pp = panelParams ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panelGrabState = GrabState(pp.x, pp.y, e.rawX, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                val g = panelGrabState ?: return true
                pp.x = (g.startWx + (e.rawX - g.downRawX)).toInt()
                pp.y = (g.startWy + (e.rawY - g.downRawY)).toInt()
                runCatching { wm.updateViewLayout(panel, pp) }
                // 持久化位置（节流）
                val now = System.currentTimeMillis()
                if (now - lastConfigSave > 200) {
                    lastConfigSave = now
                    config.panelX = pp.x.toFloat() / screenW
                    config.panelY = pp.y.toFloat() / screenH
                    saveConfig()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panelGrabState = null
        }
        return true
    }

    // ================= 摇杆行为 =================

    private fun moveCursor(dx: Float, dy: Float) {
        cursorX += dx
        cursorY += dy
        updateCursor()
    }

    /** 供手柄方向键等外部入口调用：按像素移动光标 */
    fun moveCursorBy(dx: Float, dy: Float) {
        if (editMode) return
        nudgeCursor()
        moveCursor(dx, dy)
    }

    private fun startDrag() {
        dragging = true
        service.dragStart(cursorX, cursorY)
    }

    private fun endDrag() {
        dragging = false
        service.dragEnd(cursorX, cursorY)
    }

    // ================= 动作执行 =================

    /**
     * 注入期间让我们的窗口临时"穿透"触摸（FLAG_NOT_TOUCHABLE）：
     * 注入的点击精确落在光标处，即使光标压在我们自己的悬浮窗/按键上也不会被吞掉或触发悬浮键。
     * 只在所有手指都离开我们窗口时启用（避免用户手指被穿透误触到应用）。
     */
    private var passthroughActive = false

    private fun setPassthrough(on: Boolean) {
        if (passthroughActive == on) return
        passthroughActive = on
        fun apply(v: View?, p: WindowManager.LayoutParams?) {
            if (v == null || p == null) return
            p.flags = if (on) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { wm.updateViewLayout(v, p) }
        }
        apply(panel, panelParams)
        apply(editBar, editBarParams)
        buttonViews.values.forEach { apply(it, it.layoutParams as? WindowManager.LayoutParams) }
        picker.setTouchable(!on)
    }

    private fun injectWithRetry(dispatch: (onResult: (Boolean) -> Unit) -> Unit) {
        var attempts = 0
        val passthrough = ourTouchCount == 0
        val task = object : Runnable {
            override fun run() {
                if (attempts >= 5) return
                attempts++
                // 每次注入前刷新活动时间：注入手势本身也会触发触摸休眠检测，避免被误判为"用户触摸"而关闭鼠标
                lastActivity = System.currentTimeMillis()
                if (passthrough) setPassthrough(true)
                dispatch { ok ->
                    if (!ok && attempts < 5) {
                        tickHandler.postDelayed(this, 220)
                    } else {
                        if (passthrough) tickHandler.postDelayed({ setPassthrough(false) }, 250)
                        if (!ok) android.util.Log.w("JoyMouse", "注入手势失败(已重试5次)")
                    }
                }
            }
        }
        tickHandler.postDelayed(task, 80)
    }

    /** 所有手指离开我们的窗口后，补发被挂起的点击 */
    private fun flushPendingClick() {
        if (!pendingClickActive) return
        pendingClickActive = false
        tickHandler.postDelayed({
            injectWithRetry { cb -> GestureAccessibilityService.instance?.tap(pendingClickX, pendingClickY, onResult = cb) }
        }, 80)
    }

    /** 左键单击：若还有手指按在我们的窗口上，挂起等全部抬起再注入（避免被真实触摸打断） */
    private fun doClick(tx: Float, ty: Float) {
        if (ourTouchCount > 0) {
            pendingClickX = tx
            pendingClickY = ty
            pendingClickActive = true
        } else {
            injectWithRetry { cb -> GestureAccessibilityService.instance?.tap(tx, ty, onResult = cb) }
        }
        // 点击也是活动：重置光标自动隐藏计时
        scheduleCursorHide()
        haptic()
    }

    fun execute(action: Action) {
        // 拖拽链进行中：忽略悬浮键触发，避免注入手势又触发按键形成循环
        if (chainActive) return
        val s = GestureAccessibilityService.instance ?: return
        lastActivity = System.currentTimeMillis()
        // 点击精确落在光标处（注入期间我们的窗口会临时穿透，无需挪动光标）
        val tx = cursorX
        val ty = cursorY
        when (action) {
            Action.CLICK -> doClick(tx, ty)
            Action.DOUBLE_CLICK -> {
                if (joystickTouched) doClick(tx, ty) // 简化：按住摇杆时双击降级为单击，抬起后补发
                else {
                    injectWithRetry { cb -> s.doubleTap(tx, ty, onResult = cb) }
                    haptic()
                }
            }
            Action.LONG_PRESS -> {
                if (joystickTouched) doClick(tx, ty)
                else {
                    injectWithRetry { cb -> s.longPress(tx, ty, onResult = cb) }
                    haptic()
                }
            }
            Action.SWIPE_UP -> injectWithRetry { cb -> s.swipe(tx, ty, tx, ty - dp(180).toFloat(), 300, cb) }
            Action.SWIPE_DOWN -> injectWithRetry { cb -> s.swipe(tx, ty, tx, ty + dp(180).toFloat(), 300, cb) }
            Action.SWIPE_LEFT -> injectWithRetry { cb -> s.swipe(tx, ty, tx - dp(180).toFloat(), ty, 300, cb) }
            Action.SWIPE_RIGHT -> injectWithRetry { cb -> s.swipe(tx, ty, tx + dp(180).toFloat(), ty, 300, cb) }
            Action.SCROLL_UP -> injectWithRetry { cb -> s.scrollAt(tx, ty, config.scrollStep, up = true, onResult = cb) }
            Action.SCROLL_DOWN -> injectWithRetry { cb -> s.scrollAt(tx, ty, config.scrollStep, up = false, onResult = cb) }
            Action.MUTE -> {
                (ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)
                    ?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, 0)
            }
            // 播放/暂停：通过活跃媒体会话控制
            Action.MEDIA_PLAY_PAUSE -> toggleMediaPlayPause()
            // 快进/快退：双击屏幕右侧 80% / 左侧 20% 宽度处（参考应用行为，适配视频类 App 点按区）
            Action.MEDIA_FORWARD -> s.doubleTap(screenW * 0.8f, cursorY)
            Action.MEDIA_REWIND -> s.doubleTap(screenW * 0.2f, cursorY)
            Action.TOGGLE_MOUSE -> toggleMouse()
            Action.TOGGLE_PANEL -> togglePanel()
            else -> s.performGlobal(action)
        }
    }

    // ================= 物理手柄（焦点捕获） =================

    /** 激活手柄焦点：添加 1×1 可聚焦窗口（常驻不销毁，避免重新添加后焦点丢失）。
     *  按键由焦点窗+服务双通道处理；摇杆轴事件由焦点窗接收。 */
    private fun activateGamepadFocus() {
        if (gamepadView != null) {
            requestFocusRetry()
            return
        }
        try {
            val v = GamepadInputView(ctx, this)
            gamepadParams = baseParams(1, 1).apply {
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                x = 0
                y = 0
            }
            wm.addView(v, gamepadParams)
            gamepadView = v
            requestFocusRetry()
        } catch (t: Throwable) {
            t.printStackTrace()
            gamepadView = null
        }
    }

    /** requestFocus 有竞态：添加窗口后立即请求可能失败，延迟重试 */
    private fun requestFocusRetry() {
        val v = gamepadView ?: return
        try {
            v.requestFocus()
            tickHandler.postDelayed({
                if (gamepadView === v && !v.hasFocus()) v.requestFocus()
            }, 100)
            tickHandler.postDelayed({
                if (gamepadView === v && !v.hasFocus()) v.requestFocus()
            }, 400)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    /** 鼠标关闭时不再销毁焦点窗（保持按键通道常驻），只隐藏光标、停用注入 */
    private fun deactivateGamepadFocus() {
        // 常驻：焦点窗保留，按键通道不中断（未激活时仅响应唤出键，其余放行给应用）
    }

    /**
     * 唤出/关闭鼠标（L3 或面板"鼠标"按钮触发）。唤出过程绝不派发任何手势。
     * 注意：参考应用的"触摸屏幕自动休眠鼠标"已移除——虚拟控制台用户碰一下
     * 应用界面鼠标就没了、摇杆失灵，体验灾难。鼠标只由 L3/按钮/空闲超时(默认关)控制。
     */
    fun toggleMouse() {
        mouseActive = !mouseActive
        if (mouseActive) {
            showCursor()
            requestFocusRetry()
        } else {
            hideCursor()
            releaseLeftButton()
            stickX = 0f
            stickY = 0f
            scrollX = 0f
            scrollY = 0f
            scrollGestureBusy = false
            vJoyX = 0f
            vJoyY = 0f
            vJoySpeed = 0f
        }
        refreshModeButtons()
        haptic()
    }

    /**
     * 屏幕其他位置被触摸（焦点窗 ACTION_OUTSIDE）→ 自动收起鼠标（用户要求：手指控制时取消光标）。
     * 触摸本应用窗口（控制台/按键/编辑面板）不会触发；刚操作完 300ms 内忽略；
     * 关闭后 1.5s 冷却防反复。
     */
    fun onOutsideTouch(x: Float, y: Float) {
        if (!mouseActive || editMode) return
        if (leftHeld || dragging) return
        val now = System.currentTimeMillis()
        if (now - lastOutsideDismiss < 1500) return
        if (now - lastActivity < 300) return
        if (isTouchOnOurWindows(x, y)) return
        lastOutsideDismiss = now
        toggleMouse()
    }

    /** 触摸点是否落在我们自己的交互窗口上（控制台/编辑栏/选择面板/自定义按键） */
    fun isTouchOnOurWindows(x: Float, y: Float): Boolean =
        ourWindowRects().any { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }

    /** 本应用全部交互窗口的屏幕矩形 */
    private fun ourWindowRects(): List<android.graphics.Rect> {
        val rects = mutableListOf<android.graphics.Rect>()
        panelParams?.let { rects.add(android.graphics.Rect(it.x, it.y, it.x + it.width, it.y + it.height)) }
        editBarParams?.let { rects.add(android.graphics.Rect(it.x, it.y, it.x + it.width, it.y + it.height)) }
        picker.windowRect()?.let { rects.add(it) }
        config.buttons.forEach { b ->
            val size = dp(b.sizeDp)
            val l = (b.x * screenW - size / 2f).toInt()
            val t = (b.y * screenH - size / 2f).toInt()
            rects.add(android.graphics.Rect(l, t, l + size, t + size))
        }
        return rects
    }

    /** 按键消抖：双通道（焦点窗+服务）可能重复送达，250ms 内同动作只执行一次 */
    private var lastKeyActionTime = 0L
    private var lastKeyAction: Action? = null

    /** 手柄摇杆轴事件（来自 GamepadInputView） */
    fun onGamepadMotion(event: MotionEvent): Boolean {
        if (!mouseActive) return false
        val src = event.source
        val isGamepad = (src and InputDevice.SOURCE_GAMEPAD) != 0 || (src and InputDevice.SOURCE_JOYSTICK) != 0
        if (!isGamepad || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        // 左摇杆：AXIS_X / AXIS_Y
        stickX = event.getAxisValue(MotionEvent.AXIS_X)
        stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        // 右摇杆：RX/RY（部分手柄用 Z/RZ）
        var rx = event.getAxisValue(MotionEvent.AXIS_RX)
        var ry = event.getAxisValue(MotionEvent.AXIS_RY)
        val rz = event.getAxisValue(MotionEvent.AXIS_Z)
        val rw = event.getAxisValue(MotionEvent.AXIS_RZ)
        if (kotlin.math.abs(rx) <= kotlin.math.abs(rw)) rx = rw
        if (kotlin.math.abs(ry) <= kotlin.math.abs(rz)) ry = rz
        scrollX = rx
        scrollY = ry
        lastMotionTime = System.currentTimeMillis()
        lastActivity = lastMotionTime
        return true
    }

    /** 手柄按键事件（来自焦点窗或服务全局通道；两条路径都处理，用消抖去重） */
    fun onGamepadKey(keyCode: Int, down: Boolean, event: KeyEvent?): Boolean {
        val name = ConfigStore.keyNameOf(keyCode) ?: return false
        val cfg = config
        // 鼠标未激活：仅响应唤出键与映射为"唤出/隐藏光标"的键，其余放行（回落给应用）
        if (!mouseActive) {
            val isToggle = name == cfg.toggleKey || cfg.gamepadMap[name] == Action.TOGGLE_MOUSE.id
            if (isToggle && down && (event == null || event.repeatCount == 0)) {
                toggleMouse()
                return true
            }
            return false
        }
        // 唤出键：切换鼠标开关（仅在按下瞬间）
        if (name == cfg.toggleKey) {
            if (down && (event == null || event.repeatCount == 0)) toggleMouse()
            return true
        }
        // 左键（单击/拖拽）状态机：A 键（或交换后 B 键）
        val clickKey = if (cfg.swapAB) "b" else "a"
        if (name == clickKey) {
            if (down && (event == null || event.repeatCount == 0)) leftButtonDown()
            if (!down) leftButtonUp()
            return true
        }
        val actionId = cfg.gamepadMap[name] ?: return false
        val action = Action.fromId(actionId)
        if (down && (event == null || event.repeatCount == 0)) {
            // 消抖：双通道可能重复送达同一按键，250ms 内同动作只执行一次
            val now = System.currentTimeMillis()
            if (now - lastKeyActionTime < 250 && lastKeyAction == action) return true
            lastKeyActionTime = now
            lastKeyAction = action
            if (action == Action.NOOP) return true
            if (action == Action.TOGGLE_MOUSE) toggleMouse()
            else execute(action)
        }
        return true
    }

    /** 左键按下：不立即派发，等 tick 决定点击/拖拽。按下点取光标当前位置 */
    private fun leftButtonDown() {
        if (leftHeld) return
        leftHeld = true
        leftDragging = false
        pressX = cursorX
        pressY = cursorY
        pressTime = System.currentTimeMillis()
        lastActivity = pressTime
    }

    /** 左键抬起：未拖拽则单击 */
    private fun leftButtonUp() {
        if (!leftHeld) return
        leftHeld = false
        lastActivity = System.currentTimeMillis()
        if (leftDragging) {
            endChainDrag()
            leftDragging = false
        } else {
            val s = GestureAccessibilityService.instance ?: return
            injectWithRetry { cb -> s.tap(cursorX, cursorY, onResult = cb) }
            haptic()
        }
    }

    private fun releaseLeftButton() {
        if (leftHeld) {
            leftHeld = false
            if (leftDragging) endChainDrag()
            leftDragging = false
        }
    }

    private fun startChainDrag() {
        val s = GestureAccessibilityService.instance ?: return
        s.dragStart(pressX, pressY)
        chainActive = true
        haptic()
    }

    private fun endChainDrag() {
        if (!chainActive) return
        chainActive = false
        // 终笔划抬指可能落在我们窗口上：短暂穿透，避免误触发悬浮键
        setPassthrough(true)
        GestureAccessibilityService.instance?.dragEnd(cursorX, cursorY)
        tickHandler.postDelayed({ setPassthrough(false) }, 300)
    }

    /** 60fps 主循环：速度积分、拖拽状态机、右摇杆滚动、空闲超时 */
    private fun tick() {
        if (!mouseActive) return
        val cfg = config
        val s = GestureAccessibilityService.instance ?: return
        val now = System.currentTimeMillis()

        // 摇杆松开兜底：250ms 无轴事件则速度/滚动方向归零（部分手柄松开时不发归零事件）
        if (now - lastMotionTime > 250) {
            stickX = 0f
            stickY = 0f
            scrollX = 0f
            scrollY = 0f
        }

        // 1) 左摇杆 → 光标速度（死区 + 幂次曲线）
        //    曲线: v = norm^exp, exp = 2.5 - sensitivity*0.015（1..2.5）
        //    灵敏度越高 exp 越低 → 越线性（低偏转响应越快）。
        //    恒为正且单调 —— 修复参考应用公式在默认灵敏度下产生负速度导致的"反向乱飘"
        val dead = cfg.deadzone / 100f
        val len = hypot(stickX, stickY)
        if (len > dead) {
            val norm = ((len - dead) / (1f - dead)).coerceIn(0f, 1f)
            val exp = (2.5f - (cfg.sensitivity / 100f) * 1.5f).coerceIn(1f, 2.5f)
            val v = norm.toDouble().pow(exp.toDouble()).toFloat()
            val speedPx = (cfg.mouseSpeed / 100f) * 2.4f * density * 16f // 每 tick 位移
            val dx = (stickX / len) * v * speedPx
            val dy = (stickY / len) * v * speedPx
            moveCursorBy(dx, dy)
            lastActivity = now
        }

        // 2) 虚拟摇杆（面板）→ 光标速度（速度模型 + 加速度）
        //    偏转持续生效：推住不放光标一直走；速度/加速度均可由用户设置
        if (!editMode) {
            val vDead = 0.06f
            val vLen = hypot(vJoyX, vJoyY)
            // 加速度：指数逼近目标速度，tau=accelTime/3；松手按同时间常数衰减
            val tau = (cfg.accelTime / 3f).coerceAtLeast(16f)
            val alpha = 1f - kotlin.math.exp(-16f / tau)
            if (vLen > vDead && (mode == Mode.MOVE || mode == Mode.DRAG)) {
                val norm = ((vLen - vDead) / (1f - vDead)).coerceIn(0f, 1f)
                val target = norm * norm * (3f - 2f * norm) // smoothstep：起步柔和、推满线性
                vJoySpeed += (target - vJoySpeed) * alpha
                // 速度标定：cursorSpeed(1..20) × 100 × density = 像素/秒（默认 6 → 1800px/s）
                val maxPxPerSec = cfg.cursorSpeed * 100f * density
                val speedPx = vJoySpeed * maxPxPerSec * 16f / 1000f
                val dx = (vJoyX / vLen) * speedPx
                val dy = (vJoyY / vLen) * speedPx
                moveCursorBy(dx, dy)
                lastActivity = now
            } else {
                vJoySpeed *= (1f - alpha) // 未推动时速度衰减（缓停）
            }
            // 虚拟摇杆拖拽模式：拖动链跟随光标（节流 40ms）
            if (mode == Mode.DRAG && dragging) {
                val g = GestureAccessibilityService.instance
                if (g != null && now - lastDragMoveTime > 40) {
                    lastDragMoveTime = now
                    g.dragMove(cursorX, cursorY)
                }
            }
            // 虚拟摇杆滚轮模式：垂直偏转 → 滚动
            if (mode == Mode.SCROLL && kotlin.math.abs(vJoyY) > 0.35f && !leftHeld) {
                if (now - lastScrollTime > 150) {
                    lastScrollTime = now
                    val step = (cfg.scrollStep * cfg.scrollSpeed / 100f).toInt().coerceAtLeast(80)
                    s.scrollAt(cursorX, cursorY, step, up = vJoyY < 0)
                    lastActivity = now
                }
            }
        }

        // 3) 左键状态机：移动超阈值或静止超 500ms 转拖拽
        if (leftHeld && !leftDragging) {
            val moved = hypot(cursorX - pressX, cursorY - pressY) > 6f * density
            val heldLong = now - pressTime > 500
            if (moved || heldLong) {
                leftDragging = true
                startChainDrag()
            }
        }
        if (leftDragging) {
            val g = GestureAccessibilityService.instance
            if (g != null && now - lastDragMoveTime > 40) { // 节流，与参考应用一致
                lastDragMoveTime = now
                g.dragMove(cursorX, cursorY)
            }
        }

        // 4) 右摇杆 → 链式滚动（参考应用方案）
        //    派发一个滑动，完成回调后立即派发下一个 → 连续无间隙的滚动流；
        //    方向跟随摇杆（摇杆向下=手指上滑=页面下滚），长度随推杆幅度与滚动速度设置变化
        val scrollLen = hypot(scrollX, scrollY)
        val scrollDead = dead * 0.5f // 滚动比移动更灵敏（轻推即可滚动）
        if (scrollLen > scrollDead && !leftHeld && !scrollGestureBusy) {
            // 手势被取消后退避 300ms：避免与真实触摸形成"派发-取消"风暴
            if (now - lastScrollCancel < 300) return
            val norm = ((scrollLen - scrollDead) / (1f - scrollDead)).coerceIn(0f, 1f)
            val len = (cfg.scrollStep * cfg.scrollSpeed / 100f * (0.8f + norm * 0.5f)).coerceAtLeast(120f)
            val dirX = scrollX / scrollLen
            val dirY = scrollY / scrollLen
            scrollGestureBusy = true
            lastActivity = now
            val dispatched = s.swipe(cursorX, cursorY, cursorX - dirX * len, cursorY - dirY * len, 130) { ok ->
                if (!ok) lastScrollCancel = System.currentTimeMillis()
                scrollGestureBusy = false
            }
            if (!dispatched) {
                // 派发被拒绝：立即复位并退避，避免 busy 卡死导致滚动失效
                scrollGestureBusy = false
                lastScrollCancel = System.currentTimeMillis()
            }
        }

        // 5) 空闲超时自动关闭鼠标
        if (cfg.mouseTimeout > 0 && now - lastActivity > cfg.mouseTimeout * 1000L) {
            toggleMouse()
        }
    }

    // ================= 自定义按键 =================

    private fun rebuildButtons() {
        buttonViews.keys.toList().forEach { removeButtonWindow(it) }
        buttonViews.clear()
        config.buttons.forEach { addButtonWindow(it) }
    }

    /** 自定义按键显隐开关（面板"按键"按钮） */
    fun setButtonsVisible(on: Boolean) {
        config.buttonsVisible = on
        saveConfig()
        if (on) {
            rebuildButtons()
        } else {
            buttonViews.keys.toList().forEach { removeButtonWindow(it) }
            buttonViews.clear()
        }
        refreshModeButtons()
        haptic()
    }

    private fun addButtonWindow(btn: MappedButton) {
        if (!config.buttonsVisible) return
        if (buttonViews.containsKey(btn.id)) return
        val size = dp(btn.sizeDp)
        val view = MappedButtonView(ctx, btn, this)
        view.setEditMode(editMode)
        val p = baseParams(size, size).apply {
            x = (btn.x * screenW - size / 2f).toInt()
            y = (btn.y * screenH - size / 2f).toInt()
        }
        wm.addView(view, p)
        buttonViews[btn.id] = view
    }

    private fun removeButtonWindow(id: Long) {
        buttonViews.remove(id)?.let { runCatching { wm.removeView(it) } }
    }

    fun refreshButton(view: MappedButtonView) {
        val btn = view.btn
        val size = dp(btn.sizeDp)
        val p = baseParams(size, size).apply {
            x = (btn.x * screenW - size / 2f).toInt()
            y = (btn.y * screenH - size / 2f).toInt()
        }
        runCatching { wm.updateViewLayout(view, p) }
        view.requestLayout()
    }

    fun onEditButtonMoved(view: MappedButtonView, dx: Float, dy: Float) {
        view.moveBy(dx, dy)
        val now = System.currentTimeMillis()
        if (now - lastConfigSave > 200) {
            lastConfigSave = now
            saveConfig()
        }
    }

    fun onEditButtonTapped(view: MappedButtonView) {
        picker.showFor(view.btn)
    }

    fun onButtonDeleted(btn: MappedButton) {
        config.buttons.removeAll { it.id == btn.id }
        removeButtonWindow(btn.id)
        saveConfig()
    }

    fun onButtonResized(btn: MappedButton, deltaDp: Int) {
        btn.sizeDp = (btn.sizeDp + deltaDp).coerceIn(36, 110)
        buttonViews[btn.id]?.let { refreshButton(it) }
        saveConfig()
    }

    fun addNewButton() {
        if (config.buttons.size >= 12) {
            Toast.makeText(ctx, "最多 12 个自定义按键", Toast.LENGTH_SHORT).show()
            return
        }
        // 添加按键时自动确保按键可见
        if (!config.buttonsVisible) {
            config.buttonsVisible = true
            saveConfig()
        }
        val btn = MappedButton(
            id = System.currentTimeMillis(),
            label = "键${config.buttons.size + 1}",
            action = Action.CLICK,
            x = 0.5f,
            y = 0.5f,
            sizeDp = 52
        )
        config.buttons.add(btn)
        addButtonWindow(btn)
        saveConfig()
        picker.showFor(btn)
    }

    // ================= 编辑模式 =================

    fun setEditing(on: Boolean) {
        editMode = on
        joystick?.active = !on
        buttonViews.values.forEach { it.setEditMode(on) }
        if (on) showEditBar() else {
            hideEditBar()
            picker.hide() // 退出编辑时同时关闭可能打开的选择面板
        }
        refreshModeButtons()
    }

    private fun showEditBar() {
        if (editBar != null) return
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedDrawable(Color.argb(235, 18, 18, 18), 18f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            elevation = 10f * density
        }
        bar.addView(barKey("＋ 添加按键") { addNewButton() })
        bar.addView(barKey("光标 开/关") { toggleCursor() })
        bar.addView(barKey("完成编辑") { setEditing(false) })
        editBarParams = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(56) // 避开手势导航条区域
        }
        wm.addView(bar, editBarParams)
        editBar = bar
    }

    private fun hideEditBar() {
        editBar?.let { runCatching { wm.removeView(it) } }
        editBar = null
        editBarParams = null
    }

    private fun barKey(label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.argb(90, 255, 255, 255), 14f)
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(34)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> onOurTouch(true)
                    MotionEvent.ACTION_UP -> {
                        onOurTouch(false)
                        onClick()
                        haptic()
                    }
                    MotionEvent.ACTION_CANCEL -> onOurTouch(false)
                }
                true
            }
        }

    // ================= 工具 =================

    fun saveConfig() {
        ConfigStore.save(ctx, config)
    }

    private fun haptic() {
        if (!config.hapticEnabled) return
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(
            VibrationEffect.createOneShot(
                14,
                (config.vibrationIntensity * VibrationEffect.DEFAULT_AMPLITUDE / 255).coerceAtLeast(1)
            )
        )
    }

    fun roundedDrawable(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
        }

    fun dp(v: Int): Int = (v * density).toInt()
    fun dp(v: Float): Int = (v * density).toInt()

    private fun baseParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            // 无障碍专用窗口类型：由无障碍服务创建，无需 SYSTEM_ALERT_WINDOW 权限
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // LAYOUT_IN_SCREEN 保证窗口坐标与注入手势同为屏幕坐标系（对齐参考应用）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
}
