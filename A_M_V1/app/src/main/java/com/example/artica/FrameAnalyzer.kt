import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.artica.FomoDetection
import com.example.artica.OverlayView
import com.example.artica.TFLiteClassifier
import kotlin.math.exp
fun sigmoid(x: Float) = 1f / (1f + kotlin.math.exp(-x))
class FrameAnalyzer(
    private val classifier: TFLiteClassifier,
    private val overlayView: OverlayView
) : ImageAnalysis.Analyzer {

    private val INPUT_SIZE = 96
    private val GRID_SIZE = 12
    private val NUM_CLASSES = 2 // classes réelles (0 = background)
    private val THRESHOLD = 0.7f // score minimal pour afficher

    override fun analyze(image: ImageProxy) {

        val bitmap = image.toBitmap()
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)

        // Conversion bitmap → array [1, INPUT_SIZE, INPUT_SIZE, 3]
        val input = Array(1) {
            Array(INPUT_SIZE) {
                Array(INPUT_SIZE) {
                    FloatArray(3)
                }
            }
        }

        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = resized.getPixel(x, y)
                input[0][y][x][0] = ((pixel shr 16) and 0xFF) / 255f
                input[0][y][x][1] = ((pixel shr 8) and 0xFF) / 255f
                input[0][y][x][2] = (pixel and 0xFF) / 255f
            }
        }

        val output = classifier.run(input) // output: [1][12][12][3]
        val detections = mutableListOf<FomoDetection>()

        for (y in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE) {
                val cell = output[0][y][x]

                // Détermine la classe et score "relatif" à partir des logits
                val classId = if (cell[1] > cell[2]) 0 else 1
                val score = cell[classId + 1] // ⚠️ prendre la valeur de la classe choisie

                if (score > THRESHOLD) { // seuil sur la classe, pas sur cell[0]
                    detections.add(FomoDetection(x, y, classId, score))
                }
            }
        }

        overlayView.post {
            overlayView.setResults(detections)
        }






        image.close()
    }
}
