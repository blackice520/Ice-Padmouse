package com.joymouse.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.joymouse.app.config.GamePoint

/**
 * 游戏模式点位标记：编辑时显示在屏幕上的可拖动圆点。
 * 拖动改位置（绝对定位，无累加漂移）；长按弹出按键绑定设置；普通模式下不显示。
 */
class GamePointView(context: Context, val point: GamePoint, private val controller: OverlayController) :
    View(context) {

    private val density = resources.displayMetrics.density
    private var downRawX = 0f
    private var downRawY = 0f
    private var downPtX = 0f
    private var downPtY = 0f
    private var pointerId = -1
    private var downTime = 0L
    private var moved = false

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        // 透明度由配置控制（0~100%，默认 80%；0=完全透明但仍可拖动）
        val alpha = (255 * controller.gamePointOpacity() / 100).coerceIn(0, 255)
        fillPaint.color = Color.argb(alpha, 255, 87, 34)
        borderPaint.alpha = alpha
        textPaint.alpha = alpha
        canvas.drawCircle(r, r, r - 1f * density, fillPaint)
        canvas.drawCircle(r, r, r - 1f * density, borderPaint)
        val baseline = r - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(point.label, r, baseline, textPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = e.getPointerId(0)
                // 用屏幕坐标 rawX/rawY：移动窗口会改变局部坐标映射，
                // 局部坐标算位移会形成"位置振荡"（拖动时不停晃动）
                downRawX = e.rawX
                downRawY = e.rawY
                downPtX = point.x
                downPtY = point.y
                downTime = SystemClock.uptimeMillis()
                moved = false
                controller.onOurTouch(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(pointerId)
                if (idx >= 0) {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (kotlin.math.hypot(dx, dy) > 4f * density) {
                        moved = true
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
                    // 长按（未拖动、按住 >550ms）：弹出按键绑定设置
                    if (!moved && SystemClock.uptimeMillis() - downTime > 550) {
                        controller.onGamePointLongPressed(point.id)
                    } else {
                        controller.saveConfig()
                    }
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
