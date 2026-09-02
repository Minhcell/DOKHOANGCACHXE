package com.hi.khoangcachxe

object ObjectSize {
    fun getWidth(label: String): Float = when (label.lowercase().trim()) {
        "person" -> 0.45f
        "car" -> 1.80f
        "truck" -> 2.50f
        "bus" -> 2.50f
        "bicycle" -> 0.60f
        "motorbike" -> 0.75f
        "dog" -> 0.55f
        "cat" -> 0.40f
        "chair" -> 0.45f
        "sofa" -> 2.00f
        "bed" -> 1.40f
        "tvmonitor" -> 1.20f
        "laptop" -> 0.35f
        "backpack" -> 0.35f
        "handbag" -> 0.35f
        "bottle" -> 0.25f
        else -> 0.50f
    }
}
