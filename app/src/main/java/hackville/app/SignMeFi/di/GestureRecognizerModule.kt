package hackville.app.SignMeFi.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import hackville.app.SignMeFi.gesture.GeminiGestureRecognizer
import hackville.app.SignMeFi.gesture.GestureRecognizer
import hackville.app.SignMeFi.gesture.HybridGestureRecognizer
import hackville.app.SignMeFi.gesture.MediaPipeGestureRecognizer
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifiers to distinguish between different gesture recognizer implementations
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiRecognizer

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaPipeRecognizer

/**
 * Hilt module for providing gesture recognizer implementations.
 * 
 * To switch between recognizers, change the @Provides method that returns
 * the unqualified GestureRecognizer.
 */
@Module
@InstallIn(SingletonComponent::class)
object GestureRecognizerModule {
    
    private const val GEMINI_API_KEY = "AIzaSyAL5tnE4UnKHGK0n_r547TnLwH4EjXF30k"
    
    /**
     * Provides the Gemini gesture recognizer (qualified).
     */
    @Provides
    @Singleton
    @GeminiRecognizer
    fun provideGeminiGestureRecognizer(): GestureRecognizer {
        return GeminiGestureRecognizer(apiKey = GEMINI_API_KEY)
    }
    
    /**
     * Provides the MediaPipe gesture recognizer (qualified).
     */
    @Provides
    @Singleton
    @MediaPipeRecognizer
    fun provideMediaPipeGestureRecognizer(
        @ApplicationContext context: Context
    ): GestureRecognizer {
        return MediaPipeGestureRecognizer(context = context)
    }
    
    /**
     * Provides the default gesture recognizer to use throughout the app.
     * 
     * Currently using Hybrid (MediaPipe + Gemini) approach.
     * 
     * Flow:
     * - MediaPipe continuously scans at ~30 FPS (local, free)
     * - When MediaPipe detects a hand, triggers Gemini
     * - Gemini processes frames at 2 FPS while hand is detected
     * - When hand leaves, stops Gemini stream
     * 
     * TO SWITCH BETWEEN RECOGNIZERS:
     * 1. To use Hybrid (default, recommended): Keep as is
     * 2. To use Gemini only: Change to @GeminiRecognizer geminiRecognizer and return geminiRecognizer
     * 3. To use MediaPipe only: Change to @MediaPipeRecognizer mediaPipeRecognizer and return mediaPipeRecognizer
     */
    @Provides
    @Singleton
    fun provideGestureRecognizer(
        @MediaPipeRecognizer mediaPipeRecognizer: GestureRecognizer,
        @GeminiRecognizer geminiRecognizer: GestureRecognizer
    ): GestureRecognizer {
        // Using hybrid approach (MediaPipe gatekeeper + Gemini recognition)
        val mediaPipe = mediaPipeRecognizer as MediaPipeGestureRecognizer
        val gemini = geminiRecognizer as GeminiGestureRecognizer
        return HybridGestureRecognizer(mediaPipe, gemini)
    }
}
