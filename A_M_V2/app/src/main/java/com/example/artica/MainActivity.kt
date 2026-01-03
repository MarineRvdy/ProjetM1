package com.example.artica

import FrameAnalyzer
import TFLiteClassifier
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

import org.tensorflow.lite.Interpreter
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.FileInputStream

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.camera.core.Preview

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat


import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors




class MainActivity : ComponentActivity() {
    lateinit var tflite: Interpreter
    private lateinit var classifier: TFLiteClassifier
    private val CAMERA_PERMISSION_REQUEST_CODE = 1001

    private lateinit var cameraExecutor: ExecutorService

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 10
    }
    lateinit var overlayView: OverlayView



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        overlayView = findViewById(R.id.overlayView)


        tflite = loadModel()
        classifier = TFLiteClassifier(this)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }


    private fun loadModel(): Interpreter {
        val fileDescriptor = assets.openFd("model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength

        val buffer: MappedByteBuffer = fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            startOffset,
            declaredLength
        )

        return Interpreter(buffer)
    }

    private fun analyzeImage(image: ImageProxy) {
        Log.d("CAMERA", "Frame reçue : ${image.width}x${image.height}")
        image.close()
    }

    private fun startCamera() {

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            // 1️⃣ Preview caméra
            val preview = androidx.camera.core.Preview.Builder()
                .build()
                .also {
                    val previewView = findViewById<PreviewView>(R.id.previewView)
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // 2️⃣ ImageAnalysis (UNE SEULE FOIS)
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // 3️⃣ Analyzer avec IA + overlay
            val analyzer = FrameAnalyzer(classifier, overlayView)
            imageAnalysis.setAnalyzer(cameraExecutor, analyzer)

            // 4️⃣ Caméra arrière
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // 5️⃣ Bind lifecycle
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalysis
            )

        }, ContextCompat.getMainExecutor(this))
    }



    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        }
    }

}

