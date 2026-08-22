package com.joymouse.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.joymouse.app.config.GamePoint
import kotlin.math.hypot

/**
 * 游戏模式点位标记：编辑时显示在屏幕上的可拖动圆点。
 * 拖动改位置（绝对定位，无累加漂移）；普通模式下不显示。
 */
class GamePointView(context: Context, val point: GamePoint, private val controller: OverlayController) :
    View(context) {

    private val density = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var downPtX = 0f
    private var downPtY = 0f
    private var pointerId = -1

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 87, 34) // deep orange
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 13f * density
    }

    override fun onDraw(canvas: Canvas) {
        val r = width / 2f
        canvas.drawCircle(r, r, r - 1f * density, fillPaint)
        canvas.drawCircle(r, r, r - 1f * density, borderPaint)
        val baseline = r - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(point.label, r, baseline, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                downX = e.x
                downY = e.y
                downPtX = point.x
                downPtY = point.y
                controller.onOurTouch(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(pointerId)
                if (idx >= 0) {
                    val dx = e.getX(idx) - downX
                    val dy = e.getY(idx) - downY
                    if (hypot(dx, dy) > 4f * density) {
                        point.x = (downPtX * controller.screenW + dx).coerceIn(0f, controller.screenW.toFloat()) / controller.screenW
                        point.y = (downPtY * controller.screenH + dy).coerceIn(0f, controller.screenH.toFloat()) / controller.screenH
                        controller.refreshGamePoint(this)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pointerId != -1) {
                    pointerId = -1
                    controller.onOurTouch(false)
                    controller.saveConfig()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pointerId != -1) {
                    pointerId = -1
                    controller.onOurTouch(false)
                }
            }
        }
        return true
    }
}
