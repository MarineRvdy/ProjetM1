package com.example.test_camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.Manifest
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.util.Size
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
import androidx.core.app.ActivityCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.media.AudioManager
import android.media.ToneGenerator

data class InferenceResult(
    val classification: Map<String, Float>?,   // Classification labels and values
    val objectDetections: List<BoundingBox>?,  // Object detection results
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
private const val STORAGE_PERMISSION_REQUEST_CODE = 1002
private const val EI_CLASSIFIER_INPUT_WIDTH = 320  // Taille d'entrée du modèle
private const val EI_CLASSIFIER_INPUT_HEIGHT = 320 // Taille d'entrée du modèle

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

    // Ajout du paint pour le cadre de centrage
    private val centeringFramePaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val centeringFrameFillPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        alpha = 30 // Transparent
    }

    var boundingBoxes: List<BoundingBox> = emptyList()
        set(value) {
            field = value
            invalidate() // Redraw when new bounding boxes are set
        }

    private var isCalibrated = false
    private var isCenteringMode = false

    fun setCalibrationEnabled(enabled: Boolean) {
        isCalibrated = enabled
    }

    fun setCenteringMode(enabled: Boolean) {
        isCenteringMode = enabled
        invalidate() // Redraw when centering mode changes
    }

    // Suppression de la correction de distorsion - le nouveau scaling gère correctement l'alignement

    @SuppressLint("DefaultLocale")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.TRANSPARENT) // Ensure transparency

        // Dessiner le cadre de centrage 320x320 si en mode centrage
        if (isCenteringMode) {
            val frameSize = 320f
            val frameLeft = (width - frameSize) / 2f
            val frameTop = (height - frameSize) / 2f
            val frameRight = frameLeft + frameSize
            val frameBottom = frameTop + frameSize
            
            // Dessiner le fond semi-transparent
            canvas.drawRect(frameLeft, frameTop, frameRight, frameBottom, centeringFrameFillPaint)
            
            // Dessiner le cadre
            canvas.drawRect(frameLeft, frameTop, frameRight, frameBottom, centeringFramePaint)
            
            // Dessiner des coins pour mieux visualiser
            val cornerSize = 50f
            // Coin supérieur gauche
            canvas.drawRect(frameLeft, frameTop, frameLeft + cornerSize, frameTop + 8f, centeringFramePaint)
            canvas.drawRect(frameLeft, frameTop, frameLeft + 8f, frameTop + cornerSize, centeringFramePaint)
            // Coin supérieur droit
            canvas.drawRect(frameRight - cornerSize, frameTop, frameRight, frameTop + 8f, centeringFramePaint)
            canvas.drawRect(frameRight - 8f, frameTop, frameRight, frameTop + cornerSize, centeringFramePaint)
            // Coin inférieur gauche
            canvas.drawRect(frameLeft, frameBottom - 8f, frameLeft + cornerSize, frameBottom, centeringFramePaint)
            canvas.drawRect(frameLeft, frameBottom - cornerSize, frameLeft + 8f, frameBottom, centeringFramePaint)
            // Coin inférieur droit
            canvas.drawRect(frameRight - cornerSize, frameBottom - 8f, frameRight, frameBottom, centeringFramePaint)
            canvas.drawRect(frameRight - 8f, frameBottom - cornerSize, frameRight, frameBottom, centeringFramePaint)
        }

        // Dessiner les bounding boxes seulement si présentes
        if (boundingBoxes.isNotEmpty()) {
            boundingBoxes.forEach { box ->
                // Utiliser directement les coordonnées sans correction de distorsion
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
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val CONFIDENCE_THRESHOLD = 0.4f  // 40% confidence threshold (temporairement bas pour test)
        private const val OBJECT_COOLDOWN_MS = 10000L  // 10 secondes de cooldown par objet
        private const val PROXIMITY_THRESHOLD = 120f  // 120 pixels de distance max pour les objets de 6480 pixels²
        private const val CALIBRATION_GRID_SIZE = 5  // Grille 5x5 pour la calibration
    }

    // Variables globales pour les ratios d'écran (synchronisées avec C++)
    private var g_x_ratio = 1.0f
    private var g_y_ratio = 1.0f

    // Suivi des objets déjà comptés avec leur position et timestamp
    data class CountedObject(
        val label: String,
        val x: Float,
        val y: Float,
        val timestamp: Long
    )
    private val countedObjects = mutableListOf<CountedObject>()
    
    // Variables pour la calibration de l'écran
    data class CalibrationPoint(
        val screenX: Float,
        val screenY: Float,
        val detectedX: Float,
        val detectedY: Float
    )
    private val calibrationGrid = Array(CALIBRATION_GRID_SIZE) { 
        Array(CALIBRATION_GRID_SIZE) { CalibrationPoint(0f, 0f, 0f, 0f) } 
    }
    private var isCalibrated = false

    private lateinit var binding: ActivityMainBinding
    private lateinit var previewView: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay
    private lateinit var captureButton: Button
    private lateinit var detectionCounterTextView: TextView
    private lateinit var validationButtonsLayout: LinearLayout
    private lateinit var yesButton: Button
    private lateinit var noButton: Button
    private lateinit var trashButton: Button
    private lateinit var frozenImageView: ImageView
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    private var lastInferenceTime = 0L
    private val inferenceInterval = 100L  // 100ms entre les inférences (10 FPS) - remis comme avant
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var lastDetectionTime = 0L
    private val detectionCooldown = 5000L // 5 secondes entre les détections pour éviter les validations trop fréquentes
    
    // Variables pour le flux de validation
    private var isValidationMode = false
    private var isCenteringMode = false  // Nouveau mode pour le centrage
    private var frozenBitmap: Bitmap? = null
    private var currentDetections: List<BoundingBox>? = null
    private var centeredFrameCount = 0  // Compteur de frames centrées
    private val REQUIRED_CENTERED_FRAMES = 5  // Nombre de frames requises pour validation (réduit de 10 à 3)

    // Fonction pour déclencher une vibration
    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }

    // Fonction pour jouer un son de détection
    private fun playDetectionSound() {
        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
    }

    // Fonction combinée pour alerte vibration + son
    private fun triggerDetectionAlert() {
        vibrate()
        playDetectionSound()
    }

    // Fonction pour mettre à l'échelle les bounding boxes du modèle vers les coordonnées de la vue
    // Gère correctement le "fit shortest axis" utilisé dans l'entraînement
    private fun scaleBoundingBox(modelX: Float, modelY: Float, modelWidth: Float, modelHeight: Float): BoundingBox {
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        val modelSize = EI_CLASSIFIER_INPUT_WIDTH.toFloat() // Modèle 320x320
        
        // Calcul du scaling pour "fit shortest axis"
        val scale = minOf(viewWidth / modelSize, viewHeight / modelSize)
        
        // Dimensions de l'image après scaling
        val scaledWidth = modelSize * scale
        val scaledHeight = modelSize * scale
        
        // Calcul des offsets pour centrer l'image
        val offsetX = (viewWidth - scaledWidth) / 2f
        val offsetY = (viewHeight - scaledHeight) / 2f
        
        // Mise à l'échelle des coordonnées
        val scaledX = modelX * scale + offsetX
        val scaledY = modelY * scale + offsetY
        val scaledW = modelWidth * scale
        val scaledH = modelHeight * scale
        
        Log.d("SCALING", "Model: ($modelX,$modelY,$modelWidth,$modelHeight) -> View: ($scaledX,$scaledY,$scaledW,$scaledH)")
        Log.d("SCALING", "Scale: $scale, offsets: ($offsetX,$offsetY), view: ${viewWidth}x${viewHeight}")
        
        return BoundingBox(
            label = "", // Sera rempli plus tard
            confidence = 0f, // Sera rempli plus tard
            x = scaledX.toInt(),
            y = scaledY.toInt(),
            width = scaledW.toInt(),
            height = scaledH.toInt()
        )
    }

    // Fonction pour filtrer les détections selon le seuil de confiance
    private fun filterDetections(detections: List<BoundingBox>?): List<BoundingBox>? {
        return detections?.filter { it.confidence >= CONFIDENCE_THRESHOLD }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        previewView = findViewById(R.id.previewView) // Camera preview view
        boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay) // overlay for bbxes / visual ad
        captureButton = findViewById(R.id.captureButton) // Capture button
        detectionCounterTextView = findViewById(R.id.detectionCounterTextView) // Detection counter
        validationButtonsLayout = findViewById(R.id.validationButtonsLayout) as LinearLayout // Validation buttons layout
        yesButton = findViewById(R.id.yesButton) // Yes button
        noButton = findViewById(R.id.noButton) // No button
        trashButton = findViewById(R.id.trashButton) // Trash button
        frozenImageView = findViewById(R.id.frozenImageView) // Frozen image display

        // Mettre à jour les ratios d'écran pour les bounding boxes
        updateScreenDimensions()
        
        // Initialiser la calibration
        initCalibration()

        // Set overlay size to match PreviewView
        previewView.post {
            boundingBoxOverlay.layoutParams = boundingBoxOverlay.layoutParams.apply {
                width = previewView.width
                height = previewView.height
            }
            // Mettre à jour les ratios une seconde fois avec les bonnes dimensions
            updateScreenDimensions()
        }

        if (!hasCameraPermission()) {
            requestCameraPermission()
        } else {
            startCamera()
        }

        captureButton.setOnClickListener {
            if (hasStoragePermission()) {
                takePhoto()
            } else {
                requestStoragePermission()
            }
        }
        
        // Validation buttons listeners
        yesButton.setOnClickListener {
            handleValidationChoice("yes")
        }
        
        noButton.setOnClickListener {
            handleValidationChoice("no")
        }
        
        trashButton.setOnClickListener {
            handleValidationChoice("trash")
        }

    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            // Configurer le PreviewView pour correspondre exactement à ce que la capture voit
            previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
            
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            
            // Configurer ImageCapture avec la même résolution que ImageAnalysis
            imageCapture = ImageCapture.Builder()
                .setTargetResolution(Size(640, 480))
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
                
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))  // Résolution plus élevée pour meilleure détection
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setImageQueueDepth(1)  // Réduire la profondeur de la queue
                .build()

            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }

            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
    }

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Log.e("MainActivity", "Camera permission required!")
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takePhoto()
            } else {
                Toast.makeText(this, "Storage permission required to save photos!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Process the captured image
    private fun processImage(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        
        // Si en mode validation, ignorer le traitement
        if (isValidationMode) {
            imageProxy.close()
            return
        }
        
        // Limiter la fréquence d'inférence à 10 FPS
        if (currentTime - lastInferenceTime < inferenceInterval) {
            imageProxy.close()
            return
        }
        
        lastInferenceTime = currentTime
        
        // Convert ImageProxy to Bitmap
        val bitmap = imageProxy.toBitmap()
        frozenBitmap = bitmap // Garder une référence pour la validation

        // Resize the Bitmap to Edge Impulse model size
        // val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        // resizing is done in C++ code

        // Convert the resized bitmap to ByteArray
        val byteArray = getByteArrayFromBitmap(bitmap)

        // Close the imageProxy after processing
        imageProxy.close()

        // Pass to C++ for Edge Impulse inference
        lifecycleScope.launch(Dispatchers.IO) {
            val result = passToCpp(byteArray)
            runOnUiThread {
                displayResults(result)
            }
        }
    }

    // Fonction pour recadrer une image pour correspondre au preview (portrait)
    private fun cropImageToPreviewPortrait(bitmap: Bitmap): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        
        Log.d("CROP_INFO", "Image originale: ${originalWidth}x${originalHeight}")
        
        // Le capteur est paysage, mais nous voulons un crop portrait centré
        // Pour passer de paysage à portrait, nous recadrons la partie centrale
        val cropSize = minOf(originalWidth, originalHeight)
        val cropLeft = (originalWidth - cropSize) / 2
        val cropTop = (originalHeight - cropSize) / 2
        
        Log.d("CROP_INFO", "Crop: ${cropSize}x${cropSize} à (${cropLeft},${cropTop})")
        
        // Recadrer la partie carrée centrale
        val croppedBitmap = Bitmap.createBitmap(
            bitmap, 
            cropLeft, 
            cropTop, 
            cropSize, 
            cropSize
        )
        
        return croppedBitmap
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

    // Display results in UI
    @SuppressLint("SetTextI18n")
    private fun displayResults(result: InferenceResult?) {
        if (result == null) {
            Log.e("MainActivity", "Error running inference")
            detectionCounterTextView.text = "Détections: 0"
            boundingBoxOverlay.visibility = View.GONE
        } else
        {
            val combinedText = StringBuilder()
            var currentDetectionCount = 0
            
            if (result.classification != null) {
                // Display classification results
                val classificationText = result.classification.entries.joinToString("\n") {
                    "${it.key}: ${it.value}"
                }
                combinedText.append("Classification:\n$classificationText\n\n")
            }
            if (result.objectDetections != null) {
                // Filtrer les détections selon le seuil de confiance
                val filteredDetections = filterDetections(result.objectDetections)
                currentDetectionCount = filteredDetections?.size ?: 0
                
                if (filteredDetections != null && filteredDetections.isNotEmpty()) {
                    // Appliquer la mise à l'échelle correcte pour les bounding boxes (optimisé)
                    val scaledDetections = filteredDetections.map { detection ->
                        // Utiliser les coordonnées brutes du modèle (inverser le scaling actuel)
                        val modelX = detection.x.toFloat() / g_x_ratio
                        val modelY = detection.y.toFloat() / g_y_ratio  
                        val modelWidth = detection.width.toFloat() / g_x_ratio
                        val modelHeight = detection.height.toFloat() / g_y_ratio
                        
                        // Appliquer le nouveau scaling correct
                        val scaledBox = scaleBoundingBox(modelX, modelY, modelWidth, modelHeight)
                        
                        // Conserver label et confidence originaux
                        BoundingBox(
                            label = detection.label,
                            confidence = detection.confidence,
                            x = scaledBox.x,
                            y = scaledBox.y,
                            width = scaledBox.width,
                            height = scaledBox.height
                        )
                    }
                    
                    // Afficher les bounding boxes sur le flux temps réel (priorité absolue)
                    boundingBoxOverlay.visibility = View.VISIBLE
                    boundingBoxOverlay.boundingBoxes = scaledDetections
                    
                    // Logique de détection légère - seulement si pas déjà en mode
                    if (!isCenteringMode && !isValidationMode) {
                        // Mode normal : première détection
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDetectionTime > detectionCooldown) {
                            lastDetectionTime = currentTime
                            currentDetections = scaledDetections
                            triggerDetectionAlert()
                            enterCenteringMode()
                        }
                    } else if (isCenteringMode) {
                        // Mode centrage : vérification simple
                        val isCentered = checkIfDetectionIsCentered(scaledDetections)
                        if (isCentered) {
                            centeredFrameCount++
                            if (centeredFrameCount >= REQUIRED_CENTERED_FRAMES) {
                                currentDetections = scaledDetections
                                exitCenteringMode()
                                enterValidationMode()
                            }
                        } else {
                            centeredFrameCount = 0
                        }
                    }
                } else {
                    // Masquer les bounding boxes si aucune détection
                    boundingBoxOverlay.visibility = View.GONE
                }
            } else {
                // Masquer les bounding boxes si aucune détection
                boundingBoxOverlay.visibility = View.GONE
            }
            
            // Mettre à jour le compteur selon le mode
            if (isValidationMode) {
                // Mode validation : afficher le nombre de détections sur l'image capturée
                val capturedDetectionsCount = currentDetections?.size ?: 0
                detectionCounterTextView.text = "Détections: $capturedDetectionsCount"
            } else {
                // Mode temps réel : afficher le nombre de détections actuelles
                detectionCounterTextView.text = "Détections: $currentDetectionCount"
            }
        }
    }

    // Load the native library
    init {
        System.loadLibrary("test_camera")
    }

    // Système de calibration simplifié - plus de correction de distorsion nécessaire
    // Le nouveau scaling gère correctement l'alignement des bounding boxes

    // Fonction native pour mettre à jour les ratios d'écran
    private external fun updateScreenRatios(screenWidth: Int, screenHeight: Int)

    // Fonction pour mettre à jour les ratios locaux (appelée par le code natif)
    private fun updateLocalRatios(xRatio: Float, yRatio: Float) {
        g_x_ratio = xRatio
        g_y_ratio = yRatio
        Log.d("RATIOS", "Local ratios updated: x=$g_x_ratio, y=$g_y_ratio")
    }

    // Fonction pour obtenir les dimensions de l'écran et mettre à jour les ratios
    private fun updateScreenDimensions() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        Log.d("SCREEN_INFO", "Écran: ${screenWidth}x${screenHeight}")
        Log.d("SCREEN_INFO", "PreviewView: ${previewView.width}x${previewView.height}")
        Log.d("SCREEN_INFO", "BoundingBoxOverlay: ${boundingBoxOverlay.width}x${boundingBoxOverlay.height}")
        
        // Utiliser les dimensions réelles du PreviewView pour les ratios
        val previewWidth = if (previewView.width > 0) previewView.width else screenWidth
        val previewHeight = if (previewView.height > 0) previewView.height else screenHeight
        
        // Mettre à jour les ratios locaux aussi
        g_x_ratio = previewWidth.toFloat() / EI_CLASSIFIER_INPUT_WIDTH.toFloat()
        g_y_ratio = previewHeight.toFloat() / EI_CLASSIFIER_INPUT_HEIGHT.toFloat()
        
        // Mettre à jour les ratios dans le code C++
        updateScreenRatios(previewWidth, previewHeight)
        
        Log.d("SCREEN_INFO", "Ratios calculés avec: ${previewWidth}x${previewHeight}")
        Log.d("SCREEN_INFO", "Local ratios: x=$g_x_ratio, y=$g_y_ratio")
    }

    private fun initCalibration() {
        Log.d("CALIBRATION", "Correction de distorsion désactivée - utilisation du nouveau scaling")
        boundingBoxOverlay.setCalibrationEnabled(false)
    }
    
    // Fonctions pour le flux de validation
    private fun enterCenteringMode() {
        isCenteringMode = true
        centeredFrameCount = 0
        
        // Afficher le cadre de centrage 320x320
        boundingBoxOverlay.setCenteringMode(true)
        
        // Afficher un message pour guider l'utilisateur
        runOnUiThread {
            Toast.makeText(this, "Centrez la détection dans le cadre", Toast.LENGTH_LONG).show()
        }
        
        Log.d("CENTERING", "Entrée en mode centrage")
    }
    
    private fun exitCenteringMode() {
        isCenteringMode = false
        
        // Masquer le cadre de centrage
        boundingBoxOverlay.setCenteringMode(false)
        
        Log.d("CENTERING", "Sortie du mode centrage")
    }
    
    private fun checkIfDetectionIsCentered(detections: List<BoundingBox>): Boolean {
        if (detections.isEmpty()) return false
        
        val viewWidth = previewView.width.toFloat()
        val viewHeight = previewView.height.toFloat()
        
        // Calculer les dimensions du cadre 320x320 au centre
        val frameSize = 320f
        val frameLeft = (viewWidth - frameSize) / 2f
        val frameTop = (viewHeight - frameSize) / 2f
        val frameRight = frameLeft + frameSize
        val frameBottom = frameTop + frameSize
        
        // Vérifier si AU MOINS UNE des détections est dans le cadre
        for (detection in detections) {
            val detectionCenterX = detection.x + detection.width / 2f
            val detectionCenterY = detection.y + detection.height / 2f
            
            val isCentered = detectionCenterX >= frameLeft && 
                            detectionCenterX <= frameRight && 
                            detectionCenterY >= frameTop && 
                            detectionCenterY <= frameBottom
            
            if (isCentered) {
                // Log seulement pour les changements importants
                if (centeredFrameCount % 5 == 0) {
                    Log.d("CENTERING", "Frames centrées: $centeredFrameCount/$REQUIRED_CENTERED_FRAMES")
                }
                return true // Au moins une détection est centrée
            }
        }
        
        return false // Aucune détection n'est centrée
    }
    
    private fun enterValidationMode() {
        isValidationMode = true
        
        // Capturer une nouvelle image fraîche pour garantir la dernière frame
        imageCapture?.let { capture ->
            capture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // Convertir l'image capturée en Bitmap
                        val freshBitmap = image.toBitmap()
                        image.close()
                        
                        // ÉTAPE 1: Recadrer l'image pour correspondre au preview
                        val croppedBitmap = cropImageToPreviewPortrait(freshBitmap)
                        Log.d("VALIDATION", "Image fraîche capturée: ${freshBitmap.width}x${freshBitmap.height}")
                        Log.d("VALIDATION", "Image fraîche recadrée: ${croppedBitmap.width}x${croppedBitmap.height}")
                        
                        // ÉTAPE 2: Tourner l'image à 90 degrés comme dans getByteArrayFromBitmap
                        val matrix = Matrix()
                        matrix.postRotate(90f)
                        val rotatedBitmap = Bitmap.createBitmap(croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true)
                        
                        // Libérer la mémoire
                        croppedBitmap.recycle()
                        
                        // Mettre à jour frozenBitmap avec la nouvelle image AVANT de recycler
                        frozenBitmap = freshBitmap
                        
                        runOnUiThread {
                            frozenImageView.setImageBitmap(rotatedBitmap)
                            frozenImageView.visibility = View.VISIBLE
                            
                            // Afficher les bounding boxes sur l'image fraîche
                            displayBoundingBoxesOnFrozenImage(rotatedBitmap)
                            
                            // Mettre à jour le compteur avec les détections sur l'image capturée
                            val capturedDetectionsCount = currentDetections?.size ?: 0
                            detectionCounterTextView.text = "Détections: $capturedDetectionsCount"
                        }
                    }
                    
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("VALIDATION", "Erreur capture image: ${exception.message}")
                        // En cas d'erreur, utiliser le frozenBitmap existant comme fallback
                        useFallbackFrozenBitmap()
                    }
                }
            )
        } ?: run {
            // Si imageCapture n'est pas disponible, utiliser frozenBitmap comme fallback
            useFallbackFrozenBitmap()
        }
        
        // Afficher les 3 boutons pour la détection automatique avec les nouveaux textes
        yesButton.text = "Détection vraie"
        noButton.text = "Détection fausse"
        trashButton.text = "Trash"
        
        yesButton.visibility = View.VISIBLE
        noButton.visibility = View.VISIBLE
        trashButton.visibility = View.VISIBLE
        
        validationButtonsLayout.visibility = View.VISIBLE
        captureButton.visibility = View.GONE
        
        Log.d("VALIDATION", "Entrée en mode validation automatique (3 boutons) - capture fraîche")
    }
    
    // Fonction fallback si la capture fraîche échoue
    private fun useFallbackFrozenBitmap() {
        frozenBitmap?.let { bitmap ->
            // ÉTAPE 1: Recadrer l'image pour correspondre au preview
            val croppedBitmap = cropImageToPreviewPortrait(bitmap)
            Log.d("VALIDATION", "Fallback - Image recadrée: ${croppedBitmap.width}x${croppedBitmap.height}")
            
            // ÉTAPE 2: Tourner l'image à 90 degrés
            val matrix = Matrix()
            matrix.postRotate(90f)
            val rotatedBitmap = Bitmap.createBitmap(croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true)
            
            // Libérer la mémoire
            croppedBitmap.recycle()
            
            runOnUiThread {
                frozenImageView.setImageBitmap(rotatedBitmap)
                frozenImageView.visibility = View.VISIBLE
                displayBoundingBoxesOnFrozenImage(rotatedBitmap)
                
                val capturedDetectionsCount = currentDetections?.size ?: 0
                detectionCounterTextView.text = "Détections: $capturedDetectionsCount"
            }
        }
    }
    
    private fun displayBoundingBoxesOnFrozenImage(rotatedBitmap: Bitmap) {
        // Créer un bitmap copie pour dessiner les bounding boxes
        val mutableBitmap = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        
        // Configuration des peintures
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 2f  // Réduit de 4f à 2f pour des boîtes plus fines
        }
        
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 20f  // Réduit de 30f à 20f pour un texte plus petit
            style = Paint.Style.FILL
            setShadowLayer(2f, 1f, 1f, Color.BLACK)  // Ombre plus légère
        }
        
        // Adapter les coordonnées des bounding boxes pour l'image capturée
        // Utiliser la même logique de scaling que scaleBoundingBox() pour la cohérence
        val imageWidth = rotatedBitmap.width.toFloat()
        val imageHeight = rotatedBitmap.height.toFloat()
        val previewWidth = previewView.width.toFloat()
        val previewHeight = previewView.height.toFloat()
        val modelSize = EI_CLASSIFIER_INPUT_WIDTH.toFloat() // 320
        
        // Calcul du scaling pour "fit shortest axis" (même logique que scaleBoundingBox)
        val scale = minOf(previewWidth / modelSize, previewHeight / modelSize)
        
        // Dimensions de l'image après scaling dans le preview
        val scaledWidth = modelSize * scale
        val scaledHeight = modelSize * scale
        
        // Calcul des offsets pour le centrage dans le preview
        val offsetX = (previewWidth - scaledWidth) / 2f
        val offsetY = (previewHeight - scaledHeight) / 2f
        
        // Calcul du ratio entre l'image capturée et la zone scaled du preview
        val imageToPreviewScaleX = scaledWidth / imageWidth
        val imageToPreviewScaleY = scaledHeight / imageHeight
        
        Log.d("BOUNDING_SCALE", "Image: ${imageWidth}x${imageHeight}, Preview: ${previewWidth}x${previewHeight}")
        Log.d("BOUNDING_SCALE", "Scale: $scale, Scaled: ${scaledWidth}x${scaledHeight}")
        Log.d("BOUNDING_SCALE", "Offsets: ($offsetX,$offsetY), ImageToPreview: ($imageToPreviewScaleX,$imageToPreviewScaleY)")
        
        // Dessiner les bounding boxes adaptées à l'image
        currentDetections?.forEach { box ->
            // Les coordonnées de la box sont déjà en coordonnées preview (depuis scaleBoundingBox)
            // On doit les convertir en coordonnées image capturée
            
            // 1. Retirer les offsets du preview pour obtenir les coordonnées dans la zone scaled
            val scaledX = box.x - offsetX
            val scaledY = box.y - offsetY
            
            // 2. Convertir les coordonnées preview vers image capturée
            val adaptedX = (scaledX / imageToPreviewScaleX).toInt()
            val adaptedY = (scaledY / imageToPreviewScaleY).toInt()
            val adaptedWidth = (box.width / imageToPreviewScaleX).toInt()
            val adaptedHeight = (box.height / imageToPreviewScaleY).toInt()
            
            // S'assurer que les coordonnées sont dans les limites de l'image
            val clampedX = maxOf(0, minOf(adaptedX, imageWidth.toInt()))
            val clampedY = maxOf(0, minOf(adaptedY, imageHeight.toInt()))
            val clampedWidth = maxOf(0, minOf(adaptedWidth, imageWidth.toInt() - clampedX))
            val clampedHeight = maxOf(0, minOf(adaptedHeight, imageHeight.toInt() - clampedY))
            
            Log.d("BOUNDING_SCALE", "Box preview: (${box.x},${box.y},${box.width},${box.height}) -> Image: ($clampedX,$clampedY,$clampedWidth,$clampedHeight)")
            
            val rect = Rect(clampedX, clampedY, clampedX + clampedWidth, clampedY + clampedHeight)
            
            // Dessiner le rectangle
            canvas.drawRect(rect, paint)
            
            // Dessiner le label et la confiance (adapter la position du texte)
            val labelText = "${box.label} (${(box.confidence * 100).toInt()}%)"
            val textX = clampedX.toFloat()
            val textY = (clampedY - 10).toFloat() // Léger ajustement pour le texte plus petit
            canvas.drawText(labelText, textX, textY, textPaint)
        }
        
        // Mettre à jour l'image avec les bounding boxes
        frozenImageView.setImageBitmap(mutableBitmap)
    }
    
    private fun exitValidationMode() {
        isValidationMode = false
        
        // Masquer l'image figée et les boutons de validation
        frozenImageView.visibility = View.GONE
        validationButtonsLayout.visibility = View.GONE
        captureButton.visibility = View.VISIBLE
        
        // Nettoyer les ressources
        frozenBitmap = null
        currentDetections = null
        
        Log.d("VALIDATION", "Sortie du mode validation")
    }
    
    private fun handleValidationChoice(choice: String) {
        val bitmap = frozenBitmap
        if (bitmap != null) {
            when (choice) {
                "yes" -> {
                    // Vérifier si c'est une capture manuelle ou automatique
                    val isManualCapture = (noButton.visibility == View.GONE)
                    
                    if (isManualCapture) {
                        // Capture manuelle : sauvegarder dans non_detection_fausse
                        saveValidationImage(bitmap, "non_detection_fausse")
                        Log.d("MANUAL_VALIDATION", "Image sauvegardée dans non_detection_fausse/")
                    } else {
                        // Détection automatique : sauvegarder dans detection_vraie
                        saveValidationImage(bitmap, "detection_vraie")
                        Log.d("VALIDATION", "Image sauvegardée dans detection_vraie/")
                    }
                }
                "no" -> {
                    // Seulement pour la détection automatique : sauvegarder dans fausse_alarme
                    saveValidationImage(bitmap, "fausse_alarme")
                    Log.d("VALIDATION", "Image sauvegardée dans fausse_alarme/")
                }
                "trash" -> {
                    Log.d("VALIDATION", "Image ignorée")
                }
            }
        }
        
        // Reprendre le flux temps réel (le compteur est déjà mis à jour dans displayResults)
        exitValidationMode()
    }
    
    private fun handleManualCapture() {
        // Capturer une nouvelle image fraîche pour la capture manuelle
        imageCapture?.let { capture ->
            capture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // Convertir l'image capturée en Bitmap
                        val freshBitmap = image.toBitmap()
                        image.close()
                        
                        // Mettre à jour frozenBitmap avec la nouvelle image
                        frozenBitmap = freshBitmap
                        
                        // Créer des détections factices pour le mode validation (ou utiliser les détections actuelles si présentes)
                        val detectionsForValidation = currentDetections ?: emptyList()
                        currentDetections = detectionsForValidation
                        
                        // Entrer en mode validation manuelle avec 2 boutons
                        enterManualValidationMode()
                        
                        Log.d("MANUAL_CAPTURE", "Capture manuelle fraîche déclenchée: ${freshBitmap.width}x${freshBitmap.height}")
                    }
                    
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("MANUAL_CAPTURE", "Erreur capture manuelle: ${exception.message}")
                        // En cas d'erreur, utiliser le frozenBitmap existant comme fallback
                        frozenBitmap?.let { bitmap ->
                            val detectionsForValidation = currentDetections ?: emptyList()
                            currentDetections = detectionsForValidation
                            enterManualValidationMode()
                        }
                    }
                }
            )
        } ?: run {
            Log.e("MANUAL_CAPTURE", "imageCapture non disponible")
        }
    }
    
    private fun enterManualValidationMode() {
        isValidationMode = true
        
        // Afficher l'image figée par-dessus le flux vidéo
        frozenBitmap?.let { bitmap ->
            // ÉTAPE 1: Recadrer l'image pour correspondre au preview
            val croppedBitmap = cropImageToPreviewPortrait(bitmap)
            Log.d("MANUAL_VALIDATION", "Image figée recadrée: ${croppedBitmap.width}x${croppedBitmap.height}")
            
            // ÉTAPE 2: Tourner l'image à 90 degrés comme dans getByteArrayFromBitmap
            val matrix = Matrix()
            matrix.postRotate(90f)
            val rotatedBitmap = Bitmap.createBitmap(croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true)
            
            // Libérer la mémoire du bitmap recadré
            croppedBitmap.recycle()
            
            frozenImageView.setImageBitmap(rotatedBitmap)
            frozenImageView.visibility = View.VISIBLE
            
            // Afficher les bounding boxes sur l'image figée
            displayBoundingBoxesOnFrozenImage(rotatedBitmap)
            
            // Mettre à jour le compteur avec les détections sur l'image capturée
            val capturedDetectionsCount = currentDetections?.size ?: 0
            detectionCounterTextView.text = "Détections: $capturedDetectionsCount"
        }
        
        // Afficher seulement les boutons Yes et Trash pour la capture manuelle avec les nouveaux textes
        yesButton.text = "Objet non détecté"
        trashButton.text = "Trash"
        
        yesButton.visibility = View.VISIBLE
        trashButton.visibility = View.VISIBLE
        noButton.visibility = View.GONE  // Masquer le bouton No
        
        validationButtonsLayout.visibility = View.VISIBLE
        captureButton.visibility = View.GONE
        
        Log.d("MANUAL_VALIDATION", "Entrée en mode validation manuelle (2 boutons)")
    }
    
    private fun saveValidationImage(bitmap: Bitmap, subfolder: String) {
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "Detection_$name")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, 
                 Environment.DIRECTORY_PICTURES + "/TrividaeDetection/$subfolder")
        }

        // Utiliser la même logique que takePhoto mais avec le bitmap figé
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ÉTAPE 1: Recadrer l'image pour correspondre au preview
                val croppedBitmap = cropImageToPreviewPortrait(bitmap)
                Log.d("VALIDATION", "Image recadrée: ${croppedBitmap.width}x${croppedBitmap.height}")
                
                // ÉTAPE 2: Tourner l'image à 90 degrés pour l'orientation correcte
                val matrix = Matrix()
                matrix.postRotate(90f)
                val rotatedBitmap = Bitmap.createBitmap(croppedBitmap, 0, 0, croppedBitmap.width, croppedBitmap.height, matrix, true)
                
                // Libérer la mémoire du bitmap recadré
                croppedBitmap.recycle()
                
                // Créer un fichier temporaire pour sauvegarder le bitmap
                val tempFile = File(cacheDir, "temp_image_$name.jpg")
                val fos = FileOutputStream(tempFile)
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.close()
                
                // Créer l'URI de destination
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                
                if (uri != null) {
                    // Copier vers MediaStore
                    val inputStream = tempFile.inputStream()
                    val outputStream = contentResolver.openOutputStream(uri)
                    inputStream.use { input ->
                        outputStream?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    // Nettoyer
                    tempFile.delete()
                    rotatedBitmap.recycle() // Libérer la mémoire
                    
                    Log.d("VALIDATION", "Image sauvegardée avec succès dans: $subfolder")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Image sauvegardée dans: $subfolder", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e("VALIDATION", "Impossible de créer l'URI pour la sauvegarde")
                    tempFile.delete()
                    rotatedBitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e("VALIDATION", "Erreur lors de la sauvegarde: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Erreur lors de la sauvegarde: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun takePhoto() {
        // Utiliser le flux de validation au lieu de la sauvegarde simple
        handleManualCapture()
    }
}