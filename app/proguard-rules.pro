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

# MSVQ-SC crypto classes (accessed via reflection in ONNX pipeline)
-keep class com.cubeos.meshsat.crypto.MsvqscEncoder { *; }
-keep class com.cubeos.meshsat.crypto.MsvqscCodebook { *; }

# Data classes used in rules/transports
-keepnames class com.cubeos.meshsat.rules.ForwardingRule { *; }
-keepnames class com.cubeos.meshsat.bt.IridiumSpp$SbdixResult { *; }
-keepnames class com.cubeos.meshsat.bt.IridiumSpp$SbdsxResult { *; }
-keepnames class com.cubeos.meshsat.bt.IridiumSpp$ModemInfo { *; }
-keepnames class com.cubeos.meshsat.ble.MeshtasticProtocol$MeshTextMessage { *; }
