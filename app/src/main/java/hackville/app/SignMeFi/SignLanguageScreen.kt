package hackville.app.SignMeFi

import android.graphics.Bitmap
import android.graphics.Matrix
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
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Camera View
        CameraView(
            onFrameCaptured = { bitmap ->
                viewModel.recognizeGesture(bitmap)
            }
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

@Composable
fun CameraView(onFrameCaptured: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val previewView = remember { PreviewView(context) }
    var frameCounter by remember { mutableStateOf(0) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var isUsingBackCamera by remember { mutableStateOf(false) }
    
    LaunchedEffect(lifecycleOwner) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            
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
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                // Process every 2nd frame for MediaPipe hand detection (~15 FPS)
                // This gives MediaPipe enough frames to quickly detect hands while keeping CPU usage reasonable
                // The HybridGestureRecognizer will handle rate limiting Gemini to 2 FPS
                if (frameCounter++ % 2 == 0) {
                    try {
                        // Use built-in toBitmap() method from CameraX
                        val bitmap = imageProxy.toBitmap()
                        
                        // Apply transformation based on camera type
                        val matrix = Matrix()
                        if (!isUsingBackCamera) {
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
            
            // Try front camera first, fallback to back camera
            var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            var bindSuccess = false
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                bindSuccess = true
                cameraError = null
                android.util.Log.d("CameraView", "Front camera bound successfully")
            } catch (e: Exception) {
                android.util.Log.w("CameraView", "Front camera failed, trying back camera", e)
                // Fallback to back camera
                try {
                    cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    isUsingBackCamera = true
                    bindSuccess = true
                    cameraError = null
                    android.util.Log.d("CameraView", "Back camera bound successfully")
                } catch (e2: Exception) {
                    android.util.Log.e("CameraView", "Both cameras failed", e2)
                    cameraError = "Camera initialization failed: ${e2.message}"
                }
            }
            
            if (!bindSuccess) {
                cameraError = "No camera available. Please check emulator camera settings."
            }
        } catch (e: Exception) {
            android.util.Log.e("CameraView", "Camera provider initialization failed", e)
            cameraError = "Camera error: ${e.message}"
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

