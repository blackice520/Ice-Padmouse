package com.joymouse.app.overlay

import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.joymouse.app.config.ConfigStore
import com.joymouse.app.config.GamePoint

/**
 * 游戏模式点位绑定面板：长按点位标记后在当前屏幕弹出的悬浮选择面板
 * （不跳回应用设置、不抢焦点：FLAG_NOT_FOCUSABLE + NOT_TOUCH_MODAL）。
 * 两步流程：选手柄按键 → 选动作（点击/长按/上滑/下滑/左滑/右滑/取消绑定）。
 */
class GameBindingPicker(private val controller: OverlayController) {

    private val ctx get() = controller.ctx
    private val wm get() = controller.wm
    private val density get() = controller.density

    private var window: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private var point: GamePoint? = null
    private var pendingKey: String? = null

    companion object {
        val KEYS = listOf(
            "a", "b", "x", "y", "lb", "rb", "lt", "rt",
            "up", "down", "left", "right", "start", "select", "mode", "center", "l3", "r3"
        )
        val KEY_LABELS = mapOf(
            "a" to "A 键", "b" to "B 键", "x" to "X 键", "y" to "Y 键",
            "lb" to "L1 肩键", "rb" to "R1 肩键", "lt" to "L2 扳机", "rt" to "R2 扳机",
            "up" to "十字键 ↑", "down" to "十字键 ↓", "left" to "十字键 ←", "right" to "十字键 →",
            "start" to "Start 键", "select" to "Select 键", "mode" to "Logo 键", "center" to "十字键确认",
            "l3" to "左摇杆按下 L3", "r3" to "右摇杆按下 R3"
        )
        val ACTIONS = listOf(
            "tap" to "点击",
            "longpress" to "长按",
            "swipe_up" to "上滑",
            "swipe_down" to "下滑",
            "swipe_left" to "左滑",
            "swipe_right" to "右滑"
        )
    }

    fun showFor(p: GamePoint) {
        point = p
        pendingKey = null
        build()
    }

    fun hide() {
        window?.let { runCatching { wm.removeView(it) } }
        window = null
        params = null
        pendingKey = null
    }

    fun isVisible(): Boolean = window != null

    private fun build() {
        window?.let { runCatching { wm.removeView(it) } }
        window = null
        val p = point ?: return
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = controller.roundedDrawable(Color.argb(245, 245, 245, 245), 14f)
            elevation = 12f * density
            setPadding(controller.dp(4), controller.dp(4), controller.dp(4), controller.dp(4))
        }

        val key = pendingKey
        if (key == null) {
            col.addView(header("${p.label}：选一个手柄按键"))
            KEYS.forEach { k ->
                col.addView(row("${KEY_LABELS[k]}  →  ${currentLabel(k)}") {
                    pendingKey = k
                    build()
                })
            }
            col.addView(row("关闭") { hide() })
        } else {
            col.addView(header("${KEY_LABELS[key]} 在 ${p.label} 的动作"))
            ACTIONS.forEach { (act, label) ->
                col.addView(row(label) {
                    val cfg = ConfigStore.load(ctx)
                    cfg.gameKeyMap[key] = "$act:${p.id}"
                    controller.saveConfig()
                    controller.hapticFeedback()
                    pendingKey = null
                    build()
                })
            }
            col.addView(row("取消绑定") {
                val cfg = ConfigStore.load(ctx)
                cfg.gameKeyMap.remove(key)
                controller.saveConfig()
                controller.hapticFeedback()
                pendingKey = null
                build()
            })
            col.addView(row("返回") {
                pendingKey = null
                build()
            })
        }

        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            addView(col)
        }
        val w = controller.dp(230)
        val h = (400 * density).toInt()
        params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 优先出现在标记右侧，越界时向左/向上收
            var px = (p.x * controller.screenW).toInt() + controller.dp(40)
            var py = (p.y * controller.screenH).toInt() - h / 2
            px = px.coerceIn(controller.dp(4), controller.screenW - w - controller.dp(4))
            py = py.coerceIn(controller.dp(64), controller.screenH - h - controller.dp(56))
            x = px
            y = py
        }
        wm.addView(scroll, params)
        window = scroll
    }

    /** 当前键在此点位上的绑定显示 */
    private fun currentLabel(key: String): String {
        val cfg = ConfigStore.load(ctx)
        val binding = cfg.gameKeyMap[key] ?: return "无"
        val id = binding.substringAfter(':').toLongOrNull()
        if (id != null) {
            val p = point ?: return "?"
            if (id != p.id) return "其他点位"
            val act = ACTIONS.firstOrNull { binding.startsWith(it.first) }?.second ?: binding
            return act
        }
        return when (binding) {
            "home" -> "主页"; "back" -> "返回"; "recents" -> "最近任务"
            "notifications" -> "通知栏"; "quick_settings" -> "快捷设置"; "screenshot" -> "截屏"
            "vol_up" -> "音量+"; "vol_down" -> "音量-"; "mute" -> "静音"
            "media_play_pause" -> "播放/暂停"; "toggle_panel" -> "控制台"
            else -> binding
        }
    }

    private fun header(text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.rgb(30, 30, 30))
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(controller.dp(8), controller.dp(8), controller.dp(8), controller.dp(6))
        }

    private fun row(label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            setTextColor(Color.rgb(40, 40, 40))
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(controller.dp(10), controller.dp(9), controller.dp(10), controller.dp(9))
            background = controller.roundedDrawable(Color.rgb(235, 235, 235), 8f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                controller.dp(38)
            ).apply { setMargins(controller.dp(3), controller.dp(2), controller.dp(3), controller.dp(2)) }
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> controller.onOurTouch(true)
                    MotionEvent.ACTION_UP -> {
                        controller.onOurTouch(false)
                        onClick()
                    }
                    MotionEvent.ACTION_CANCEL -> controller.onOurTouch(false)
                }
                true
            }
        }
}
