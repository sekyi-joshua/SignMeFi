package hackville.app.SignMeFi.gesture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer

/**
 * MediaPipe-based gesture recognizer for sign language detection.
 * 
 * Note: This requires a MediaPipe gesture recognizer model file.
 * To use this recognizer:
 * 1. Download the gesture_recognizer.task model from MediaPipe
 * 2. Place it in app/src/main/assets/gesture_recognizer.task
 * 3. The model will be automatically loaded from assets
 * 
 * Model download: https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task
 */
class MediaPipeGestureRecognizer(
    private val context: Context
) : hackville.app.SignMeFi.gesture.GestureRecognizer {
    
    private var recognizer: GestureRecognizer? = null
    
    init {
        initializeRecognizer()
    }
    
    private fun initializeRecognizer() {
        try {
            // MediaPipe GestureRecognizer requires a model file in assets folder
            // Download from: https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task
            // Place in: app/src/main/assets/gesture_recognizer.task
            
            // Verify the asset exists
            val modelFileName = "gesture_recognizer.task"
            try {
                context.assets.open(modelFileName).close()
                Log.d("MediaPipeGestureRecognizer", "Model file found in assets: $modelFileName")
            } catch (e: Exception) {
                Log.e("MediaPipeGestureRecognizer", "Model file not found in assets: $modelFileName. Please ensure the file is in app/src/main/assets/", e)
                recognizer = null
                return
            }
            
            // Create options with model loaded from assets
            // setModelAssetPath() automatically loads from app/src/main/assets/ when given just a filename
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(modelFileName) // Loads from assets folder
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            
            recognizer = GestureRecognizer.createFromOptions(context, options)
            Log.d("MediaPipeGestureRecognizer", "Gesture recognizer initialized successfully from assets/$modelFileName")
        } catch (e: Exception) {
            Log.e("MediaPipeGestureRecognizer", "Failed to initialize recognizer. Make sure gesture_recognizer.task is in assets folder.", e)
            recognizer = null
        }
    }
    
    /**
     * Checks if a hand is present in the image (without requiring a recognized gesture).
     * @param bitmap The image to check
     * @return true if hand landmarks are detected, false otherwise
     */
    suspend fun hasHand(bitmap: Bitmap): Boolean {
        val recognizer = this.recognizer ?: return false
        
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = recognizer.recognize(mpImage)
            
            // Check if hand landmarks exist (hand is present)
            // result.landmarks is a List<List<NormalizedLandmark>> - one list per detected hand
            val landmarks = result.landmarks()
            val hasHand = landmarks.isNotEmpty()
            
            if (hasHand) {
                Log.d("MediaPipeGestureRecognizer", "Hand detected (${landmarks.size} hand(s))")
            }
            
            hasHand
        } catch (e: Exception) {
            Log.e("MediaPipeGestureRecognizer", "Error detecting hand", e)
            false
        }
    }
    
    override suspend fun recognizeGesture(bitmap: Bitmap): String? {
        val recognizer = this.recognizer ?: run {
            Log.e("MediaPipeGestureRecognizer", "Recognizer not initialized. Please ensure gesture_recognizer.task model file is in assets folder.")
            return null
        }
        
        return try {
            // Convert Bitmap to MPImage
            val mpImage = BitmapImageBuilder(bitmap).build()
            
            // Recognize gesture
            val result = recognizer.recognize(mpImage)
            
            // Extract gesture categories
            // gestures() returns List<List<Category>> - one list per detected hand
            val gestures = result.gestures()
            if (gestures.isNotEmpty()) {
                // Get gestures for the first detected hand
                val handGestures = gestures[0]
                
                if (handGestures.isNotEmpty()) {
                    // Get the top gesture (highest score)
                    val topGesture = handGestures.maxByOrNull { it.score() }
                    val categoryName = topGesture?.categoryName()
                    Log.d("MediaPipeGestureRecognizer", "Recognized gesture: $categoryName")
                    return categoryName
                }
            }
            
            null
        } catch (e: Exception) {
            Log.e("MediaPipeGestureRecognizer", "Error recognizing gesture", e)
            null
        }
    }
    
    override fun release() {
        recognizer?.close()
        recognizer = null
    }
}
