package com.joymouse.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.joymouse.app.config.Action
import com.joymouse.app.config.ConfigStore
import com.joymouse.app.config.shortLabel

/**
 * 虚拟手柄图例：画出物理手柄布局，每个按键上直接标注当前映射的功能。
 * 点按任意按键 → onKeyTap 回调（由界面弹出动作选择）。
 */
class GamepadDiagramView(context: Context) : View(context) {

    /** 点按手柄按键回调（参数为键名 a/b/x/y/lb/...） */
    var onKeyTap: ((String) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var pressedKey: String? = null

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 22, 22, 26)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 60, 60, 70)
        style = Paint.Style.FILL
    }
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 170, 110)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }

    /** 按键元素：键名 + 归一化中心 + 半宽半高（isCircle=false 时为圆角矩形） */
    private data class El(val key: String, val x: Float, val y: Float, val rx: Float, val ry: Float, val circle: Boolean = true)

    private val elements: List<El> = listOf(
        // 肩键/扳机（顶部两排）
        El("lb", 0.10f, 0.10f, 0.085f, 0.042f, false),
        El("rb", 0.90f, 0.10f, 0.085f, 0.042f, false),
        El("lt", 0.10f, 0.205f, 0.085f, 0.042f, false),
        El("rt", 0.90f, 0.205f, 0.085f, 0.042f, false),
        // 十字键（左侧，center 先判定避免被遮挡）
        El("center", 0.27f, 0.365f, 0.028f, 0.028f),
        El("up", 0.27f, 0.285f, 0.05f, 0.035f, false),
        El("down", 0.27f, 0.445f, 0.05f, 0.035f, false),
        El("left", 0.205f, 0.365f, 0.035f, 0.05f, false),
        El("right", 0.335f, 0.365f, 0.035f, 0.05f, false),
        // 功能键（中间）
        El("mode", 0.50f, 0.24f, 0.038f, 0.038f),
        El("select", 0.42f, 0.50f, 0.05f, 0.026f, false),
        El("start", 0.58f, 0.50f, 0.05f, 0.026f, false),
        // 摇杆按下键
        El("l3", 0.22f, 0.65f, 0.065f, 0.065f),
        El("r3", 0.78f, 0.65f, 0.065f, 0.065f),
        // 右侧功能键（Xbox 布局）
        El("y", 0.70f, 0.30f, 0.052f, 0.052f),
        El("x", 0.615f, 0.385f, 0.052f, 0.052f),
        El("a", 0.70f, 0.47f, 0.052f, 0.052f),
        El("b", 0.785f, 0.385f, 0.052f, 0.052f),
    )

    private fun cfg() = ConfigStore.load(context)

    private fun labelFor(key: String): String {
        val c = cfg()
        if (key == c.toggleKey) return "唤出"
        return Action.fromId(c.gamepadMap[key]).shortLabel()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 6f * density

        // 手柄主体
        val body = RectF(pad, pad, w - pad, h - pad)
        canvas.drawRoundRect(body, 24f * density, 24f * density, bodyPaint)
        canvas.drawRoundRect(body, 24f * density, 24f * density, borderPaint)

        // 摇杆静态提示
        textPaint.textSize = 10f * density
        hintPaint.textSize = 9f * density
        hintPaint.textAlign = Paint.Align.CENTER
        // 左摇杆提示（l3 圈上方）
        canvas.drawText("左摇杆=移动光标", 0.22f * w, 0.575f * h, hintPaint)
        canvas.drawText("右摇杆=滚动", 0.78f * w, 0.575f * h, hintPaint)
        canvas.drawText("点按按键可修改映射", 0.5f * w, h - 10f * density, hintPaint)

        // 按键
        for (el in elements) {
            val cx = el.x * w
            val cy = el.y * h
            val rx = el.rx * w
            val ry = el.ry * h
            val pressed = pressedKey == el.key
            canvas.drawRoundRect(
                RectF(cx - rx, cy - ry, cx + rx, cy + ry),
                if (el.circle) rx else 6f * density,
                if (el.circle) rx else 6f * density,
                if (pressed) keyPressedPaint else keyPaint
            )
            canvas.drawRoundRect(
                RectF(cx - rx, cy - ry, cx + rx, cy + ry),
                if (el.circle) rx else 6f * density,
                if (el.circle) rx else 6f * density,
                borderPaint
            )
            // 标签
            val label = labelFor(el.key)
            textPaint.textSize = (minOf(rx, ry) * 1.15f).coerceIn(7f * density, 13f * density)
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x / width
                val y = event.y / height
                val hit = elements.firstOrNull { el ->
                    val dx = (x - el.x) / el.rx
                    val dy = (y - el.y) / el.ry
                    (dx * dx + dy * dy) <= 1.25f
                }
                if (hit != null) {
                    pressedKey = hit.key
                    invalidate()
                    onKeyTap?.invoke(hit.key)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pressedKey != null) {
                    pressedKey = null
                    invalidate()
                }
            }
        }
        return true
    }

    /** 映射变化后刷新图例 */
    fun refresh() = invalidate()
}
