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
    private val geminiRecognizer: GeminiGestureRecognizer
) : GestureRecognizer {
    
    private var isHandPresent = false
    private var lastGeminiFrameTime = 0L
    private val geminiFrameIntervalMs = 500L // 2 FPS = 500ms between frames
    private val stateMutex = Mutex()
    
    override suspend fun recognizeGesture(bitmap: Bitmap): String? {
        return stateMutex.withLock {
            // Step 1: Check for hand presence using MediaPipe (fast, local, free)
            val handDetected = mediaPipeRecognizer.hasHand(bitmap)
            
            // Step 2: Update hand presence state
            val handJustAppeared = handDetected && !isHandPresent
            val handJustDisappeared = !handDetected && isHandPresent
            isHandPresent = handDetected
            
            if (handJustAppeared) {
                Log.d("HybridGestureRecognizer", "Hand detected - starting Gemini recognition")
                lastGeminiFrameTime = 0L // Reset timer to send first frame immediately
            }
            
            if (handJustDisappeared) {
                Log.d("HybridGestureRecognizer", "Hand left - stopping Gemini recognition")
                return@withLock null
            }
            
            // Step 3: If hand is present, send to Gemini at 2 FPS
            if (isHandPresent) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastGeminiFrame = currentTime - lastGeminiFrameTime
                
                if (timeSinceLastGeminiFrame >= geminiFrameIntervalMs) {
                    Log.d("HybridGestureRecognizer", "Sending frame to Gemini (2 FPS)")
                    lastGeminiFrameTime = currentTime
                    
                    // Send to Gemini for actual recognition
                    return@withLock try {
                        geminiRecognizer.recognizeGesture(bitmap)
                    } catch (e: Exception) {
                        Log.e("HybridGestureRecognizer", "Gemini recognition failed", e)
                        null
                    }
                } else {
                    // Too soon since last Gemini frame, skip this frame
                    Log.d("HybridGestureRecognizer", "Skipping Gemini frame (rate limiting to 2 FPS)")
                    return@withLock null
                }
            }
            
            // No hand detected, return null
            null
        }
    }
    
    override fun release() {
        mediaPipeRecognizer.release()
        geminiRecognizer.release()
    }
}
