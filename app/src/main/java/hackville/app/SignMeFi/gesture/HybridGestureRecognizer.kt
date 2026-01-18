package hackville.app.SignMeFi.gesture

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hybrid gesture recognizer that uses MediaPipe as a gatekeeper and Gemini for actual recognition.
 * 
 * Flow:
 * 1. MediaPipe continuously scans at ~30 FPS (local, no cost)
 * 2. When MediaPipe detects a hand, starts streaming requests to Gemini at 2 FPS (every 0.5s)
 * 3. Requests are sent asynchronously without waiting for previous responses
 * 4. Results are streamed back in order as they complete
 * 5. When hand leaves, stops sending requests and clears results
 */
class HybridGestureRecognizer(
    private val mediaPipeRecognizer: MediaPipeGestureRecognizer,
    private val geminiRecognizer: GeminiGestureRecognizer,
    private var onHandDetectionChanged: ((Boolean) -> Unit)? = null,
    private var onGeminiStatusChanged: ((String) -> Unit)? = null,
    private var onResultReceived: ((String) -> Unit)? = null,
    private var onResultsCleared: (() -> Unit)? = null,
    private var onPendingCountChanged: ((Int) -> Unit)? = null
) : GestureRecognizer {
    
    private var isHandPresent = false
    private val geminiCallIntervalMs = 500L // 0.5 seconds between calls (2 FPS)
    private val stateMutex = Mutex()
    private var lastRequestTime = 0L
    private var requestCounter = 0
    private val maxRequestsPerSession = 20 // Debug limit
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pendingRequests = mutableMapOf<Int, Job>() // Track requests by sequence number
    private var requestSequenceNumber = 0
    
    /**
     * Set callbacks for state changes (for debugging/monitoring)
     */
    fun setCallbacks(
        onHandDetectionChanged: ((Boolean) -> Unit)? = null,
        onGeminiStatusChanged: ((String) -> Unit)? = null,
        onResultReceived: ((String) -> Unit)? = null,
        onResultsCleared: (() -> Unit)? = null,
        onPendingCountChanged: ((Int) -> Unit)? = null
    ) {
        this.onHandDetectionChanged = onHandDetectionChanged
        this.onGeminiStatusChanged = onGeminiStatusChanged
        this.onResultReceived = onResultReceived
        this.onResultsCleared = onResultsCleared
        this.onPendingCountChanged = onPendingCountChanged
    }
    
    /**
     * Notify about pending count changes
     */
    private fun updatePendingCount() {
        onPendingCountChanged?.invoke(pendingRequests.size)
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
                Log.d("SignMeFi_HandDetection", "Hand detected - starting Gemini streaming")
                lastRequestTime = 0L // Reset timer to send first frame immediately
                requestCounter = 0 // Reset counter
                onGeminiStatusChanged?.invoke("Hand detected, streaming at 2 FPS")
                // Don't clear results - they expire automatically after 3 seconds
            }
            
            if (handJustDisappeared) {
                Log.d("SignMeFi_HandDetection", "Hand left - stopping new requests (${pendingRequests.size} pending will complete)")
                // Don't cancel pending requests - let them finish naturally
                // Just stop sending new requests
                // Results will persist and pending requests can still add their results
                lastRequestTime = 0L // Reset so we can start fresh when hand is detected again
                requestCounter = 0 // Reset counter for next session
                onGeminiStatusChanged?.invoke("Hand left, ${pendingRequests.size} requests pending")
                // Don't clear results - let them persist and let pending requests add theirs
                return@withLock null
            }
            
            // Step 3: If hand is present, send requests every 0.5 seconds (2 FPS) asynchronously
            if (isHandPresent) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastRequest = if (lastRequestTime > 0) {
                    currentTime - lastRequestTime
                } else {
                    Long.MAX_VALUE // First request, send immediately
                }
                
                // Check if we've reached the debug limit
                if (requestCounter >= maxRequestsPerSession) {
                    onGeminiStatusChanged?.invoke("Debug limit reached (20 calls), waiting for hand to leave")
                    return@withLock null
                }
                
                // Send new request if enough time has passed
                if (timeSinceLastRequest >= geminiCallIntervalMs) {
                    lastRequestTime = currentTime
                    requestCounter++
                    val sequenceNumber = requestSequenceNumber++
                    
                    val requestTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(currentTime))
                    Log.d("SignMeFi_Gemini", "═══════════════════════════════════════")
                    Log.d("SignMeFi_Gemini", "REQUEST #$requestCounter SENT at $requestTimestamp (Sequence: $sequenceNumber)")
                    Log.d("SignMeFi_Gemini", "═══════════════════════════════════════")
                    
                    onGeminiStatusChanged?.invoke("Request #$requestCounter sent (${pendingRequests.size} pending)")
                    
                    // Create a copy of the bitmap for async processing
                    val bitmapCopy = bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                    
                    // Launch async request without blocking
                    val requestJob = coroutineScope.launch {
                        val apiCallStartTime = System.currentTimeMillis()
                        try {
                            val result = geminiRecognizer.recognizeGesture(bitmapCopy)
                            
                            // Check if coroutine was cancelled before processing result
                            if (!isActive) {
                                Log.d("SignMeFi_Gemini", "Request #$requestCounter (Sequence: $sequenceNumber) was cancelled, ignoring result")
                                return@launch
                            }
                            
                            val apiCallEndTime = System.currentTimeMillis()
                            val callDuration = apiCallEndTime - apiCallStartTime
                            val callDurationSec = callDuration / 1000.0
                            
                            val responseTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(apiCallEndTime))
                            Log.d("SignMeFi_Gemini", "═══════════════════════════════════════")
                            Log.d("SignMeFi_Gemini", "RESPONSE #$requestCounter RECEIVED at $responseTimestamp (Sequence: $sequenceNumber)")
                            Log.d("SignMeFi_Gemini", "CALL DURATION: ${String.format("%.2f", callDurationSec)}s (${callDuration}ms)")
                            
                            if (result != null) {
                                Log.d("SignMeFi_Gemini", "Result: $result")
                                onResultReceived?.invoke(result)
                                onGeminiStatusChanged?.invoke("Result #$requestCounter: $result (${String.format("%.2f", callDurationSec)}s)")
                            } else {
                                Log.d("SignMeFi_Gemini", "No gesture detected")
                                onGeminiStatusChanged?.invoke("Result #$requestCounter: No gesture (${String.format("%.2f", callDurationSec)}s)")
                            }
                            Log.d("SignMeFi_Gemini", "═══════════════════════════════════════")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Cancellation is expected when hand leaves - don't log as error
                            val apiCallEndTime = System.currentTimeMillis()
                            val callDuration = apiCallEndTime - apiCallStartTime
                            val callDurationSec = callDuration / 1000.0
                            Log.d("SignMeFi_Gemini", "Request #$requestCounter (Sequence: $sequenceNumber) cancelled after ${String.format("%.2f", callDurationSec)}s (hand left)")
                            // Re-throw to properly handle cancellation
                            throw e
                        } catch (e: Exception) {
                            // Check if coroutine was cancelled during error handling
                            if (!isActive) {
                                Log.d("SignMeFi_Gemini", "Request #$requestCounter (Sequence: $sequenceNumber) was cancelled during error handling")
                                return@launch
                            }
                            
                            val apiCallEndTime = System.currentTimeMillis()
                            val callDuration = apiCallEndTime - apiCallStartTime
                            val callDurationSec = callDuration / 1000.0
                            val responseTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(apiCallEndTime))
                            Log.e("SignMeFi_Gemini", "═══════════════════════════════════════")
                            Log.e("SignMeFi_Gemini", "RESPONSE #$requestCounter ERROR at $responseTimestamp (Sequence: $sequenceNumber)")
                            Log.e("SignMeFi_Gemini", "CALL DURATION: ${String.format("%.2f", callDurationSec)}s (${callDuration}ms)")
                            Log.e("SignMeFi_Gemini", "ERROR: ${e.message ?: e.javaClass.simpleName}")
                            Log.e("SignMeFi_Gemini", "═══════════════════════════════════════")
                            onGeminiStatusChanged?.invoke("Error #$requestCounter: ${e.message ?: e.javaClass.simpleName}")
                        } finally {
                            // Remove from pending requests
                            pendingRequests.remove(sequenceNumber)
                            updatePendingCount()
                        }
                    }
                    
                    // Track the request
                    pendingRequests[sequenceNumber] = requestJob
                    updatePendingCount()
                } else {
                    // Still waiting for next request interval
                    val remainingMs = geminiCallIntervalMs - timeSinceLastRequest
                    val remainingSec = remainingMs / 1000.0
                    val pendingCount = pendingRequests.size
                    onGeminiStatusChanged?.invoke("Waiting ${String.format("%.2f", remainingSec)}s... ($pendingCount pending, $requestCounter/$maxRequestsPerSession calls)")
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
        coroutineScope.cancel()
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        mediaPipeRecognizer.release()
        geminiRecognizer.release()
    }
}
