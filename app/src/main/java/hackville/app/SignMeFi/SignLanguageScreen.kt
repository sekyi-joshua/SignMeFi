package hackville.app.SignMeFi

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import hackville.app.SignMeFi.camera.components.CameraPreview
import hackville.app.SignMeFi.di.GeminiRecognizer
import hackville.app.SignMeFi.di.MediaPipeRecognizer
import hackville.app.SignMeFi.gesture.GestureRecognizer
import hackville.app.SignMeFi.gesture.HybridGestureRecognizer
import hackville.app.SignMeFi.gesture.MediaPipeGestureRecognizer
import hackville.app.SignMeFi.gesture.GeminiGestureRecognizer
import hackville.app.SignMeFi.tts.ElevenLabsTTS
import hackville.app.SignMeFi.audio.AudioPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.Executors

@HiltViewModel
class SignLanguageViewModel @Inject constructor(
    @MediaPipeRecognizer private val mediaPipeRecognizer: GestureRecognizer,
    @GeminiRecognizer private val geminiRecognizer: GestureRecognizer,
    private val elevenLabsTTS: ElevenLabsTTS,
    private val audioPlayer: AudioPlayer
) : androidx.lifecycle.ViewModel() {
    
    // Store results with timestamps for expiration
    private data class ResultWithTimestamp(val result: String, val timestamp: Long)
    
    // Current active recognizer
    private var currentRecognizer: GestureRecognizer
    
    // Mode state: true = Hybrid (MediaPipe + Gemini), false = MediaPipe only
    private val _isHybridMode = MutableStateFlow(true)
    val isHybridMode: StateFlow<Boolean> = _isHybridMode.asStateFlow()
    
    private val _detectedResults = MutableStateFlow<List<ResultWithTimestamp>>(emptyList())
    val detectedResults: StateFlow<List<String>> = _detectedResults.map { results ->
        results.map { it.result }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // Debug state tracking
    private val _handDetected = MutableStateFlow(false)
    val handDetected: StateFlow<Boolean> = _handDetected.asStateFlow()
    
    private val _geminiStatus = MutableStateFlow<String>("Idle")
    val geminiStatus: StateFlow<String> = _geminiStatus.asStateFlow()
    
    private val _pendingRequestCount = MutableStateFlow(0)
    val pendingRequestCount: StateFlow<Int> = _pendingRequestCount.asStateFlow()
    
    // TTS mode: true = single request (blocks until finished), false = concurrent (current behavior)
    private val _isSingleRequestTTSMode = MutableStateFlow(false)
    val isSingleRequestTTSMode: StateFlow<Boolean> = _isSingleRequestTTSMode.asStateFlow()
    
    // Track if TTS request is in progress
    private val _isTTSInProgress = MutableStateFlow(false)
    val isTTSInProgress: StateFlow<Boolean> = _isTTSInProgress.asStateFlow()
    
    init {
        // Initialize with Hybrid mode (MediaPipe + Gemini)
        val mediaPipe = mediaPipeRecognizer as MediaPipeGestureRecognizer
        val gemini = geminiRecognizer as GeminiGestureRecognizer
        currentRecognizer = HybridGestureRecognizer(mediaPipe, gemini)
        setupCallbacks()
        
        // Start coroutine to remove expired results (older than 3 seconds)
        viewModelScope.launch {
            while (true) {
                delay(100) // Check every 100ms
                val now = System.currentTimeMillis()
                val threeSecondsAgo = now - 3000L
                _detectedResults.value = _detectedResults.value.filter { it.timestamp > threeSecondsAgo }
            }
        }
    }
    
    private fun setupCallbacks() {
        // Release previous recognizer if it was Hybrid
        if (currentRecognizer is HybridGestureRecognizer) {
            (currentRecognizer as HybridGestureRecognizer).setCallbacks(
                onHandDetectionChanged = { detected ->
                    _handDetected.value = detected
                },
                onGeminiStatusChanged = { status ->
                    _geminiStatus.value = status
                },
                onResultReceived = { result ->
                    val now = System.currentTimeMillis()
                    _detectedResults.value = _detectedResults.value + ResultWithTimestamp(result, now)
                    
                    android.util.Log.d("TTS", "SignLanguageViewModel: Received result from Gemini: '$result'")
                    
                    // Check if we should process TTS based on mode
                    val isSingleRequestMode = _isSingleRequestTTSMode.value
                    val ttsInProgress = _isTTSInProgress.value
                    
                    if (isSingleRequestMode && ttsInProgress) {
                        android.util.Log.d("TTS", "SignLanguageViewModel: TTS in progress, skipping new request (single-request mode)")
                        return@setCallbacks
                    }
                    
                    // Convert text to speech and play audio
                    viewModelScope.launch {
                        try {
                            _isTTSInProgress.value = true
                            
                            // In single-request mode, pause Gemini requests (cancels pending ones)
                            if (isSingleRequestMode && currentRecognizer is HybridGestureRecognizer) {
                                val hybridRecognizer = currentRecognizer as HybridGestureRecognizer
                                android.util.Log.d("TTS", "SignLanguageViewModel: Pausing Gemini requests (cancelling pending)")
                                hybridRecognizer.pause()
                            }
                            
                            android.util.Log.d("TTS", "SignLanguageViewModel: Starting TTS conversion for: '$result' (mode: ${if (isSingleRequestMode) "single-request" else "concurrent"})")
                            
                            val audioPath = elevenLabsTTS.textToSpeech(result)
                            
                            if (audioPath != null) {
                                android.util.Log.d("TTS", "SignLanguageViewModel: TTS conversion successful, playing audio: $audioPath")
                                audioPlayer.playFile(audioPath)
                                
                                // Wait for audio to finish playing in single-request mode
                                if (isSingleRequestMode) {
                                    android.util.Log.d("TTS", "SignLanguageViewModel: Waiting for audio playback to finish...")
                                    audioPlayer.waitForPlaybackToFinish()
                                    android.util.Log.d("TTS", "SignLanguageViewModel: Audio playback finished")
                                }
                            } else {
                                android.util.Log.w("TTS", "SignLanguageViewModel: Failed to generate TTS audio for: '$result'")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TTS", "SignLanguageViewModel: Error generating or playing TTS for: '$result'", e)
                        } finally {
                            // Resume Gemini requests in single-request mode (will check for hand detection again)
                            if (isSingleRequestMode && currentRecognizer is HybridGestureRecognizer) {
                                val hybridRecognizer = currentRecognizer as HybridGestureRecognizer
                                android.util.Log.d("TTS", "SignLanguageViewModel: Resuming Gemini requests - will check for hand detection again")
                                hybridRecognizer.resume()
                            }
                            
                            _isTTSInProgress.value = false
                            android.util.Log.d("TTS", "SignLanguageViewModel: TTS request completed")
                        }
                    }
                },
                onResultsCleared = {
                    // Don't clear - results expire automatically after 3 seconds
                },
                onPendingCountChanged = { count ->
                    _pendingRequestCount.value = count
                }
            )
        } else {
            // MediaPipe only mode - reset status indicators
            _geminiStatus.value = "Not used (MediaPipe only)"
            _pendingRequestCount.value = 0
        }
    }
    
    fun toggleTTSMode() {
        _isSingleRequestTTSMode.value = !_isSingleRequestTTSMode.value
        android.util.Log.d("TTS", "SignLanguageViewModel: TTS mode toggled to: ${if (_isSingleRequestTTSMode.value) "single-request" else "concurrent"}")
    }
    
    fun toggleRecognizerMode() {
        viewModelScope.launch {
            // Note: We don't release the Hybrid recognizer when switching away from it
            // because release() would also release the underlying MediaPipe and Gemini recognizers
            // (which are singletons we need to reuse). Instead, we just stop using it.
            // The old Hybrid instance will be garbage collected, and its coroutines will
            // eventually complete or be cancelled when the instance is GC'd.
            
            // Clear current results
            _detectedResults.value = emptyList()
            _handDetected.value = false
            _errorMessage.value = null
            
            // Switch mode
            val newMode = !_isHybridMode.value
            _isHybridMode.value = newMode
            
            if (newMode) {
                // Switch to Hybrid mode (MediaPipe + Gemini)
                // Always create a new Hybrid instance to ensure clean state
                val mediaPipe = mediaPipeRecognizer as MediaPipeGestureRecognizer
                val gemini = geminiRecognizer as GeminiGestureRecognizer
                currentRecognizer = HybridGestureRecognizer(mediaPipe, gemini)
                android.util.Log.d("SignLanguageViewModel", "Switched to Hybrid mode (MediaPipe + Gemini)")
            } else {
                // Switch to MediaPipe only mode
                // Use the singleton instance directly
                // Note: The MediaPipe recognizer should still be usable even if Hybrid released it,
                // because MediaPipeGestureRecognizer's release() just closes the internal recognizer
                // and we can't easily reinitialize it. For now, we'll assume it's still usable.
                // If issues occur, we may need to modify HybridGestureRecognizer to not release
                // underlying recognizers, or add a reinitialize method to MediaPipeGestureRecognizer.
                currentRecognizer = mediaPipeRecognizer
                android.util.Log.d("SignLanguageViewModel", "Switched to MediaPipe only mode")
            }
            
            setupCallbacks()
        }
    }
    
    fun recognizeGesture(bitmap: Bitmap) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                if (currentRecognizer is HybridGestureRecognizer) {
                    // With streaming architecture, results come through callbacks
                    // This just triggers the recognition process
                    currentRecognizer.recognizeGesture(bitmap)
                } else {
                    // MediaPipe only mode - process result directly
                    val result = currentRecognizer.recognizeGesture(bitmap)
                    if (result != null) {
                        val now = System.currentTimeMillis()
                        _detectedResults.value = _detectedResults.value + ResultWithTimestamp(result, now)
                        
                        android.util.Log.d("TTS", "SignLanguageViewModel: Received result from MediaPipe: '$result'")
                        
                        // Check if we should process TTS based on mode
                        val isSingleRequestMode = _isSingleRequestTTSMode.value
                        val ttsInProgress = _isTTSInProgress.value
                        
                        if (isSingleRequestMode && ttsInProgress) {
                            android.util.Log.d("TTS", "SignLanguageViewModel: TTS in progress, skipping new request (single-request mode)")
                        } else {
                            // Convert text to speech and play audio
                            viewModelScope.launch {
                                try {
                                    _isTTSInProgress.value = true
                                    android.util.Log.d("TTS", "SignLanguageViewModel: Starting TTS conversion for: '$result' (mode: ${if (isSingleRequestMode) "single-request" else "concurrent"})")
                                    
                                    val audioPath = elevenLabsTTS.textToSpeech(result)
                                    
                                    if (audioPath != null) {
                                        android.util.Log.d("TTS", "SignLanguageViewModel: TTS conversion successful, playing audio: $audioPath")
                                        audioPlayer.playFile(audioPath)
                                        
                                        // Wait for audio to finish playing in single-request mode
                                        if (isSingleRequestMode) {
                                            android.util.Log.d("TTS", "SignLanguageViewModel: Waiting for audio playback to finish...")
                                            audioPlayer.waitForPlaybackToFinish()
                                            android.util.Log.d("TTS", "SignLanguageViewModel: Audio playback finished")
                                        }
                                    } else {
                                        android.util.Log.w("TTS", "SignLanguageViewModel: Failed to generate TTS audio for: '$result'")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("TTS", "SignLanguageViewModel: Error generating or playing TTS for: '$result'", e)
                                } finally {
                                    _isTTSInProgress.value = false
                                    android.util.Log.d("TTS", "SignLanguageViewModel: TTS request completed")
                                }
                            }
                        }
                        
                        // Update hand detection status
                        val mediaPipe = currentRecognizer as MediaPipeGestureRecognizer
                        _handDetected.value = mediaPipe.hasHand(bitmap)
                    } else {
                        // No gesture detected, but check if hand is present
                        val mediaPipe = currentRecognizer as MediaPipeGestureRecognizer
                        _handDetected.value = mediaPipe.hasHand(bitmap)
                    }
                }
                _errorMessage.value = null // Clear any previous errors on success
            } catch (e: Exception) {
                // Log full exception details for debugging
                android.util.Log.e("SignLanguageViewModel", "Error recognizing gesture", e)
                android.util.Log.e("SignLanguageViewModel", "Exception type: ${e.javaClass.simpleName}")
                android.util.Log.e("SignLanguageViewModel", "Exception message: ${e.message}")
                e.cause?.let { cause ->
                    android.util.Log.e("SignLanguageViewModel", "Caused by: ${cause.javaClass.simpleName} - ${cause.message}")
                }
                
                // Use the detailed error message from the recognizer if available
                // The recognizer now throws exceptions with detailed messages
                _errorMessage.value = e.message ?: when {
                    e is java.net.UnknownHostException -> "No internet connection. Check network."
                    e is java.net.SocketTimeoutException -> "Request timed out. Try again."
                    e is java.io.IOException -> "Network error: ${e.message ?: "Unknown network error"}"
                    e.message?.contains("401", ignoreCase = true) == true -> 
                        "Invalid API key. Please check your key."
                    e.message?.contains("403", ignoreCase = true) == true -> 
                        "API access denied. Check API key permissions."
                    e.message?.contains("quota", ignoreCase = true) == true || 
                    e.message?.contains("429", ignoreCase = true) == true -> 
                        "API quota/rate limit exceeded. Please try again later."
                    else -> "Error: ${e.javaClass.simpleName} - ${e.message ?: "Unknown error"}"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        currentRecognizer.release()
        audioPlayer.stop()
        audioPlayer.release()
    }
}

@Composable
fun SignLanguageScreen(
    onBackClick: () -> Unit = {},
    viewModel: SignLanguageViewModel = hiltViewModel()
) {
    val detectedResults by viewModel.detectedResults.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val handDetected by viewModel.handDetected.collectAsState()
    val geminiStatus by viewModel.geminiStatus.collectAsState()
    val pendingRequestCount by viewModel.pendingRequestCount.collectAsState()
    val isHybridMode by viewModel.isHybridMode.collectAsState()
    val isSingleRequestTTSMode by viewModel.isSingleRequestTTSMode.collectAsState()
    val isTTSInProgress by viewModel.isTTSInProgress.collectAsState()
    
    var isUsingBackCamera by remember { mutableStateOf(false) }
    var switchCameraTrigger by remember { mutableStateOf(0) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera Preview
        CameraPreview(
            onFrameCaptured = { bitmap ->
                viewModel.recognizeGesture(bitmap)
            },
            onCameraStateChanged = { usingBack ->
                isUsingBackCamera = usingBack
            },
            switchCameraTrigger = switchCameraTrigger,
            modifier = Modifier.fillMaxSize()
        )
        
        // Back button at top left
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .clickable(onClick = onBackClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // Large detected results display at top center (all results separated by spaces)
        if (detectedResults.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            ) {
                Text(
                    text = detectedResults.joinToString(" "),
                    color = Color.White,
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                )
            }
        }
        
        // Overlay UI at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Error message
            errorMessage?.let { error ->
                Text(
                    text = error,
                    color = Color.Red,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                )
            }
            
            // Loading indicator - show when there are pending requests
            if (pendingRequestCount > 0) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(bottom = 4.dp),
                        color = Color.White
                    )
                    Text(
                        text = "$pendingRequestCount pending",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            // Debug information
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                // Hand detection status
                Text(
                    text = "Hand: ${if (handDetected) "DETECTED ✓" else "Not detected"}",
                    color = if (handDetected) Color.Green else Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                
                // Gemini API status
                Text(
                    text = "Gemini: $geminiStatus",
                    color = when {
                        geminiStatus.contains("Error", ignoreCase = true) -> Color.Red
                        geminiStatus.contains("Success", ignoreCase = true) -> Color.Green
                        geminiStatus.contains("Calling", ignoreCase = true) -> Color.Yellow
                        else -> Color.White
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .padding(top = 4.dp)
                )
            }
            
            // Recognizer mode toggle and camera controls
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                // TTS mode toggle
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "TTS: ${if (isSingleRequestTTSMode) "Single Request" else "Concurrent"}${if (isTTSInProgress && isSingleRequestTTSMode) " (Processing...)" else ""}",
                        color = if (isTTSInProgress && isSingleRequestTTSMode) Color.Yellow else Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    Button(
                        onClick = {
                            viewModel.toggleTTSMode()
                        },
                        modifier = Modifier.padding(0.dp),
                        enabled = !isTTSInProgress || !isSingleRequestTTSMode
                    ) {
                        Text(if (isSingleRequestTTSMode) "Concurrent" else "Single")
                    }
                }
                
                // Recognizer mode indicator and toggle button
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(
                        text = "Mode: ${if (isHybridMode) "Hybrid (MP+Gemini)" else "MediaPipe Only"}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    
                    Button(
                        onClick = {
                            viewModel.toggleRecognizerMode()
                        },
                        modifier = Modifier.padding(0.dp)
                    ) {
                        Text(if (isHybridMode) "MP Only" else "Hybrid")
                    }
                }
                
                // Current camera indicator
                Text(
                    text = "Camera: ${if (isUsingBackCamera) "Back" else "Front"}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                // Switch camera button
                Button(
                    onClick = {
                        switchCameraTrigger++
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text("Switch Camera")
                }
            }
            
            // Status text (only show if no detected results)
            if (detectedResults.isEmpty()) {
                Text(
                    text = "Sign clearly at camera...",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                        .padding(16.dp)
                )
            }
        }
    }
}

/**
 * Data class to store camera setup for switching
 */
private data class CameraSetup(
    val provider: ProcessCameraProvider,
    val preview: Preview,
    val imageAnalysis: ImageAnalysis,
    val hasFrontCamera: Boolean,
    val hasBackCamera: Boolean,
    val mainHandler: Handler
)

/**
 * Checks if a bitmap is mostly empty/black (indicating no camera feed)
 * Samples pixels across the image to detect if it's all black or very dark
 */
private fun isFrameEmpty(bitmap: Bitmap, threshold: Float = 0.05f): Boolean {
    if (bitmap.width == 0 || bitmap.height == 0) return true
    
    // Sample pixels across the image (check every Nth pixel to avoid performance issues)
    val sampleStep = maxOf(1, minOf(bitmap.width, bitmap.height) / 20)
    var totalBrightness = 0f
    var sampleCount = 0
    
    for (y in 0 until bitmap.height step sampleStep) {
        for (x in 0 until bitmap.width step sampleStep) {
            val pixel = bitmap.getPixel(x, y)
            val r = android.graphics.Color.red(pixel) / 255f
            val g = android.graphics.Color.green(pixel) / 255f
            val b = android.graphics.Color.blue(pixel) / 255f
            // Calculate brightness (luminance)
            val brightness = 0.299f * r + 0.587f * g + 0.114f * b
            totalBrightness += brightness
            sampleCount++
        }
    }
    
    val averageBrightness = if (sampleCount > 0) totalBrightness / sampleCount else 0f
    return averageBrightness < threshold
}

@Composable
fun CameraView(
    onFrameCaptured: (Bitmap) -> Unit,
    onCameraStateChanged: ((Boolean) -> Unit)? = null,
    switchCameraTrigger: Int = 0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val previewView = remember { PreviewView(context) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var isUsingBackCamera by remember { mutableStateOf(false) }
    
    // Expose camera state to parent
    LaunchedEffect(isUsingBackCamera) {
        onCameraStateChanged?.invoke(isUsingBackCamera)
    }
    
    // Store camera setup in remember to allow switching
    val cameraSetup = remember {
        mutableStateOf<CameraSetup?>(null)
    }
    
    LaunchedEffect(lifecycleOwner) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            
            // Check camera availability first
            val hasFrontCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            val hasBackCamera = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            
            android.util.Log.d("CameraView", "Front camera available: $hasFrontCamera, Back camera available: $hasBackCamera")
            
            // Preview use case
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
            
            // Analysis use case - process every 10th frame to reduce load
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            
            val executor = Executors.newSingleThreadExecutor()
            val mainHandler = Handler(Looper.getMainLooper())
            
            // Local mutable variables for camera state (accessible from analyzer thread)
            var currentIsUsingBackCamera = false
            var hasSwitchedCamera = false
            var emptyFrameCount = 0
            var localFrameCounter = 0
            var currentCameraSelector: CameraSelector? = null
            
            // Store setup for camera switching
            cameraSetup.value = CameraSetup(
                cameraProvider,
                preview,
                imageAnalysis,
                hasFrontCamera,
                hasBackCamera,
                mainHandler
            )
            
            // Function to bind camera with error handling
            fun bindCamera(selector: CameraSelector): Boolean {
                return try {
                    cameraProvider.unbindAll()
                    
                    // Try to bind - this may throw immediately or fail asynchronously
                    try {
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            selector,
                            preview,
                            imageAnalysis
                        )
                        
                        if (camera != null) {
                            android.util.Log.d("CameraView", "Camera bound: ${camera.cameraInfo}")
                            return true
                        } else {
                            android.util.Log.w("CameraView", "Camera binding returned null")
                            return false
                        }
                    } catch (e: Exception) {
                        // Check for specific camera errors
                        val errorMessage = e.message ?: ""
                        val causeMessage = e.cause?.message ?: ""
                        
                        android.util.Log.e("CameraView", "Camera binding exception: ${e.javaClass.simpleName}", e)
                        
                        // Check for camera configuration errors
                        if (errorMessage.contains("CAMERA_ERROR") || 
                            errorMessage.contains("Function not implemented") ||
                            errorMessage.contains("endConfigure") ||
                            causeMessage.contains("CAMERA_ERROR") ||
                            causeMessage.contains("Function not implemented")) {
                            android.util.Log.w("CameraView", "Camera configuration error - camera may not be available", e)
                            return false
                        }
                        
                        // Re-throw if it's not a camera configuration error
                        throw e
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CameraView", "Failed to bind camera: ${e.javaClass.simpleName}", e)
                    return false
                }
            }
            
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                // Process every frame for 30 FPS
                localFrameCounter++
                try {
                    // Use built-in toBitmap() method from CameraX
                    val bitmap = imageProxy.toBitmap()
                    
                    // Check if frame is empty (only if using front camera and haven't switched yet)
                    if (!currentIsUsingBackCamera && !hasSwitchedCamera && localFrameCounter > 10) {
                        // Start checking after a few frames to allow camera to initialize
                        if (isFrameEmpty(bitmap)) {
                            emptyFrameCount++
                            android.util.Log.d("CameraView", "Empty frame detected (count: $emptyFrameCount)")
                            
                            // If we get 15 consecutive empty frames (about 0.5 seconds at 30fps), switch to back camera
                            if (emptyFrameCount >= 15) {
                                android.util.Log.w("CameraView", "Front camera showing no images, switching to back camera")
                                // Switch camera on main thread (CameraX requires main thread)
                                mainHandler.post {
                                    try {
                                        val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                        currentCameraSelector = backCameraSelector
                                        if (bindCamera(backCameraSelector)) {
                                            currentIsUsingBackCamera = true
                                            hasSwitchedCamera = true
                                            emptyFrameCount = 0
                                            isUsingBackCamera = true
                                            android.util.Log.d("CameraView", "Successfully switched to back camera")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("CameraView", "Failed to switch to back camera", e)
                                    }
                                }
                            }
                        } else {
                            // Reset counter if we get a valid frame
                            emptyFrameCount = 0
                        }
                    }
                    
                    // Apply transformation based on camera type
                    val matrix = Matrix()
                    if (!currentIsUsingBackCamera) {
                        // Front camera: mirror horizontally
                        matrix.postScale(-1f, 1f)
                    }
                    // Rotate 90 degrees (most devices need this)
                    matrix.postRotate(90f)
                    
                    val transformedBitmap = android.graphics.Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.width,
                        bitmap.height,
                        matrix,
                        true
                    )
                    onFrameCaptured(transformedBitmap)
                } catch (e: Exception) {
                    android.util.Log.e("CameraView", "Error processing frame", e)
                }
                imageProxy.close()
            }
            
            // Try to bind camera - prefer front if available, otherwise use back
            // Note: Even if hasCamera() returns true, the camera might fail during configuration
            var bindSuccess = false
            
            // Try front camera first if available, but always have back camera as fallback
            if (hasFrontCamera) {
                try {
                    val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                    currentCameraSelector = frontCameraSelector
                    android.util.Log.d("CameraView", "Attempting to bind front camera...")
                    if (bindCamera(frontCameraSelector)) {
                        bindSuccess = true
                        cameraError = null
                        currentIsUsingBackCamera = false
                        isUsingBackCamera = false
                        android.util.Log.d("CameraView", "Front camera bound successfully")
                    } else {
                        android.util.Log.w("CameraView", "Front camera binding returned false, trying back camera")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CameraView", "Front camera exception during binding, trying back camera", e)
                    // Check if it's a configuration error
                    val errorMsg = e.message ?: ""
                    if (errorMsg.contains("CAMERA_ERROR") || 
                        errorMsg.contains("Function not implemented") ||
                        errorMsg.contains("endConfigure")) {
                        android.util.Log.w("CameraView", "Front camera configuration error detected, will use back camera")
                    }
                }
            } else {
                android.util.Log.d("CameraView", "Front camera not available (hasCamera=false), using back camera")
            }
            
            // Fallback to back camera if front camera failed or not available
            if (!bindSuccess) {
                if (hasBackCamera) {
                    try {
                        val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        currentCameraSelector = backCameraSelector
                        android.util.Log.d("CameraView", "Attempting to bind back camera...")
                        if (bindCamera(backCameraSelector)) {
                            currentIsUsingBackCamera = true
                            hasSwitchedCamera = true
                            isUsingBackCamera = true
                            bindSuccess = true
                            cameraError = null
                            android.util.Log.d("CameraView", "Back camera bound successfully")
                        } else {
                            android.util.Log.e("CameraView", "Back camera binding returned false")
                            cameraError = "Failed to initialize back camera."
                        }
                    } catch (e2: Exception) {
                        android.util.Log.e("CameraView", "Back camera binding exception", e2)
                        cameraError = "Camera initialization failed: ${e2.message ?: "Unknown error"}"
                    }
                } else {
                    android.util.Log.e("CameraView", "No cameras available (hasBackCamera=false)")
                    cameraError = "No camera available on this device."
                }
            }
            
            // If binding succeeded but camera fails later (e.g., during stream configuration),
            // we'll detect it through empty frames and switch automatically (handled in the image analyzer)
        } catch (e: Exception) {
            android.util.Log.e("CameraView", "Camera provider initialization failed", e)
            cameraError = "Camera error: ${e.message}"
        }
    }
    
    // Handle manual camera switching
    LaunchedEffect(switchCameraTrigger) {
        if (switchCameraTrigger > 0) {
            val setup = cameraSetup.value
            if (setup != null) {
                try {
                    val newSelector = if (isUsingBackCamera) {
                        if (setup.hasFrontCamera) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            android.util.Log.w("CameraView", "Front camera not available, cannot switch")
                            return@LaunchedEffect
                        }
                    } else {
                        if (setup.hasBackCamera) {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        } else {
                            android.util.Log.w("CameraView", "Back camera not available, cannot switch")
                            return@LaunchedEffect
                        }
                    }
                    
                    android.util.Log.d("CameraView", "Manually switching to ${if (newSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"} camera")
                    
                    setup.provider.unbindAll()
                    val camera = setup.provider.bindToLifecycle(
                        lifecycleOwner,
                        newSelector,
                        setup.preview,
                        setup.imageAnalysis
                    )
                    
                    if (camera != null) {
                        isUsingBackCamera = newSelector == CameraSelector.DEFAULT_BACK_CAMERA
                        cameraError = null
                        android.util.Log.d("CameraView", "Successfully switched to ${if (isUsingBackCamera) "back" else "front"} camera")
                    } else {
                        android.util.Log.e("CameraView", "Camera switch returned null")
                        cameraError = "Failed to switch camera"
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CameraView", "Error switching camera", e)
                    cameraError = "Error switching camera: ${e.message}"
                }
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Use AndroidView to host the PreviewView
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        
        // Show error message if camera failed
        cameraError?.let { error ->
            Text(
                text = error,
                color = Color.Red,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            )
        }
    }
}

