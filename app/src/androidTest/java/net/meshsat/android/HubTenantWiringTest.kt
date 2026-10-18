package net.meshsat.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.meshsat.android.hub.HubReporter
import net.meshsat.android.hub.HubReporterConfig
import net.meshsat.android.mqtt.MqttTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundle's topic root reaches the Hub reporter and the MQTT transport (MESHSAT-1467), and a
 * root that is not meshsat or meshsat/<tenant> stops both before any connection.
 */
@RunWith(AndroidJUnit4::class)
class HubTenantWiringTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun reporter(scope: CoroutineScope, prefix: String) =
        HubReporter(context, scope, HubReporterConfig(hubUrl = "ssl://127.0.0.1:1", bridgeId = "pixel", topicPrefix = prefix))

    @Test
    fun theReporterUsesTheTenantsRootAndRefusesAnyOther() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            assertEquals("meshsat/acme", reporter(scope, "meshsat/acme").topicRoot)
            assertEquals("meshsat", reporter(scope, "").topicRoot)
            val bad = reporter(scope, "meshsat/+")
            bad.start()
            assertEquals(HubReporter.State.Error, bad.state.value)
            assertTrue(bad.lastError.value, bad.lastError.value.contains("meshsat/+"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theMqttTransportUsesTheTenantsRootAndRefusesAnyOther() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val t = MqttTransport(scope)
            t.connect("tcp://127.0.0.1:1", "dev-1", topicPrefix = "meshsat/acme")
            assertEquals("meshsat/acme", t.topicRoot)
            t.disconnect()
            val bad = MqttTransport(scope)
            bad.connect("tcp://127.0.0.1:1", "dev-1", topicPrefix = "meshsat/a.b")
            assertEquals(MqttTransport.State.Error, bad.state.value)
            assertEquals("meshsat", bad.topicRoot)
        } finally {
            scope.cancel()
        }
    }
}
