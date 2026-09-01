package com.hi.khoangcachxe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Vẽ khung bao + nhãn khoảng cách lên trên preview camera.
 *
 * Ngoài ra hỗ trợ "thước kẹp": hai vạch dọc kéo được, dùng để đo vật bất kỳ mà
 * bộ nhận diện không bắt được (bức tường, mép bàn, khung cửa...).
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var box: Rect? = null
    private var imgW = 0
    private var imgH = 0
    private var label = ""
    private var alert = false

    /** Bật thước kẹp thủ công. */
    var caliperEnabled = false
        set(value) { field = value; invalidate() }

    /** Vị trí hai vạch, tính theo toạ độ ảnh (không phải toạ độ view). */
    var caliperX1 = -1f
    var caliperX2 = -1f

    var onTap: ((Float, Float) -> Unit)? = null

    private var dragging = 0   // 0 = không, 1 = vạch trái, 2 = vạch phải

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.rgb(255, 212, 0)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 212, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        isFakeBoldText = true
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
    }

    fun setImageSize(w: Int, h: Int) {
        if (w != imgW || h != imgH) { imgW = w; imgH = h }
    }

    fun setResult(rect: Rect?, imageW: Int, imageH: Int, text: String, isAlert: Boolean) {
        box = rect
        imgW = imageW
        imgH = imageH
        label = text
        alert = isAlert
        postInvalidate()
    }

    /** Bề ngang giữa hai vạch thước kẹp, tính bằng pixel ảnh. */
    fun caliperWidthPx(): Float =
        if (caliperX1 < 0f || caliperX2 < 0f) 0f else abs(caliperX2 - caliperX1)

    fun resetCaliper() {
        caliperX1 = -1f
        caliperX2 = -1f
        invalidate()
    }

    // ------------------------------------------------- quy đổi toạ độ (fitCenter)

    private fun scale(): Float =
        if (imgW <= 0 || imgH <= 0) 1f
        else min(width.toFloat() / imgW, height.toFloat() / imgH)

    private fun offX(): Float = (width - imgW * scale()) / 2f
    private fun offY(): Float = (height - imgH * scale()) / 2f

    private fun toViewX(x: Float) = x * scale() + offX()
    private fun toViewY(y: Float) = y * scale() + offY()
    private fun toImageX(x: Float) = (x - offX()) / scale()
    private fun toImageY(y: Float) = (y - offY()) / scale()

    // ------------------------------------------------------------------- chạm

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imgW <= 0 || imgH <= 0) return false
        val ix = toImageX(event.x).coerceIn(0f, imgW - 1f)

        if (caliperEnabled) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = if (abs(ix - caliperX1) <= abs(ix - caliperX2)) 1 else 2
                    if (dragging == 1) caliperX1 = ix else caliperX2 = ix
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging == 1) caliperX1 = ix
                    if (dragging == 2) caliperX2 = ix
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = 0
                    performClick()
                }
            }
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            onTap?.invoke(ix, toImageY(event.y))
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // ------------------------------------------------------------------- vẽ

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imgW <= 0 || imgH <= 0) return

        if (caliperEnabled) {
            if (caliperX1 < 0f || caliperX2 < 0f) {
                caliperX1 = imgW * 0.40f
                caliperX2 = imgW * 0.60f
            }
            drawCaliper(canvas)
            return
        }

        val r = box ?: return
        val s = scale()
        val v = RectF(
            r.left * s + offX(), r.top * s + offY(),
            r.right * s + offX(), r.bottom * s + offY()
        )

        boxPaint.color = if (alert) Color.rgb(255, 60, 60) else Color.rgb(76, 217, 100)
        canvas.drawRoundRect(v, 10f, 10f, boxPaint)

        if (label.isNotEmpty()) {
            val tw = textPaint.measureText(label)
            val ty = if (v.top > 60f) v.top - 12f else v.bottom + 48f
            canvas.drawRect(v.left - 6f, ty - 42f, v.left + tw + 12f, ty + 12f, bgPaint)
            canvas.drawText(label, v.left, ty, textPaint)
        }
    }

    private fun drawCaliper(canvas: Canvas) {
        val x1 = toViewX(caliperX1)
        val x2 = toViewX(caliperX2)
        val top = toViewY(0f)
        val bot = toViewY(imgH.toFloat())
        val midY = (top + bot) / 2f

        canvas.drawLine(x1, top, x1, bot, linePaint)
        canvas.drawLine(x2, top, x2, bot, linePaint)

        // tay cầm để dễ kéo
        canvas.drawCircle(x1, midY, 26f, fillPaint)
        canvas.drawCircle(x2, midY, 26f, fillPaint)

        // mũi tên hai đầu
        canvas.drawLine(x1, midY, x2, midY, linePaint)
        arrow(canvas, x1, midY, if (x2 > x1) -1f else 1f)
        arrow(canvas, x2, midY, if (x2 > x1) 1f else -1f)

        if (label.isNotEmpty()) {
            val tw = textPaint.measureText(label)
            val cx = (x1 + x2) / 2f - tw / 2f
            val ty = midY - 48f
            canvas.drawRect(cx - 10f, ty - 42f, cx + tw + 10f, ty + 12f, bgPaint)
            canvas.drawText(label, cx, ty, textPaint)
        }
    }

    private fun arrow(canvas: Canvas, x: Float, y: Float, dir: Float) {
        val p = Path()
        p.moveTo(x + 22f * dir, y)
        p.lineTo(x + 22f * dir - 18f * dir, y - 12f)
        p.lineTo(x + 22f * dir - 18f * dir, y + 12f)
        p.close()
        canvas.drawPath(p, fillPaint)
    }
}
