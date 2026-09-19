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
import net.meshsat.android.rules.AccessEvaluator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Proxy
import java.lang.reflect.WildcardType
import java.util.Collections
import kotlin.coroutines.Continuation

/**
 * A send that has started finishes and is recorded when its worker is stopped meanwhile. The
 * Iridium interface going offline mid-send used to cancel it and leave the row 'sending' until
 * the app restarted (MESHSAT-1243).
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
            deliveryCallback = { _, _, _ ->
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

    private companion object {
        val NONE = Any()
    }
}
