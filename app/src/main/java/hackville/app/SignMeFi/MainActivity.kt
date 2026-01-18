package hackville.app.SignMeFi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import hackville.app.SignMeFi.home.HomeScreen
import hackville.app.SignMeFi.navigation.OnboardingNavigationHandler
import hackville.app.SignMeFi.splash.SplashScreen
import hackville.app.SignMeFi.ui.theme.MyApplicationTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var hasCameraPermission by mutableStateOf(false)
    private var showSplash by mutableStateOf(true)
    private var showHome by mutableStateOf(false)
    private var showSignLanguageScreen by mutableStateOf(false)
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted && showHome) {
            // If permission granted and we're on home screen, navigate to sign language screen
            showHome = false
            showSignLanguageScreen = true
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Set up navigation handler for DI
        val navigationHandler = object : OnboardingNavigationHandler {
            override fun onGetStarted() {
                showHome = true
            }
            
            override fun onSkip() {
                showHome = true
            }
        }
        hackville.app.SignMeFi.di.NavigationModule.setNavigationHandler(navigationHandler)
        
        // Check camera permission
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!hasCameraPermission) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        showSplash -> {
                            SplashScreen(
                                onNavigateToOnboarding = {
                                    showSplash = false
                                    showHome = true
                                }
                            )
                        }
                        showSignLanguageScreen && hasCameraPermission -> {
                            SignLanguageScreen(
                                onBackClick = {
                                    showSignLanguageScreen = false
                                    showHome = true
                                }
                            )
                        }
                        showHome -> {
                            HomeScreen(
                                onNavigateToSignLanguage = {
                                    if (hasCameraPermission) {
                                        showHome = false
                                        showSignLanguageScreen = true
                                    } else {
                                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }
                            )
                        }
                        else -> {
                            androidx.compose.material3.Text(
                                text = "Camera permission is required",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
