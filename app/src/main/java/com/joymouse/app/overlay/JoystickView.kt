package com.joymouse.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 虚拟摇杆：位移驱动光标移动 / 拖拽 / 滚轮。
 * onMove 上报“本次事件相对上次”的增量位移（像素，已限位）。
 */
class JoystickView(context: Context) : View(context) {

    var onMove: ((defX: Float, defY: Float) -> Unit)? = null
    var onTap: (() -> Unit)? = null
    var onPress: (() -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    /** 编辑模式下置为 false，屏蔽摇杆 */
    var active: Boolean = true

    private val density = resources.displayMetrics.density
    private val knobR = 18f * density
    private var downX = 0f
    private var downY = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var pointerId = -1
    private var downTime = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val knobRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 180, 120)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - 4f * density
        canvas.drawCircle(cx, cy, r, bgPaint)
        canvas.drawCircle(cx, cy, r - density, ringPaint)
        canvas.drawLine(cx - r + 12f * density, cy, cx + r - 12f * density, cy, ringPaint)
        canvas.drawLine(cx, cy - r + 12f * density, cx, cy + r - 12f * density, ringPaint)
        canvas.drawCircle(cx + knobX, cy + knobY, knobR, knobPaint)
        canvas.drawCircle(cx + knobX, cy + knobY, knobR - 1f * density, knobRingPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!active) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                downX = e.x
                downY = e.y
                knobX = 0f
                knobY = 0f
                downTime = SystemClock.uptimeMillis()
                onPress?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(pointerId)
                if (idx >= 0) {
                    val maxR = width / 2f - knobR - 4f * density
                    val rawX = e.getX(idx) - downX
                    val rawY = e.getY(idx) - downY
                    val len = hypot(rawX, rawY)
                    // 摇杆帽位置钳制在盘内（仅绘制用）
                    val kx = if (len > maxR) rawX / len * maxR else rawX
                    val ky = if (len > maxR) rawY / len * maxR else rawY
                    knobX = kx
                    knobY = ky
                    // 上报"归一化偏转"(-1..1)：主循环按偏转持续移动光标，
                    // 推住摇杆帽停在边缘时偏转保持 → 光标持续移动（速度模型，同物理手柄）
                    onMove?.invoke(kx / maxR, ky / maxR)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pointerId != -1) {
                    pointerId = -1
                    val isTap = hypot(knobX, knobY) < 8f * density &&
                        SystemClock.uptimeMillis() - downTime < 260
                    knobX = 0f
                    knobY = 0f
                    invalidate()
                    onRelease?.invoke()
                    if (isTap && e.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
                }
            }
        }
        return true
    }
}
