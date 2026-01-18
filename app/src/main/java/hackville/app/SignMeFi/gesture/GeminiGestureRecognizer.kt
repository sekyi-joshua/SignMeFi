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
    private val modelName: String = "gemini-2.5-flash"
) : GestureRecognizer {
    
    private val model: GenerativeModel? by lazy {
        if (apiKey.isNotBlank() && apiKey != "YOUR_API_KEY") {
            try {
                GenerativeModel(
                    modelName = modelName,
                    apiKey = apiKey
                )
            } catch (e: Exception) {
                Log.e("GeminiGestureRecognizer", "Failed to create model", e)
                null
            }
        } else {
            null
        }
    }
    
    private val signLanguagePrompt = """
        Look at this image of a person's hands. Identify what sign language letter, word, or symbol they are signing.
        Respond with ONLY the letter, word, or symbol name in plain text. 
        If you cannot clearly identify a sign, respond with "UNKNOWN".
        Do not include any explanations or additional text.
    """.trimIndent()
    
    private val requestMutex = Mutex() // Ensures only one request at a time
    private var lastProcessedTime = 0L
    private val rateLimitIntervalMs = 10_000L // 10 seconds between API calls
    
    override suspend fun recognizeGesture(bitmap: Bitmap): String? {
        val currentModel = model ?: run {
            val errorMsg = "Model not initialized. Check API key. API key is ${if (apiKey.isBlank()) "blank" else "set (length: ${apiKey.length})"}"
            Log.e("GeminiGestureRecognizer", errorMsg)
            throw IllegalStateException(errorMsg)
        }
        
        // Ensure only one request at a time and wait for rate limit
        return requestMutex.withLock {
            val currentTime = System.currentTimeMillis()
            val timeSinceLastRequest = currentTime - lastProcessedTime
            
            // Wait if we're within the rate limit window
            if (timeSinceLastRequest < rateLimitIntervalMs) {
                val waitTime = rateLimitIntervalMs - timeSinceLastRequest
                Log.d("GeminiGestureRecognizer", "Rate limited. Waiting ${waitTime}ms before next request")
                delay(waitTime)
            }
            
            // Now execute the request
            val requestStartTime = System.currentTimeMillis()
            
            try {
                val result = executeRequest(currentModel, bitmap)
                lastProcessedTime = System.currentTimeMillis()
                Log.d("GeminiGestureRecognizer", "Request completed successfully in ${System.currentTimeMillis() - requestStartTime}ms")
                result
            } catch (e: Exception) {
                // Request failed - update last processed time and wait for rate limit before allowing retry
                lastProcessedTime = System.currentTimeMillis()
                Log.e("GeminiGestureRecognizer", "Request failed. Will wait ${rateLimitIntervalMs}ms before allowing next attempt.", e)
                
                // Re-throw the exception after updating the timestamp
                // The next request will wait for the rate limit interval
                throw e
            }
        }
    }
    
    /**
     * Executes the actual API request to Gemini.
     */
    private suspend fun executeRequest(currentModel: GenerativeModel, bitmap: Bitmap): String? {
        return try {
            // Resize bitmap if too large (Gemini has size limits)
            val resizedBitmap = if (bitmap.width > 1024 || bitmap.height > 1024) {
                val scale = minOf(1024f / bitmap.width, 1024f / bitmap.height)
                val newWidth = (bitmap.width * scale).toInt()
                val newHeight = (bitmap.height * scale).toInt()
                Log.d("GeminiGestureRecognizer", "Resizing bitmap from ${bitmap.width}x${bitmap.height} to ${newWidth}x${newHeight}")
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }
            
            Log.d("GeminiGestureRecognizer", "Sending request to Gemini API (model: $modelName, image size: ${resizedBitmap.width}x${resizedBitmap.height})...")
            Log.d("GeminiGestureRecognizer", "API endpoint: generativelanguage.googleapis.com (via Google AI SDK)")
            
            // Generate content with image and prompt
            val response = currentModel.generateContent(
                content {
                    text(signLanguagePrompt)
                    image(resizedBitmap)
                }
            )
            
            Log.d("GeminiGestureRecognizer", "Received response from Gemini")
            
            // Extract text from response
            val responseText = response.text?.trim()
            
            Log.d("GeminiGestureRecognizer", "Response text: $responseText")
            
            if (!responseText.isNullOrBlank() && responseText != "UNKNOWN") {
                responseText
            } else {
                Log.d("GeminiGestureRecognizer", "No valid gesture detected (response was blank or UNKNOWN)")
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
            Log.e("GeminiGestureRecognizer", errorMsg, e)
            Log.e("GeminiGestureRecognizer", "Full exception: ${e.javaClass.name}", e)
            e.printStackTrace()
            throw RuntimeException(errorMsg, e)
        } catch (e: java.net.SocketTimeoutException) {
            val errorMsg = "Request timed out. The API took too long to respond. Details: ${e.message}"
            Log.e("GeminiGestureRecognizer", errorMsg, e)
            throw RuntimeException(errorMsg, e)
        } catch (e: java.io.IOException) {
            val errorMsg = "Network error: ${e.message}. Check your internet connection."
            Log.e("GeminiGestureRecognizer", errorMsg, e)
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
                Log.e("GeminiGestureRecognizer", errorMsg, e)
                Log.e("GeminiGestureRecognizer", "Wrapped exception type: ${e.javaClass.simpleName}")
                e.printStackTrace()
                throw RuntimeException(errorMsg, e)
            }
            
            // Log detailed error information
            val exceptionType = e.javaClass.simpleName
            val exceptionMessage = e.message ?: "Unknown error"
            val cause = e.cause?.message ?: ""
            val stackTrace = e.stackTrace.take(5).joinToString("\n") { it.toString() }
            
            Log.e("GeminiGestureRecognizer", "Exception type: $exceptionType")
            Log.e("GeminiGestureRecognizer", "Exception message: $exceptionMessage")
            Log.e("GeminiGestureRecognizer", "Cause: $cause")
            Log.e("GeminiGestureRecognizer", "Stack trace (first 5 lines):\n$stackTrace")
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
                    "API quota/rate limit exceeded (429). Please try again later. Details: $exceptionMessage"
                exceptionMessage.contains("500", ignoreCase = true) ||
                exceptionMessage.contains("internal server", ignoreCase = true) -> 
                    "Server error (500). Gemini API is having issues. Try again later. Details: $exceptionMessage"
                else -> "Error ($exceptionType): $exceptionMessage${if (cause.isNotBlank()) " (Cause: $cause)" else ""}"
            }
            
            throw Exception(userMsg, e)
        }
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
