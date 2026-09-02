package com.hi.khoangcachxe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {
    private var box: Rect? = null
    private var imgW = 0
    private var imgH = 0
    private var label = ""
    private var alert = false

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; isFakeBoldText = true }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0) }

    fun setImageSize(w: Int, h: Int) { if (w != imgW || h != imgH) { imgW = w; imgH = h } }
    fun setResult(rect: Rect?, imageW: Int, imageH: Int, text: String, isAlert: Boolean) {
        box = rect; imgW = imageW; imgH = imageH; label = text; alert = isAlert; postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = box ?: return
        if (imgW <= 0 || imgH <= 0) return
        val scale = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f
        val v = RectF(r.left * scale + dx, r.top * scale + dy, r.right * scale + dx, r.bottom * scale + dy)
        boxPaint.color = if (alert) Color.rgb(255, 60, 60) else Color.rgb(76, 217, 100)
        canvas.drawRoundRect(v, 10f, 10f, boxPaint)
        if (label.isNotEmpty()) {
            val tw = textPaint.measureText(label)
            val ty = if (v.top > 60f) v.top - 12f else v.bottom + 48f
            canvas.drawRect(v.left - 6f, ty - 42f, v.left + tw + 12f, ty + 12f, bgPaint)
            canvas.drawText(label, v.left, ty, textPaint)
        }
    }
}
