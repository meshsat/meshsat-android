package net.meshsat.android.hubmsg

/** The JVM's android.jar has no device model; the reporter's birth needs one. [MESHSAT-1584] */
internal fun jvmBuildModel() {
    val f = android.os.Build::class.java.getDeclaredField("MODEL")
    if (runCatching { f.get(null) }.getOrNull() != null) return
    val u = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val base = u.javaClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(u, f)
    val off = u.javaClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(u, f) as Long
    u.javaClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java).invoke(u, base, off, "hubmsg-live")
}
