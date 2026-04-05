# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep class com.cubeos.meshsat.data.*_Impl { *; }

# DataStore
-keep class androidx.datastore.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Bluetooth
-keep class android.bluetooth.** { *; }

# ONNX Runtime (uses JNI/reflection — R8 strips needed classes without this)
-keep class ai.onnxruntime.** { *; }
-keep class com.microsoft.onnxruntime.** { *; }

# BouncyCastle JCA provider (Ed25519/X25519 — MESHSAT-497)
# BC registers crypto algorithms via JCA reflection; R8 must not strip provider classes.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.** { *; }
-dontwarn org.bouncycastle.**

# AndroidX Security — EncryptedSharedPreferences (MESHSAT-194)
-keep class androidx.security.crypto.** { *; }
-keep class com.cubeos.meshsat.crypto.SecureKeyStore { *; }

# Google Tink annotations (compile-time only, not needed at runtime)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# MSVQ-SC crypto classes (accessed via reflection in ONNX pipeline)
-keep class com.cubeos.meshsat.crypto.MsvqscEncoder { *; }
-keep class com.cubeos.meshsat.crypto.MsvqscCodebook { *; }

# NanoHTTPD (local REST API server)
-keep class fi.iki.elonen.** { *; }

# osmdroid (map tile rendering — uses reflection for tile providers and HTTP threads)
# Without these rules R8 strips tile provider classes and the map renders white tiles (MESHSAT-493)
-keep class org.osmdroid.** { *; }
-keep interface org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Phase F: signing + config + API
-keepnames class com.cubeos.meshsat.engine.SigningService { *; }
-keepnames class com.cubeos.meshsat.config.ConfigManager { *; }
-keepnames class com.cubeos.meshsat.config.DiffResult { *; }
-keepnames class com.cubeos.meshsat.config.DiffCounts { *; }

# Hub Reporter protocol types (MESHSAT-292)
-keepnames class com.cubeos.meshsat.hub.HubProtocol { *; }
-keepnames class com.cubeos.meshsat.hub.HubReporter { *; }
-keepnames class com.cubeos.meshsat.hub.HubReporterConfig { *; }
-keepnames class com.cubeos.meshsat.hub.BridgeBirth { *; }
-keepnames class com.cubeos.meshsat.hub.BridgeDeath { *; }
-keepnames class com.cubeos.meshsat.hub.BridgeHealth { *; }
-keepnames class com.cubeos.meshsat.hub.DeviceBirth { *; }
-keepnames class com.cubeos.meshsat.hub.DeviceDeath { *; }
-keepnames class com.cubeos.meshsat.hub.DevicePosition { *; }
-keepnames class com.cubeos.meshsat.hub.DeviceTelemetry { *; }

# Data classes used in rules/transports
-keepnames class com.cubeos.meshsat.rules.ForwardingRule { *; }
-keepnames class com.cubeos.meshsat.bt.IridiumSpp$SbdixResult { *; }
-keepnames class com.cubeos.meshsat.bt.IridiumSpp$SbdsxResult { *; }
-keepnames class com.cubeos.meshsat.bt.IridiumSpp$ModemInfo { *; }
-keepnames class com.cubeos.meshsat.ble.MeshtasticProtocol$MeshTextMessage { *; }
