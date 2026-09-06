package com.hi.khoangcachxe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var box: Rect? = null
    var alert = false
    var distance = 0f
    var currentSpeed = 80
    var rulerDistance = 0f  // Khoảng cách từ ruler (chuẩn)
    var confidencePercent = 0  // Độ tin cậy
    
    private var imgW = 0
    private var imgH = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; color = Color.WHITE }

    fun setImageSize(w: Int, h: Int) { imgW = w; imgH = h }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        drawRuler(canvas)
        drawBoxAndInfo(canvas)
        if (alert) drawAlert(canvas)
    }

    private fun drawBoxAndInfo(canvas: Canvas) {
        val r = box ?: return
        if (imgW <= 0 || imgH <= 0) return
        
        val scale = kotlin.math.min(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2f
        val dy = (height - imgH * scale) / 2f
        
        val x1 = r.left * scale + dx
        val y1 = r.top * scale + dy
        val x2 = r.right * scale + dx
        val y2 = r.bottom * scale + dy
        
        // Khung bao xe
        paint.color = if (alert) Color.rgb(255, 60, 60) else Color.rgb(76, 217, 100)
        canvas.drawRect(x1, y1, x2, y2, paint)
        
        // Hiển thị khoảng cách từ khung bao
        textPaint.color = paint.color
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 16f
        canvas.drawText("%.1fm (khung)".format(distance), (x1 + x2) / 2, y1 - 15, textPaint)
        
        // Độ tin cậy khoảng cách
        textPaint.textSize = 12f
        canvas.drawText("Tin cay: ${confidencePercent}%", (x1 + x2) / 2, y1 - 35, textPaint)
    }

    private fun drawRuler(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val margins = 150f
        val rulerTop = h - margins
        val rulerHeight = h - margins - 50f
        val pixelPerMeter = if (rulerHeight > 0) rulerHeight / 110f else 1f
        
        val minDist = getMinDist(currentSpeed)
        
        // ===== RULER - HIỂN THỊ CHÍNH =====
        // Vạch từng 5m
        for (i in 0..110 step 5) {
            val y = rulerTop - i * pixelPerMeter
            val isMain = i in listOf(35, 55, 70, 100)
            val isMin = i == minDist
            val isRulerDist = kotlin.math.abs(i - rulerDistance) < 3  // Trong 3m
            
            paint.style = Paint.Style.STROKE
            when {
                isRulerDist -> {
                    // Vạch hiện tại từ RULER - màu XANH đậm
                    paint.strokeWidth = 6f
                    paint.color = Color.rgb(0, 200, 255)
                }
                isMin -> {
                    // Vạch tối thiểu - màu ĐỎ
                    paint.strokeWidth = 4f
                    paint.color = Color.rgb(255, 68, 68)
                }
                isMain -> {
                    // Vạch chính - màu VÀNG
                    paint.strokeWidth = 3f
                    paint.color = Color.rgb(255, 170, 0)
                }
                else -> {
                    // Vạch phụ - màu XÁM
                    paint.strokeWidth = 1f
                    paint.color = Color.rgb(68, 68, 68)
                }
            }
            
            val len = if (isRulerDist) 80f else if (isMain) 60f else if (i % 10 == 0) 40f else 20f
            canvas.drawLine(w / 2 - len / 2, y, w / 2 + len / 2, y, paint)
            
            if (i % 10 == 0 || isMain || isRulerDist) {
                textPaint.color = paint.color
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.textSize = if (isRulerDist) 16f else 14f
                canvas.drawText("${i}m", w / 2 - len / 2 - 10, y + 4, textPaint)
            }
        }
        
        // Hiển thị khoảng cách RULER (từ taplo camera)
        val rulerY = rulerTop - rulerDistance * pixelPerMeter
        textPaint.color = Color.rgb(0, 200, 255)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 20f
        canvas.drawText("RULER: %.1fm".format(rulerDistance), w / 2, rulerY - 50, textPaint)
        
        // Dòng 0m (xe phía trước)
        paint.color = Color.rgb(0, 255, 0)
        paint.strokeWidth = 2f
        canvas.drawLine(0f, rulerTop, w, rulerTop, paint)
        
        textPaint.color = Color.rgb(0, 255, 0)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 14f
        canvas.drawText("0m (Xe phía trước)", 20f, rulerTop + 25, textPaint)
        
        // Chú thích
        textPaint.color = Color.rgb(200, 200, 200)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 12f
        canvas.drawText("RULER (xanh) = chính xác | KHUNG (xanh/đỏ) = xác nhận", 20f, 30f, textPaint)
    }

    private fun drawAlert(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        
        paint.color = Color.argb(50, 255, 0, 0)
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, w, h, paint)
        
        textPaint.color = Color.rgb(255, 68, 68)
        textPaint.textSize = 40f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("VI PHAM!", w / 2, 80f, textPaint)
    }

    private fun getMinDist(speed: Int) = when {
        speed < 60 -> 35
        speed < 80 -> 55
        speed < 100 -> 70
        else -> 100
    }
}
