plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adam.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adam.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }

    buildFeatures { viewBinding = true }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // MLC LLM AAR — place the file at app/libs/mlc4j-release.aar
    // Get it from: https://github.com/mlc-ai/mlc-llm/releases
    // Without it the app runs in regex-fallback mode.
    sourceSets { getByName("main") { jniLibs.srcDirs("libs") } }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Keep native libs uncompressed so the dynamic linker can mmap them
            // directly from the APK on Android 15+ (which requires 16 KB page
            // alignment and breaks when libs are extracted and re-padded).
            jniLibs {
                useLegacyPackaging = false
            }
        }
    }
}

dependencies {
    // Vosk offline STT — 0.3.75 ships 16 KB-aligned .so files for Android 15+
    implementation("com.alphacephei:vosk-android:0.3.75@aar") { isTransitive = true }

    // Sherpa-ONNX offline STT — Parakeet 0.6B via JitPack AAR (offline, no cloud).
    // Vosk stays for wake-word + fallback; Parakeet owns command transcription.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.7")
    // tar.bz2 extraction for the Parakeet model archive on-device
    implementation("org.apache.commons:commons-compress:1.26.0")

    // MLC LLM AAR (optional — app works without it via regex fallback)
    val mlcAar = file("libs/mlc4j-release.aar")
    if (mlcAar.exists()) implementation(files(mlcAar))

    // On-device LLM brain — MediaPipe LLM Inference (no native build needed).
    // Model file (.task) is fetched on first Start; brain stays inactive until then.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // JSON parsing for LLM responses
    implementation("org.json:json:20231013")

    // Unit tests (local JVM)
    testImplementation("junit:junit:4.13.2")
}
