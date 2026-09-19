package net.meshsat.android.sos

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.meshsat.android.MainActivity
import net.meshsat.android.MeshSatApp
import net.meshsat.android.R
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.MessageDeliveryEntity
import net.meshsat.android.data.SettingsRepository
import net.meshsat.android.engine.Dispatcher
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.service.GatewayService

/**
 * Sends an SOS on every route the phone has and keeps at it until the user cancels (MESHSAT-1249).
 *
 * What replaced: three sends 30 s apart written straight to each radio, so a modem that was not
 * connected at that instant was skipped for good, SMS went only to a kit's number, the Hub heard of
 * it only through TAK, and nothing recorded what went out.
 *
 * Now every route is a delivery in the queue at priority 0: the satellite leg is the frame the Hub
 * decodes into an SOS alert from any modem, and it retries every few minutes until it goes; the mesh
 * leg is a broadcast any MeshSat kit in range relays to the Hub; each emergency contact gets an SMS
 * from the phone's own SIM; and the Hub is told over the internet as soon as it is connected. A
 * cancelled SOS stops everything that has not gone out, and every route that did carry it carries a
 * cancellation next. A test uses the same routes with a text that raises no alarm anywhere.
 */
class SosController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val env: Env,
) {
    /** What the controller needs from the running service. */
    interface Env {
        val dispatcher: Dispatcher?
        val hubReporter: HubReporter?
        /** The IMEI of the modem connected now, or else the last one this phone talked to. */
        suspend fun modemImei(): String
        /** A mesh radio is paired, connected or not. */
        suspend fun meshPaired(): Boolean
        fun location(): Location?
        fun tak(lat: Double, lon: Double, alt: Double, reason: String)
        /** A line in the signed audit log. */
        suspend fun audit(event: String, detail: String)
    }

    companion object {
        private const val TAG = "Sos"
        const val NOTIFICATION_ID = 1249
        private const val HUB_RETRY_MS = 15_000L
        /** A test that has not finished by then stops what is still waiting. */
        const val TEST_LIMIT_MS = 30 * 60_000L

        private val _run = MutableStateFlow<SosRun?>(null)
        /** The SOS in progress, or the last one: the banner, the Home card and the result screen read it. */
        val run: StateFlow<SosRun?> = _run
    }

    private val mutex = Mutex()
    private var hubJob: Job? = null
    private var watchJob: Job? = null

    /** Pick up an SOS that was in progress when the app stopped. */
    fun restore() {
        scope.launch {
            val saved = SosRun.fromJson(settings.sosRun.first())
            _run.value = saved
            if (saved != null && (saved.active || needsCancelPublish(saved))) follow(saved)
        }
    }

    fun stop() {
        hubJob?.cancel()
        watchJob?.cancel()
    }

    /**
     * The dispatcher asks before it sends a delivery: an SOS delivery of a run that has been cancelled
     * or replaced is never sent. A send in progress when the user cancelled can fail and be retried,
     * and without this it would go out after the cancellation.
     */
    suspend fun mayDeliver(del: MessageDeliveryEntity): Boolean {
        val (runId, isCancel) = SosRun.parseRef(del.msgRef) ?: return true
        if (isCancel) return true
        // Right after a restart the run may not be loaded yet: read it rather than guess.
        val current = _run.value ?: SosRun.fromJson(settings.sosRun.first()) ?: return true
        return runId == current.id && current.active
    }

    /** A delivery went out: if its SOS was cancelled meanwhile, that route gets the cancellation too. */
    suspend fun onSent(del: MessageDeliveryEntity) {
        val (runId, isCancel) = SosRun.parseRef(del.msgRef) ?: return
        if (isCancel) return
        mutex.withLock {
            val r = _run.value ?: return
            if (r.id != runId || r.test || r.cancelledAt == null) return
            enqueueCancel(r, del)
        }
    }

    /** Start an SOS, or a test of the alarm. A real SOS replaces a test that is still running. */
    suspend fun start(test: Boolean, trigger: String) {
        mutex.withLock {
            val current = _run.value
            if (current != null && current.active) {
                if (!current.test || test) {
                    Log.w(TAG, "An SOS or test is already running (${current.id}), not starting another")
                    return
                }
                finishLocked(current)
            }

            val now = System.currentTimeMillis()
            val loc = env.location()
            val fix = loc?.let { SosMessages.Fix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time) }
            val name = settings.sosName.first().ifBlank { settings.hubCallsign.first() }
            val contacts = settings.sosContacts.first()
            val imei = env.modemImei()
            val hub = env.hubReporter
            val bridgeId = hub?.bridgeId?.ifBlank { null } ?: "meshsat-android"
            val deviceId = imei.ifBlank { bridgeId }
            val prefix = SosRun.refPrefix(now)
            val routes = mutableListOf<SosRun.Route>()
            val skipped = mutableListOf<String>()
            val disp = env.dispatcher

            val meshText = if (test) SosMessages.testText(name) else SosMessages.meshText(name, fix, now)
            // The Hub files the satellite frame's alert as "sos-<bearer>-<bridge>-<time>"
            // (meshsat-hub internal/bridge/uplink.go); the internet leg uses the same id, so the
            // Hub pages once for this SOS whichever way it arrives first.
            val frameBridge = SosMessages.truncateUtf8(bridgeId, 16)
            val hubAlertId = "sos-sbd-$frameBridge-${now / 1000}"

            if (disp == null) {
                skipped += "The message queue is not running, so nothing could be queued. Restart the app."
            } else {
                // Satellite
                if (imei.isNotBlank()) {
                    val ok = if (test && fix != null) {
                        // The test's satellite leg is a position report: same path to the Hub's uplink
                        // decoder as the SOS frame, and it never reaches the Hub's routing engine.
                        val frame = SosMessages.positionFrame(bridgeId, fix, loc?.altitude ?: 0.0, now / 1000)
                        disp.enqueueDirect("iridium_0", frame, "Alarm test: position report to the Hub", prefix + "sat", priority = 0)
                    } else if (test) {
                        val t = SosMessages.testText(name)
                        disp.enqueueDirect("iridium_0", t.toByteArray(Charsets.UTF_8), t, prefix + "sat", priority = 0)
                    } else {
                        val frame = SosMessages.satFrame(bridgeId, imei, fix, SosMessages.frameMessage(name, fix), now / 1000)
                        disp.enqueueDirect("iridium_0", frame, meshText, prefix + "sat", priority = 0)
                    }
                    if (ok != null) routes += SosRun.Route("sat", "Satellite, to the Hub")
                    else skipped += "Satellite: could not be queued."
                } else {
                    skipped += "Satellite: no satellite modem has been connected to this phone yet."
                }

                // Mesh
                if (env.meshPaired()) {
                    val ok = disp.enqueueDirect("mesh_0", meshText.toByteArray(Charsets.UTF_8), meshText, prefix + "mesh", priority = 0)
                    if (ok != null) routes += SosRun.Route("mesh", "Mesh, everyone in range")
                    else skipped += "Mesh: could not be queued."
                } else {
                    skipped += "Mesh: no mesh radio is paired with this phone."
                }

                // SMS to each emergency contact
                val telephony = net.meshsat.android.sms.SmsCapability.canSend(context)
                when {
                    !telephony -> skipped += "SMS: this device cannot send SMS."
                    contacts.isEmpty() -> skipped += "SMS: you have no emergency contacts. Add them in Setup, Safety."
                    else -> {
                        val sms = if (test) SosMessages.testText(name) else SosMessages.smsText(name, fix, now)
                        for (c in contacts) {
                            val key = "sms:${c.phone}"
                            val ok = disp.enqueueDirect("sms_0", sms.toByteArray(Charsets.UTF_8), sms, prefix + key, priority = 0, recipient = c.phone)
                            if (ok != null) routes += SosRun.Route(key, "SMS to ${c.name.ifBlank { c.phone }}")
                            else skipped += "SMS to ${c.name.ifBlank { c.phone }}: could not be queued."
                        }
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            skipped += "SMS is not allowed yet: allow it in Setup, SMS, and the messages go out."
                        }
                    }
                }
            }

            if (hub == null) skipped += "Hub: not set up on this phone."

            // TAK clients on the same network see the alarm as well (MESHSAT-191).
            if (!test && fix != null) env.tak(fix.lat, fix.lon, loc?.altitude ?: 0.0, meshText)

            val run = SosRun(
                id = now, test = test, trigger = trigger, name = name, fix = fix,
                routes = routes, skipped = skipped, hubWanted = hub != null,
                deviceId = deviceId, hubAlertId = hubAlertId,
            )
            // Route kinds only: the audit log is no place for the contacts' phone numbers.
            val kinds = routes.groupingBy { it.key.substringBefore(':') }.eachCount().entries
                .joinToString(", ") { (k, n) -> if (n > 1) "$k x$n" else k } + if (hub != null) ", hub" else ""
            Log.w(TAG, (if (test) "Alarm test" else "SOS") + " ${run.id} started ($trigger): $kinds; skipped ${skipped.size}")
            env.audit(if (test) "sos_test" else "sos_activated", "trigger=$trigger routes=$kinds position=${if (fix != null) "known" else "unknown"}")
            save(run)
            follow(run)
        }
    }

    /** Cancel the SOS, or stop the test. */
    suspend fun cancel() {
        mutex.withLock {
            val r = _run.value ?: return
            if (!r.active) return
            val dao = db.messageDeliveryDao()
            dao.cancelWaitingByRefPrefix(r.refPrefix)
            if (r.test) {
                finishLocked(r)
                return
            }
            val cancelled = r.copy(cancelledAt = System.currentTimeMillis())
            save(cancelled)
            // A route that carried the SOS, or may be carrying it right now, is told it is over.
            for (del in dao.getByRefPrefix(r.refPrefix)) {
                if (del.status in listOf("sent", "delivered", "sending")) enqueueCancel(cancelled, del)
            }
            Log.w(TAG, "SOS ${r.id} cancelled")
            env.audit("sos_cancelled", "sos=${r.id}")
            follow(cancelled)
        }
    }

    private suspend fun finishLocked(r: SosRun) {
        db.messageDeliveryDao().cancelWaitingByRefPrefix(r.refPrefix)
        save(r.copy(finishedAt = System.currentTimeMillis()))
        Log.i(TAG, "Alarm test ${r.id} finished")
    }

    private suspend fun enqueueCancel(r: SosRun, del: MessageDeliveryEntity) {
        val route = del.msgRef.removePrefix(r.refPrefix)
        val ref = r.refPrefix + "cancel:" + route
        if (db.messageDeliveryDao().getByRefPrefix(ref).isNotEmpty()) return
        val text = SosMessages.cancelText(r.name)
        env.dispatcher?.enqueueDirect(del.channel, text.toByteArray(Charsets.UTF_8), text, ref, priority = 0, recipient = del.recipient)
    }

    private suspend fun save(r: SosRun) {
        _run.value = r
        GatewayService.noteSosActive(r.active && !r.test)
        settings.setSosRun(r.toJson())
    }

    private fun needsCancelPublish(r: SosRun) =
        !r.test && r.cancelledAt != null && r.hubSentAt != null && r.cancelHubSentAt == null

    /** Keep the Hub informed and the notification current while this run needs it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun follow(run: SosRun) {
        hubJob?.cancel()
        hubJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val r = _run.value ?: break
                if (r.id != run.id) break
                val hub = env.hubReporter
                if (r.hubWanted && r.hubSentAt == null && r.active && hub != null) {
                    val text = if (r.test) SosMessages.testText(r.name) else SosMessages.meshText(r.name, r.fix, r.id)
                    val sent = hub.publishSos(
                        r.deviceId, r.hubAlertId, text, sos = !r.test,
                        type = if (r.test) "test" else "triggered", lat = r.fix?.lat, lon = r.fix?.lon,
                        asMessage = !r.test,
                    )
                    if (sent) mutex.withLock { _run.value?.takeIf { it.id == r.id }?.let { save(it.copy(hubSentAt = System.currentTimeMillis())) } }
                }
                if (needsCancelPublish(r) && hub != null) {
                    val sent = hub.publishSos(
                        r.deviceId, r.hubAlertId + "-cancelled", SosMessages.cancelText(r.name), sos = false,
                        type = "cancelled", lat = r.fix?.lat, lon = r.fix?.lon,
                    )
                    if (sent) mutex.withLock { _run.value?.takeIf { it.id == r.id }?.let { save(it.copy(cancelHubSentAt = System.currentTimeMillis())) } }
                }
                if (r.test && r.active) {
                    val statuses = SosProgress.routes(r, db.messageDeliveryDao().getByRefPrefix(r.refPrefix))
                    if (SosProgress.allSettled(r, statuses) || System.currentTimeMillis() - r.id > TEST_LIMIT_MS) {
                        mutex.withLock { _run.value?.takeIf { it.id == r.id && it.active }?.let { finishLocked(it) } }
                    }
                }
                val now = _run.value ?: break
                val hubDone = !now.hubWanted || now.hubSentAt != null || !now.active
                if (!now.active && !needsCancelPublish(now) && hubDone) break
                delay(HUB_RETRY_MS)
            }
        }
        watchJob?.cancel()
        watchJob = scope.launch {
            _run.flatMapLatest { r ->
                if (r == null || r.id != run.id) flowOf(null)
                else db.messageDeliveryDao().observeByRefPrefix(r.refPrefix).map { r to it }
            }.collectLatest { pair ->
                val (r, dels) = pair ?: return@collectLatest
                notify(r, SosProgress.routes(r, dels))
            }
        }
    }

    private fun notify(r: SosRun, statuses: List<SosRouteStatus>) {
        val nm = NotificationManagerCompat.from(context)
        if (!r.active && r.test) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ROUTE, "sos")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when {
            r.test -> "Alarm test running"
            r.cancelledAt != null -> "SOS cancelled"
            else -> "SOS is on"
        }
        val text = if (r.cancelledAt != null) "Telling everyone who got it that you are safe." else SosProgress.summary(statuses)
        val builder = NotificationCompat.Builder(context, MeshSatApp.CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(0xFFF87171.toInt())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setOngoing(r.active)
            .setContentIntent(open)
        if (r.active) {
            val cancel = PendingIntent.getService(
                context, NOTIFICATION_ID + 1,
                Intent(context, GatewayService::class.java).setAction(GatewayService.ACTION_SOS_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val label = if (r.test) "Stop test" else "Cancel SOS"
            // From a locked screen, cancelling a real SOS asks for the unlock first.
            val action = NotificationCompat.Action.Builder(0, label, cancel)
                .setAuthenticationRequired(!r.test)
                .build()
            builder.addAction(action)
        }
        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Notifications not allowed: the banner in the app still shows it.
        }
    }
}
