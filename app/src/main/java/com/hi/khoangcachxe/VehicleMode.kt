package com.hi.khoangcachxe

/**
 * Quy định khoảng cách an toàn giữa các xe trên cao tốc theo tốc độ,
 * theo Luật Giao thông Đường bộ Việt Nam.
 */
object SafeDistance {
    
    /**
     * Tính khoảng cách an toàn tối thiểu (mét) theo tốc độ (km/h).
     * 
     * Quy định:
     * - Tốc độ < 60 km/h: 35 m
     * - 60–80 km/h: 55 m
     * - 80–100 km/h: 70 m
     * - 100–120 km/h: 100 m
     */
    fun getMinDistance(speedKmh: Float): Float {
        return when {
            speedKmh < 60 -> 35f
            speedKmh < 80 -> 55f
            speedKmh < 100 -> 70f
            speedKmh < 120 -> 100f
            else -> 110f  // mặc định tầm đo tối đa
        }
    }

    /**
     * Kiểm tra xe có vi phạm khoảng cách an toàn hay không.
     */
    fun isViolation(distanceM: Float, speedKmh: Float): Boolean {
        return distanceM < getMinDistance(speedKmh)
    }

    /**
     * Lấy mô tả trạng thái khoảng cách.
     */
    fun getStatus(distanceM: Float, speedKmh: Float): String {
        val minDist = getMinDistance(speedKmh)
        return when {
            distanceM < minDist * 0.8f -> "⚠️ NGUY HIỂM"
            distanceM < minDist -> "⚠️ CẢNH BÁO"
            else -> "✓ AN TOÀN"
        }
    }
}
