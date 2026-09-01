package com.hi.khoangcachxe

/**
 * Bề ngang tiêu chuẩn của các vật thể phổ biến, theo kết quả nhận diện từ ML Kit.
 * Dùng để ước lượng khoảng cách khi chỉ biết loại vật, không cần nhập tay.
 */
object ObjectSize {

    private val sizes = mapOf(
        // Loại vật thể -> bề ngang tiêu chuẩn (mét)
        // Từ thứ tự: laptop, teddy bear, pizza, backpack, handbag, suitcase, frisbee,
        // skateboard, surfboard, tennis racket, bottle, wine glass, cup, fork, knife,
        // spoon, bowl, banana, apple, sandwich, orange, broccoli, carrot, hot dog, pizza,
        // donut, cake, chair, sofa, pottedplant, bed, diningtable, toilet, tvmonitor,
        // laptop, mouse, remote, keyboard, microwave, oven, toaster, sink, refrigerator,
        // book, clock, vase, scissors, teddy bear, hair drier, toothbrush, hair brush

        // Danh sách COCO 80 class
        "person" to 0.45f,           // người: khoảng 45 cm vai
        "bicycle" to 0.60f,
        "car" to 1.80f,              // xe con
        "motorbike" to 0.75f,
        "aeroplane" to 35f,
        "bus" to 2.50f,
        "train" to 2.70f,
        "truck" to 2.50f,
        "boat" to 5f,
        "traffic light" to 0.30f,
        "fire hydrant" to 0.70f,
        "stop sign" to 0.75f,
        "parking meter" to 0.50f,
        "bench" to 1.50f,
        "cat" to 0.40f,
        "dog" to 0.55f,
        "horse" to 1.50f,
        "sheep" to 1.20f,
        "cow" to 1.80f,
        "elephant" to 2.50f,
        "bear" to 1.50f,
        "zebra" to 1.50f,
        "giraffe" to 2.50f,
        "backpack" to 0.35f,
        "umbrella" to 1.00f,
        "handbag" to 0.35f,
        "tie" to 0.08f,
        "suitcase" to 0.70f,
        "frisbee" to 0.25f,
        "skis" to 1.70f,
        "snowboard" to 1.60f,
        "sports ball" to 0.24f,
        "kite" to 1.20f,
        "baseball bat" to 0.85f,
        "baseball glove" to 0.30f,
        "skateboard" to 0.80f,
        "surfboard" to 2.00f,
        "tennis racket" to 0.70f,
        "bottle" to 0.25f,
        "wine glass" to 0.12f,
        "cup" to 0.10f,
        "fork" to 0.20f,
        "knife" to 0.25f,
        "spoon" to 0.20f,
        "bowl" to 0.25f,
        "banana" to 0.20f,
        "apple" to 0.08f,
        "sandwich" to 0.15f,
        "orange" to 0.08f,
        "broccoli" to 0.25f,
        "carrot" to 0.20f,
        "hot dog" to 0.15f,
        "pizza" to 0.30f,
        "donut" to 0.08f,
        "cake" to 0.30f,
        "chair" to 0.45f,
        "sofa" to 2.00f,
        "pottedplant" to 0.40f,
        "bed" to 1.40f,
        "diningtable" to 1.20f,
        "toilet" to 0.50f,
        "tvmonitor" to 1.20f,
        "laptop" to 0.35f,
        "mouse" to 0.08f,
        "remote" to 0.15f,
        "keyboard" to 0.45f,
        "microwave" to 0.60f,
        "oven" to 0.70f,
        "toaster" to 0.30f,
        "sink" to 0.90f,
        "refrigerator" to 0.80f,
        "book" to 0.25f,
        "clock" to 0.30f,
        "vase" to 0.30f,
        "scissors" to 0.20f,
        "teddy bear" to 0.35f,
        "hair drier" to 0.25f,
        "toothbrush" to 0.20f
    )

    fun getWidth(label: String): Float {
        val norm = label.lowercase().trim()
        return sizes[norm] ?: 0.50f  // mặc định 50 cm nếu không biết
    }

    fun hasLabel(label: String): Boolean = sizes.containsKey(label.lowercase().trim())
}
