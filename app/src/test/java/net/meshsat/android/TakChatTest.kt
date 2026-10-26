package net.meshsat.android

import net.meshsat.android.hub.HubTopics
import net.meshsat.android.tak.CotBuilder
import net.meshsat.android.tak.CotType
import net.meshsat.android.tak.CotXml
import net.meshsat.android.tak.TakChats
import net.meshsat.android.ui.Peers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dispatcher's TAK chat to this phone and the phone's answer (MESHSAT-1707). The GeoChats are as
 * ATAK, WinTAK and iTAK write them; every "not for me" has a positive control beside it.
 */
class TakChatTest {

    private val me = "MESHSAT-msa-test"
    private val meCallsign = "MESHSAT-TEST"

    private fun chat(dest: String, toUid: String = me, toCallsign: String = meCallsign, text: String = "check in &amp; report") =
        """<event version="2.0" uid="GeoChat.ANDROID-abc.$toUid.m1" type="b-t-f" how="h-g-i-g-o" time="2026-10-27T10:00:00Z" start="2026-10-27T10:00:00Z" stale="2026-10-28T10:00:00Z">""" +
            """<point lat="0" lon="0" hae="9999999" ce="9999999" le="9999999"/><detail>""" +
            """<__chat parent="RootContactGroup" groupOwner="false" messageId="m1" chatroom="$toCallsign" id="$toUid" senderCallsign="DISPATCH-1">""" +
            """<chatgrp uid0="ANDROID-abc" uid1="$toUid" id="$toUid"/></__chat>""" +
            """<link uid="ANDROID-abc" type="a-f-G-U-C" relation="p-p"/>""" +
            """<remarks source="BAO.F.ATAK.ANDROID-abc" to="$toUid">$text</remarks>$dest</detail></event>"""

    @Test
    fun `a chat ATAK addresses to this phone by callsign is for it`() {
        val ev = CotXml.parse(chat("""<marti><dest callsign="$meCallsign"/></marti>"""))
        assertNotNull(ev)
        val c = TakChats.chatFor(ev!!, me, meCallsign)
        assertEquals(TakChats.Chat("ANDROID-abc", "DISPATCH-1", "check in & report"), c)
    }

    @Test
    fun `a chat iTAK addresses to this phone by uid is for it`() {
        val ev = CotXml.parse(chat("""<marti><dest uid="$me"/></marti>"""))!!
        assertEquals("DISPATCH-1", TakChats.chatFor(ev, me, meCallsign)?.fromCallsign)
    }

    @Test
    fun `a chat for somebody else, the room or this phone itself is not`() {
        val other = CotXml.parse(chat("""<marti><dest callsign="OTHER-1"/></marti>""", toUid = "ANDROID-xyz", toCallsign = "OTHER-1"))!!
        assertNull(TakChats.chatFor(other, me, meCallsign))
        val room = CotXml.parse(chat("", toUid = CotBuilder.CHAT_ROOM, toCallsign = CotBuilder.CHAT_ROOM))!!
        assertNull(TakChats.chatFor(room, me, meCallsign))
        val own = CotXml.parse(chat("""<marti><dest uid="$me"/></marti>""").replace("ANDROID-abc", me))!!
        assertNull(TakChats.chatFor(own, me, meCallsign))
        val empty = CotXml.parse(chat("""<marti><dest uid="$me"/></marti>""", text = "  "))!!
        assertNull(TakChats.chatFor(empty, me, meCallsign))
        // Positive control: the same chat, addressed to this phone.
        assertNotNull(TakChats.chatFor(CotXml.parse(chat("""<marti><dest uid="$me"/></marti>"""))!!, me, meCallsign))
    }

    @Test
    fun `a mission dest names nobody`() {
        val ev = CotXml.parse(chat("""<marti><dest mission="ops"/></marti>"""))!!
        assertTrue(ev.detail!!.marti.isEmpty())
        // Without a person dest, __chat names the recipient: this phone.
        assertNotNull(TakChats.chatFor(ev, me, meCallsign))
    }

    @Test
    fun `the answer is a GeoChat the dispatcher's client reads as a chat to it`() {
        val ev = CotBuilder.geoChat(me, meCallsign, "ANDROID-abc", "DISPATCH-1", "on my way <5 min>", "id-1")
        val back = CotXml.parse(CotXml.marshal(ev))!!
        assertEquals(CotType.CHAT, back.type)
        assertEquals("GeoChat.$me.ANDROID-abc.id-1", back.uid)
        val c = TakChats.chatFor(back, "ANDROID-abc", "DISPATCH-1")
        assertEquals(TakChats.Chat(me, meCallsign, "on my way <5 min>"), c)
        val d = back.detail!!
        assertEquals(listOf("ANDROID-abc" to "DISPATCH-1"), d.marti.map { it.uid to it.callsign })
        assertEquals("id-1", d.chat!!.messageId)
        assertEquals(me, d.chat!!.uid0)
        assertEquals("ANDROID-abc", d.remarks!!.to)
    }

    @Test
    fun `a position offers chat to a TAK client`() {
        val xml = CotXml.marshal(CotBuilder.position("MESHSAT-x", "MESHSAT-X", 52.0, 4.0))
        assertTrue(xml, xml.contains("""endpoint="*:-1:stcp""""))
        assertEquals(CotBuilder.TAK_ENDPOINT, CotXml.parse(xml)!!.detail!!.contact!!.endpoint)
    }

    @Test
    fun `a TAK conversation is keyed, named and answered on TAK`() {
        val peer = Peers.takPeer("ANDROID-abc", "DISPATCH-1")
        assertTrue(Peers.isTak(peer))
        assertEquals("ANDROID-abc", Peers.takUid(peer))
        assertEquals("DISPATCH-1", Peers.takCallsign(peer))
        assertEquals("DISPATCH-1", Peers.displayName(peer))
        assertEquals("tak", Peers.defaultTransport(peer))
        assertEquals(listOf("tak"), Peers.transportsFor(peer))
        // An operator on the Hub map.
        val op = Peers.takPeer("meshsat-hub-op-7", "Office")
        assertEquals("meshsat-hub-op-7", Peers.takUid(op))
        // Not TAK: the Hub route and a mesh node keep their own ways.
        assertEquals("hub", Peers.defaultTransport(Peers.hubPeer("kit-a")))
        assertEquals("mesh", Peers.defaultTransport("!0a0b0c0d"))
    }

    @Test
    fun `the chat topic is this phone's own subtree`() {
        assertEquals("meshsat/bridge/msa-test/tak/cot/in", HubTopics.PLATFORM.takCotInFor("msa-test"))
        assertEquals("meshsat/t-42/bridge/msa-test/tak/cot/in", HubTopics.of("meshsat/t-42")!!.takCotInFor("msa-test"))
        // Not one of the broadcast filters.
        assertTrue(HubTopics.PLATFORM.takCotInFilters().none { it == HubTopics.PLATFORM.takCotInFor("msa-test") })
    }
}
