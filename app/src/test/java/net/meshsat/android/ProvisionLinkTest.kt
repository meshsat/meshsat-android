package net.meshsat.android

import net.meshsat.android.crypto.ProvisionImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Deep-link provisioning accepts only the nonce form, and says what it will claim
 * before anything is fetched (MESHSAT-1235).
 */
class ProvisionLinkTest {

    private val nonce = "0123456789abcdef0123456789abcdef"

    @Test
    fun `nonce link yields bridge, nonce and claim host`() {
        val req = ProvisionImporter.parseLink("meshsat://provision/msa-flaneur/$nonce?hub=hub.meshsat.net")
        assertEquals("msa-flaneur", req.bridgeId)
        assertEquals(nonce, req.nonce)
        assertEquals("hub.meshsat.net", req.hubHost)
    }

    @Test
    fun `inline bundle link is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProvisionImporter.parseLink("meshsat://provision/eyJicmlkZ2VfaWQiOiJ4In0")
        }
    }

    @Test
    fun `link without a claim host is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProvisionImporter.parseLink("meshsat://provision/msa-flaneur/$nonce?hub=")
        }
    }

    @Test
    fun `malformed nonce is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProvisionImporter.parseLink("meshsat://provision/msa-flaneur/not-a-nonce?hub=hub.meshsat.net")
        }
    }

    @Test
    fun `other schemes are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProvisionImporter.parseLink("https://hub.meshsat.net/api/bridges/msa-flaneur/provision/$nonce")
        }
    }
}
