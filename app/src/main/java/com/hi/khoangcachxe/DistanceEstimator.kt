package com.hi.khoangcachxe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class DistanceEstimator {
    var vehicleWidthM = 1.8f
    private var d = -1f
    private var v = 0f
    private var p00 = 0f
    private var p11 = 0f
    private var lastT = 0L

    data class Result(val distanceM: Float, val uncertaintyM: Float)

    fun reset() { d = -1f; v = 0f; p00 = 0f; p11 = 0f; lastT = 0L }

    fun update(boxWidthPx: Float, focalPx: Float, nowMs: Long): Result? {
        if (boxWidthPx < 6f || focalPx <= 0f) return null
        val k = vehicleWidthM * focalPx
        val z = k / boxWidthPx
        if (!z.isFinite() || z < 0.25f || z > 250f) return null
        
        if (d <= 0f) {
            d = z
            v = 0f
            p00 = z * z * 0.1f
            p11 = 100f
            lastT = nowMs
            return Result(d, sqrt(p00))
        }
        
        val dt = ((nowMs - lastT) / 1000f).coerceIn(0.01f, 0.5f)
        lastT = nowMs
        
        d += v * dt
        p00 = p00 + 2f * p11 * dt + 9f * dt * dt
        p11 = p11 + 9f * dt
        
        val r = z * z * 0.1f
        val y = z - d
        val s = p00 + r
        
        if (abs(y) > 4f * sqrt(s)) return Result(d, sqrt(p00))
        
        val k0 = p00 / s
        val k1 = p11 / s
        d += k0 * y
        v += k1 * y
        
        p00 -= k0 * p00
        p11 -= k1 * p11
        
        v = v.coerceIn(-60f, 60f)
        d = d.coerceIn(0.2f, 250f)
        
        return Result(d, sqrt(max(p00, 0f)))
    }
}
