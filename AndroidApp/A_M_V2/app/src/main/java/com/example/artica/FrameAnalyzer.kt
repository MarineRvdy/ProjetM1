import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.artica.OverlayView

class FrameAnalyzer(
    private val classifier: TFLiteClassifier,
    private val overlayView: OverlayView
) : ImageAnalysis.Analyzer {

    private val INPUT_SIZE = 96 // ⚠️ adapte si ton modèle diffère

    override fun analyze(image: ImageProxy) {

        val bitmap = image.toBitmap()
        val resized = Bitmap.createScaledBitmap(bitmap, 96, 96, true)

        val input = Array(1) {
            Array(96) {
                Array(96) {
                    FloatArray(3)
                }
            }
        }

        for (y in 0 until 96) {
            for (x in 0 until 96) {
                val pixel = resized.getPixel(x, y)

                input[0][y][x][0] = ((pixel shr 16) and 0xFF) / 255f
                input[0][y][x][1] = ((pixel shr 8) and 0xFF) / 255f
                input[0][y][x][2] = (pixel and 0xFF) / 255f
            }
        }

        val detections = classifier.run(input)

        overlayView.post {
            overlayView.setResults(detections)
        }

        image.close()


    }

}
