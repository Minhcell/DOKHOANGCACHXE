package com.hi.khoangcachxe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class DistanceEstimator {
    var vehicleWidthM = 1.8f
    var calibration = 1.0f
    private var d = -1f
    private var v = 0f
    private var p00 = 0f
    private var p01 = 0f
    private var p10 = 0f
    private var p11 = 0f
    private var lastT = 0L

    data class Result(val distanceM: Float, val closingMs: Float, val ttcS: Float, val uncertaintyM: Float)

    fun reset() { d = -1f; v = 0f; p00 = 0f; p01 = 0f; p10 = 0f; p11 = 0f; lastT = 0L }

    fun update(boxWidthPx: Float, focalPx: Float, nowMs: Long): Result? {
        if (boxWidthPx < 6f || focalPx <= 0f) return null
        val k = vehicleWidthM * focalPx * calibration
        val z = k / boxWidthPx
        if (!z.isFinite() || z < 0.25f || z > 250f) return null
        val sigma = max(z * z * 1.6f / k, 0.15f)
        val r = sigma * sigma
        if (d <= 0f) { d = z; v = 0f; p00 = r; p01 = 0f; p10 = 0f; p11 = 100f; lastT = nowMs
            return Result(d, 0f, -1f, sqrt(p00))
        }
        val dt = ((nowMs - lastT) / 1000f).coerceIn(0.01f, 0.5f); lastT = nowMs
        d += v * dt
        val q = 9f; val dt2 = dt * dt; val dt3 = dt2 * dt; val dt4 = dt2 * dt2
        p00 = p00 + dt * (p01 + p10) + dt2 * p11 + q * dt4 / 4f
        p01 = p01 + dt * p11 + q * dt3 / 2f
        p10 = p10 + dt * p11 + q * dt3 / 2f
        p11 = p11 + q * dt2
        val y = z - d; val s = p00 + r
        if (abs(y) > 4f * sqrt(s)) return Result(d, -v, -1f, sqrt(p00))
        val k0 = p00 / s; val k1 = p10 / s
        d += k0 * y; v += k1 * y
        p00 -= k0 * p00; p01 -= k0 * p01; p10 -= k1 * p00; p11 -= k1 * p01
        v = v.coerceIn(-60f, 60f); d = d.coerceIn(0.2f, 250f)
        val closing = -v
        val ttc = if (closing > 0.3f) d / closing else -1f
        return Result(d, closing, ttc, sqrt(max(p00, 0f)))
    }
}
