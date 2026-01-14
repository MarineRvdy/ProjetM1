data class DetectionResult(
    val boxes: Array<FloatArray>,   // [10][4]
    val scores: FloatArray,         // [10]
    val classes: FloatArray,        // [10]
    val count: Int
)
