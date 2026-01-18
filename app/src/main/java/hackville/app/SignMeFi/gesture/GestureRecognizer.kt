package hackville.app.SignMeFi.gesture

import android.graphics.Bitmap

/**
 * Interface for gesture recognition implementations.
 * Allows swapping between different recognition backends (Gemini, MediaPipe, etc.)
 */
interface GestureRecognizer {
    /**
     * Recognizes a gesture from a bitmap image.
     * @param bitmap The image containing the gesture
     * @return The recognized gesture text, or null if no gesture was detected
     */
    suspend fun recognizeGesture(bitmap: Bitmap): String?
    
    /**
     * Releases any resources held by the recognizer.
     */
    fun release()
}
