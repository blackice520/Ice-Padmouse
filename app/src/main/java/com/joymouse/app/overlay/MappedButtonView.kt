package com.joymouse.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.joymouse.app.config.MappedButton
import kotlin.math.hypot
import kotlin.math.min

/**
 * 可自定义的悬浮按键。
 * 普通模式：点击执行动作；长按进入编辑。
 * 编辑模式：拖动改位置，点击打开动作选择面板。
 */
class MappedButtonView(context: Context, val btn: MappedButton, private val controller: OverlayController) :
    View(context) {

    private val density = resources.displayMetrics.density
    private var editing = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var pointerId = -1
    private var downTime = 0L
    /** 按下瞬间的按键位置（百分比坐标），拖动时按"起点+位移"绝对定位，避免累加漂移 */
    private var downBtnX = 0f
    private var downBtnY = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    fun setEditMode(on: Boolean) {
        editing = on
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f - 2f * density
        val cx = width / 2f
        val cy = height / 2f
        fillPaint.color = if (editing) Color.argb(170, 0, 220, 130)
        else Color.argb(120, 20, 20, 20)
        canvas.drawCircle(cx, cy, r, fillPaint)
        borderPaint.color = if (editing) Color.argb(230, 255, 255, 255)
        else Color.argb(140, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, borderPaint)
        textPaint.textSize = min(width, height) * 0.30f
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(btn.label, cx, baseline, textPaint)
        if (editing) {
            textPaint.textSize = 9f * density
            canvas.drawText(btn.action.label, cx, cy + r - 6f * density, textPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                downX = e.x
                downY = e.y
                downBtnX = btn.x
                downBtnY = btn.y
                moved = false
                downTime = SystemClock.uptimeMillis()
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(pointerId)
                if (idx >= 0) {
                    val dx = e.getX(idx) - downX
                    val dy = e.getY(idx) - downY
                    if (hypot(dx, dy) > 6f * density) moved = true
                    if (editing && moved) controller.onEditButtonMoved(this, dx, dy)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pointerId != -1) {
                    pointerId = -1
                    val longPress = !moved && SystemClock.uptimeMillis() - downTime > 550
                    if (editing) {
                        if (!moved) controller.onEditButtonTapped(this)
                    } else {
                        if (!moved) {
                            if (longPress) controller.onEditButtonTapped(this)
                            else controller.execute(btn.action)
                        }
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> pointerId = -1
        }
        return true
    }

    /** 拖动中的即时位置回写（编辑模式）：起点 + 手指位移，绝对定位 */
    fun moveBy(dx: Float, dy: Float) {
        val px = (downBtnX * controller.screenW + dx).coerceIn(0f, controller.screenW.toFloat())
        val py = (downBtnY * controller.screenH + dy).coerceIn(0f, controller.screenH.toFloat())
        btn.x = px / controller.screenW
        btn.y = py / controller.screenH
        controller.refreshButton(this)
    }
}
