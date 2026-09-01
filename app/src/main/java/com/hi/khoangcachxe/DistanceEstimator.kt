package com.hi.khoangcachxe

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Ước lượng khoảng cách + tốc độ tương đối bằng bộ lọc Kalman 2 trạng thái [d, ḋ].
 *
 * Vì sao không dùng trung bình trượt (EMA):
 *  - Khi cả hai xe đang chạy, khoảng cách thay đổi liên tục. EMA luôn bám trễ
 *    sau giá trị thật, càng làm mượt thì càng trễ -> số hiển thị sai ngay lúc
 *    cần nhất (đang tiến gần nhanh).
 *  - Kalman có trạng thái vận tốc nên nó *dự đoán* trước rồi mới hiệu chỉnh
 *    bằng số đo, gần như không trễ mà vẫn khử được nhiễu.
 *
 * Nhiễu đo được mô hình theo đúng vật lý: sai số 1 pixel ở khung bao gây sai số
 *      σ(d) = d² · σ_pixel / (W_thực · f_pixel)
 * nghĩa là ở 20 m sai vài chục cm, ở 110 m sai vài mét. Bộ lọc tự động tin số đo
 * nhiều khi xe gần và tin dự đoán nhiều khi xe xa.
 */
class DistanceEstimator {

    var vehicleWidthM: Float = 1.8f
    var calibration: Float = 1.0f

    /** Nhiễu đo của bề ngang khung bao, tính bằng pixel. */
    var pixelNoise: Float = 1.6f

    /** Gia tốc tương đối tối đa giả định giữa hai xe (m/s²) - nhiễu quá trình. */
    var processAccel: Float = 3.0f

    private var d = -1f          // khoảng cách (m)
    private var v = 0f           // ḋ (m/s); âm = đang tiến gần
    private var p00 = 0f
    private var p01 = 0f
    private var p10 = 0f
    private var p11 = 0f
    private var lastT = 0L
    private var outliers = 0

    data class Result(
        val distanceM: Float,
        val closingMs: Float,      // > 0 = đang tiến gần
        val ttcS: Float,           // thời gian tới va chạm, -1 nếu không tiến gần
        val uncertaintyM: Float,   // sai số ước lượng (± m)
        val rawM: Float            // số đo thô của khung hình này
    )

    fun reset() {
        d = -1f; v = 0f
        p00 = 0f; p01 = 0f; p10 = 0f; p11 = 0f
        lastT = 0L; outliers = 0
    }

    fun isTracking() = d > 0f

    /** Hệ số k trong công thức d = k / w_pixel. */
    fun scaleK(focalPx: Float) = vehicleWidthM * focalPx * calibration

    fun update(boxWidthPx: Float, focalPx: Float, nowMs: Long): Result? {
        if (boxWidthPx < 6f || focalPx <= 0f) return null

        val k = scaleK(focalPx)
        val z = k / boxWidthPx
        if (!z.isFinite() || z < 0.25f || z > 250f) return null

        // sai số đo quy đổi từ 1 pixel sang mét, tại khoảng cách này
        val sigma = max(z * z * pixelNoise / k, 0.15f)
        val r = sigma * sigma

        if (d <= 0f) {                       // khởi tạo
            d = z; v = 0f
            p00 = r; p01 = 0f; p10 = 0f; p11 = 100f
            lastT = nowMs; outliers = 0
            return Result(d, 0f, -1f, sqrt(p00), z)
        }

        val dt = ((nowMs - lastT) / 1000f).coerceIn(0.01f, 0.5f)
        lastT = nowMs

        // --- dự đoán ---
        d += v * dt
        val q = processAccel * processAccel
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt2 * dt2
        val n00 = p00 + dt * (p01 + p10) + dt2 * p11 + q * dt4 / 4f
        val n01 = p01 + dt * p11 + q * dt3 / 2f
        val n10 = p10 + dt * p11 + q * dt3 / 2f
        val n11 = p11 + q * dt2
        p00 = n00; p01 = n01; p10 = n10; p11 = n11

        // --- hiệu chỉnh ---
        val y = z - d
        val s = p00 + r
        if (abs(y) > 4f * sqrt(s)) {         // số đo bất thường (bắt nhầm vật khác)
            outliers++
            if (outliers > 10) { reset(); return null }
            return Result(d, -v, ttc(), sqrt(p00), z)
        }
        outliers = 0

        val k0 = p00 / s
        val k1 = p10 / s
        d += k0 * y
        v += k1 * y
        val m00 = p00; val m01 = p01
        p00 -= k0 * m00
        p01 -= k0 * m01
        p10 -= k1 * m00
        p11 -= k1 * m01

        v = v.coerceIn(-60f, 60f)
        d = d.coerceIn(0.2f, 250f)

        return Result(d, -v, ttc(), sqrt(max(p00, 0f)), z)
    }

    private fun ttc(): Float {
        val closing = -v
        return if (closing > 0.3f) d / closing else -1f
    }
}
