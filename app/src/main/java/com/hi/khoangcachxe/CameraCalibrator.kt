package com.hi.khoangcachxe

/**
 * Hiệu chỉnh tiêu cự camera từ một vật tham chiếu có kích thước và khoảng cách biết trước.
 *
 * Công thức: f_pixel = (W_thực × w_pixel) / (2 × d × tan(θ))
 *
 * Trong đó:
 *  W_thực  : bề ngang thực của vật tham chiếu (m)
 *  w_pixel : bề ngang vật trên ảnh (pixel)
 *  d       : khoảng cách từ camera tới vật (m)
 *  θ       : nửa góc nhìn ngang của camera (radian)
 *
 * Bộ lọc: loại bỏ kết quả quá sai, làm trơn trên nhiều frame.
 */
class CameraCalibrator {

    private var focalPxSum = 0f
    private var focalPxCount = 0
    private var focalPxSmoothed = 0f

    fun reset() {
        focalPxSum = 0f
        focalPxCount = 0
        focalPxSmoothed = 0f
    }

    /**
     * Tích luỹ một frame, tính tiêu cự từ vật tham chiếu.
     *
     * @param widthM       bề ngang thực của vật tham chiếu (m)
     * @param distanceM    khoảng cách từ camera tới vật (m), ≥ 0.5m
     * @param pixelWidth   bề ngang vật trên ảnh (pixel)
     * @param imageWidth   bề ngang ảnh (pixel)
     * @return tiêu cự ước lượng (pixel), hay -1 nếu dữ liệu xấu
     */
    fun add(widthM: Float, distanceM: Float, pixelWidth: Float, imageWidth: Int): Float {
        if (widthM <= 0.05f || distanceM < 0.5f || pixelWidth < 8f || imageWidth <= 0) return -1f

        // Công thức: f = (W × w) / (2 × d × tan(θ))
        // θ = arctan(W / (2 × d)) là nửa góc nhìn
        val halfAngle = kotlin.math.atan(widthM / (2f * distanceM))
        val focalPx = (widthM * pixelWidth) / (2f * distanceM * kotlin.math.tan(halfAngle))

        if (!focalPx.isFinite() || focalPx < 100f || focalPx > 5000f) return -1f

        // Bộ lọc: loại bỏ giá trị sai lệch quá xa so với trung bình
        if (focalPxCount > 0) {
            val avg = focalPxSum / focalPxCount
            if (kotlin.math.abs(focalPx - avg) > avg * 0.4f) return -1f
        }

        focalPxSum += focalPx
        focalPxCount++
        focalPxSmoothed = if (focalPxSmoothed <= 0f) focalPx
        else focalPxSmoothed * 0.7f + focalPx * 0.3f

        return focalPxSmoothed
    }

    fun getCalibrated(): Float = if (focalPxCount >= 5) focalPxSmoothed else -1f
    fun getCount(): Int = focalPxCount
}
