# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep class net.meshsat.android.data.*_Impl { *; }

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
-keep class net.meshsat.android.crypto.SecureKeyStore { *; }

# Google Tink annotations (compile-time only, not needed at runtime)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# MSVQ-SC crypto classes (accessed via reflection in ONNX pipeline)
-keep class net.meshsat.android.crypto.MsvqscEncoder { *; }
-keep class net.meshsat.android.crypto.MsvqscCodebook { *; }

# Eclipse Paho MQTT (Hub reporter, MQTT transport, relay) — MESHSAT-1235
# Paho loads its logger with Class.forName and its tcp/ssl/ws/wss network modules through
# ServiceLoader. v2.8.6 renamed every Paho class, so `new MqttClient(...)` threw
# "MissingResourceException: Error locating the logging class" and no release build
# could reach the Hub at all. Debug builds are not minified, which is why it went unseen.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keep interface org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.**

# NanoHTTPD (local REST API server)
-keep class fi.iki.elonen.** { *; }

# osmdroid (map tile rendering — uses reflection for tile providers and HTTP threads)
# Without these rules R8 strips tile provider classes and the map renders white tiles (MESHSAT-493)
-keep class org.osmdroid.** { *; }
-keep interface org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Phase F: signing + config + API
-keepnames class net.meshsat.android.engine.SigningService { *; }
-keepnames class net.meshsat.android.config.ConfigManager { *; }
-keepnames class net.meshsat.android.config.DiffResult { *; }
-keepnames class net.meshsat.android.config.DiffCounts { *; }

# Hub Reporter protocol types (MESHSAT-292)
-keepnames class net.meshsat.android.hub.HubProtocol { *; }
-keepnames class net.meshsat.android.hub.HubReporter { *; }
-keepnames class net.meshsat.android.hub.HubReporterConfig { *; }
-keepnames class net.meshsat.android.hub.BridgeBirth { *; }
-keepnames class net.meshsat.android.hub.BridgeDeath { *; }
-keepnames class net.meshsat.android.hub.BridgeHealth { *; }
-keepnames class net.meshsat.android.hub.DeviceBirth { *; }
-keepnames class net.meshsat.android.hub.DeviceDeath { *; }
-keepnames class net.meshsat.android.hub.DevicePosition { *; }
-keepnames class net.meshsat.android.hub.DeviceTelemetry { *; }

# Data classes used in rules/transports
-keepnames class net.meshsat.android.rules.ForwardingRule { *; }
-keepnames class net.meshsat.android.bt.IridiumSpp$SbdixResult { *; }
-keepnames class net.meshsat.android.bt.IridiumSpp$SbdsxResult { *; }
-keepnames class net.meshsat.android.bt.IridiumSpp$ModemInfo { *; }
-keepnames class net.meshsat.android.ble.MeshtasticProtocol$MeshTextMessage { *; }
