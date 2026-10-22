package net.meshsat.android.hub.directory

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * The phone's signed directory (HeMB-NG C9.5, MESHSAT-1642) against the Hub's C7 fixture
 * (fixtures-v1.1), answers made by the Hub's own relay directory handler
 * (hubdirectory/relay_directory_answer.json) and the Bridge's apply rules.
 */
class HubDirectoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After fun stopServer() = server.shutdown()

    private fun resource(path: String) =
        File(requireNotNull(javaClass.classLoader?.getResource(path)).toURI()).readText()

    private val fixture by lazy { JSONObject(resource("hemb-ng/v1/directory_v2.json")) }
    private val hub by lazy { JSONObject(resource("hubdirectory/relay_directory_answer.json")) }
    private fun b64(s: String) = Base64.getDecoder().decode(s)
    private fun fixtureKey(name: String) = fixture.getJSONObject("keys").getJSONObject(name).getString("pkix_b64")
    private fun case(i: Int) = fixture.getJSONArray("cases").getJSONObject(i)

    private fun pinned(pkixB64: String) = DirectoryState(pinnedKey = pkixB64, pinSource = "bundle")

    // ---- signing helpers for the rules: a key of our own, as the Hub signs ----

    private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun seal(kp: KeyPair, payload: JSONObject): JSONObject {
        val bytes = payload.toString().toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(bytes)
            sign()
        }
        return JSONObject().put("v", 2).put("key_id", DirectoryEnvelope.keyId(kp.public.encoded)).put("alg", "ES256")
            .put("payload", Base64.getEncoder().encodeToString(bytes)).put("sig", Base64.getEncoder().encodeToString(sig))
    }

    private fun snap(tenant: String, version: Long, contacts: JSONArray = JSONArray(), groups: JSONArray = JSONArray()) =
        JSONObject().put("type", "snapshot").put("tenant_id", tenant).put("version", version)
            .put("contacts", contacts).put("groups", groups)

    private fun contact(id: String, name: String, vararg addresses: Pair<String, String>) = JSONObject()
        .put("id", id).put("display_name", name)
        .put("addresses", JSONArray(addresses.map { (k, v) -> JSONObject().put("kind", k).put("value", v) }))

    private fun rotationRecord(tenant: String, version: Long, newKey: ByteArray, id: String = DirectoryEnvelope.keyId(newKey)) =
        JSONObject().put("type", "rotation").put("tenant_id", tenant).put("version", version)
            .put("new_key_id", id).put("new_key_pkix", Base64.getEncoder().encodeToString(newKey))

    private fun ownKeyState(kp: KeyPair) = pinned(Base64.getEncoder().encodeToString(kp.public.encoded))

    // ---- the C7 fixture ----

    @Test fun everyFixtureEnvelopeVerifiesOrIsRefusedAsTheFixtureSays() {
        val cases = fixture.getJSONArray("cases")
        assertEquals(6, cases.length())
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val trusted = b64(fixtureKey(c.getString("trusted_key")))
            val ok = try {
                DirectoryEnvelope.open(c.getJSONObject("envelope"), trusted)
                true
            } catch (e: DirectoryEnvelope.Refused) {
                false
            }
            assertEquals(c.getString("case"), c.getBoolean("verify"), ok)
        }
    }

    @Test fun theFixtureChainMovesThePinAndTheNewKeysSnapshotApplies() {
        var s = pinned(fixtureKey("old"))
        var r = DirectoryApply.snapshot(s, case(0).getJSONObject("envelope"), 1)
        assertTrue(r.reason, r.applied)
        assertEquals(42L, r.state.version)
        // The fixture's rows are not the Hub's REST shape ("name" for "display_name", "members"
        // for "member_ids"): the contact is skipped as on the Bridge, the group stays, empty.
        assertEquals(listOf("contact:c-1:no_display_name"), r.state.skipped.map { "${it.kind}:${it.id}:${it.reason}" })
        assertEquals(listOf("North team" to "TEAM"), r.state.groups.map { it.name to it.kind })
        assertTrue(r.state.groups.single().memberIds.isEmpty())
        s = r.state

        r = DirectoryApply.snapshot(s, case(1).getJSONObject("envelope"), 2)
        assertEquals(DirectoryEnvelope.BAD_SIGNATURE, r.reason)
        assertEquals(42L, r.state.version)

        r = DirectoryApply.rotation(s, case(5).getJSONObject("envelope"), 3)
        assertEquals("a rotation signed only by the new key", DirectoryEnvelope.UNKNOWN_KEY, r.reason)

        r = DirectoryApply.snapshot(s, case(4).getJSONObject("envelope"), 3)
        assertEquals("the new key's snapshot before the rotation", DirectoryEnvelope.UNKNOWN_KEY, r.reason)

        r = DirectoryApply.rotation(s, case(2).getJSONObject("envelope"), 4)
        assertTrue(r.reason, r.applied)
        assertEquals(fixture.getJSONObject("keys").getJSONObject("new").getString("key_id"), r.state.keyId)
        assertEquals("rotation", r.state.pinSource)
        assertEquals(43L, r.state.keyVersion)
        assertEquals("a rotation never moves the snapshot version", 42L, r.state.version)
        s = r.state

        // Replayed under the new pin, the record is signed by a key the phone no longer trusts.
        assertEquals(DirectoryEnvelope.UNKNOWN_KEY, DirectoryApply.rotation(s, case(2).getJSONObject("envelope"), 5).reason)
        r = DirectoryApply.snapshot(s, case(3).getJSONObject("envelope"), 6)
        assertTrue(r.reason, r.applied)
        assertEquals(44L, r.state.version)
    }

    // ---- the Hub's own answers ----

    @Test fun theHubsAnswerAppliesWithItsContactsGroupsAndAddresses() {
        val s = pinned(hub.getString("old_key_pkix"))
        val r = DirectoryApply.answer(s, hub.getJSONObject("first"), 10)
        assertTrue(r.reason, r.applied)
        assertEquals("acme", r.state.tenantId)
        assertEquals(hub.getLong("first_version"), r.state.version)
        assertEquals(listOf("Kit A", "One", "Two"), r.state.contacts.map { it.displayName })
        val kitA = r.state.contacts.first { it.id == "c3" }
        assertEquals(listOf("MQTT" to "bridge:kit-a", "TETRA" to "tetra:1001"), kitA.addresses.map { it.kind to it.value })
        assertEquals("gateway", kitA.role)
        assertEquals(1, r.state.groups.size)
        val g = r.state.groups.single()
        assertEquals("North team" to "TEAM", g.name to g.kind)
        assertEquals(setOf("c1", "c3"), g.memberIds.toSet())
        assertTrue(r.state.skipped.isEmpty())
        assertEquals("", r.state.refusedReason)

        // The same answer again is not newer.
        val again = DirectoryApply.answer(r.state, hub.getJSONObject("first"), 11)
        assertFalse(again.applied)
        assertEquals(DirectoryApply.NOT_NEWER, again.reason)
        assertEquals(r.state.contacts, again.state.contacts)
    }

    @Test fun afterARotationTheHubsChainLeadsThePhoneToTheNewKey() {
        val held = DirectoryApply.answer(pinned(hub.getString("old_key_pkix")), hub.getJSONObject("first"), 10).state
        val r = DirectoryApply.answer(held, hub.getJSONObject("after_rotation"), 20)
        assertTrue(r.reason, r.applied)
        assertEquals(hub.getString("new_key_id"), r.state.keyId)
        assertEquals("rotation", r.state.pinSource)
        assertTrue(r.state.version > held.version)
        assertTrue(r.state.keyVersion > 0)
        // The old key's snapshot is no longer trusted.
        val old = DirectoryApply.answer(r.state, hub.getJSONObject("first"), 21)
        assertEquals(DirectoryEnvelope.UNKNOWN_KEY, old.reason)
    }

    @Test fun anotherTenantsDirectoryIsRefused() {
        // The other tenant's answer was read after the rotation: follow it first, so the refusal
        // is the tenant's and not the key's.
        val first = DirectoryApply.answer(pinned(hub.getString("old_key_pkix")), hub.getJSONObject("first"), 9).state
        val held = DirectoryApply.answer(first, hub.getJSONObject("after_rotation"), 10).state
        assertEquals(hub.getString("new_key_id"), held.keyId)
        val r = DirectoryApply.answer(held, hub.getJSONObject("other_tenant"), 11)
        assertFalse(r.applied)
        assertEquals(DirectoryApply.OTHER_TENANT, r.reason)
        assertEquals("acme", r.state.tenantId)
        assertEquals(held.contacts, r.state.contacts)
        assertEquals(11L, r.state.refusedAt)
    }

    // ---- the Bridge's rules ----

    @Test fun rowsTheBridgeSkipsAreSkippedHereToo() {
        val kp = p256()
        val contacts = JSONArray()
            .put(contact("a", "Alpha", "SMS" to "+31600000001", "PIGEON" to "loft 3", "MQTT" to ""))
            .put(contact("b", "Bravo", "SMS" to "+31600000001", "TETRA" to "2002"))
            .put(contact("n", ""))
        val groups = JSONArray()
            .put(JSONObject().put("id", "g1").put("name", "Ops").put("kind", "team").put("member_ids", JSONArray(listOf("a", "n", "zz"))))
            .put(JSONObject().put("id", "g2").put("name", "").put("member_ids", JSONArray(listOf("b"))))
            .put(JSONObject().put("id", "g3").put("name", "X").put("kind", "BROADCAST").put("member_ids", JSONArray()))
        val r = DirectoryApply.snapshot(ownKeyState(kp), seal(kp, snap("t", 5, contacts, groups)), 1)
        assertTrue(r.reason, r.applied)
        assertEquals(listOf("Alpha", "Bravo"), r.state.contacts.map { it.displayName })
        assertEquals(listOf("SMS"), r.state.contacts[0].addresses.map { it.kind })
        assertEquals("the second holder of an address goes without it", listOf("TETRA"), r.state.contacts[1].addresses.map { it.kind })
        assertEquals(
            setOf("address::unknown_kind", "address::address_taken", "contact:n:no_display_name", "member:g1/n:unknown_member",
                "member:g1/zz:unknown_member", "group:g3:unknown_kind"),
            r.state.skipped.map { "${it.kind}:${it.id}:${it.reason}" }.toSet(),
        )
        assertEquals(listOf("g2" to "LIST", "Ops" to "TEAM"), r.state.groups.map { it.name to it.kind })
    }

    @Test fun aSnapshotNeedsATenantANewerVersionAndTheSnapshotType() {
        val kp = p256()
        val s = ownKeyState(kp)
        assertEquals(DirectoryApply.OTHER_TENANT, DirectoryApply.snapshot(s, seal(kp, snap("", 1)), 1).reason)
        val held = DirectoryApply.snapshot(s, seal(kp, snap("t", 7)), 1).state
        assertEquals(DirectoryApply.NOT_NEWER, DirectoryApply.snapshot(held, seal(kp, snap("t", 7)), 2).reason)
        assertEquals(DirectoryApply.NOT_A_SNAPSHOT,
            DirectoryApply.snapshot(held, seal(kp, snap("t", 8).put("type", "rotation")), 3).reason)
        // A payload with no type is a snapshot, as on the Bridge.
        assertTrue(DirectoryApply.snapshot(held, seal(kp, snap("t", 8).apply { remove("type") }), 4).applied)
    }

    @Test fun aRotationMustNameAP256KeyThatHashesToItsId() {
        val kp = p256()
        val s = ownKeyState(kp)
        val next = p256().public.encoded
        assertEquals(DirectoryEnvelope.UNKNOWN_KEY,
            DirectoryApply.rotation(s, seal(kp, rotationRecord("t", 2, next, id = "00".repeat(32))), 1).reason)
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public.encoded
        assertEquals(DirectoryEnvelope.UNKNOWN_KEY, DirectoryApply.rotation(s, seal(kp, rotationRecord("t", 2, p384)), 1).reason)
        assertEquals(DirectoryApply.NOT_A_ROTATION,
            DirectoryApply.rotation(s, seal(kp, rotationRecord("t", 2, next).put("type", "snapshot")), 1).reason)
        val r = DirectoryApply.rotation(s, seal(kp, rotationRecord("t", 2, next)), 1)
        assertTrue(r.applied)
        assertEquals(DirectoryEnvelope.keyId(next), r.state.keyId)
    }

    @Test fun anAnswerStopsAtItsFirstRefusalAndKeepsWhatCameBefore() {
        val k1 = p256()
        val k2 = p256()
        val k3 = p256()
        val answer = JSONObject()
            .put("rotations", JSONArray()
                .put(seal(k1, rotationRecord("t", 2, k2.public.encoded)))
                // Signed by k1 again, but the pin is k2 by now.
                .put(seal(k1, rotationRecord("t", 3, k3.public.encoded))))
            .put("snapshot", seal(k3, snap("t", 4)))
        val r = DirectoryApply.answer(ownKeyState(k1), answer, 1)
        assertTrue("the first rotation stays applied", r.applied)
        assertEquals(DirectoryEnvelope.UNKNOWN_KEY, r.reason)
        assertEquals(DirectoryEnvelope.keyId(k2.public.encoded), r.state.keyId)
        assertEquals(0L, r.state.version)
    }

    // ---- the store ----

    @Test fun theStoreKeepsTheStateAcrossARestartAndSurvivesABrokenFile() {
        val f = File(tmp.root, "hub_directory.json")
        val held = DirectoryApply.answer(pinned(hub.getString("old_key_pkix")), hub.getJSONObject("first"), 10).state
        HubDirectoryStore(f).save(held)
        assertEquals(held, HubDirectoryStore(f).current)
        assertFalse(File(f.path + ".tmp").exists())
        f.writeText("{ not json")
        assertEquals(DirectoryState(), HubDirectoryStore(f).current)
    }

    // ---- the client against a server ----

    private fun client(store: HubDirectoryStore, requireHttps: Boolean = false) =
        HubDirectoryClient(store, now = { 1000L }, requireHttps = requireHttps, timeoutMs = 5_000)

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun keyAnswer(pkixB64: String, id: String = DirectoryEnvelope.keyId(b64(pkixB64))) =
        MockResponse().setBody(JSONObject().put("key_id", id).put("pkix", pkixB64).put("alg", "ES256").toString())

    @Test fun pollPinsTheKeyOnceThenReadsTheDirectoryWithTheBridgeCredentials() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        server.enqueue(keyAnswer(hub.getString("old_key_pkix")))
        server.enqueue(MockResponse().setBody(hub.getJSONObject("first").toString()))
        val o = client(store).poll(base(), "phone-1", "phone-1-pw")
        assertTrue(o.toString(), o is HubDirectoryClient.Outcome.Applied)
        assertEquals("/api/relay/directory-key", server.takeRequest().path)
        val req = server.takeRequest()
        assertEquals("/api/relay/directory?since=0&key_id=" + hub.getString("old_key_id"), req.path)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("phone-1:phone-1-pw".toByteArray()), req.getHeader("Authorization"))
        assertEquals("https", store.current.pinSource)
        assertEquals(3, store.current.contacts.size)

        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(HubDirectoryClient.Outcome.NotModified, client(store).poll(base(), "phone-1", "phone-1-pw"))
        assertEquals("/api/relay/directory?since=${hub.getLong("first_version")}&key_id=${hub.getString("old_key_id")}",
            server.takeRequest().path)
        assertEquals(1000L, store.current.checkedAt)
        assertEquals("no second key fetch", 3, server.requestCount)
    }

    @Test fun aPinnedKeyIsNeverReplacedByTheHttpsBootstrap() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        val bundleKey = p256().public.encoded
        client(store).applyProvisioning("ctx", "acme", bundleKey)
        assertTrue(client(store).bootstrap(base()))
        assertEquals("nothing fetched", 0, server.requestCount)
        assertEquals(DirectoryEnvelope.keyId(bundleKey), store.current.keyId)
        assertEquals("bundle", store.current.pinSource)
        // The directory read names the bundle's key.
        server.enqueue(MockResponse().setResponseCode(304))
        client(store).poll(base(), "phone-1", "pw")
        assertTrue(server.takeRequest().path!!.endsWith("key_id=" + DirectoryEnvelope.keyId(bundleKey)))
    }

    @Test fun theBootstrapFollowsNoRedirect() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        // A followed redirect would find a good key behind it.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere").toString()))
        server.enqueue(keyAnswer(hub.getString("old_key_pkix")))
        assertTrue(client(store).poll(base(), "p", "pw") is HubDirectoryClient.Outcome.Failed)
        assertEquals("", store.current.pinnedKey)
        assertEquals(1, server.requestCount)
    }

    @Test fun theBootstrapRefusesAKeyThatDoesNotHashToItsIdAndPlainHttp() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        server.enqueue(keyAnswer(hub.getString("old_key_pkix"), id = "00".repeat(32)))
        assertTrue(client(store).poll(base(), "p", "pw") is HubDirectoryClient.Outcome.Failed)
        assertEquals("", store.current.pinnedKey)
        assertEquals(1, server.requestCount)

        val o = client(store, requireHttps = true).poll(base(), "p", "pw")
        assertTrue(o is HubDirectoryClient.Outcome.Failed && o.message.contains("not an https URL"))
        assertEquals("no request over plain http", 1, server.requestCount)
    }

    @Test fun theProvisioningBundleCarriesTheDirectoryKeyAndTheTenant() {
        val b = net.meshsat.android.crypto.ProvisionImporter.parseBundle(
            JSONObject().put("bid", "phone-1").put("dir_sign_pub", hub.getString("old_key_pkix")).put("tenant_id", "acme").toString(),
        )
        assertEquals(hub.getString("old_key_id"), DirectoryEnvelope.keyId(b64(b.dirSignPub)))
        assertEquals("acme", b.tenantId)
        val old = net.meshsat.android.crypto.ProvisionImporter.parseBundle("""{"bid":"p"}""")
        assertEquals("" to "", old.dirSignPub to old.tenantId)
    }

    // ---- the provisioning context (r40 review R40-R3) ----

    private val ctxA = HubDirectoryClient.provisioningContext("wss://mqtt-hub.example/mqtt", "phone-1", "meshsat/acme", "acme")
    private val ctxB = HubDirectoryClient.provisioningContext("wss://mqtt-hub.example/mqtt", "phone-1", "meshsat/other", "other")

    /** The review's probe: reprovisioned to another tenant on the same Hub, same key: the directory starts over. */
    @Test fun aReprovisionToAnotherTenantUnderTheSameKeyStartsOver() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        val c = client(store)
        c.applyProvisioning(ctxA, "acme", b64(hub.getString("old_key_pkix")))
        store.update { DirectoryApply.answer(it, hub.getJSONObject("first"), 10).state }
        store.update { DirectoryApply.answer(it, hub.getJSONObject("after_rotation"), 11).state }
        assertEquals("acme" to 3, store.current.tenantId to store.current.contacts.size)
        // The Hub's current key, the same for every tenant of it, in the new tenant's bundle.
        c.applyProvisioning(ctxB, "other", b64(store.current.pinnedKey))
        assertEquals("", store.current.tenantId)
        assertTrue("the old tenant's contacts are gone", store.current.contacts.isEmpty())
        assertEquals(0L to 0L, store.current.version to store.current.keyVersion)
        val r = DirectoryApply.answer(store.current, hub.getJSONObject("other_tenant"), 12)
        assertTrue(r.reason, r.applied)
        assertEquals("other", r.state.tenantId)
        assertEquals(listOf("Nine"), r.state.contacts.map { it.displayName })
    }

    /** Tenant B's snapshot applies whether its version is below, equal to or above what A held. */
    @Test fun theNewTenantsVersionMayBeLowerEqualOrHigher() {
        val key = p256()
        val pkix = key.public.encoded
        for (bVersion in listOf(5L, 10L, 20L)) {
            val store = HubDirectoryStore(File(tmp.newFolder(), "d.json"))
            val c = client(store)
            c.applyProvisioning(ctxA, "acme", pkix)
            store.update { DirectoryApply.snapshot(it, seal(key, snap("acme", 10, JSONArray().put(contact("a1", "A one")))), 1).state }
            assertEquals(10L, store.current.version)
            c.applyProvisioning(ctxB, "other", pkix)
            val r = DirectoryApply.snapshot(store.current, seal(key, snap("other", bVersion, JSONArray().put(contact("b1", "B one")))), 2)
            assertTrue("B at $bVersion: ${r.reason}", r.applied)
            assertEquals(listOf("B one"), r.state.contacts.map { it.displayName })
        }
    }

    @Test fun reScanningTheSameContextKeepsTheDirectory() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        val c = client(store)
        c.applyProvisioning(ctxA, "acme", b64(hub.getString("old_key_pkix")))
        store.update { DirectoryApply.answer(it, hub.getJSONObject("first"), 10).state }
        val held = store.current
        c.applyProvisioning(ctxA, "acme", b64(hub.getString("old_key_pkix")))
        assertEquals(held, store.current)
        c.applyProvisioning(ctxA, "acme", null)
        assertEquals("a bundle without a key keeps it in the same context", held, store.current)
        // Another key in the same context starts over under it.
        val other = p256().public.encoded
        c.applyProvisioning(ctxA, "acme", other)
        assertEquals(DirectoryEnvelope.keyId(other), store.current.keyId)
        assertTrue(store.current.contacts.isEmpty())
        assertNull(runCatching { c.applyProvisioning(ctxA, "acme", ByteArray(10)) }.getOrNull())
    }

    @Test fun anotherHubWithAnotherKeyAndNoKeyInTheBundle() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        val c = client(store)
        c.applyProvisioning(ctxA, "acme", b64(hub.getString("old_key_pkix")))
        store.update { DirectoryApply.answer(it, hub.getJSONObject("first"), 10).state }
        val otherHub = HubDirectoryClient.provisioningContext("wss://mqtt-hub.elsewhere/mqtt", "phone-1", "meshsat", "default")
        c.applyProvisioning(otherHub, "default", null)
        assertEquals("", store.current.pinnedKey)
        assertTrue(store.current.contacts.isEmpty())
        // The bootstrap then fills the pin from the new Hub, once.
        server.enqueue(keyAnswer(hub.getString("old_key_pkix")))
        assertTrue(c.bootstrap(base()))
        assertEquals("https", store.current.pinSource)
    }

    /** A read that started before the reprovision cannot bring the old tenant back. */
    @Test fun aReplyFromBeforeTheSwitchIsDropped() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        val c = client(store)
        c.applyProvisioning(ctxA, "acme", b64(hub.getString("old_key_pkix")))
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                // The phone is reprovisioned while the read is out.
                c.applyProvisioning(ctxB, "other", b64(hub.getString("old_key_pkix")))
                return MockResponse().setBody(hub.getJSONObject("first").toString())
            }
        }
        val o = c.poll(base(), "phone-1", "pw")
        assertTrue(o.toString(), o is HubDirectoryClient.Outcome.Failed)
        assertEquals(ctxB, store.current.context)
        assertTrue(store.current.contacts.isEmpty())
        assertEquals("", store.current.tenantId)
    }

    /** A bootstrap that started before the reprovision does not pin the old Hub's key. */
    @Test fun aBootstrapFromBeforeTheSwitchPinsNothing() {
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        val c = client(store)
        c.applyProvisioning(ctxA, "acme", null)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                c.applyProvisioning(ctxB, "other", null)
                return keyAnswer(hub.getString("old_key_pkix"))
            }
        }
        val o = c.poll(base(), "phone-1", "pw")
        assertTrue(o.toString(), o is HubDirectoryClient.Outcome.Failed)
        assertEquals("", store.current.pinnedKey)
        assertEquals("no directory read without a pinned key", 1, server.requestCount)
    }

    @Test fun theContextSurvivesARestart() {
        val f = File(tmp.root, "d.json")
        client(HubDirectoryStore(f)).applyProvisioning(ctxB, "other", b64(hub.getString("old_key_pkix")))
        val again = HubDirectoryStore(f).current
        assertEquals(ctxB to "other", again.context to again.expectedTenant)
    }

    /** The bundle's tenant binds the directory from the first snapshot on. */
    @Test fun aSnapshotOfAnotherTenantIsRefusedFromTheFirstOne() {
        val key = p256()
        val store = HubDirectoryStore(File(tmp.root, "d.json"))
        client(store).applyProvisioning(ctxA, "acme", key.public.encoded)
        val r = DirectoryApply.snapshot(store.current, seal(key, snap("other", 1)), 1)
        assertEquals(DirectoryApply.OTHER_TENANT, r.reason)
        assertTrue(DirectoryApply.snapshot(store.current, seal(key, snap("acme", 1)), 1).applied)
    }
}
