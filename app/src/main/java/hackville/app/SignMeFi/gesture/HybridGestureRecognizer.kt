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
 * 2. When MediaPipe detects a hand, starts sending requests to Gemini at 2 FPS (every 0.5s)
 * 3. Only one Gemini request is allowed at a time - new requests wait for the current one to finish
 * 4. Results are sent back via callback when they complete
 * 5. When hand leaves, cancels current request and stops sending new ones
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
    private val recordingDurationMs = 2000L // 2 seconds of recording
    private val stateMutex = Mutex()
    private var lastRequestTime = 0L
    private var handDetectionStartTime = 0L // Track when hand was first detected
    private var recordingEndTime = 0L // Track when recording should end
    private var isRecording = false // Track if we're in a recording session
    private var requestCounter = 0
    private val maxRequestsPerSession = 20 // Debug limit
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentRequest: Job? = null // Only one request at a time
    private var isPaused = false // Pause flag to stop sending new requests
    private val recordedFrames = mutableListOf<android.graphics.Bitmap>() // Store frames for 2-second video
    
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
        onPendingCountChanged?.invoke(if (currentRequest != null && currentRequest!!.isActive) 1 else 0)
    }
    
    /**
     * Pause sending new Gemini requests and cancel current request if any
     */
    suspend fun pause() {
        stateMutex.withLock {
            isPaused = true
            // Cancel current request if any
            currentRequest?.cancel()
            currentRequest = null
            updatePendingCount()
            Log.d("SignMeFi_Gemini", "HybridGestureRecognizer: Paused - cancelled current request")
            onGeminiStatusChanged?.invoke("Paused - processing current result")
        }
    }
    
    /**
     * Resume sending new Gemini requests
     */
    suspend fun resume() {
        stateMutex.withLock {
            isPaused = false
            lastRequestTime = 0L // Reset timer to allow immediate request
            Log.d("SignMeFi_Gemini", "HybridGestureRecognizer: Resumed - ready for new requests")
            onGeminiStatusChanged?.invoke("Resumed - ready for new requests")
        }
    }
    
    override suspend fun recognizeGesture(bitmap: Bitmap): String? {
        return stateMutex.withLock {
            // Step 1: If recording, store this frame
            val currentTime = System.currentTimeMillis()
            if (isRecording && recordingEndTime > currentTime) {
                // Store frame for video (keep a reasonable number, sample every few frames)
                // At 30 FPS for 2 seconds = 60 frames, sample every 3rd frame = ~20 frames
                if (recordedFrames.size < 20 || (recordedFrames.size % 3 == 0)) {
                    recordedFrames.add(bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false))
                }
            }
            
            // Step 2: Check for hand presence using MediaPipe (fast, local, free)
            val handDetected = mediaPipeRecognizer.hasHand(bitmap)
            
            // Step 3: Update hand presence state (only if not currently recording)
            val handJustAppeared = handDetected && !isHandPresent && !isRecording
            val handJustDisappeared = !handDetected && isHandPresent && !isRecording
            isHandPresent = handDetected
            
            // Notify hand detection state change
            if (handJustAppeared || handJustDisappeared) {
                onHandDetectionChanged?.invoke(isHandPresent)
            }
            
            if (handJustAppeared) {
                Log.d("SignMeFi_HandDetection", "Hand detected - starting 2 second video recording")
                handDetectionStartTime = currentTime
                recordingEndTime = currentTime + recordingDurationMs
                isRecording = true
                recordedFrames.clear() // Clear any previous frames
                lastRequestTime = 0L // Reset timer to send first frame immediately
                requestCounter = 0 // Reset counter
                onGeminiStatusChanged?.invoke("Recording 2 second video...")
                // Don't clear results - they expire automatically after 3 seconds
            }
            
            if (handJustDisappeared && isRecording) {
                Log.d("SignMeFi_HandDetection", "Hand left - continuing recording until 2 seconds complete")
                // Don't cancel recording - continue until 2 seconds are up
                // Don't cancel current request - let it finish naturally
                // Just stop sending new requests, but recording continues
                onGeminiStatusChanged?.invoke("Hand left - recording continues until 2s complete")
                // Continue recording even though hand left
            }
            
            // Step 4: If recording (hand was detected), check if recording is complete
            val timeSinceHandDetected = if (handDetectionStartTime > 0) {
                currentTime - handDetectionStartTime
            } else {
                0L
            }
            val timeUntilRecordingEnds = if (recordingEndTime > 0) {
                recordingEndTime - currentTime
            } else {
                0L
            }
            
            // Check if 2 seconds of recording have completed
            if (isRecording && timeUntilRecordingEnds <= 0) {
                Log.d("SignMeFi_HandDetection", "2 second recording complete - processing video")
                isRecording = false
                handDetectionStartTime = 0L
                recordingEndTime = 0L
                onGeminiStatusChanged?.invoke("Recording complete - processing ${recordedFrames.size} frames")
                
                // Process the recorded frames - send the last frame (most recent) to Gemini
                if (recordedFrames.isNotEmpty() && currentRequest == null) {
                    val lastFrame = recordedFrames.last()
                    recordedFrames.clear()
                    
                    // Send the last frame for processing
                    val requestJob = coroutineScope.launch {
                        val apiCallStartTime = System.currentTimeMillis()
                        try {
                            val result = geminiRecognizer.recognizeGesture(lastFrame)
                            
                            if (!isActive) {
                                Log.d("SignMeFi_Gemini", "Request was cancelled, ignoring result")
                                return@launch
                            }
                            
                            val apiCallEndTime = System.currentTimeMillis()
                            val callDuration = apiCallEndTime - apiCallStartTime
                            val callDurationSec = callDuration / 1000.0
                            
                            Log.d("SignMeFi_Gemini", "Video processing complete (${String.format("%.2f", callDurationSec)}s)")
                            
                            if (result != null) {
                                Log.d("SignMeFi_Gemini", "Result: $result")
                                onResultReceived?.invoke(result)
                                onGeminiStatusChanged?.invoke("Detected: $result")
                            } else {
                                Log.d("SignMeFi_Gemini", "No gesture detected in video")
                                onGeminiStatusChanged?.invoke("No gesture detected")
                            }
                        } catch (e: Exception) {
                            Log.e("SignMeFi_Gemini", "Error processing video", e)
                            onGeminiStatusChanged?.invoke("Error processing video: ${e.message}")
                        } finally {
                            stateMutex.withLock {
                                currentRequest = null
                                updatePendingCount()
                            }
                        }
                    }
                    
                    currentRequest = requestJob
                    updatePendingCount()
                }
                
                return@withLock null
            }
            
            // Step 5: If recording is active, just collect frames (don't send requests during recording)
            if (isRecording) {
                // Show recording status
                val remainingTime = maxOf(0L, timeUntilRecordingEnds)
                val remainingSec = remainingTime / 1000.0
                onGeminiStatusChanged?.invoke("Recording... ${String.format("%.1f", remainingSec)}s remaining (${recordedFrames.size} frames)")
                // Don't send requests during recording - just collect frames
                return@withLock null
            }
            
            // Step 6: If hand is present but not recording, send requests normally (for MediaPipe-only mode)
            if (isHandPresent && !isRecording) {
                // Don't send new requests if paused or if there's already a request in progress
                if (isPaused) {
                    onGeminiStatusChanged?.invoke("Paused - processing current result")
                    return@withLock null
                }
                
                // Only allow one request at a time
                if (currentRequest != null && currentRequest!!.isActive) {
                    onGeminiStatusChanged?.invoke("Waiting for current request to finish")
                    return@withLock null
                }
                
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
                    
                    val requestTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(currentTime))
                    Log.d("SignMeFi_Gemini", "═══════════════════════════════════════")
                    Log.d("SignMeFi_Gemini", "REQUEST #$requestCounter SENT at $requestTimestamp")
                    Log.d("SignMeFi_Gemini", "═══════════════════════════════════════")
                    
                    onGeminiStatusChanged?.invoke("Request #$requestCounter sent")
                    
                    // Create a copy of the bitmap for async processing
                    val bitmapCopy = bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                    
                    // Launch async request without blocking
                    val requestJob = coroutineScope.launch {
                        val apiCallStartTime = System.currentTimeMillis()
                        try {
                            val result = geminiRecognizer.recognizeGesture(bitmapCopy)
                            
                            // Check if coroutine was cancelled before processing result
                            if (!isActive) {
                                Log.d("SignMeFi_Gemini", "Request #$requestCounter was cancelled, ignoring result")
                                return@launch
                            }
                            
                            val apiCallEndTime = System.currentTimeMillis()
                            val callDuration = apiCallEndTime - apiCallStartTime
                            val callDurationSec = callDuration / 1000.0
                            
                            val responseTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(apiCallEndTime))
                            Log.d("SignMeFi_Gemini", "═══════════════════════════════════════")
                            Log.d("SignMeFi_Gemini", "RESPONSE #$requestCounter RECEIVED at $responseTimestamp")
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
                            // Cancellation is expected when hand leaves or paused - don't log as error
                            val apiCallEndTime = System.currentTimeMillis()
                            val callDuration = apiCallEndTime - apiCallStartTime
                            val callDurationSec = callDuration / 1000.0
                            Log.d("SignMeFi_Gemini", "Request #$requestCounter cancelled after ${String.format("%.2f", callDurationSec)}s")
                            // Re-throw to properly handle cancellation
                            throw e
                        } catch (e: Exception) {
                            // Check if coroutine was cancelled during error handling
                            if (!isActive) {
                                Log.d("SignMeFi_Gemini", "Request #$requestCounter was cancelled during error handling")
                                return@launch
                            }
                            
                            val apiCallEndTime = System.currentTimeMillis()
                            val callDuration = apiCallEndTime - apiCallStartTime
                            val callDurationSec = callDuration / 1000.0
                            val responseTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(apiCallEndTime))
                            Log.e("SignMeFi_Gemini", "═══════════════════════════════════════")
                            Log.e("SignMeFi_Gemini", "RESPONSE #$requestCounter ERROR at $responseTimestamp")
                            Log.e("SignMeFi_Gemini", "CALL DURATION: ${String.format("%.2f", callDurationSec)}s (${callDuration}ms)")
                            Log.e("SignMeFi_Gemini", "ERROR: ${e.message ?: e.javaClass.simpleName}")
                            Log.e("SignMeFi_Gemini", "═══════════════════════════════════════")
                            onGeminiStatusChanged?.invoke("Error #$requestCounter: ${e.message ?: e.javaClass.simpleName}")
                        } finally {
                            // Clear current request
                            stateMutex.withLock {
                                currentRequest = null
                                updatePendingCount()
                                // If hand is no longer present, log that request finished
                                if (!isHandPresent) {
                                    Log.d("SignMeFi_Gemini", "Request #$requestCounter finished after hand left - ready for next detection")
                                    onGeminiStatusChanged?.invoke("Request finished - ready for hand detection")
                                }
                            }
                        }
                    }
                    
                    // Track the current request
                    currentRequest = requestJob
                    updatePendingCount()
                } else {
                    // Still waiting for next request interval
                    val remainingMs = geminiCallIntervalMs - timeSinceLastRequest
                    val remainingSec = remainingMs / 1000.0
                    val hasRequest = if (currentRequest != null && currentRequest!!.isActive) "1" else "0"
                    onGeminiStatusChanged?.invoke("Waiting ${String.format("%.2f", remainingSec)}s... ($hasRequest active, $requestCounter/$maxRequestsPerSession calls)")
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
        currentRequest?.cancel()
        currentRequest = null
        mediaPipeRecognizer.release()
        geminiRecognizer.release()
    }
}
