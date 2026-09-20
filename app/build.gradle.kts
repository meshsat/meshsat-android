import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
}

// One APK per processor type (MESHSAT-1260). The universal APK is 116 MB, three quarters of it
// ONNX Runtime built for four of them; one processor type is about half that. Each APK needs its
// own versionCode, so the base is multiplied by ten and the ABI adds the last digit — the
// universal APK keeps 0. The file name carries that number, which is how F-Droid's recipe picks
// the right APK out of the five.
val baseVersionCode = 77
val baseVersionName = "2.14.6"
val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

// Splitting a debug build would make five APKs for every local run, so it happens on release only.
val splitAbis = gradle.startParameter.taskNames.any { it.contains("Release") || it.contains("release") }

android {
    namespace = "net.meshsat.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.meshsat.android"
        minSdk = 26
        targetSdk = 35
        versionCode = baseVersionCode
        versionName = baseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storePassword = System.getenv("ANDROID_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseConfig = signingConfigs.findByName("release")
            if (releaseConfig?.storeFile != null) {
                signingConfig = releaseConfig
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
        buildConfig = true
    }

    // Don't compress MSVQ-SC model assets (ONNX Runtime needs raw file access)
    androidResources {
        noCompress += listOf("onnx", "bin", "mbtiles")
    }

    // One APK per processor type on release builds (MESHSAT-1260)
    splits {
        abi {
            isEnable = splitAbis
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Compress the native libraries in the APK instead of storing them uncompressed.
    // Android extracts them at install time, which costs install space but makes the
    // download markedly smaller — the point of the split (F-Droid MR !49450).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // No dependency metadata block in the APK or AAB: AGP encrypts it with a Google key,
    // and F-Droid rejects APKs that carry one (MESHSAT-1258)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Give every APK its own versionCode and a file name that carries it, so each one can be
// released and verified on its own (MESHSAT-1260).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            val code = baseVersionCode * 10 + (abiVersionCodes[abi] ?: 0)
            output.versionCode.set(code)
            (output as? VariantOutputImpl)?.outputFileName?.set(
                "meshsat-android-$baseVersionName-${abi ?: "universal"}-$code.apk"
            )
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room (SQLite)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // Security — EncryptedSharedPreferences backed by Android Keystore (MESHSAT-194)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // BouncyCastle — software Ed25519/X25519 for Android 16+ (MESHSAT-354)
    // Android 16 removed Ed25519 from Conscrypt; only AndroidKeyStore has it,
    // but AndroidKeyStore won't export raw private keys for Reticulum wire format.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Protobuf (Meshtastic official bindings — MESHSAT-241)
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    // BLE
    implementation("no.nordicsemi.android:ble:2.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ONNX Runtime (MSVQ-SC sentence encoder for lossy semantic compression)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")

    // osmdroid (native OpenStreetMap map rendering with offline MBTiles support)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // NanoHTTPD (lightweight local REST API server)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // MQTT (Hub connectivity — Eclipse Paho)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // QR code scanning (Hub key sync — MESHSAT-205)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // WebSocket client for the Hub relay tunnel (MESHSAT-1157)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
