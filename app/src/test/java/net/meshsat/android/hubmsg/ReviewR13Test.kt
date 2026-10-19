package net.meshsat.android.hubmsg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewR13Test {
    @Test
    fun reconnectDuringSuccessfulPublishMustStillRecover() = runBlocking {
        var t = 1_792_400_000_000L
        val hub = ContractHub()
        val sa = MemHubMsgStore()
        val sb = MemHubMsgStore()
        val la = hub.link("bridge:phone-a")
        val lb = hub.link("bridge:kit-b")
        lateinit var a: HubMsgRoute
        var intercept = true
        val publisher = object : HubMsgRoute.Publisher {
            override val principal = la.principal
            override fun isConnected() = la.isConnected()
            override fun publishMsg(kind: String, payload: String): HubMsgRoute.Published {
                val result = la.publishMsg(kind, payload)
                if (kind == "out" && intercept) {
                    intercept = false
                    la.up = false
                    runBlocking { hub.pump() }
                    la.up = true
                    a.onConnected()
                }
                return result
            }
        }
        a = HubMsgRoute(sa, publisher, { t })
        hub.routes[la.principal] = a
        hub.routes[lb.principal] = HubMsgRoute(sb, lb, { t })
        a.onConnected()
        val id = (a.send(lb.principal, "generation boundary") as HubMsgRoute.SendResult.Queued).messageId
        repeat(3) { t += 60_000; a.pass(); hub.pump() }
        val observed = sa.row(id)!!.forwardedTo
        println("after reconnect: status=$observed replays=${hub.replays} receiverRows=${sb.rows().size}")
        a.onConnected()
        a.pass()
        hub.pump()
        assertEquals("positive control", HubMsgStatus.DELIVERED, sa.row(id)!!.forwardedTo)
        assertEquals("new connection must recover without another reconnect", HubMsgStatus.DELIVERED, observed)
    }
}
