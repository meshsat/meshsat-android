plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.protobuf")
}

android {
    namespace = "com.cubeos.meshsat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cubeos.meshsat"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        noCompress += listOf("onnx", "bin")
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

    // Protobuf (Meshtastic official bindings — MESHSAT-241)
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    // BLE
    implementation("no.nordicsemi.android:ble:2.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ONNX Runtime (MSVQ-SC sentence encoder for lossy semantic compression)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")

    // NanoHTTPD (lightweight local REST API server)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // MQTT (Hub connectivity — Eclipse Paho)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // QR code scanning (Hub key sync — MESHSAT-205)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Test
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
