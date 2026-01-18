plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

// Load local.properties to get API keys
fun getLocalProperty(key: String): String {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        try {
            localPropertiesFile.inputStream().use { stream ->
                stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("$key=") && !line.trimStart().startsWith("#")) {
                            val value = line.substringAfter("=").trim()
                            if (value.isNotEmpty()) {
                                return value
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Warning: Failed to read $key from local.properties: ${e.message}")
        }
    }
    return ""
}

android {
    namespace = "hackville.app.SignMeFi"
    compileSdk = 35

    defaultConfig {
        applicationId = "hackville.app.SignMeFi"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Load API key from local.properties
        val geminiApiKey = getLocalProperty("GEMINI_API_KEY")
        // Ensure API key is not empty and not too large (Android BuildConfig has limits)
        // Truncate if necessary and properly escape the string for BuildConfig
        val maxKeyLength = 500 // Conservative limit to avoid BuildConfig issues
        val trimmedKey = if (geminiApiKey.length > maxKeyLength) {
            println("Warning: GEMINI_API_KEY truncated from ${geminiApiKey.length} to $maxKeyLength chars")
            geminiApiKey.substring(0, maxKeyLength)
        } else {
            geminiApiKey
        }
        
        val escapedApiKey = if (trimmedKey.isNotEmpty()) {
            trimmedKey.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\$", "\\\$")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        } else {
            ""
        }
        
        if (escapedApiKey.isNotEmpty()) {
            // Use single quotes in Gradle to avoid issues with special characters
            buildConfigField("String", "GEMINI_API_KEY", "\"$escapedApiKey\"")
        } else {
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            println("Warning: GEMINI_API_KEY is empty. Please add it to local.properties")
        }
        
        // Load Eleven Labs API key and voice ID from gradle.properties
        val elevenLabsApiKey = project.findProperty("ELEVENLABS_API_KEY") as String? ?: ""
        val elevenLabsVoiceId = project.findProperty("ELEVENLABS_VOICE_ID") as String? ?: ""
        
        // Ensure API key is not too large (Android BuildConfig has limits)
        val trimmedElevenLabsApiKey = if (elevenLabsApiKey.length > maxKeyLength) {
            println("Warning: ELEVENLABS_API_KEY truncated from ${elevenLabsApiKey.length} to $maxKeyLength chars")
            elevenLabsApiKey.substring(0, maxKeyLength)
        } else {
            elevenLabsApiKey
        }
        
        // Escape the Eleven Labs API key for BuildConfig
        val escapedElevenLabsApiKey = if (trimmedElevenLabsApiKey.isNotEmpty()) {
            trimmedElevenLabsApiKey.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\$", "\\\$")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        } else {
            ""
        }
        
        // Escape the Eleven Labs Voice ID for BuildConfig
        val escapedElevenLabsVoiceId = if (elevenLabsVoiceId.isNotEmpty()) {
            elevenLabsVoiceId.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\$", "\\\$")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
        } else {
            ""
        }
        
        // Expose Eleven Labs credentials via BuildConfig
        if (escapedElevenLabsApiKey.isNotEmpty()) {
            buildConfigField("String", "ELEVENLABS_API_KEY", "\"$escapedElevenLabsApiKey\"")
        } else {
            buildConfigField("String", "ELEVENLABS_API_KEY", "\"\"")
            println("Warning: ELEVENLABS_API_KEY is empty. Please add it to gradle.properties")
        }
        
        if (escapedElevenLabsVoiceId.isNotEmpty()) {
            buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"$escapedElevenLabsVoiceId\"")
        } else {
            buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"\"")
            println("Warning: ELEVENLABS_VOICE_ID is empty. Please add it to gradle.properties")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    // Hilt for Dependency Injection
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    
    // Google AI Client SDK for Gemini
    implementation("com.google.ai.client.generativeai:generativeai:0.2.2")
    
    // OkHttp for HTTP requests (required by Google AI SDK)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // MediaPipe for gesture recognition
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // ExoPlayer for audio playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.viewfinder.compose)

    // Add other necessary Firebase or UI libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}