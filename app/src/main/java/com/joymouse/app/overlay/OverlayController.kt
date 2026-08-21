package com.joymouse.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
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

/**
 * 悬浮窗控制器：负责控制台（摇杆+面板键）、鼠标光标、自定义按键、编辑模式、
 * 动作执行（点击/拖拽/滑动/滚动/系统动作）。
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
    internal val picker = ActionPicker(this)

    private class GrabState(var startWx: Int, var startWy: Int, var downRawX: Float, var downRawY: Float)

    // ---- 状态 ----
    var cursorX = 0f
    var cursorY = 0f
    var editMode = false
    var mode = Mode.MOVE
    private var panelVisible = true
    private var cursorVisible = true
    private var scrollAccum = 0f
    private var dragging = false
    private var lastConfigSave = 0L

    // 光标空闲自动隐藏
    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        if (!editMode && !dragging && !picker.isVisible() && cursor != null && config.cursorAutoHide) {
            hideCursor()
        }
    }

    // ================= 显示 / 隐藏 =================

    fun canOverlay(): Boolean = Settings.canDrawOverlays(ctx)

    fun show() {
        if (!canOverlay()) return
        instance = this
        mode = if (config.dragMode) Mode.DRAG else Mode.MOVE
        try {
            cursorX = screenW / 2f
            cursorY = screenH / 2f
            showCursor()
            showPanel()
            rebuildButtons()
            if (editMode) showEditBar()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    fun hide() {
        instance = null
        idleHandler.removeCallbacks(idleRunnable)
        try {
            hideCursor()
            hidePanel()
            hideEditBar()
            picker.hide()
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
        cursorParams = baseParams(size, size).apply {
            x = (cursorX - size / 2f).toInt()
            y = (cursorY - size / 2f).toInt()
        }
        wm.addView(iv, cursorParams)
        cursor = iv
        cursorVisible = true
        scheduleCursorHide()
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

    fun toggleCursor() {
        if (cursorVisible) hideCursor() else showCursor()
        cursorVisible = cursor != null
    }

    private fun updateCursor() {
        val c = cursor ?: return
        val p = cursorParams ?: return
        val size = (22 * density).toInt()
        cursorX = cursorX.coerceIn(0f, screenW.toFloat())
        cursorY = cursorY.coerceIn(0f, screenH.toFloat())
        p.x = (cursorX - size / 2f).toInt()
        p.y = (cursorY - size / 2f).toInt()
        runCatching { wm.updateViewLayout(c, p) }
    }

    // ================= 控制台面板 =================

    private fun showPanel() {
        if (panel != null) { if (panelVisible) return else addPanelWindow() }
        buildPanel()
        addPanelWindow()
    }

    private fun addPanelWindow() {
        val p = panel ?: return
        val w = (110 * density).toInt()
        val defaultX = dp(10)
        val defaultY = (screenH / 2 - 120 * density).toInt().coerceAtLeast(dp(10))
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
        panelVisible = false
    }

    fun togglePanel() {
        if (panelVisible) hidePanel() else addPanelWindow()
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

        // 摇杆
        val stick = JoystickView(ctx)
        stick.layoutParams = LinearLayout.LayoutParams(dp(92), dp(92))
        stick.onMove = ::onJoystickMove
        stick.onTap = { if (mode == Mode.MOVE) execute(Action.CLICK) }
        stick.onPress = { if (mode == Mode.DRAG) startDrag() }
        stick.onRelease = { if (mode == Mode.DRAG) endDrag() }
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
        p.addView(rowOf(
            dragKey,
            scrollKey,
            panelKey("编辑") { setEditing(!editMode) },
        ))

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
            setOnClickListener { onClick(); haptic() }
        }

    private fun modeKey(label: String, m: Mode): TextView =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.argb(80, 255, 255, 255), 10f)
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            setOnClickListener {
                mode = if (mode == m) Mode.MOVE else m
                if (mode == Mode.DRAG) config.dragMode = true
                if (mode != Mode.DRAG) config.dragMode = false
                saveConfig()
                refreshModeButtons()
                haptic()
            }
        }

    private fun refreshModeButtons() {
        modeButtons.forEach { (m, v) ->
            v.background = roundedDrawable(
                if (mode == m) Color.argb(255, 0, 180, 110) else Color.argb(80, 255, 255, 255), 10f
            )
        }
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

    private fun onJoystickMove(dx: Float, dy: Float) {
        if (editMode) return
        nudgeCursor()
        when (mode) {
            Mode.MOVE -> moveCursor(dx * config.cursorSpeed, dy * config.cursorSpeed)
            Mode.DRAG -> {
                moveCursor(dx * config.cursorSpeed, dy * config.cursorSpeed)
                if (dragging) service.dragMove(cursorX, cursorY)
            }
            Mode.SCROLL -> accumulateScroll(dy)
        }
    }

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

    private fun accumulateScroll(dy: Float) {
        scrollAccum += dy
        val threshold = 36 * density
        while (scrollAccum >= threshold) {
            scrollAccum -= threshold
            service.scrollAt(cursorX, cursorY, config.scrollStep, up = false)
        }
        while (scrollAccum <= -threshold) {
            scrollAccum += threshold
            service.scrollAt(cursorX, cursorY, config.scrollStep, up = true)
        }
    }

    // ================= 动作执行 =================

    fun execute(action: Action) {
        val s = GestureAccessibilityService.instance ?: return
        when (action) {
            Action.CLICK -> { s.tap(cursorX, cursorY); haptic() }
            Action.DOUBLE_CLICK -> { s.doubleTap(cursorX, cursorY); haptic() }
            Action.LONG_PRESS -> { s.longPress(cursorX, cursorY); haptic() }
            Action.SWIPE_UP -> s.swipe(cursorX, cursorY, cursorX, cursorY - dp(180).toFloat(), 300)
            Action.SWIPE_DOWN -> s.swipe(cursorX, cursorY, cursorX, cursorY + dp(180).toFloat(), 300)
            Action.SWIPE_LEFT -> s.swipe(cursorX, cursorY, cursorX - dp(180).toFloat(), cursorY, 300)
            Action.SWIPE_RIGHT -> s.swipe(cursorX, cursorY, cursorX + dp(180).toFloat(), cursorY, 300)
            Action.SCROLL_UP -> s.scrollAt(cursorX, cursorY, config.scrollStep, up = true)
            Action.SCROLL_DOWN -> s.scrollAt(cursorX, cursorY, config.scrollStep, up = false)
            Action.TOGGLE_PANEL -> togglePanel()
            else -> s.performGlobal(action)
        }
    }

    // ================= 自定义按键 =================

    private fun rebuildButtons() {
        buttonViews.keys.toList().forEach { removeButtonWindow(it) }
        buttonViews.clear()
        config.buttons.forEach { addButtonWindow(it) }
    }

    private fun addButtonWindow(btn: MappedButton) {
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
        if (on) showEditBar() else hideEditBar()
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
            y = dp(24)
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
            setOnClickListener { onClick(); haptic() }
        }

    // ================= 工具 =================

    fun saveConfig() {
        ConfigStore.save(ctx, config)
    }

    private fun haptic() {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(14, VibrationEffect.DEFAULT_AMPLITUDE))
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
}
