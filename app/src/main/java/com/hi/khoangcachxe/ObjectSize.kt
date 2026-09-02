package com.hi.khoangcachxe

object ObjectSize {
    private val sizes = mapOf(
        "person" to 0.45f, "car" to 1.80f, "truck" to 2.50f, "bus" to 2.50f,
        "bicycle" to 0.60f, "motorbike" to 0.75f, "dog" to 0.55f, "cat" to 0.40f,
        "chair" to 0.45f, "sofa" to 2.00f, "bed" to 1.40f, "diningtable" to 1.20f,
        "laptop" to 0.35f, "tvmonitor" to 1.20f, "backpack" to 0.35f, "handbag" to 0.35f,
        "suitcase" to 0.70f, "keyboard" to 0.45f, "microwave" to 0.60f, "oven" to 0.70f,
        "sink" to 0.90f, "refrigerator" to 0.80f, "umbrella" to 1.00f, "bottle" to 0.25f
    )
    fun getWidth(label: String): Float = sizes[label.lowercase().trim()] ?: 0.50f
}
