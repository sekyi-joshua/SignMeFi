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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import hackville.app.SignMeFi.gesture.GestureRecognizer
import hackville.app.SignMeFi.gesture.HybridGestureRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.Executors

@HiltViewModel
class SignLanguageViewModel @Inject constructor(
    private val gestureRecognizer: GestureRecognizer
) : androidx.lifecycle.ViewModel() {
    
    private val _detectedText = MutableStateFlow<String?>(null)
    val detectedText: StateFlow<String?> = _detectedText.asStateFlow()
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // Debug state tracking
    private val _handDetected = MutableStateFlow(false)
    val handDetected: StateFlow<Boolean> = _handDetected.asStateFlow()
    
    private val _geminiStatus = MutableStateFlow<String>("Idle")
    val geminiStatus: StateFlow<String> = _geminiStatus.asStateFlow()
    
    init {
        // Set up callbacks if using HybridGestureRecognizer
        if (gestureRecognizer is HybridGestureRecognizer) {
            gestureRecognizer.setCallbacks(
                onHandDetectionChanged = { detected ->
                    _handDetected.value = detected
                },
                onGeminiStatusChanged = { status ->
                    _geminiStatus.value = status
                }
            )
        }
    }
    
    fun recognizeGesture(bitmap: Bitmap) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            
            try {
                val result = gestureRecognizer.recognizeGesture(bitmap)
                if (result != null) {
                    _detectedText.value = result
                    _errorMessage.value = null // Clear any previous errors on success
                }
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
        gestureRecognizer.release()
    }
}

@Composable
fun SignLanguageScreen(
    viewModel: SignLanguageViewModel = hiltViewModel()
) {
    val detectedText by viewModel.detectedText.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val handDetected by viewModel.handDetected.collectAsState()
    val geminiStatus by viewModel.geminiStatus.collectAsState()
    
    var isUsingBackCamera by remember { mutableStateOf(false) }
    var switchCameraTrigger by remember { mutableStateOf(0) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera View
        CameraView(
            onFrameCaptured = { bitmap ->
                viewModel.recognizeGesture(bitmap)
            },
            onCameraStateChanged = { usingBack ->
                isUsingBackCamera = usingBack
            },
            switchCameraTrigger = switchCameraTrigger
        )
        
        // Large detected text display at top center
        detectedText?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            ) {
                Text(
                    text = text,
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
            
            // Loading indicator
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(8.dp),
                    color = Color.White
                )
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
            
            // Camera info and switch button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
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
            
            // Status text (only show if no detected text)
            if (detectedText == null) {
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
                // Process every 2nd frame for MediaPipe hand detection (~15 FPS)
                // This gives MediaPipe enough frames to quickly detect hands while keeping CPU usage reasonable
                // The HybridGestureRecognizer will handle rate limiting Gemini to 2 FPS
                if (localFrameCounter++ % 2 == 0) {
                    try {
                        // Use built-in toBitmap() method from CameraX
                        val bitmap = imageProxy.toBitmap()
                        
                        // Check if frame is empty (only if using front camera and haven't switched yet)
                        if (!currentIsUsingBackCamera && !hasSwitchedCamera && localFrameCounter > 10) {
                            // Start checking after a few frames to allow camera to initialize
                            if (isFrameEmpty(bitmap)) {
                                emptyFrameCount++
                                android.util.Log.d("CameraView", "Empty frame detected (count: $emptyFrameCount)")
                                
                                // If we get 15 consecutive empty frames (about 1 second at 30fps), switch to back camera
                                if (emptyFrameCount >= 15) {
                                    android.util.Log.w("CameraView", "Front camera showing no images, switching to back camera")
                                    try {
                                        val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                        currentCameraSelector = backCameraSelector
                                        if (bindCamera(backCameraSelector)) {
                                            currentIsUsingBackCamera = true
                                            hasSwitchedCamera = true
                                            emptyFrameCount = 0
                                            // Update state on main thread
                                            mainHandler.post {
                                                isUsingBackCamera = true
                                            }
                                            android.util.Log.d("CameraView", "Successfully switched to back camera")
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("CameraView", "Failed to switch to back camera", e)
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

