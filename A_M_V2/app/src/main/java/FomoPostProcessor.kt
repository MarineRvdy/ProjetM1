object FomoPostProcessor {

    private const val GRID_SIZE = 12
    private const val CELL_SIZE = 1f / GRID_SIZE

    fun decode(
        output: Array<Array<FloatArray>>,
        threshold: Float
    ): List<DetectionResult> {

        val detections = mutableListOf<DetectionResult>()

        for (y in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE) {

                // Classe 0 = background
                // Classe 1..N = objets
                for (c in 1 until output[y][x].size) {

                    val score = output[y][x][c]
                    if (score > threshold) {
                        detections.add(
                            DetectionResult(
                                x = x * CELL_SIZE,
                                y = y * CELL_SIZE,
                                width = CELL_SIZE,
                                height = CELL_SIZE,
                                score = score,
                                label = "class_$c"
                            )
                        )
                    }
                }
            }
        }
        return detections
    }
}
