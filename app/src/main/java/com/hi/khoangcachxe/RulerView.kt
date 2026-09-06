package com.hi.khoangcachxe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class RulerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {
    var distance = 30f
    var currentSpeed = 80
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f
        color = Color.WHITE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawRuler(canvas)
        drawAlert(canvas)
    }

    private fun drawRuler(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val margins = 150f
        val rulerTop = h - margins
        val rulerHeight = h - margins - 50f
        val pixelPerMeter = rulerHeight / 110f
        
        val minDistance = getMinDistance(currentSpeed)
        val mainMarks = listOf(35, 55, 70, 100)
        
        // Vạch
        for (i in 0..110 step 5) {
            val y = rulerTop - i * pixelPerMeter
            val isMain = mainMarks.contains(i)
            val isMin = i == minDistance
            
            when {
                isMin -> {
                    paint.strokeWidth = 4f
                    paint.color = Color.rgb(255, 68, 68)
                }
                isMain -> {
                    paint.strokeWidth = 3f
                    paint.color = Color.rgb(255, 170, 0)
                }
                else -> {
                    paint.strokeWidth = 1f
                    paint.color = Color.rgb(68, 68, 68)
                    paint.alpha = 150
                }
            }
            
            val len = if (isMain) 60f else if (i % 10 == 0) 40f else 20f
            canvas.drawLine(w / 2 - len / 2, y, w / 2 + len / 2, y, paint)
            
            if (i % 10 == 0 || isMain) {
                paint.color = if (isMin) Color.rgb(255, 68, 68) else Color.rgb(255, 170, 0)
                textPaint.color = paint.color
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("${i}m", w / 2 - len / 2 - 10, y + 4, textPaint)
            }
            paint.alpha = 255
        }
        
        // Xe
        val carY = rulerTop - distance * pixelPerMeter
        if (carY >= 0 && carY <= h) {
            val carW = 80f
            val carH = 120f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = if (distance < minDistance) Color.rgb(255, 68, 68) else Color.rgb(0, 255, 0)
            canvas.drawRect(w / 2 - carW / 2, carY - carH / 2, w / 2 + carW / 2, carY + carH / 2, paint)
            
            textPaint.color = paint.color
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 20f
            canvas.drawText("%.1fm".format(distance), w / 2, carY - carH / 2 - 30, textPaint)
        }
        
        // Dòng 0m
        paint.color = Color.rgb(0, 255, 0)
        paint.strokeWidth = 2f
        paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 5f), 0f)
        canvas.drawLine(0f, rulerTop, w, rulerTop, paint)
        paint.pathEffect = null
        
        textPaint.color = Color.rgb(0, 255, 0)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 14f
        canvas.drawText("0m (Xe phía trước)", 20f, rulerTop + 25, textPaint)
    }

    private fun drawAlert(canvas: Canvas) {
        val minDistance = getMinDistance(currentSpeed)
        if (distance < minDistance) {
            val w = width.toFloat()
            val h = height.toFloat()
            
            paint.color = Color.argb(50, 255, 0, 0)
            canvas.drawRect(0f, 0f, w, h, paint)
            
            textPaint.color = Color.rgb(255, 68, 68)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 40f
            canvas.drawText("⚠️ VI PHẠM!", w / 2, 80f, textPaint)
            
            textPaint.textSize = 24f
            canvas.drawText("Tối thiểu ${minDistance}m @ ${currentSpeed}km/h", w / 2, 130f, textPaint)
        }
    }

    private fun getMinDistance(speed: Int): Int = when {
        speed < 60 -> 35
        speed < 80 -> 55
        speed < 100 -> 70
        else -> 100
    }
}
