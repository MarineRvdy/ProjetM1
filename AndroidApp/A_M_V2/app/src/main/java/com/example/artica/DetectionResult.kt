data class DetectionResult(
    val x: Float,        // 0..1
    val y: Float,        // 0..1
    val width: Float,    // 0..1
    val height: Float,   // 0..1
    val score: Float,
    val label: String
)
