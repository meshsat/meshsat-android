package net.meshsat.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.meshsat.android.channel.ChannelRegistry
import net.meshsat.android.data.AccessRuleDao
import net.meshsat.android.data.MessageDeliveryDao
import net.meshsat.android.data.MessageDeliveryEntity
import net.meshsat.android.data.ObjectGroupDao
import net.meshsat.android.engine.AckTracker
import net.meshsat.android.engine.Dispatcher
import net.meshsat.android.rules.AccessEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.WildcardType
import java.util.Collections
import kotlin.coroutines.Continuation

/**
 * What the delivery record says about confirmation (MESHSAT-1584): each channel's pending ACKs time
 * out on that channel's own timeout, and a mesh send, a broadcast that never asks for an ACK, is not
 * left waiting for one.
 */
class AckStatusTest {

    private object None

    /** A Room DAO answered by [answer], with empty defaults for everything else. */
    private inline fun <reified T> fake(noinline answer: (String, Array<Any?>) -> Any? = { _, _ -> None }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            val a = args ?: emptyArray()
            val given = answer(method.name, a)
            if (given !== None) return@newProxyInstance given
            val type = if (a.lastOrNull() is Continuation<*>) {
                val cont = method.genericParameterTypes.last() as ParameterizedType
                when (val arg = cont.actualTypeArguments[0]) {
                    is WildcardType -> arg.lowerBounds.firstOrNull() ?: arg.upperBounds[0]
                    else -> arg
                }
            } else {
                method.genericReturnType
            }
            when ((type as? ParameterizedType)?.rawType ?: type) {
                Integer::class.java, Integer.TYPE -> 0
                java.lang.Long::class.java, java.lang.Long.TYPE -> 0L
                java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> false
                List::class.java -> emptyList<Any>()
                Unit::class.java, Void.TYPE -> Unit
                else -> null
            }
        } as T

    @Test
    fun `each channel's pending ACKs time out on its own timeout`() = runBlocking {
        val sweeps = Collections.synchronizedList(mutableListOf<String>())
        val dao = fake<MessageDeliveryDao> { name, args ->
            when (name) {
                "pendingAckChannels" -> listOf("iridium_0", "sms_0", "mqtt_0")
                "timeoutPendingAcksOn" -> { sweeps.add("${args[0]}:${args[1]}"); 0 }
                "timeoutPendingAcks" -> { sweeps.add("all:${args[0]}"); 0 }
                else -> None
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val now = 1_000_000_000L
            AckTracker(dao, scope).checkTimeouts(now)
            assertEquals(
                listOf("iridium_0:${now - 120_000}", "sms_0:${now - 30_000}", "mqtt_0:${now - 30_000}"),
                sweeps.toList(),
            )
        } finally {
            scope.cancel()
        }
    }

    private fun sendOne(channel: String): List<String> = runBlocking {
        val row = MessageDeliveryEntity(id = 3, msgRef = "msg:3", channel = channel, textPreview = "hello", maxRetries = 3, qosLevel = 1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var served = false
        val dao = fake<MessageDeliveryDao> { name, args ->
            when (name) {
                "getPending" -> if (served) emptyList<MessageDeliveryEntity>() else { served = true; listOf(row) }
                "getById" -> row
                "claimForSend" -> 1
                "markSent" -> { calls.add("status:sent"); if (args[2] == true) calls.add("ackPending"); 1 }
                else -> None
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dispatcher = Dispatcher(
            deliveryDao = dao,
            accessEvaluator = AccessEvaluator(fake<AccessRuleDao>(), fake<ObjectGroupDao>(), scope),
            failoverResolver = null,
            registry = ChannelRegistry(),
            deliveryCallback = { _, _, _, _, _, _ -> null },
            scope = scope,
        )
        try {
            dispatcher.startWorker(channel)
            withTimeout(10_000) { while ("status:sent" !in calls) delay(10) }
            delay(100) // what handleSuccess does after "sent"
            calls.toList()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a mesh broadcast is sent and not left waiting for an ACK that never comes`() {
        val mesh = sendOne("mesh_0")
        assertTrue(mesh.toString(), "status:sent" in mesh)
        assertFalse("a mesh broadcast waits for an ACK: $mesh", "ackPending" in mesh)
        val sat = sendOne("iridium_0")
        assertTrue("a satellite send with QoS 1 waits for its ACK: $sat", "ackPending" in sat)
    }

    @Test
    fun `a late ACK after the timeout is recorded and one already recorded stays`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var status: String? = "timeout"
        val dao = fake<MessageDeliveryDao> { name, args ->
            when (name) {
                "getByChannelAndSeq" -> MessageDeliveryEntity(id = 9, msgRef = "msg:9", channel = "iridium_0", textPreview = "x", ackStatus = status)
                "setAcked" -> { calls.add("acked:${args[0]}"); Unit }
                "setNacked" -> { calls.add("nacked:${args[0]}"); Unit }
                else -> None
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val tracker = AckTracker(dao, scope)
            assertTrue("a late ACK was dropped", tracker.processAck("iridium_0", 4))
            status = "acked"
            assertFalse("an ACK over a recorded one was taken", tracker.processAck("iridium_0", 4, positive = false))
            assertEquals(listOf("acked:9"), calls.toList())
        } finally {
            scope.cancel()
        }
    }
}
