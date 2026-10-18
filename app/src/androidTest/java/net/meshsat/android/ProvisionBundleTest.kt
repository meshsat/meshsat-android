package net.meshsat.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.meshsat.android.crypto.ProvisionImporter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The provisioning bundle's tenant topic root (MESHSAT-1467), read with the device's org.json (the
 * JVM tests only have its stub).
 */
@RunWith(AndroidJUnit4::class)
class ProvisionBundleTest {
    @Test
    fun theBundlesTopicRootIsReadAndAbsentIsThePlatformTenant() {
        val tenant = ProvisionImporter.parseBundle("""{"v":"1","bid":"pixel","mqtt":"wss://x/mqtt","user":"pixel","pass":"p","mqtt_topic_prefix":"meshsat/acme"}""")
        assertEquals("meshsat/acme", tenant.topicPrefix)
        assertEquals("pixel", tenant.bridgeId)
        val platform = ProvisionImporter.parseBundle("""{"v":"1","bid":"pixel","mqtt":"wss://x/mqtt","user":"pixel","pass":"p"}""")
        assertEquals("", platform.topicPrefix)
    }
}
