package net.meshsat.android.hub.directory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fixtures-v1.2's directory_v2_rest.json (r40 review, decision 1): envelopes the Hub's own store,
 * signer and rotation made, in the REST shapes. The phone, bound to the fixture's tenant by its
 * provisioning, ends each case holding the contacts (names and addresses), groups (memberships),
 * versions and key the fixture names, refuses what it refuses with its state unchanged, and skips
 * the rows it skips by kind, id and reason. The same file runs through the Bridge and iOS.
 */
class HubDirectoryRestFixtureTest {
    private val fx by lazy {
        JSONObject(File(requireNotNull(javaClass.classLoader?.getResource("hemb-ng/v1/directory_v2_rest.json")).toURI()).readText())
    }
    private fun key(name: String) = fx.getJSONObject("keys").getJSONObject(name).getString("pkix_b64")
    private fun cases(list: String) = fx.getJSONArray(list).let { a -> List(a.length()) { a.getJSONObject(it) } }

    private fun bound(keyName: String) =
        DirectoryState(pinnedKey = key(keyName), pinSource = "bundle", context = "fixture", expectedTenant = fx.getString("tenant"))

    private fun apply(state: DirectoryState, c: JSONObject, now: Long): DirectoryApply.Result = when (c.getString("kind")) {
        "snapshot" -> DirectoryApply.snapshot(state, c.getJSONObject("envelope"), now)
        "rotation" -> DirectoryApply.rotation(state, c.getJSONObject("envelope"), now)
        else -> error("kind ${c.getString("kind")}")
    }

    private fun contacts(s: DirectoryState) =
        s.contacts.map { c -> "${c.id} \"${c.displayName}\" ${c.addresses.map { "${it.kind} ${it.value}" }.sorted()}" }.sorted()

    private fun groups(s: DirectoryState) = s.groups.map { g -> "${g.id} \"${g.name}\" ${g.kind} ${g.memberIds.sorted()}" }.sorted()

    private fun contacts(e: JSONObject) = e.optJSONArray("contacts")?.let { a ->
        List(a.length()) { i ->
            val c = a.getJSONObject(i)
            val addrs = c.getJSONArray("addresses").let { x -> List(x.length()) { "${x.getJSONObject(it).getString("kind")} ${x.getJSONObject(it).getString("value")}" } }
            "${c.getString("id")} \"${c.getString("display_name")}\" ${addrs.sorted()}"
        }.sorted()
    } ?: emptyList()

    private fun groups(e: JSONObject) = e.optJSONArray("groups")?.let { a ->
        List(a.length()) { i ->
            val g = a.getJSONObject(i)
            val ids = g.getJSONArray("member_ids").let { x -> List(x.length()) { x.getString(it) } }
            "${g.getString("id")} \"${g.getString("name")}\" ${g.getString("kind")} ${ids.sorted()}"
        }.sorted()
    } ?: emptyList()

    private fun skips(e: JSONObject) = e.optJSONArray("skipped")?.let { a ->
        List(a.length()) { a.getJSONObject(it).let { s -> "${s.getString("kind")} ${s.getString("id")} ${s.getString("reason")}" } }.sorted()
    } ?: emptyList()

    private fun holds(name: String, s: DirectoryState, e: JSONObject) {
        assertEquals("$name: version", e.getLong("version"), s.version)
        assertEquals("$name: key version", e.getLong("key_version"), s.keyVersion)
        assertEquals("$name: pinned key", key(e.getString("pinned")), s.pinnedKey)
        assertEquals("$name: contacts", contacts(e), contacts(s))
        assertEquals("$name: groups", groups(e), groups(s))
    }

    @Test
    fun `the sequence ends each step with the Hub's names, addresses and memberships`() {
        var s = bound("old")
        for ((i, c) in cases("sequence").withIndex()) {
            val r = apply(s, c, 10L + i)
            assertTrue("${c.getString("name")}: ${r.reason} ${r.detail}", r.applied)
            s = r.state
            holds(c.getString("name"), s, c.getJSONObject("expect"))
        }
        assertEquals(fx.getString("tenant"), s.tenantId)
    }

    @Test
    fun `what the phone refuses leaves it as it was`() {
        var s = bound("old")
        for ((i, c) in cases("sequence").withIndex()) s = apply(s, c, 10L + i).state
        val last = cases("sequence").last().getJSONObject("expect")
        for (c in cases("refused")) {
            val name = c.getString("name")
            val r = apply(s, c, 99)
            assertFalse("$name was applied", r.applied)
            if (c.getBoolean("verify")) assertEquals(name, c.getJSONObject("expect").getString("reason"), r.reason)
            holds(name, r.state, last)
        }
    }

    @Test
    fun `rows a receiver skips are skipped by kind, id and reason, and the rest is held`() {
        for (c in cases("rows")) {
            val name = c.getString("name")
            val e = c.getJSONObject("expect")
            val r = apply(bound(c.getString("trusted_key")), c, 1)
            assertTrue("$name: ${r.reason} ${r.detail}", r.applied)
            assertEquals(name, skips(e), r.state.skipped.map { "${it.kind} ${it.id} ${it.reason}" }.sorted())
            holds(name, r.state, e)
        }
    }

    @Test
    fun `the fixture has every case`() {
        assertEquals(listOf(3, 5, 5), listOf("sequence", "refused", "rows").map { cases(it).size })
    }
}
