plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mitv.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mitv.player"
        minSdk = 23
        targetSdk = 34
        versionCode = 4
        versionName = "3.1"
    }

    // Llave fija para que cada versión nueva se instale encima de la anterior.
    signingConfigs {
        getByName("debug") {
            storeFile = file("mitv.keystore")
            storePassword = "mitv1234"
            keyAlias = "mitv"
            keyPassword = "mitv1234"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    val media3 = "1.3.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Decodificador de audio FFmpeg (AC3, E-AC3, DTS, TrueHD…) para películas y series.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+2")
    implementation("io.coil-kt:coil:2.7.0")
}
