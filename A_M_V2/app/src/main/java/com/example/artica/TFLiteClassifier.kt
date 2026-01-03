import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream

class TFLiteClassifier(context: Context) {

    private val interpreter: Interpreter

    init {
        interpreter = Interpreter(loadModelFile(context))
        val inputShape = interpreter.getInputTensor(0).shape()
        Log.d("TFLite", "Input shape = ${inputShape.contentToString()}")
        for (i in 0 until interpreter.outputTensorCount) {
            val t = interpreter.getOutputTensor(i)
            Log.d(
                "TFLite",
                "Output $i name=${t.name()} shape=${t.shape().contentToString()}"
            )
        }

    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    fun run(input: Array<Array<Array<FloatArray>>>): List<DetectionResult> {

        // Shape EXACTE du modèle : [1, 12, 12, 3]
        val output = Array(1) {
            Array(12) {
                Array(12) {
                    FloatArray(3)
                }
            }
        }

        interpreter.run(input, output)

        return FomoPostProcessor.decode(
            output = output[0],
            threshold = 0.5f
        )
    }




}
