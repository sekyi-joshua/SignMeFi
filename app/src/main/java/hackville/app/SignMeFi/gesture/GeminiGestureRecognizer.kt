package hackville.app.SignMeFi.gesture

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemini-based gesture recognizer using Google's Generative AI.
 */
class GeminiGestureRecognizer(
    private val apiKey: String,
    private val modelName: String = "gemini-2.5-flash" // Using Flash model for speed
) : GestureRecognizer {
    
    private val model: GenerativeModel? by lazy {
        if (apiKey.isNotBlank() && apiKey != "YOUR_API_KEY") {
            try {
                GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey
                )
            } catch (e: Exception) {
                Log.e("SignMeFi_Gemini", "Failed to create model", e)
                null
            }
        } else {
            null
        }
    }
    
    // Prompt to detect both letters and words
    private val signLanguagePrompt = "What sign language letter or word is shown in this image? Respond with ONLY the letter or word detected, or 'UNKNOWN' if nothing is clear. For words, respond with the complete word."
    
    private val requestMutex = Mutex() // Ensures only one request at a time
    
    override suspend fun recognizeGesture(bitmap: Bitmap): String? {
        val currentModel = model ?: run {
            val errorMsg = "Model not initialized. Check API key. API key is ${if (apiKey.isBlank()) "blank" else "set (length: ${apiKey.length})"}"
            Log.e("SignMeFi_Gemini", errorMsg)
            throw IllegalStateException(errorMsg)
        }
        
        // Ensure only one request at a time
        return requestMutex.withLock {
            val requestStartTime = System.currentTimeMillis()
            val totalRequestStartTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(requestStartTime))
            Log.d("SignMeFi_Gemini", "┌─ TOTAL REQUEST STARTED at $totalRequestStartTimestamp")
            
            try {
                val result = executeRequest(currentModel, bitmap)
                val totalRequestEndTime = System.currentTimeMillis()
                val totalRequestDuration = totalRequestEndTime - requestStartTime
                val totalRequestDurationSec = totalRequestDuration / 1000.0
                val totalRequestEndTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(totalRequestEndTime))
                Log.d("SignMeFi_Gemini", "└─ TOTAL REQUEST COMPLETED at $totalRequestEndTimestamp")
                Log.d("SignMeFi_Gemini", "   TOTAL REQUEST DURATION: ${String.format("%.2f", totalRequestDurationSec)}s (${totalRequestDuration}ms)")
                result
            } catch (e: Exception) {
                val totalRequestEndTime = System.currentTimeMillis()
                val totalRequestDuration = totalRequestEndTime - requestStartTime
                val totalRequestDurationSec = totalRequestDuration / 1000.0
                val totalRequestEndTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(totalRequestEndTime))
                Log.e("SignMeFi_Gemini", "└─ TOTAL REQUEST FAILED at $totalRequestEndTimestamp")
                Log.e("SignMeFi_Gemini", "   TOTAL REQUEST DURATION: ${String.format("%.2f", totalRequestDurationSec)}s (${totalRequestDuration}ms)")
                throw e
            }
        }
    }
    
    /**
     * Executes the actual API request to Gemini with retry on rate limit.
     */
    private suspend fun executeRequest(currentModel: GenerativeModel, bitmap: Bitmap): String? {
        return executeRequestWithRetry(currentModel, bitmap, maxRetries = 3)
    }
    
    /**
     * Executes the actual API request to Gemini with retry logic for rate limits.
     */
    private suspend fun executeRequestWithRetry(
        currentModel: GenerativeModel, 
        bitmap: Bitmap, 
        maxRetries: Int = 3,
        attempt: Int = 1
    ): String? {
        return try {
            // Downscale to 640x480 for faster processing (maintains aspect ratio)
            val targetWidth = 640
            val targetHeight = 480
            val scale = minOf(targetWidth.toFloat() / bitmap.width, targetHeight.toFloat() / bitmap.height)
            val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            
            val resizedBitmap = if (bitmap.width != newWidth || bitmap.height != newHeight) {
                Log.d("SignMeFi_Gemini", "Downscaling bitmap from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight} for faster processing")
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }
            
            Log.d("SignMeFi_Gemini", "Sending request to Gemini API (model: $modelName, image size: ${resizedBitmap.width}x${resizedBitmap.height})...")
            Log.d("SignMeFi_Gemini", "API endpoint: generativelanguage.googleapis.com (via Google AI SDK)")
            
            // Generate content with image and prompt
            val apiCallStartTime = System.currentTimeMillis()
            val apiCallStartTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(apiCallStartTime))
            Log.d("SignMeFi_Gemini", ">>> API CALL STARTED at $apiCallStartTimestamp")
            
            val response = currentModel.generateContent(
                content {
                    text(signLanguagePrompt)
                    image(resizedBitmap)
                }
            )
            
            val apiCallEndTime = System.currentTimeMillis()
            val apiCallDuration = apiCallEndTime - apiCallStartTime
            val apiCallDurationSec = apiCallDuration / 1000.0
            val apiCallEndTimestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(apiCallEndTime))
            Log.d("SignMeFi_Gemini", "<<< API CALL COMPLETED at $apiCallEndTimestamp")
            Log.d("SignMeFi_Gemini", "    API CALL DURATION: ${String.format("%.2f", apiCallDurationSec)}s (${apiCallDuration}ms)")
            Log.d("SignMeFi_Gemini", "Received response from Gemini")
            
            // Extract text from response
            val responseText = response.text?.trim()
            
            Log.d("SignMeFi_Gemini", "Response text: $responseText")
            
            if (!responseText.isNullOrBlank() && responseText != "UNKNOWN") {
                responseText
            } else {
                Log.d("SignMeFi_Gemini", "No valid gesture detected (response was blank or UNKNOWN)")
                null
            }
        } catch (e: java.net.UnknownHostException) {
            val hostName = e.message?.substringAfter("Unable to resolve host ")?.substringBefore(":") 
                ?: e.message?.substringAfter("Unable to resolve host ") 
                ?: "unknown host"
            val errorMsg = "Unable to resolve host '$hostName'. This usually means:\n" +
                    "1. No internet connection - Check WiFi/mobile data\n" +
                    "2. DNS issues - Try restarting your device/emulator\n" +
                    "3. Emulator network - If using emulator, ensure it has internet access\n" +
                    "4. Firewall/VPN blocking - Check if firewall/VPN is blocking Google APIs\n\n" +
                    "Technical details: ${e.message}"
            Log.e("SignMeFi_Gemini", errorMsg, e)
            Log.e("SignMeFi_Gemini", "Full exception: ${e.javaClass.name}", e)
            e.printStackTrace()
            throw RuntimeException(errorMsg, e)
        } catch (e: java.net.SocketTimeoutException) {
            val errorMsg = "Request timed out. The API took too long to respond. Details: ${e.message}"
            Log.e("SignMeFi_Gemini", errorMsg, e)
            throw RuntimeException(errorMsg, e)
        } catch (e: java.io.IOException) {
            val errorMsg = "Network error: ${e.message}. Check your internet connection."
            Log.e("SignMeFi_Gemini", errorMsg, e)
            e.printStackTrace()
            throw RuntimeException(errorMsg, e)
        } catch (e: Exception) {
            // Check if the cause is UnknownHostException (common when wrapped by GoogleGenerativeAIException)
            val unknownHostCause = findCauseOfType<java.net.UnknownHostException>(e)
            if (unknownHostCause != null) {
                val hostName = unknownHostCause.message?.substringAfter("Unable to resolve host ")?.substringBefore(":") 
                    ?: unknownHostCause.message?.substringAfter("Unable to resolve host ") 
                    ?: unknownHostCause.message?.substringAfter("\"")?.substringBefore("\"")
                    ?: "generativelanguage.googleapis.com"
                val errorMsg = "Unable to resolve host '$hostName'. This usually means:\n" +
                        "1. No internet connection - Check WiFi/mobile data\n" +
                        "2. DNS issues - Try restarting your device/emulator\n" +
                        "3. Emulator network - If using emulator:\n" +
                        "   - Go to Emulator Settings > Extended Controls (⋯) > Settings > Proxy\n" +
                        "   - Try 'Cold Boot Now' from AVD Manager\n" +
                        "   - Check that DNS is set (usually 8.8.8.8 or your router's DNS)\n" +
                        "4. Firewall/VPN blocking - Check if firewall/VPN is blocking Google APIs\n\n" +
                        "Technical details: ${unknownHostCause.message}"
                Log.e("SignMeFi_Gemini", errorMsg, e)
                Log.e("SignMeFi_Gemini", "Wrapped exception type: ${e.javaClass.simpleName}")
                e.printStackTrace()
                throw RuntimeException(errorMsg, e)
            }
            
            // Log detailed error information
            val exceptionType = e.javaClass.simpleName
            val exceptionMessage = e.message ?: "Unknown error"
            val cause = e.cause?.message ?: ""
            val stackTrace = e.stackTrace.take(5).joinToString("\n") { it.toString() }
            
            Log.e("SignMeFi_Gemini", "Exception type: $exceptionType")
            Log.e("SignMeFi_Gemini", "Exception message: $exceptionMessage")
            Log.e("SignMeFi_Gemini", "Cause: $cause")
            Log.e("SignMeFi_Gemini", "Stack trace (first 5 lines):\n$stackTrace")
            e.printStackTrace()
            
            // Check if cause message contains host resolution errors
            val hasHostResolutionError = cause.contains("Unable to resolve host", ignoreCase = true) ||
                    cause.contains("No address associated with hostname", ignoreCase = true) ||
                    exceptionMessage.contains("Unable to resolve host", ignoreCase = true)
            
            // Provide user-friendly error message with details
            val userMsg = when {
                hasHostResolutionError -> {
                    val hostMatch = Regex("Unable to resolve host \"?([^\":\\s]+)").find(cause + exceptionMessage)
                    val host = hostMatch?.groupValues?.get(1) ?: "generativelanguage.googleapis.com"
                    "Unable to resolve host '$host'. Check internet connection and DNS settings. " +
                            "If using emulator, try cold boot or check network settings."
                }
                exceptionMessage.contains("401", ignoreCase = true) || 
                exceptionMessage.contains("unauthorized", ignoreCase = true) -> 
                    "Invalid API key (401 Unauthorized). Please check your API key in GestureRecognizerModule.kt"
                exceptionMessage.contains("400", ignoreCase = true) || 
                exceptionMessage.contains("bad request", ignoreCase = true) -> 
                    "Invalid request (400 Bad Request). Check API key and model name ($modelName). Details: $exceptionMessage"
                exceptionMessage.contains("403", ignoreCase = true) || 
                exceptionMessage.contains("forbidden", ignoreCase = true) -> 
                    "API access denied (403 Forbidden). Check API key permissions. Details: $exceptionMessage"
                exceptionMessage.contains("429", ignoreCase = true) ||
                exceptionMessage.contains("quota", ignoreCase = true) ||
                exceptionMessage.contains("rate limit", ignoreCase = true) -> 
                    "API quota/rate limit exceeded (429). Details: $exceptionMessage"
                exceptionMessage.contains("500", ignoreCase = true) ||
                exceptionMessage.contains("internal server", ignoreCase = true) -> 
                    "Server error (500). Gemini API is having issues. Try again later. Details: $exceptionMessage"
                else -> "Error ($exceptionType): $exceptionMessage${if (cause.isNotBlank()) " (Cause: $cause)" else ""}"
            }
            
            // If it's a rate limit error and we haven't exceeded max retries, handle retry
            val isRateLimit = exceptionMessage.contains("429", ignoreCase = true) ||
                    exceptionMessage.contains("quota", ignoreCase = true) ||
                    exceptionMessage.contains("rate limit", ignoreCase = true)
            
            if (isRateLimit && attempt <= maxRetries) {
                // Try to extract retry-after time from error message, default to 10 seconds
                val retryAfterMs = extractRetryAfterMs(exceptionMessage, cause) ?: 10_000L
                Log.w("SignMeFi_Gemini", "Rate limit hit (attempt $attempt/$maxRetries). Waiting ${retryAfterMs}ms before retry...")
                delay(retryAfterMs)
                // Retry with the same bitmap
                return executeRequestWithRetry(currentModel, bitmap, maxRetries, attempt + 1)
            }
            
            throw Exception(userMsg, e)
        }
    }
    
    /**
     * Extracts retry-after time in milliseconds from error message.
     * Looks for patterns like "retry after 5s", "retry-after: 10", etc.
     */
    private fun extractRetryAfterMs(message: String, cause: String): Long? {
        val combined = "$message $cause"
        
        // Look for "retry after X seconds" or "retry-after: X"
        val patterns = listOf(
            Regex("retry[\\s-]after[\\s:]+(\\d+)\\s*seconds?", RegexOption.IGNORE_CASE),
            Regex("retry[\\s-]after[\\s:]+(\\d+)\\s*s", RegexOption.IGNORE_CASE),
            Regex("retry[\\s-]after[\\s:]+(\\d+)", RegexOption.IGNORE_CASE),
            Regex("wait[\\s:]+(\\d+)\\s*seconds?", RegexOption.IGNORE_CASE),
            Regex("wait[\\s:]+(\\d+)\\s*s", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(combined)
            if (match != null) {
                val seconds = match.groupValues[1].toIntOrNull()
                if (seconds != null && seconds > 0) {
                    return (seconds * 1000L).coerceAtMost(60_000L) // Cap at 60 seconds
                }
            }
        }
        
        return null
    }
    
    /**
     * Recursively searches the exception cause chain for a specific exception type.
     */
    private inline fun <reified T : Throwable> findCauseOfType(throwable: Throwable): T? {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is T) {
                return current
            }
            current = current.cause
        }
        return null
    }
    
    override fun release() {
        // Gemini model doesn't need explicit cleanup
    }
}
