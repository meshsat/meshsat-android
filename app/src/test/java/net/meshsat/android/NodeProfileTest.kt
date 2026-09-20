package net.meshsat.android

import com.geeksville.mesh.ChannelProtos
import com.geeksville.mesh.ConfigProtos.Config
import com.google.protobuf.ByteString
import net.meshsat.android.ble.NodeProfile
import net.meshsat.android.ble.NodeProfiles
import net.meshsat.android.ble.NodeSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node profile is written over what the node already has (MESHSAT-1285).
 *
 * set_config replaces a whole section, so a profile that names two fields of the radio section
 * and is sent as a fresh section would silently reset the region, the power and everything else
 * in it. These hold the line: what the profile leaves out survives, and nothing is guessed.
 */
class NodeProfileTest {

    private val key = ByteArray(32) { it.toByte() }

    private val node = NodeSections(
        device = Config.DeviceConfig.newBuilder()
            .setRole(Config.DeviceConfig.Role.CLIENT)
            .setNodeInfoBroadcastSecs(900)
            .setTzdef("CET-1CEST")
            .build(),
        lora = Config.LoRaConfig.newBuilder()
            .setRegion(Config.LoRaConfig.RegionCode.EU_868)
            .setUsePreset(true)
            .setTxPower(27)
            .setTxEnabled(true)
            .setHopLimit(3)
            .build(),
        position = Config.PositionConfig.newBuilder().setPositionBroadcastSecs(3600).build(),
        power = Config.PowerConfig.newBuilder().setLsSecs(300).build(),
        bluetooth = Config.BluetoothConfig.newBuilder().setEnabled(true).build(),
        network = Config.NetworkConfig.newBuilder().setWifiSsid("garden").build(),
        primaryChannel = ChannelProtos.Channel.newBuilder()
            .setIndex(0)
            .setRole(ChannelProtos.Channel.Role.PRIMARY)
            .setSettings(ChannelProtos.ChannelSettings.newBuilder().setPsk(ByteString.copyFrom(byteArrayOf(1))))
            .build(),
    )

    private fun ready(p: NodeProfile) = NodeProfiles.plan(node, p) as NodeProfiles.Plan.Ready

    @Test
    fun `one edit, opened first and committed last`() {
        val plan = ready(NodeProfile(role = "CLIENT_MUTE", ignoreMqtt = true))
        assertTrue(plan.messages.first().beginEditSettings)
        assertTrue(plan.messages.last().commitEditSettings)
        assertEquals(listOf("device", "radio"), plan.sections)
        assertEquals(4, plan.messages.size)
    }

    @Test
    fun `what the profile leaves out survives`() {
        val plan = ready(NodeProfile(role = "CLIENT_MUTE", rxBoostedGain = true, ignoreMqtt = true))
        val device = plan.messages[1].setConfig.device
        assertEquals(Config.DeviceConfig.Role.CLIENT_MUTE, device.role)
        assertEquals(900, device.nodeInfoBroadcastSecs)
        assertEquals("CET-1CEST", device.tzdef)
        val lora = plan.messages[2].setConfig.lora
        assertTrue(lora.sx126XRxBoostedGain)
        assertTrue(lora.ignoreMqtt)
        assertEquals(Config.LoRaConfig.RegionCode.EU_868, lora.region)
        assertEquals(27, lora.txPower)
        assertTrue(lora.txEnabled)
    }

    @Test
    fun `a section nobody mentioned is not sent`() {
        val plan = ready(NodeProfile(ntpServer = "meshtastic.pool.ntp.org"))
        assertEquals(listOf("network"), plan.sections)
        assertEquals("garden", plan.messages[1].setConfig.network.wifiSsid)
    }

    @Test
    fun `the channel keeps its slot and takes the new name, key and switches`() {
        val plan = ready(
            NodeProfile(
                channelName = "msat-test",
                channelPsk = key,
                channelUplink = true,
                channelDownlink = true,
                channelPositionPrecision = 0,
            ),
        )
        val ch = plan.messages[1].setChannel
        assertEquals(0, ch.index)
        assertEquals(ChannelProtos.Channel.Role.PRIMARY, ch.role)
        assertEquals("msat-test", ch.settings.name)
        assertEquals(32, ch.settings.psk.size())
        assertTrue(ch.settings.uplinkEnabled && ch.settings.downlinkEnabled)
        assertEquals(0, ch.settings.moduleSettings.positionPrecision)
    }

    @Test
    fun `never is the firmware's never`() {
        val plan = ready(NodeProfile(powerSaving = false, sdsSecs = NodeProfiles.SDS_NEVER))
        val power = plan.messages[1].setConfig.power
        assertEquals(-1, power.sdsSecs) // uint32 max, as the JVM holds it
        assertFalse(power.isPowerSaving)
        assertEquals(300, power.lsSecs)
    }

    @Test
    fun `nothing is guessed`() {
        fun refused(p: NodeProfile) = NodeProfiles.plan(node, p) is NodeProfiles.Plan.Refused
        assertTrue(refused(NodeProfile(role = "CLIENT_MUTED")))
        assertTrue(refused(NodeProfile(region = "EU868")))
        assertTrue(refused(NodeProfile(preset = "LongFast")))
        assertTrue(refused(NodeProfile(gpsMode = "OFF")))
        assertTrue(refused(NodeProfile(hopLimit = 9)))
        assertTrue(refused(NodeProfile(channelPsk = ByteArray(20))))
        assertTrue(refused(NodeProfile(channelName = "a-name-too-long")))
        assertTrue(refused(NodeProfile(longName = "Only half a name")))
        assertTrue(refused(NodeProfile()))
    }

    @Test
    fun `a section the node has not reported cannot be written`() {
        val blank = node.copy(lora = null)
        val plan = NodeProfiles.plan(blank, NodeProfile(ignoreMqtt = true))
        assertTrue(plan is NodeProfiles.Plan.Refused)
    }

    @Test
    fun `a key is shown as a fingerprint, never as itself`() {
        val shown = NodeProfiles.keyFingerprint(key)
        assertTrue(shown.endsWith("(256-bit)"))
        assertFalse(shown.contains(java.util.Base64.getEncoder().encodeToString(key)))
        assertFalse(shown.replace(" ", "").contains(key.joinToString("") { "%02x".format(it) }))
        assertEquals("default (not private)", NodeProfiles.keyFingerprint(byteArrayOf(1)))
        assertEquals("none", NodeProfiles.keyFingerprint(ByteArray(0)))
    }
}
