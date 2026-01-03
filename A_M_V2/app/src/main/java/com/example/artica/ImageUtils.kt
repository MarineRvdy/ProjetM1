import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object ImageUtils {

    fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, image.width, image.height),
            100,
            out
        )

        val imageBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(
            imageBytes,
            0,
            imageBytes.size
        )
    }

    fun bitmapToFloatBuffer(
        bitmap: Bitmap,
        inputSize: Int
    ): FloatArray {

        val resized = Bitmap.createScaledBitmap(
            bitmap,
            inputSize,
            inputSize,
            true
        )

        val floatValues = FloatArray(inputSize * inputSize * 3)
        var idx = 0

        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val pixel = resized.getPixel(x, y)

                floatValues[idx++] = ((pixel shr 16) and 0xFF) / 255f
                floatValues[idx++] = ((pixel shr 8) and 0xFF) / 255f
                floatValues[idx++] = (pixel and 0xFF) / 255f
            }
        }

        return floatValues
    }
}
