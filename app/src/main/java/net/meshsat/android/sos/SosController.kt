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
import net.meshsat.android.hubmsg.HubMsgIds
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
    /** The clock, and the follow loop's period: a test moves the one and shortens the other. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val retryMs: Long = HUB_RETRY_MS,
) {
    /** What the controller needs from the running service. */
    interface Env {
        val dispatcher: Dispatcher?
        val hubReporter: HubReporter?
        /** The Hub session the SOS uses: the reporter, unless a test gives its own (C10). */
        val sosHub: SosHubLink? get() = hubReporter
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
        /** The most earlier runs whose cancellation is still followed (r16 review C10-R4). */
        private const val PREV_MAX = 8
        /** A test that has not finished by then stops what is still waiting. */
        const val TEST_LIMIT_MS = 30 * 60_000L

        private val _run = MutableStateFlow<SosRun?>(null)
        /** The SOS in progress, or the last one: the banner, the Home card and the result screen read it. */
        val run: StateFlow<SosRun?> = _run
    }

    private val mutex = Mutex()
    private var hubJob: Job? = null
    private var watchJob: Job? = null
    private var prevJob: Job? = null
    /**
     * Earlier runs whose cancellation is still owed (r16 review C10-R4): a new SOS does not end
     * the older one's obligation, which goes on until its own ack or its day. Kept in settings,
     * guarded by [mutex].
     */
    private val prev = mutableListOf<SosRun>()

    /** The run with [id]: the current one, or an earlier one still owed its cancellation. */
    private fun findLocked(id: Long): SosRun? = _run.value?.takeIf { it.id == id } ?: prev.firstOrNull { it.id == id }

    /** Changes the run with [id] and keeps it, current or earlier; null when it is not kept. */
    private suspend fun updateLocked(id: Long, f: (SosRun) -> SosRun): SosRun? {
        val cur = _run.value
        if (cur != null && cur.id == id) return f(cur).also { save(it) }
        val i = prev.indexOfFirst { it.id == id }
        if (i < 0) return null
        prev[i] = f(prev[i])
        savePrev()
        return prev[i]
    }

    private suspend fun savePrev() {
        settings.setSosPrevRuns(org.json.JSONArray().apply { prev.forEach { put(org.json.JSONObject(it.toJson())) } }.toString())
    }

    /** The run's cancellation is still owed to the Hub, the old way or under C10. */
    private fun cancelOwed(r: SosRun) = needsCancelPublish(r) || SosV2.cancelPending(r)

    /** Pick up an SOS that was in progress when the app stopped; the job ends once it is loaded. */
    fun restore(): Job =
        scope.launch {
            var saved = SosRun.fromJson(settings.sosRun.first())
            val kept = try {
                val a = org.json.JSONArray(settings.sosPrevRuns.first().ifBlank { "[]" })
                (0 until a.length()).mapNotNull { SosRun.fromJson(a.getJSONObject(it).toString()) }
            } catch (_: Exception) {
                emptyList()
            }
            mutex.withLock {
                prev.clear()
                prev.addAll(kept)
            }
            if (kept.isNotEmpty()) followPrev()
            // A run kept from before C10 takes the old-format id, the same on every route (MESHSAT-1584).
            val bridge = env.sosHub?.bridgeId.orEmpty()
            if (saved != null && !saved.test && saved.sosId.isEmpty() && bridge.isNotEmpty()) {
                saved = saved.copy(sosId = HubMsgIds.legacySosId(bridge, saved.id), bridgeId = saved.bridgeId.ifEmpty { bridge })
                settings.setSosRun(saved.toJson())
            }
            _run.value = saved
            if (saved != null && (saved.active || needsCancelPublish(saved) || SosV2.cancelPending(saved))) follow(saved)
        }

    /** The Hub's caps say sos_v2 (the last ones it sent, kept). */
    private suspend fun capsV2(): Boolean = SosV2.capsSayV2(settings.hubCaps.first())

    /**
     * The Hub's ack of this phone's SOS start or cancel (sos/ack, C10): it has it. A start's ends
     * the hub leg's repeats; a cancel's ends the cancel's and stops the satellite's 0x08 if it has
     * not gone. An ack for another run changes nothing.
     */
    suspend fun onAck(payload: String) {
        val a = SosV2.parseAck(payload) ?: return
        mutex.withLock {
            val r = (listOfNotNull(_run.value) + prev).firstOrNull { it.sosId.isNotEmpty() && it.sosId == a.sosId }
            if (r == null) {
                Log.i(TAG, "An ack for an SOS this phone no longer follows, ignored")
                return
            }
            val now = clock()
            when (a.kind) {
                "start" -> {
                    // The Hub has the start of a run cancelled here: it is owed the cancel, even when
                    // the cancel was made before anything said the start had left (C10-R5).
                    val owe = r.cancelledAt != null && r.cancelHubAckedAt == null && !r.cancelGaveUp && !r.cancelV2Owed
                    val next = updateLocked(r.id) {
                        it.copy(hubSentAt = it.hubSentAt ?: now, hubV2 = true, hubState = a.state, cancelV2Owed = it.cancelV2Owed || owe)
                    }
                    if (owe && next != null) {
                        if (next.id == _run.value?.id) follow(next) else followPrev()
                    }
                }
                "cancel" -> if (r.cancelledAt != null && r.cancelHubAckedAt == null) {
                    updateLocked(r.id) { it.copy(cancelHubAckedAt = now) }
                    db.messageDeliveryDao().cancelWaitingByRefPrefix(r.refPrefix + "cancel:sat")
                    if (prev.removeAll { it.id == r.id && !cancelOwed(it) }) savePrev()
                    Log.w(TAG, "SOS ${r.id} cancelled: the Hub has the cancellation")
                }
            }
        }
    }

    fun stop() {
        hubJob?.cancel()
        watchJob?.cancel()
        prevJob?.cancel()
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
            val r = findLocked(runId) ?: return
            if (r.test || r.cancelledAt == null) return
            if (isSatV2(r, del)) {
                // The 0x07 went after the cancel: the Hub is owed the cancel under the run's id.
                if (!r.cancelV2Owed) {
                    val owed = updateLocked(r.id) { it.copy(cancelV2Owed = true) } ?: return
                    if (owed.id == _run.value?.id) follow(owed) else followPrev()
                }
                return
            }
            enqueueCancel(r, del)
        }
    }

    /** [del] is the satellite leg of [r] and carried the 0x07: its cancel is the 0x08 (C10). */
    private fun isSatV2(r: SosRun, del: MessageDeliveryEntity) =
        r.satV2 && r.sosId.isNotEmpty() && del.msgRef == r.refPrefix + "sat"

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

            // The run this one replaces keeps its owed cancellation (C10-R4).
            if (current != null && !current.test && !current.active && cancelOwed(current) && prev.none { it.id == current.id }) {
                prev.add(current)
                while (prev.size > PREV_MAX) prev.removeAt(0)
                savePrev()
                followPrev()
                Log.w(TAG, "A new SOS starts; the last one's cancellation is still followed")
            }
            val now = clock().let { t -> maxOf(t, (prev.maxOfOrNull { it.id } ?: 0L) + 1, (current?.id ?: 0L) + 1) }
            val loc = env.location()
            val fix = loc?.let { SosMessages.Fix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, it.time) }
            val name = settings.sosName.first().ifBlank { settings.hubCallsign.first() }
            val contacts = settings.sosContacts.first()
            val imei = env.modemImei()
            val hub = env.sosHub
            val bridgeId = hub?.bridgeId?.ifBlank { null } ?: "meshsat-android"
            val deviceId = imei.ifBlank { bridgeId }
            val prefix = SosRun.refPrefix(now)
            val routes = mutableListOf<SosRun.Route>()
            val skipped = mutableListOf<String>()
            val disp = env.dispatcher

            val meshText = if (test) SosMessages.testText(name) else SosMessages.meshText(name, fix, now)
            // Its own identity (C10): the same on every route and after a restart; the frames and
            // the Hub's event go under it while the Hub says sos_v2 (MESHSAT-1584).
            val sosId = if (test) "" else HubMsgIds.newSosId(now)
            val v2 = !test && hub != null && capsV2()
            var satV2 = false
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
                    } else if (v2) {
                        val frame = SosMessages.satFrameStart(bridgeId, imei, fix, SosMessages.frameMessage(name, fix), now / 1000, sosId, now)
                        disp.enqueueDirect("iridium_0", frame, meshText, prefix + "sat", priority = 0).also { satV2 = it != null }
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
                    !net.meshsat.android.sms.SmsCapability.included -> skipped += "SMS: not part of the Google Play edition."
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
                sosId = sosId, bridgeId = bridgeId, satV2 = satV2,
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
            val cancelled = r.copy(cancelledAt = clock())
            save(cancelled)
            // A route that carried the SOS, or may be carrying it right now, is told it is over. Under
            // C10 the satellite's is the 0x08 (the follow loop queues it, after the grace) and the
            // Hub's the v2 event, both under the run's id.
            // hubV2 is kept before the first start publish: one under way may reach the Hub (C10-R5).
            var owed = r.hubV2
            for (del in dao.getByRefPrefix(r.refPrefix)) {
                if (!satStartWent(del)) continue
                if (isSatV2(cancelled, del)) owed = true else enqueueCancel(cancelled, del)
            }
            val next = if (owed) cancelled.copy(cancelV2Owed = true) else cancelled
            if (owed) save(next)
            Log.w(TAG, "SOS ${r.id} cancelled")
            env.audit("sos_cancelled", "sos=${r.id}")
            follow(next)
        }
    }

    private suspend fun finishLocked(r: SosRun) {
        db.messageDeliveryDao().cancelWaitingByRefPrefix(r.refPrefix)
        save(r.copy(finishedAt = clock()))
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

    /** The cancel goes the old way: the Hub had the start the old way and not its cancel yet. */
    private fun needsCancelPublish(r: SosRun) =
        !r.test && r.cancelledAt != null && r.hubSentAt != null && !r.hubV2 && r.cancelHubSentAt == null

    /** A start's delivery went out, or may have: sent, delivered, being sent. */
    private fun satStartWent(del: MessageDeliveryEntity) = del.status in listOf("sent", "delivered", "sending")

    /** Keep the Hub informed and the notification current while this run needs it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun follow(run: SosRun) {
        hubJob?.cancel()
        hubJob = scope.launch(Dispatchers.IO) {
            var startPublished = 0
            var nextStart = 0L
            var cancelPublished = 0
            var nextCancel = 0L
            while (isActive) {
                val r = _run.value ?: break
                if (r.id != run.id) break
                val hub = env.sosHub
                val now = clock()
                if (r.hubWanted && r.hubSentAt == null && r.active && hub != null && SosV2.hubLegV2(r, capsV2())) {
                    // C10: the v2 start, again and again until the Hub's ack (onAck). The attempt is
                    // kept before the publish: once it is under way the start may reach the Hub
                    // whatever the answer, so a cancel from now on owes the cancel (C10-R5).
                    if (now >= nextStart && hub.isConnected) {
                        if (!r.hubV2) mutex.withLock { updateLocked(r.id) { it.copy(hubV2 = true) } }
                        if (hub.publishSosEvent(SosV2.startEvent(r, SosMessages.meshText(r.name, r.fix, r.id)))) {
                            nextStart = now + SosV2.repeatAfter(++startPublished)
                            if (r.hubPublishedAt == null) mutex.withLock { updateLocked(r.id) { it.copy(hubPublishedAt = now) } }
                        }
                    }
                } else if (r.hubWanted && r.hubSentAt == null && r.active && hub != null) {
                    val text = if (r.test) SosMessages.testText(r.name) else SosMessages.meshText(r.name, r.fix, r.id)
                    val sent = hub.publishSos(
                        r.deviceId, r.hubAlertId, text, sos = !r.test,
                        type = if (r.test) "test" else "triggered", lat = r.fix?.lat, lon = r.fix?.lon,
                        asMessage = !r.test,
                    )
                    if (sent) mutex.withLock { _run.value?.takeIf { it.id == r.id }?.let { save(it.copy(hubSentAt = clock())) } }
                }
                followCancelOldWay(r, hub)
                if (SosV2.cancelPending(r)) followCancelV2(r, now, hub, cancelPublished, nextCancel)?.let { (n, at) ->
                    cancelPublished = n; nextCancel = at
                }
                if (r.test && r.active) {
                    val statuses = SosProgress.routes(r, db.messageDeliveryDao().getByRefPrefix(r.refPrefix))
                    if (SosProgress.allSettled(r, statuses) || clock() - r.id > TEST_LIMIT_MS) {
                        mutex.withLock { _run.value?.takeIf { it.id == r.id && it.active }?.let { finishLocked(it) } }
                    }
                }
                val latest = _run.value ?: break
                val hubDone = !latest.hubWanted || latest.hubSentAt != null || !latest.active
                if (!latest.active && !cancelOwed(latest) && hubDone) break
                delay(retryMs)
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

    /** The old-way cancellation, once, for a start that went the old way. */
    private suspend fun followCancelOldWay(r: SosRun, hub: SosHubLink?) {
        if (!needsCancelPublish(r) || hub == null) return
        val sent = hub.publishSos(
            r.deviceId, r.hubAlertId + "-cancelled", SosMessages.cancelText(r.name), sos = false,
            type = "cancelled", lat = r.fix?.lat, lon = r.fix?.lon,
        )
        if (sent) mutex.withLock { updateLocked(r.id) { it.copy(cancelHubSentAt = clock()) } }
    }

    /**
     * Follows the cancellations earlier runs still owe (C10-R4): one loop over all of them, each
     * with its own repeat, until each is acked or given up; then it is no longer kept.
     */
    private fun followPrev() {
        if (prevJob?.isActive == true) return
        prevJob = scope.launch(Dispatchers.IO) {
            val repeats = mutableMapOf<Long, Pair<Int, Long>>()
            while (isActive) {
                val runs = mutex.withLock { prev.toList() }
                if (runs.isEmpty()) break
                val hub = env.sosHub
                val now = clock()
                for (r in runs) {
                    followCancelOldWay(r, hub)
                    if (SosV2.cancelPending(r)) {
                        val (n, at) = repeats[r.id] ?: (0 to 0L)
                        followCancelV2(r, now, hub, n, at)?.let { repeats[r.id] = it }
                    }
                }
                mutex.withLock { if (prev.removeAll { !cancelOwed(it) }) savePrev() }
                delay(retryMs)
            }
        }
    }

    /**
     * One round of the cancel under the run's identity (C10): after a day it is given up and the
     * 0x08 still waiting stops; else the v2 cancel is published when its repeat is due, and the 0x08
     * is queued on the satellite that carried the 0x07 once the grace is over (at once with no Hub
     * session). The frame is owed until its row exists: a failed enqueue is tried again next round,
     * and a row already under its reference (a crash before the run was kept) counts, so the
     * satellite never gets two (C10-R3). Answers the publish count and the next publish time when
     * it published.
     */
    private suspend fun followCancelV2(r: SosRun, now: Long, hub: SosHubLink?, published: Int, next: Long): Pair<Int, Long>? {
        val dao = db.messageDeliveryDao()
        if (SosV2.cancelOver(r, now)) {
            mutex.withLock {
                updateLocked(r.id) { it.copy(cancelGaveUp = true) }
                dao.cancelWaitingByRefPrefix(r.refPrefix + "cancel:sat")
            }
            Log.w(TAG, "SOS ${r.id} cancelled: no ack from the Hub in a day; the cancel is no longer repeated")
            return null
        }
        val up = hub != null && hub.isConnected
        var result: Pair<Int, Long>? = null
        if (up && now >= next && hub!!.publishSosEvent(SosV2.cancelEvent(r))) {
            result = (published + 1) to now + SosV2.repeatAfter(published + 1)
            if (r.cancelHubPublishedAt == null) mutex.withLock { updateLocked(r.id) { it.copy(cancelHubPublishedAt = now) } }
        }
        val sat = dao.getByRefPrefix(r.refPrefix + "sat").firstOrNull { it.msgRef == r.refPrefix + "sat" }
        if (SosV2.cancelFrameDue(r, now, up, sat != null && satStartWent(sat))) {
            mutex.withLock {
                val cur = findLocked(r.id)?.takeIf { !it.cancelFrameDone } ?: return@withLock
                val ref = cur.refPrefix + "cancel:sat"
                val queued = dao.getByRefPrefix(ref).any { it.msgRef == ref } || run {
                    val bridge = cur.bridgeId.ifEmpty { hub?.bridgeId.orEmpty() }
                    val frame = SosMessages.cancelFrame(bridge, cur.sosId, cur.id, (cur.cancelledAt ?: now) / 1000)
                    env.dispatcher?.enqueueDirect("iridium_0", frame, SosMessages.cancelText(cur.name), ref, priority = 0) != null
                }
                if (queued) {
                    updateLocked(cur.id) { it.copy(cancelFrameDone = true) }
                    Log.w(TAG, "SOS ${cur.id} cancelled: the cancel frame is queued on the satellite")
                } else {
                    Log.w(TAG, "SOS ${cur.id} cancelled: the cancel frame could not be queued; tried again")
                }
            }
        }
        return result
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
