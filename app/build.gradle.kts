plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.issinoho.radiotimes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.issinoho.radiotimes"
        // Fire OS 7 is Android 9 (API 28); Fire OS 8 is Android 11.
        minSdk = 28
        targetSdk = 34
        versionCode = 6
        versionName = "0.4.2"

        ndk {
            // Older Fire TV Sticks are 32-bit ARM, newer ones 64-bit; x86_64
            // is only for the Android TV emulator. One universal APK keeps
            // sideloading to a single file while this is a spike.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // A fixed, deliberately public debug key. Without it every CI run
        // signs with a fresh one and Android refuses to install a build over
        // the previous one (INSTALL_FAILED_UPDATE_INCOMPATIBLE), which means
        // uninstalling -- and losing the saved tvtimes link -- every time.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Releases: a private key, never in the repo. CI decodes it from
        // the RELEASE_KEYSTORE_B64 secret (.github/workflows/release.yml);
        // without these variables release builds are left unsigned.
        val releaseStore = System.getenv("RELEASE_KEYSTORE")
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = "radiotimes"
                keyPassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

chaquopy {
    defaultConfig {
        // 3.11 because it is the newest Chaquopy ships for armeabi-v7a, and
        // older Sticks are 32-bit. Confirmed against Maven Central: the
        // 3.11 targets have an armeabi-v7a zip, no 3.12 target does (CI
        // failed on 3.12 with "not available for the ABI 'armeabi-v7a'").
        // tvdinner needs 3.10+.
        version = "3.11"
        pip {
            // tvdinner's engine modules need only requests. Its declared
            // dependencies (python-mpv, Pillow) serve cli/player/overlay,
            // which this app never imports, so tvdinner is not pip-installed
            // at all -- its source comes from the pinned submodule below.
            install("requests==2.32.3")
        }
    }
    sourceSets {
        getByName("main") {
            // Pinned, unmodified tvdinner (git submodule at a release tag).
            srcDir("../vendor/tvdinner/src")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // QR code for setting up from a phone (encoder only).
    implementation("com.google.zxing:core:3.5.3")

    // Channel logos (tvtimes passes through the provider's logo URLs).
    implementation("io.coil-kt:coil-compose:2.7.0")

    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // Software audio decoding for what the Stick's MediaCodec lacks: MPEG-1
    // Layer II, the audio of every UK DVB SD channel (an HDHomeRun's
    // BBC TWO etc.), plays silent without it. Jellyfin's prebuilt FFmpeg
    // build of Media3's decoder_ffmpeg, for the same Media3 1.5 line.
    // GPL-3.0, accepted by the project owner for this app.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")
}
