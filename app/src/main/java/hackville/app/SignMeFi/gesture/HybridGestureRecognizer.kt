package hackville.app.SignMeFi.gesture

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hybrid gesture recognizer that uses MediaPipe as a gatekeeper and Gemini for actual recognition.
 * 
 * Flow:
 * 1. MediaPipe continuously scans at ~30 FPS (local, no cost)
 * 2. When MediaPipe detects a hand (handLandmarks not empty), triggers Gemini
 * 3. Gemini processes frames at 2 FPS while hand is detected
 * 4. When MediaPipe detects hand has left, stops Gemini stream
 */
class HybridGestureRecognizer(
    private val mediaPipeRecognizer: MediaPipeGestureRecognizer,
    private val geminiRecognizer: GeminiGestureRecognizer,
    private var onHandDetectionChanged: ((Boolean) -> Unit)? = null,
    private var onGeminiStatusChanged: ((String) -> Unit)? = null
) : GestureRecognizer {
    
    private var isHandPresent = false
    private var lastGeminiFrameTime = 0L
    private val geminiFrameIntervalMs = 500L // 2 FPS = 500ms between frames
    private val stateMutex = Mutex()
    
    /**
     * Set callbacks for state changes (for debugging/monitoring)
     */
    fun setCallbacks(
        onHandDetectionChanged: ((Boolean) -> Unit)? = null,
        onGeminiStatusChanged: ((String) -> Unit)? = null
    ) {
        this.onHandDetectionChanged = onHandDetectionChanged
        this.onGeminiStatusChanged = onGeminiStatusChanged
    }
    
    override suspend fun recognizeGesture(bitmap: Bitmap): String? {
        return stateMutex.withLock {
            // Step 1: Check for hand presence using MediaPipe (fast, local, free)
            val handDetected = mediaPipeRecognizer.hasHand(bitmap)
            
            // Step 2: Update hand presence state
            val handJustAppeared = handDetected && !isHandPresent
            val handJustDisappeared = !handDetected && isHandPresent
            isHandPresent = handDetected
            
            // Notify hand detection state change
            if (handJustAppeared || handJustDisappeared) {
                onHandDetectionChanged?.invoke(isHandPresent)
            }
            
            if (handJustAppeared) {
                Log.d("HybridGestureRecognizer", "Hand detected - starting Gemini recognition")
                lastGeminiFrameTime = 0L // Reset timer to send first frame immediately
                onGeminiStatusChanged?.invoke("Hand detected, waiting for rate limit...")
            }
            
            if (handJustDisappeared) {
                Log.d("HybridGestureRecognizer", "Hand left - stopping Gemini recognition")
                onGeminiStatusChanged?.invoke("Hand left, idle")
                return@withLock null
            }
            
            // Step 3: If hand is present, send to Gemini at 2 FPS
            if (isHandPresent) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastGeminiFrame = currentTime - lastGeminiFrameTime
                
                if (timeSinceLastGeminiFrame >= geminiFrameIntervalMs) {
                    Log.d("HybridGestureRecognizer", "Sending frame to Gemini (2 FPS)")
                    lastGeminiFrameTime = currentTime
                    onGeminiStatusChanged?.invoke("Calling Gemini API...")
                    
                    // Send to Gemini for actual recognition
                    return@withLock try {
                        val result = geminiRecognizer.recognizeGesture(bitmap)
                        if (result != null) {
                            onGeminiStatusChanged?.invoke("Success: $result")
                        } else {
                            onGeminiStatusChanged?.invoke("No gesture detected")
                        }
                        result
                    } catch (e: Exception) {
                        Log.e("HybridGestureRecognizer", "Gemini recognition failed", e)
                        onGeminiStatusChanged?.invoke("Error: ${e.message ?: e.javaClass.simpleName}")
                        null
                    }
                } else {
                    // Too soon since last Gemini frame, skip this frame
                    val waitTime = geminiFrameIntervalMs - timeSinceLastGeminiFrame
                    onGeminiStatusChanged?.invoke("Rate limiting (${waitTime}ms remaining)")
                    return@withLock null
                }
            }
            
            // No hand detected, return null
            if (!isHandPresent) {
                onGeminiStatusChanged?.invoke("No hand detected")
            }
            null
        }
    }
    
    override fun release() {
        mediaPipeRecognizer.release()
        geminiRecognizer.release()
    }
}
