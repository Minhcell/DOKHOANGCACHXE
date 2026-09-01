package com.hi.khoangcachxe

import kotlin.math.sqrt

/**
 * Bám khoảng cách tới xe phía trước bằng bộ lọc Kalman 2 trạng thái [d, v].
 *
 *  d : khoảng cách (m)
 *  v : tốc độ biến thiên khoảng cách (m/s). v < 0 nghĩa là đang tiến gần.
 *
 * Số đo thô lấy từ mô hình camera lỗ kim:
 *      z = (bề_ngang_xe × tiêu_cự_pixel) / bề_ngang_khung_bao_pixel
 *
 * Điểm mấu chốt khi hai xe cùng chạy: sai số của z KHÔNG cố định mà tăng theo
 * bình phương khoảng cách. Nhiễu khung bao chỉ vài pixel nhưng ở 100 m nó gây
 * lệch hàng mét, còn ở 10 m gần như không đáng kể. Vì vậy phương sai đo R được
 * tính lại theo từng khung hình:
 *      sigma_d = d × sigma_pixel / bề_ngang_khung_bao_pixel
 *
 * Nhờ đó bộ lọc tự động tin số đo khi xe ở gần (bám nhanh, không trễ) và tự
 * động làm mượt mạnh khi xe ở xa (không nhảy số).
 */
class DistanceTracker {

    /** Bề ngang thực tế của xe phía trước (m). */
    var vehicleWidthM: Float = 1.8f

    /** Hệ số hiệu chỉnh thủ công. */
    var calibration: Float = 1.0f

    /** Tầm đo tối đa hiển thị (m). */
    var maxRangeM: Float = 110f

    /** Gia tốc tương đối giữa hai xe có thể xảy ra (m/s^2) — chi phối độ nhạy. */
    private val sigmaAccel = 3.5f

    /** Nhiễu bề ngang khung bao do bộ nhận diện (pixel). */
    private val sigmaBoxPx = 2.0f

    private var d = 0f
    private var v = 0f
    private var p00 = 0f
    private var p01 = 0f
    private var p11 = 0f

    private var lastT = 0L
    private var inited = false
    private var outliers = 0
    private var trackId = -1

    data class Result(
        val distanceM: Float,
        val closingMs: Float,   // > 0 = đang tiến gần
        val ttcS: Float,        // giây, -1 nếu không tiến gần
        val errorM: Float,      // sai số ước lượng ±
        val inRange: Boolean
    )

    /** Gọi mỗi khung hình. Nếu bám sang xe khác thì khởi tạo lại bộ lọc. */
    fun onTarget(id: Int) {
        if (id != trackId) {
            trackId = id
            inited = false
            outliers = 0
        }
    }

    fun reset() {
        inited = false
        outliers = 0
        trackId = -1
    }

    /** Bề ngang khung bao nhỏ nhất còn nằm trong tầm đo. */
    fun minBoxWidthPx(focalPx: Float): Float =
        (vehicleWidthM * focalPx * calibration / maxRangeM) * 0.85f

    fun update(boxWidthPx: Float, focalPx: Float, nowMs: Long): Result? {
        if (boxWidthPx < 1f || focalPx <= 0f) return null

        val z = vehicleWidthM * focalPx / boxWidthPx * calibration
        if (!z.isFinite() || z <= 0.3f || z > maxRangeM * 2f) return null

        // Phương sai số đo, phụ thuộc khoảng cách
        val rStd = (z * sigmaBoxPx / boxWidthPx).coerceAtLeast(0.10f)
        val r = rStd * rStd

        if (!inited) {
            initFilter(z, r, nowMs)
            return build(r)
        }

        var dt = (nowMs - lastT) / 1000f
        if (dt <= 0f) dt = 0.001f
        if (dt > 1.0f) {                       // mất dấu quá lâu
            initFilter(z, r, nowMs)
            return build(r)
        }
        lastT = nowMs

        // --- Dự đoán (mô hình vận tốc không đổi) ---
        d += v * dt
        val sa2 = sigmaAccel * sigmaAccel
        val q00 = sa2 * dt * dt * dt * dt / 4f
        val q01 = sa2 * dt * dt * dt / 2f
        val q11 = sa2 * dt * dt

        val n00 = p00 + 2f * dt * p01 + dt * dt * p11 + q00
        val n01 = p01 + dt * p11 + q01
        val n11 = p11 + q11
        p00 = n00; p01 = n01; p11 = n11

        // --- Loại số đo bất thường (bám nhầm vật khác trong 1-2 khung) ---
        val s = p00 + r
        val y = z - d
        if (y * y > 16f * s) {
            outliers++
            if (outliers > 8) initFilter(z, r, nowMs)
            return build(r)
        }
        outliers = 0

        // --- Hiệu chỉnh ---
        val k0 = p00 / s
        val k1 = p01 / s
        d += k0 * y
        v += k1 * y

        val o00 = p00
        val o01 = p01
        p00 = (1f - k0) * o00
        p01 = (1f - k0) * o01
        p11 -= k1 * o01

        if (d < 0.3f) d = 0.3f
        return build(r)
    }

    private fun initFilter(z: Float, r: Float, nowMs: Long) {
        d = z
        v = 0f
        p00 = r
        p01 = 0f
        p11 = 100f
        lastT = nowMs
        inited = true
        outliers = 0
    }

    private fun build(r: Float): Result {
        val closing = -v
        val ttc = if (closing > 0.3f) d / closing else -1f
        val err = sqrt((p00 + r).coerceAtLeast(0f))
        return Result(d, closing, ttc, err, d <= maxRangeM)
    }
}
