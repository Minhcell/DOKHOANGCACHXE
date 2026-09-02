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
    var box: Rect? = null
    var alert = false
    private var imgW = 0
    private var imgH = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f }

    fun setImageSize(w: Int, h: Int) { imgW = w; imgH = h }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = box ?: return
        if (imgW <= 0 || imgH <= 0) return
        
        val scale = min(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f
        
        val x1 = r.left * scale + dx
        val y1 = r.top * scale + dy
        val x2 = r.right * scale + dx
        val y2 = r.bottom * scale + dy
        
        paint.color = if (alert) Color.rgb(255, 60, 60) else Color.rgb(76, 217, 100)
        canvas.drawRect(x1, y1, x2, y2, paint)
    }
}
