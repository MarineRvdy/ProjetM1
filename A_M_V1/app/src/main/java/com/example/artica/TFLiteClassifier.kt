package com.example.artica

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class TFLiteClassifier(context: Context) {

    private val interpreter: Interpreter

    // Paramètres du modèle FOMO
    private val INPUT_SIZE = 96
    private val GRID_SIZE = 12
    private val NUM_CLASSES = 2
    private val OUTPUT_SIZE = GRID_SIZE * GRID_SIZE * (NUM_CLASSES + 1) // 12*12*(2+1)=432

    init {
        interpreter = Interpreter(loadModelFile(context))

        // Debug : formes des tenseurs
        val inputShape = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()
        Log.d("TFLite", "Input shape = ${inputShape.contentToString()}")
        Log.d("TFLite", "Output shape = ${outputShape.contentToString()}")
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

    /** Exécute le modèle FOMO et retourne un FloatArray de 432 éléments */
    fun run(input: Array<Array<Array<FloatArray>>>): Array<Array<Array<FloatArray>>> {
        // Sortie = [1, 12, 12, 3] (il faut inclure la dimension batch)
        val output = Array(1) { Array(12) { Array(12) { FloatArray(3) } } }
        interpreter.run(input, output)
        return output
    }




}
