package com.hi.khoangcachxe

object SafeDistance {
    fun getMinDistance(speedKmh: Float): Float = when {
        speedKmh < 60 -> 35f
        speedKmh < 80 -> 55f
        speedKmh < 100 -> 70f
        else -> 100f
    }
}
