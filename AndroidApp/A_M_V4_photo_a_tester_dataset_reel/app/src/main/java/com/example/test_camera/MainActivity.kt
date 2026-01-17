package com.example.test_camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.Manifest
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.test_camera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.app.ActivityCompat

data class InferenceResult(
    val classification: Map<String, Float>?,   // Classification labels and values
    val objectDetections: List<BoundingBox>?,  // Object detection results
    val visualAnomalyGridCells: List<BoundingBox>?, // Visual anomaly grid
    val anomalyResult: Map<String, Float>?, // Anomaly values
    val timing: Timing  // Timing information
)

data class BoundingBox(
    val label: String,
    val confidence: Float,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class Timing(
    val sampling: Int,
    val dsp: Int,
    val classification: Int,
    val anomaly: Int,
    val dsp_us: Long,
    val classification_us: Long,
    val anomaly_us: Long
)

private const val CAMERA_PERMISSION_REQUEST_CODE = 1001

class BoundingBoxOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
    }

    private val anomalyPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        alpha = 60 // Adjust transparency
    }

    var boundingBoxes: List<BoundingBox> = emptyList()
        set(value) {
            field = value
            invalidate() // Redraw when new bounding boxes are set
        }

    @SuppressLint("DefaultLocale")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT) // Ensure transparency

        boundingBoxes.forEach { box ->
            val rect = Rect(box.x, box.y, box.x + box.width, box.y + box.height)

            if (box.label == "anomaly") {
                // Fill the box with transparent red
                canvas.drawRect(rect, anomalyPaint)

                // Display anomaly score in the center
                val scoreText = String.format("%.2f", box.confidence)
                val textX = rect.centerX().toFloat()
                val textY = rect.centerY().toFloat()

                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(scoreText, textX, textY, textPaint)
            } else {
                // Standard object detection box
                canvas.drawRect(rect, paint)
                canvas.drawText("${box.label} (${(box.confidence * 100).toInt()}%)", box.x.toFloat(), (box.y - 10).toFloat(), textPaint)
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var resultTextView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay
    private lateinit var captureButton: Button

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

    // Function to detect current orientation
    private fun isPortrait(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resultTextView = findViewById(R.id.resultTextView)
        previewView = findViewById(R.id.previewView)
        boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay)
        captureButton = findViewById(R.id.captureButton)

        // Set overlay size to match PreviewView
        previewView.post {
            boundingBoxOverlay.layoutParams = boundingBoxOverlay.layoutParams.apply {
                width = previewView.width
                height = previewView.height
            }
        }

        if (!hasCameraPermission()) {
            requestCameraPermission()
        } else {
            startCamera()
        }

        captureButton.setOnClickListener {
            capturePhoto()
        }

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            
            imageCapture = ImageCapture.Builder()
                .setTargetResolution(android.util.Size(480, 640))
                .build()
            
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                resultTextView.text = "Camera permission required!"
            }
        }
    }

    // Capture photo and process for shellfish detection
    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return
        
        resultTextView.text = "Capture en cours..."
        captureButton.isEnabled = false
        
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processImage(image)
                    captureButton.isEnabled = true
                }
                
                override fun onError(exception: ImageCaptureException) {
                    resultTextView.text = "Erreur de capture: ${exception.message}"
                    captureButton.isEnabled = true
                }
            }
        )
    }

    // Process the captured image
    private fun processImage(imageProxy: ImageProxy) {
        // Convert ImageProxy to Bitmap
        val bitmap = imageProxy.toBitmap()
        
        // Ensure bitmap has correct dimensions for the model
        val resizedBitmap = if (bitmap.width != 480 || bitmap.height != 640) {
            Bitmap.createScaledBitmap(bitmap, 480, 640, true)
        } else {
            bitmap
        }

        // Convert the resized bitmap to ByteArray
        val byteArray = getByteArrayFromBitmap(resizedBitmap)

        // Close the imageProxy after processing
        imageProxy.close()

        // Pass to C++ for Edge Impulse inference
        lifecycleScope.launch(Dispatchers.IO) {
            val previewWidth = previewView.width
            val previewHeight = previewView.height
            val isPortraitMode = isPortrait()
            val result = passToCpp(byteArray, previewWidth, previewHeight, isPortraitMode)
            runOnUiThread {
                displayResults(result)
            }
        }
    }

    // Convert ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap {
        val planes = this.planes
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // Convert Bitmap to ByteArray (RGB888 format)
    private fun getByteArrayFromBitmap(bitmap: Bitmap): ByteArray {

        // Rotate the bitmap based on device orientation
        val matrix = Matrix()
        val rotationAngle = if (isPortrait()) 90f else 270f  // 270° for landscape to match camera orientation
        matrix.postRotate(rotationAngle)

        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        val width = rotatedBitmap.width
        val height = rotatedBitmap.height

        val pixels = IntArray(width * height) // Holds ARGB pixels
        val rgbByteArray = ByteArray(width * height * 3) // Holds RGB888 data

        rotatedBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert ARGB to RGB888
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            rgbByteArray[i * 3] = r.toByte()
            rgbByteArray[i * 3 + 1] = g.toByte()
            rgbByteArray[i * 3 + 2] = b.toByte()
        }

        return rgbByteArray
    }

    // Call the C++ function to process the image and return results
    private external fun passToCpp(imageData: ByteArray, previewWidth: Int, previewHeight: Int, isPortrait: Boolean): InferenceResult?

    // Display results in UI
    @SuppressLint("SetTextI18n")
    private fun displayResults(result: InferenceResult?) {
        resultTextView.visibility = View.VISIBLE
        boundingBoxOverlay.visibility = View.GONE

        if (result == null) {
            resultTextView.text = "Erreur lors de la détection"
        } else {
            val combinedText = StringBuilder()
            
            // Check for shellfish detections
            if (result.objectDetections != null && result.objectDetections.isNotEmpty()) {
                val shellfishDetections = result.objectDetections.filter { 
                    it.label.contains("coquillage", ignoreCase = true) || 
                    it.label.contains("shellfish", ignoreCase = true) ||
                    it.confidence > 0.5 // Filter by confidence
                }
                
                if (shellfishDetections.isNotEmpty()) {
                    combinedText.append("🐚 Coquillages détectés! (${shellfishDetections.size})\n\n")
                    shellfishDetections.forEach { detection ->
                        combinedText.append("${detection.label}: ${(detection.confidence * 100).toInt()}%\n")
                    }
                    
                    // Show bounding boxes
                    boundingBoxOverlay.visibility = View.VISIBLE
                    boundingBoxOverlay.boundingBoxes = shellfishDetections
                } else {
                    combinedText.append("❌ Aucun coquillage détecté\n\n")
                    // Show all detections for debugging
                    result.objectDetections.take(3).forEach { detection ->
                        combinedText.append("${detection.label}: ${(detection.confidence * 100).toInt()}%\n")
                    }
                }
            } else {
                combinedText.append("❌ Aucun objet détecté\n")
            }
            
            if (result.classification != null) {
                combinedText.append("\nClassification:\n")
                result.classification.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .forEach { (label, value) ->
                        combinedText.append("$label: ${(value * 100).toInt()}%\n")
                    }
            }
            
            resultTextView.text = combinedText.toString()
        }
    }

    // Load the native library
    init {
        System.loadLibrary("test_camera")
    }
}