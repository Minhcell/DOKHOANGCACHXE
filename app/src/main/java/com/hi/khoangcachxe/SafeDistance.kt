package com.hi.khoangcachxe

object SafeDistance {
    fun getMinDistance(speedKmh: Float): Float = when {
        speedKmh < 60 -> 35f
        speedKmh < 80 -> 55f
        speedKmh < 100 -> 70f
        speedKmh < 120 -> 100f
        else -> 110f
    }
    fun isViolation(distanceM: Float, speedKmh: Float): Boolean = distanceM < getMinDistance(speedKmh)
}
