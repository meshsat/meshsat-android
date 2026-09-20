package net.meshsat.android

import kotlinx.coroutines.CompletableDeferred
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
import net.meshsat.android.engine.Dispatcher
import net.meshsat.android.engine.InterfaceState
import net.meshsat.android.rules.AccessEvaluator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.WildcardType
import java.util.Collections
import kotlin.coroutines.Continuation

/**
 * The satellite queue across interface state changes (MESHSAT-1243): a send that has started
 * finishes and is recorded when its worker is stopped meanwhile (going offline mid-send used to
 * leave the row 'sending' until the app restarted), and an interface coming online makes what
 * waited for it due at once.
 */
class DispatcherInFlightTest {

    /** A Room DAO interface answered by [answer], with empty defaults for everything else. */
    private inline fun <reified T> fake(noinline answer: (String, Array<Any?>) -> Any? = { _, _ -> NONE }): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            val a = args ?: emptyArray()
            val given = answer(method.name, a)
            if (given !== NONE) return@newProxyInstance given
            // A suspend function returns Object; its real result type is Continuation's argument.
            val type = if (a.lastOrNull() is Continuation<*>) {
                val cont = method.genericParameterTypes.last() as ParameterizedType
                when (val arg = cont.actualTypeArguments[0]) {
                    is WildcardType -> arg.lowerBounds.firstOrNull() ?: arg.upperBounds[0]
                    else -> arg
                }
            } else {
                method.genericReturnType
            }
            val raw = (type as? ParameterizedType)?.rawType ?: type
            when (raw) {
                Integer::class.java, Integer.TYPE -> 0
                java.lang.Long::class.java, java.lang.Long.TYPE -> 0L
                java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> false
                List::class.java -> emptyList<Any>()
                Unit::class.java, Void.TYPE -> Unit
                else -> null
            }
        } as T

    @Test
    fun `stopping the worker mid-send lets the send finish and records it`() = runBlocking {
        val row = MessageDeliveryEntity(
            id = 7, msgRef = "msg:7", channel = "iridium_0", textPreview = "now?", maxRetries = 0,
        )
        val statuses = Collections.synchronizedList(mutableListOf<String>())
        var served = false
        val dao = fake<MessageDeliveryDao> { name, args ->
            when (name) {
                "getPending" -> if (served) emptyList<MessageDeliveryEntity>() else { served = true; listOf(row) }
                "getById" -> row
                "setStatus" -> { statuses.add(args[1] as String); Unit }
                else -> NONE
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val dispatcher = Dispatcher(
            deliveryDao = dao,
            accessEvaluator = AccessEvaluator(fake<AccessRuleDao>(), fake<ObjectGroupDao>(), scope),
            failoverResolver = null,
            registry = ChannelRegistry(),
            deliveryCallback = { _, _, _, _, _, _ ->
                started.complete(Unit)
                release.await()
                null
            },
            scope = scope,
        )
        try {
            dispatcher.startWorker("iridium_0")
            withTimeout(10_000) { started.await() }

            dispatcher.stopWorker("iridium_0") // the interface went offline during the session
            release.complete(Unit)

            withTimeout(10_000) { while ("sent" !in statuses) delay(10) }
            assertEquals(listOf("sending", "sent"), statuses.filter { it == "sending" || it == "sent" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an interface coming online makes its waiting retries due now`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val dao = fake<MessageDeliveryDao> { name, args ->
            if (name == "retryNowForChannel") {
                calls.add(args[0] as String)
                2
            } else {
                NONE
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val dispatcher = Dispatcher(
            deliveryDao = dao,
            accessEvaluator = AccessEvaluator(fake<AccessRuleDao>(), fake<ObjectGroupDao>(), scope),
            failoverResolver = null,
            registry = ChannelRegistry(),
            deliveryCallback = { _, _, _, _, _, _ -> "offline" },
            scope = scope,
        )
        try {
            dispatcher.onInterfaceStateChange("iridium_0", "iridium", InterfaceState.Connecting, InterfaceState.Online)
            withTimeout(10_000) { while (calls.isEmpty()) delay(10) }
            assertEquals(listOf("iridium_0"), calls.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a not-now answer waits without counting a try and stops the batch`() = runBlocking {
        val rows = listOf(
            MessageDeliveryEntity(id = 1, msgRef = "msg:1", channel = "iridium_0", textPreview = "old", maxRetries = 0),
            MessageDeliveryEntity(id = 2, msgRef = "msg:2", channel = "iridium_0", textPreview = "new", maxRetries = 0),
        )
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var served = false
        val dao = fake<MessageDeliveryDao> { name, args ->
            when (name) {
                "getPending" -> if (served) emptyList<MessageDeliveryEntity>() else { served = true; rows }
                "getById" -> rows.first { it.id == args[0] as Long }
                "deferRetry" -> { calls.add("defer:${args[0]}"); Unit }
                "scheduleRetry" -> { calls.add("retry:${args[0]}"); Unit }
                else -> NONE
            }
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val attempts = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = Dispatcher(
            deliveryDao = dao,
            accessEvaluator = AccessEvaluator(fake<AccessRuleDao>(), fake<ObjectGroupDao>(), scope),
            failoverResolver = null,
            registry = ChannelRegistry(),
            deliveryCallback = { _, _, text, _, _, _ ->
                attempts.add(text)
                "${Dispatcher.NOT_NOW}120000 the modem pauses"
            },
            scope = scope,
        )
        try {
            dispatcher.startWorker("iridium_0")
            withTimeout(10_000) { while ("defer:1" !in calls) delay(10) }
            delay(300)
            assertEquals(listOf("old"), attempts.toList())
            assertEquals(listOf("defer:1"), calls.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a successful send makes the channel's other waiting messages due now`() = runBlocking {
        val row = MessageDeliveryEntity(id = 5, msgRef = "msg:5", channel = "iridium_0", textPreview = "tst2", maxRetries = 0)
        val woken = Collections.synchronizedList(mutableListOf<String>())
        var served = false
        val dao = fake<MessageDeliveryDao> { name, args ->
            when (name) {
                "getPending" -> if (served) emptyList<MessageDeliveryEntity>() else { served = true; listOf(row) }
                "getById" -> row
                "retryNowForChannel" -> { woken.add(args[0] as String); 2 }
                else -> NONE
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
            dispatcher.startWorker("iridium_0")
            withTimeout(10_000) { while (woken.isEmpty()) delay(10) }
            assertEquals(listOf("iridium_0"), woken.toList())
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        val NONE = Any()
    }
}
