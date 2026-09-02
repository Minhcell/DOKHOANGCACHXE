package com.hi.khoangcachxe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class DistanceEstimator {
    var vehicleWidthM = 1.8f
    private var d = -1f
    private var v = 0f
    private var p00 = 1f
    private var p11 = 100f
    private var lastT = 0L
    private var outlierCount = 0

    data class Result(val distanceM: Float, val uncertaintyM: Float)

    fun reset() {
        d = -1f
        v = 0f
        p00 = 1f
        p11 = 100f
        lastT = 0L
        outlierCount = 0
    }

    fun update(boxWidthPx: Float, focalPx: Float, nowMs: Long): Result? {
        // Kiểm tra input hợp lệ
        if (boxWidthPx <= 0f || focalPx <= 0f) return null
        if (!boxWidthPx.isFinite() || !focalPx.isFinite()) return null
        
        // Tính khoảng cách từ công thức camera
        val k = vehicleWidthM * focalPx
        if (k <= 0f || !k.isFinite()) return null
        
        val z = k / boxWidthPx
        if (!z.isFinite()) return null
        if (z < 0.2f || z > 250f) return null
        
        // Lần đầu: khởi tạo
        if (d < 0f) {
            d = z
            v = 0f
            p00 = 0.5f
            p11 = 10f
            lastT = nowMs
            outlierCount = 0
            return Result(d, sqrt(p00))
        }
        
        // Tính thời gian trôi
        val dt = ((nowMs - lastT) / 1000f).coerceIn(0.001f, 1f)
        lastT = nowMs
        
        // Dự đoán trạng thái (Kalman predict)
        d = (d + v * dt).coerceIn(0.2f, 250f)
        p00 = p00 + 2f * p11 * dt + 9f * dt * dt
        p11 = p11 + 9f * dt
        
        // Phương sai đo
        val r = (z * z * 0.05f).coerceAtLeast(0.01f)
        val s = p00 + r
        if (s <= 0f || !s.isFinite()) {
            reset()
            return null
        }
        
        // Sai số đo
        val y = z - d
        if (!y.isFinite()) {
            reset()
            return null
        }
        
        // Loại bỏ outlier
        val threshold = 4f * sqrt(s)
        if (abs(y) > threshold) {
            outlierCount++
            if (outlierCount > 10) {
                reset()
                return null
            }
            return Result(d, sqrt(max(p00, 0f)))
        }
        outlierCount = 0
        
        // Kalman update
        val k0 = p00 / s
        val k1 = p11 / s
        d = (d + k0 * y).coerceIn(0.2f, 250f)
        v = (v + k1 * y).coerceIn(-60f, 60f)
        
        p00 = (p00 - k0 * p00).coerceAtLeast(0f)
        p11 = (p11 - k1 * p11).coerceAtLeast(0f)
        
        val unc = sqrt(max(p00, 0f))
        if (!unc.isFinite()) return null
        
        return Result(d, unc)
    }
}
