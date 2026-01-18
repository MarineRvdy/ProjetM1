package com.example.test_camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.Manifest
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.res.Configuration
import androidx.lifecycle.lifecycleScope
import com.example.test_camera.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.os.Handler
import android.os.Looper
import java.lang.System.currentTimeMillis

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

    private val articaPaint = Paint().apply {
        color = Color.parseColor("#FF6B35") // Orange pour artica
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val monachaPaint = Paint().apply {
        color = Color.parseColor("#4ECDC4") // Turquoise pour monacha
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        style = Paint.Style.FILL
        setShadowLayer(5f, 2f, 2f, Color.BLACK) // Ombre pour meilleure lisibilité
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
            val paint = when (box.label.lowercase()) {
                "artica" -> articaPaint
                "monacha" -> monachaPaint
                else -> articaPaint // Couleur par défaut
            }

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
                // Standard object detection box with class-specific color
                canvas.drawRect(rect, paint)

                // Background rectangle for text
                val confidenceText = "${box.label} (${(box.confidence * 100).toInt()}%)"
                val textBounds = android.graphics.Rect()
                textPaint.getTextBounds(confidenceText, 0, confidenceText.length, textBounds)

                val textBackgroundRect = Rect(
                    box.x,
                    box.y - textBounds.height() - 15,
                    box.x + textBounds.width() + 10,
                    box.y - 5
                )

                val backgroundPaint = Paint().apply {
                    color = paint.color
                    alpha = 200
                    style = Paint.Style.FILL
                }
                canvas.drawRect(textBackgroundRect, backgroundPaint)

                // Draw text
                canvas.drawText(confidenceText, box.x.toFloat(), (box.y - 10).toFloat(), textPaint)
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var resultTextView: TextView
    private lateinit var previewView: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay
    private lateinit var detectionCountTextView: TextView

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Throttling variables
    private var lastInferenceTime = 0L
    private val inferenceIntervalMs = 100L // 10 FPS max
    private var totalDetectionCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resultTextView = findViewById(R.id.resultTextView) // Result TextView
        previewView = findViewById(R.id.previewView) // Camera preview view
        boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay) // overlay for bbxes / visual ad
        detectionCountTextView = findViewById(R.id.detectionCountTextView) // detection counter

        // Set overlay size to match PreviewView
        previewView.post {
            boundingBoxOverlay.layoutParams = boundingBoxOverlay.layoutParams.apply {
                width = previewView.width
                height = previewView.height
            }
            // Set preview dimensions in native code for accurate bounding box scaling
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            setPreviewDimensions(previewView.width, previewView.height, isLandscape)
        }

        if (!hasCameraPermission()) {
            requestCameraPermission()
        } else {
            startCamera()
        }

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val imageAnalysis = ImageAnalysis.Builder().build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
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

    // Process the captured image with throttling
    private fun processImage(imageProxy: ImageProxy) {
        val currentTime = currentTimeMillis()

        // Apply throttling - only process inference at specified interval
        if (currentTime - lastInferenceTime < inferenceIntervalMs) {
            imageProxy.close()
            return
        }

        lastInferenceTime = currentTime

        // Convert ImageProxy to Bitmap
        val bitmap = imageProxy.toBitmap()

        // Resize the Bitmap to Edge Impulse model size (192x192 for FOMO)
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 192, 192, true)

        // Convert the resized bitmap to ByteArray
        val byteArray = getByteArrayFromBitmap(resizedBitmap)

        // Close the imageProxy after processing
        imageProxy.close()

        // Pass to C++ for Edge Impulse inference
        lifecycleScope.launch(Dispatchers.IO) {
            val result = passToCpp(byteArray)
            mainHandler.post {
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

        // Rotate the bitmap by 90 degrees
        val matrix = Matrix()
        matrix.postRotate(90f)

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
    private external fun passToCpp(imageData: ByteArray): InferenceResult?

    // Call the C++ function to set preview dimensions
    private external fun setPreviewDimensions(width: Int, height: Int, isLandscape: Boolean)

    // Display results in UI
    @SuppressLint("SetTextI18n")
    private fun displayResults(result: InferenceResult?) {
        resultTextView.visibility = View.GONE
        boundingBoxOverlay.visibility = View.GONE

        if (result == null) {
            resultTextView.text = "Error running inference"
            detectionCountTextView.text = "Erreur de détection"
        } else
        {
            val currentDetectionCount = result.objectDetections?.size ?: 0

            if (result.objectDetections != null && result.objectDetections.isNotEmpty()) {
                // Display object detection results
                boundingBoxOverlay.visibility = View.VISIBLE
                boundingBoxOverlay.boundingBoxes = result.objectDetections

                // Update detection count
                val articaCount = result.objectDetections.count { it.label.lowercase() == "artica" }
                val monachaCount = result.objectDetections.count { it.label.lowercase() == "monacha" }

                detectionCountTextView.text = when {
                    articaCount > 0 && monachaCount > 0 -> "$articaCount artica, $monachaCount monacha détectés"
                    articaCount > 0 -> "$articaCount artica détecté${if (articaCount > 1) "s" else ""}"
                    monachaCount > 0 -> "$monachaCount monacha détecté${if (monachaCount > 1) "s" else ""}"
                    else -> "Aucun coquillage détecté"
                }
                detectionCountTextView.visibility = View.VISIBLE
            } else {
                // No detections
                detectionCountTextView.text = "Aucun coquillage détecté"
                detectionCountTextView.visibility = View.VISIBLE
            }

            if (result.classification != null) {
                // Display classification results
                val classificationText = result.classification.entries.joinToString("\n") {
                    "${it.key}: ${it.value}"
                }
                resultTextView.text = "Classification:\n$classificationText"
                resultTextView.visibility = View.VISIBLE
            }

            if (result.visualAnomalyGridCells != null) {
                // Display visual anomaly grid cells
                val visualAnomalyMax = result.anomalyResult?.getValue("max")
                val visualAnomalyMean = result.anomalyResult?.getValue("mean")
                boundingBoxOverlay.visibility = View.VISIBLE
                boundingBoxOverlay.boundingBoxes = result.visualAnomalyGridCells
                resultTextView.visibility = View.VISIBLE
                resultTextView.text = "Visual anomaly values:\nMean: ${visualAnomalyMean}\nMax: ${visualAnomalyMax}"
            }

            if (result.anomalyResult?.get("anomaly") != null) {
                // Display anomaly detection score
                val anomalyScore = result.anomalyResult.get("anomaly")
                resultTextView.text = "Anomaly score:\n${anomalyScore}"
                resultTextView.visibility = View.VISIBLE
            }
        }
    }

    // Load the native library
    init {
        System.loadLibrary("test_camera")
    }
}