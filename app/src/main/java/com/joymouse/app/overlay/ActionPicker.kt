package com.joymouse.app.overlay

import android.graphics.Color
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.joymouse.app.config.Action
import com.joymouse.app.config.MappedButton

/**
 * 按键动作选择面板：以悬浮窗形式弹出，选择动作 / 删除按键 / 调整大小。
 */
class ActionPicker(private val controller: OverlayController) {

    private val ctx get() = controller.ctx
    private val wm = controller.wm
    private val density get() = controller.density

    private var window: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var target: MappedButton? = null

    fun showFor(btn: MappedButton) {
        hide()
        target = btn
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = controller.roundedDrawable(Color.argb(250, 245, 245, 245), 14f)
            elevation = 12f * density
            setPadding(controller.dp(4), controller.dp(4), controller.dp(4), controller.dp(4))
        }
        col.addView(header("编辑按键：${btn.label}（当前：${btn.action.label}）"))

        // 尺寸调节
        val sizeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        sizeRow.addView(row("尺寸－") { controller.onButtonResized(btn, -8) })
        sizeRow.addView(row("尺寸＋") { controller.onButtonResized(btn, 8) })
        sizeRow.addView(row("删除") { controller.onButtonDeleted(btn); hide() })
        col.addView(sizeRow)

        // 动作列表（滚动）
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        list.addView(groupTitle("鼠标操作"))
        Action.entries.filter { !it.isGlobal && it != Action.TOGGLE_PANEL && it != Action.NOOP }
            .forEach { a -> list.addView(actionRow(a)) }
        list.addView(groupTitle("系统动作"))
        Action.entries.filter { it.isGlobal }
            .forEach { a -> list.addView(actionRow(a)) }
        list.addView(actionRow(Action.TOGGLE_PANEL))
        list.addView(actionRow(Action.NOOP))
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(controller.dp(190), 0, 1f))

        val w = controller.dp(200)
        val h = (360 * density).toInt()
        params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            var px = (btn.x * controller.screenW).toInt() + controller.dp(60)
            var py = (btn.y * controller.screenH).toInt() - h / 2
            px = px.coerceIn(controller.dp(4), controller.screenW - w - controller.dp(4))
            py = py.coerceIn(controller.dp(4), controller.screenH - h - controller.dp(4))
            x = px
            y = py
        }
        wm.addView(col, params)
        window = col
    }

    fun hide() {
        window?.let { runCatching { wm.removeView(it) } }
        window = null
        params = null
        target = null
    }

    fun isVisible(): Boolean = window != null

    private fun header(text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.rgb(30, 30, 30))
            textSize = 12f
            setPadding(controller.dp(8), controller.dp(8), controller.dp(8), controller.dp(6))
        }

    private fun groupTitle(text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(Color.rgb(0, 140, 90))
            textSize = 11f
            setPadding(controller.dp(8), controller.dp(6), controller.dp(8), controller.dp(2))
        }

    private fun actionRow(action: Action): TextView =
        row(action.label) {
            target?.let { b ->
                b.action = action
                controller.saveConfig()
                controller.buttonViews[b.id]?.invalidate()
            }
            hide()
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
            setOnClickListener { onClick() }
        }
}
