package net.meshsat.android.support

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.meshsat.android.BuildConfig
import net.meshsat.android.ble.NodeStatsText
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.service.GatewayService
import net.meshsat.android.sms.SmsCapability
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Gathers the texts of the debug.zip (MESHSAT-1806) from the running app: what it is and runs
 * on, the state of every link, the queue's counts, the local telemetry (crashes included), the
 * app's own log and the node's log. Nothing here sends anything: the zip is written to the
 * app's cache and leaves the phone only through the app the user picks.
 */
object SupportReport {

    /** The zip's folder in the cache, also named in res/xml/support_paths.xml. */
    const val DIR = "support"

    /** Log lines asked of logcat; the app's own buffer on a busy day holds a few hours of this. */
    private const val LOGCAT_LINES = 10_000

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

    val edition: String get() = if (SmsCapability.included) "full edition (F-Droid, GitHub)" else "Google Play edition, without SMS"
    val android: String get() = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val device: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Collects the parts; [withLogs] false leaves the two logs out (the API's quick form). */
    suspend fun parts(context: Context, withLogs: Boolean = true): List<SupportBundle.Part> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        buildList {
            add(SupportBundle.Part("README.txt", readme(now)))
            add(SupportBundle.Part("about.txt", about(context, now)))
            add(SupportBundle.Part("links.txt", links()))
            add(SupportBundle.Part("queue.txt", queue(context)))
            add(SupportBundle.Part("telemetry.txt", telemetry(context)))
            if (withLogs) {
                add(SupportBundle.Part("logcat.txt", logcat()))
                add(SupportBundle.Part("node-log.txt", GatewayService.meshtasticBle?.nodeLog?.text().orEmpty().ifBlank { "(the node log is empty: it fills while Setup > Advanced > Node log is open)\n" }))
            }
        }
    }

    /** Writes the zip into the cache, removing any older one first, and returns it. */
    suspend fun writeZip(context: Context): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val now = System.currentTimeMillis()
        val file = File(dir, SupportBundle.fileName(now))
        val parts = parts(context)
        file.outputStream().use { SupportBundle.write(parts, it, now) }
        file
    }

    private fun time(ms: Long): String =
        if (ms <= 0) "never" else stamp.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun readme(now: Long) = """
        MeshSat Android debug report, made ${time(now)} on the user's request.

        about.txt      the app, the phone, permissions, the node
        links.txt      every way out and its state
        queue.txt      how many messages wait, went out or failed, per link (no message text)
        telemetry.txt  the app's own records: crashes, health and memory samples, events
        logcat.txt     the app's own log (Android lets an app read only its own)
        node-log.txt   the node's log, if it was being watched

        Blanked before the zip was written: keys and passwords, email addresses, phone numbers,
        IMEIs, and all but the last byte of Bluetooth and Wi-Fi addresses. The logs can still
        name nodes and contacts and show positions and the times messages were sent.
    """.trimIndent() + "\n"

    private fun about(context: Context, now: Long): String = buildString {
        appendLine("Report made: ${time(now)}")
        appendLine("App: MeshSat Android ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), $edition, ${BuildConfig.BUILD_TYPE}")
        appendLine("Installed from: ${installer(context)}")
        appendLine("Android: $android, security patch ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Phone: $device")
        appendLine("Language and time zone: ${Locale.getDefault().toLanguageTag()}, ${ZoneId.systemDefault().id}")
        appendLine("App process running for: ${NodeStatsText.duration((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000)}")
        appendLine("Gateway service: ${if (GatewayService.ifaceManager != null) "running" else "not running"}")
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        appendLine("Battery optimisation: ${if (pm.isIgnoringBatteryOptimizations(context.packageName)) "off for MeshSat" else "on (Android may stop the app in the background)"}")
        appendLine()
        appendLine("Permissions:")
        for (p in permissions()) {
            val granted = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            appendLine("  ${p.removePrefix("android.permission.")}: ${if (granted) "allowed" else "not allowed"}")
        }
        appendLine()
        val ble = GatewayService.meshtasticBle
        appendLine("Node:")
        if (ble == null) {
            appendLine("  no Bluetooth link set up")
        } else {
            appendLine("  Bluetooth on this phone: ${if (ble.bluetoothOn.value) "on" else "off"}")
            appendLine("  link: ${ble.state.value}")
            ble.myInfo.value?.let { appendLine("  node: !%08x".format(it.myNodeNum)) }
            appendLine("  nodes heard: ${ble.nodes.value.size}")
            val pipe = ble.iridiumPipe.value
            if (pipe == null) {
                appendLine("  satellite pipe: none")
            } else {
                appendLine("  satellite pipe owner: ${pipe.owner.value}")
                pipe.status.value?.let { appendLine("  STATUS flags: ${it.flags}") }
                pipe.stats.value?.let { s -> NodeStatsText.rows(s).forEach { (k, v) -> appendLine("  $k: $v") } }
            }
        }
        GatewayService.iridiumSpp?.let { appendLine("9603 driver: ${it.state.value}, signal ${it.signal.value} of 5") }
        GatewayService.iridium9704Spp?.let { appendLine("9704 driver: ${it.state.value}") }
        GatewayService.hubReporter?.let { appendLine("Hub: ${it.state.value}") }
    }

    private fun permissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.RECORD_AUDIO)
        if (SmsCapability.included) {
            add("android.permission.SEND_SMS")
            add("android.permission.RECEIVE_SMS")
        }
    }

    @Suppress("DEPRECATION")
    private fun installer(context: Context): String = try {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
        name ?: "not recorded (installed by hand or by adb)"
    } catch (e: Exception) {
        "unknown"
    }

    private fun links(): String = buildString {
        val all = GatewayService.ifaceManager?.getAllStatus().orEmpty()
        if (all.isEmpty()) {
            appendLine("The gateway service is not running, so there are no links to show.")
            return@buildString
        }
        for (s in all.sortedBy { it.id }) {
            appendLine("${s.id} (${s.channelType}): ${s.state.name.lowercase()}")
            if (s.error.isNotBlank()) appendLine("  last error: ${s.error}")
            appendLine("  last online: ${time(s.lastOnline)}, last activity: ${time(s.lastActivity)}, reconnect attempts: ${s.reconnectAttempts}")
        }
    }

    private suspend fun queue(context: Context): String = buildString {
        val rows = try {
            AppDatabase.getInstance(context).messageDeliveryDao().stats()
        } catch (e: Exception) {
            appendLine("The queue could not be read: ${e.message}")
            return@buildString
        }
        if (rows.isEmpty()) {
            appendLine("The queue is empty.")
            return@buildString
        }
        for (r in rows.sortedWith(compareBy({ it.channel }, { it.status }))) appendLine("${r.channel}  ${r.status}  ${r.cnt}")
    }

    private suspend fun telemetry(context: Context): String = buildString {
        val rows = try {
            AppDatabase.getInstance(context).telemetryDao().getRecent(500)
        } catch (e: Exception) {
            appendLine("The telemetry could not be read: ${e.message}")
            return@buildString
        }
        if (rows.isEmpty()) {
            appendLine("No telemetry recorded (it may be switched off in Setup).")
            return@buildString
        }
        for (t in rows) {
            appendLine("${time(t.timestamp)}  ${t.type}/${t.severity}  ${t.tag}: ${t.message}")
            if (t.detail.isNotBlank() && t.detail != "{}") appendLine("  ${t.detail}")
        }
    }

    /** The app's own log; since Android 4.1 logcat run by an app returns only that app's lines. */
    private fun logcat(): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString())
            .redirectErrorStream(true)
            .start()
        val text = proc.inputStream.bufferedReader().use { it.readText() }
        if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroy()
        text.ifBlank { "(logcat returned nothing)\n" }
    } catch (e: Exception) {
        "logcat could not be read: ${e.message}\n"
    }
}
