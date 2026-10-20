package net.meshsat.android.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import net.meshsat.android.crypto.SecureKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshsat_settings")

class SettingsRepository(private val context: Context) {

    private val secureStore: SecureKeyStore by lazy { SecureKeyStore.getInstance(context) }

    companion object {
        val KEY_ENCRYPTION_KEY = stringPreferencesKey("encryption_key")
        val KEY_ENCRYPTION_ENABLED = booleanPreferencesKey("encryption_enabled")
        val KEY_AUTO_DECRYPT_SMS = booleanPreferencesKey("auto_decrypt_sms")
        val KEY_MESHSAT_PI_PHONE = stringPreferencesKey("meshsat_pi_phone") // phone number of Pi's modem
        val KEY_MESHTASTIC_BLE_ADDR = stringPreferencesKey("meshtastic_ble_address")
        // The phone takes the MeshSat node's RockBLOCK 9603 over its BLE pipe (MESHSAT-1236).
        val KEY_IRIDIUM_NODE_PIPE = booleanPreferencesKey("iridium_node_pipe_enabled")
        val KEY_IRIDIUM9704_BT_ADDR = stringPreferencesKey("iridium9704_bt_address")
        val KEY_NODE_MODEM = stringPreferencesKey("node_modem")
        const val NODE_MODEM_AUTO = "auto"
        const val NODE_MODEM_9603 = "9603"
        const val NODE_MODEM_9704 = "9704"
        val KEY_MSVQSC_ENABLED = booleanPreferencesKey("msvqsc_enabled")
        val KEY_MSVQSC_STAGES = stringPreferencesKey("msvqsc_stages") // "auto" or "2"-"8"
        val KEY_DEADMAN_ENABLED = booleanPreferencesKey("deadman_enabled")
        val KEY_DEADMAN_TIMEOUT_MIN = stringPreferencesKey("deadman_timeout_min") // minutes as string
        val KEY_MQTT_BROKER_URL = stringPreferencesKey("mqtt_broker_url")
        val KEY_MQTT_DEVICE_ID = stringPreferencesKey("mqtt_device_id")
        val KEY_MQTT_USERNAME = stringPreferencesKey("mqtt_username")
        val KEY_MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
        val KEY_MQTT_ENABLED = booleanPreferencesKey("mqtt_enabled")
        val KEY_MQTT_CERT_PIN = stringPreferencesKey("mqtt_cert_pin")
        val KEY_MQTT_CERT_PIN_BACKUP = stringPreferencesKey("mqtt_cert_pin_backup")

        // Per-channel compression mode: "off", "msvqsc", or "smaz2"
        val KEY_COMPRESS_MESH = stringPreferencesKey("compress_mesh")
        val KEY_COMPRESS_IRIDIUM = stringPreferencesKey("compress_iridium")
        val KEY_COMPRESS_SMS = stringPreferencesKey("compress_sms")
        val KEY_COMPRESS_MQTT = stringPreferencesKey("compress_mqtt")

        // APRS settings
        val KEY_APRS_ENABLED = booleanPreferencesKey("aprs_enabled")
        val KEY_APRS_CALLSIGN = stringPreferencesKey("aprs_callsign")
        val KEY_APRS_SSID = stringPreferencesKey("aprs_ssid")
        val KEY_APRS_KISS_HOST = stringPreferencesKey("aprs_kiss_host")
        val KEY_APRS_KISS_PORT = stringPreferencesKey("aprs_kiss_port")
        val KEY_APRS_FREQUENCY = stringPreferencesKey("aprs_frequency_mhz")

        // Reticulum TCP settings (MESHSAT-268)
        val KEY_RNS_TCP_ENABLED = booleanPreferencesKey("rns_tcp_enabled")
        val KEY_RNS_TCP_HOST = stringPreferencesKey("rns_tcp_host")
        val KEY_RNS_TCP_PORT = stringPreferencesKey("rns_tcp_port")
        val KEY_RNS_TCP_TLS = booleanPreferencesKey("rns_tcp_tls")

        // Offline map settings
        val KEY_OFFLINE_MAP_ENABLED = booleanPreferencesKey("offline_map_enabled")
        val KEY_OFFLINE_MAP_FILE = stringPreferencesKey("offline_map_file")

        // Protocol enhancements (MESHSAT-407)
        val KEY_DTN_CUSTODY_ENABLED = booleanPreferencesKey("dtn_custody_enabled")
        val KEY_FEC_LORA_PERCENT = stringPreferencesKey("fec_lora_percent")
        val KEY_FEC_SBD_PERCENT = stringPreferencesKey("fec_sbd_percent")
        val KEY_TIME_SYNC_ENABLED = booleanPreferencesKey("time_sync_enabled")
        val KEY_RLNC_ENABLED = booleanPreferencesKey("rlnc_enabled")

        // Reticulum Routing settings (MESHSAT-394)
        val KEY_RNS_ANNOUNCE_INTERVAL = stringPreferencesKey("rns_announce_interval_min")
        val KEY_RNS_TCP_LISTEN_PORT = stringPreferencesKey("rns_tcp_listen_port")
        val KEY_RNS_TRANSPORT_ENABLED = booleanPreferencesKey("rns_transport_enabled")
        // Reticulum and LXMF as the clients speak them (MESHSAT-1553)
        val KEY_LXMF_DISPLAY_NAME = stringPreferencesKey("lxmf_display_name")
        val KEY_RNS_TCP_LISTEN_ENABLED = booleanPreferencesKey("rns_tcp_listen_enabled")
        val KEY_RNS_PAID_DESTS = stringPreferencesKey("rns_paid_dests")
        val KEY_RNS_PAID_EXTRA_HOPS = stringPreferencesKey("rns_paid_extra_hops")
        val KEY_RNS_PAID_MIN_INTERVAL_MIN = stringPreferencesKey("rns_paid_min_interval_min")
        val KEY_LXMF_PROPAGATION_NODE = stringPreferencesKey("lxmf_propagation_node")
        val KEY_RNS_AUTO_ENABLED = booleanPreferencesKey("rns_auto_enabled")
        val KEY_RNODE_ENABLED = booleanPreferencesKey("rnode_enabled")
        val KEY_RNODE_PORT = stringPreferencesKey("rnode_port")
        val KEY_RNODE_PRESET = stringPreferencesKey("rnode_preset")
        val KEY_RNODE_CUSTOM_PARAMS = stringPreferencesKey("rnode_custom_params")
        val KEY_RNODE_AIRTIME_LIMITS = stringPreferencesKey("rnode_airtime_limits")
        val KEY_LXMF_SYNC_INTERVAL_MIN = stringPreferencesKey("lxmf_sync_interval_min")

        // Hub Reporter settings (MESHSAT-292)
        val KEY_HUB_ENABLED = booleanPreferencesKey("hub_enabled")
        val KEY_HUB_URL = stringPreferencesKey("hub_url")
        val KEY_HUB_BRIDGE_ID = stringPreferencesKey("hub_bridge_id")
        /** The tenant's topic root from the provisioning bundle; blank = the platform tenant (MESHSAT-1467). */
        val KEY_HUB_TOPIC_PREFIX = stringPreferencesKey("hub_topic_prefix")
        val KEY_HUB_CALLSIGN = stringPreferencesKey("hub_callsign")
        val KEY_HUB_USERNAME = stringPreferencesKey("hub_username")
        val KEY_HUB_HEALTH_INTERVAL = stringPreferencesKey("hub_health_interval") // seconds as string

        // Hub relay client (MESHSAT-1157)
        val KEY_HUB_RELAY_ENABLED = booleanPreferencesKey("hub_relay_enabled")

        // hub_msg, texts through the Hub to a MeshSat app (MESHSAT-1584); off until switched on
        val KEY_HUB_MSG_ENABLED = booleanPreferencesKey("hub_msg_enabled")
        val KEY_HUB_RELAY_TARGET = stringPreferencesKey("hub_relay_target")
        val KEY_HUB_RELAY_URL = stringPreferencesKey("hub_relay_url")

        // mTLS settings (MESHSAT-387)
        val KEY_HUB_CLIENT_CERT = stringPreferencesKey("hub_client_cert_pem")
        val KEY_HUB_CLIENT_KEY = stringPreferencesKey("hub_client_key_pem")
        val KEY_HUB_CA_CERT = stringPreferencesKey("hub_ca_cert_pem")

        // TAK settings (MESHSAT-451)
        val KEY_TAK_ENABLED = booleanPreferencesKey("tak_enabled")
        val KEY_TAK_CALLSIGN_PREFIX = stringPreferencesKey("tak_callsign_prefix")
        val KEY_TAK_ATAK_BROADCAST = booleanPreferencesKey("tak_atak_broadcast")
        val KEY_TAK_MQTT_EXPORT = booleanPreferencesKey("tak_mqtt_export")

        // APRS-IS settings (MESHSAT-230)
        val KEY_APRS_MODE = stringPreferencesKey("aprs_mode") // "kiss" or "is"
        val KEY_APRS_IS_SERVER = stringPreferencesKey("aprs_is_server")
        val KEY_APRS_IS_PORT = stringPreferencesKey("aprs_is_port")
        val KEY_APRS_IS_PASSCODE = stringPreferencesKey("aprs_is_passcode")
        val KEY_APRS_IS_FILTER_RANGE = stringPreferencesKey("aprs_is_filter_range_km")
        val KEY_APRS_IS_BEACON_ENABLED = booleanPreferencesKey("aprs_is_beacon_enabled")
        val KEY_APRS_IS_BEACON_INTERVAL = stringPreferencesKey("aprs_is_beacon_interval_min") // minutes
    }

    val encryptionKey: Flow<String> = context.dataStore.data.map {
        // Read from SecureKeyStore (hardware-backed); fall back to DataStore for migration
        val secureKey = secureStore.get("encryption_key")
        if (secureKey != null) {
            secureKey
        } else {
            val dsKey = it[KEY_ENCRYPTION_KEY] ?: ""
            if (dsKey.isNotEmpty()) {
                // Migrate from DataStore to SecureKeyStore
                secureStore.set("encryption_key", dsKey)
                Log.i("SettingsRepository", "Migrated encryption key to secure store")
            }
            dsKey
        }
    }

    val encryptionEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_ENCRYPTION_ENABLED] ?: false
    }

    val autoDecryptSms: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_AUTO_DECRYPT_SMS] ?: true
    }

    val meshsatPiPhone: Flow<String> = context.dataStore.data.map {
        it[KEY_MESHSAT_PI_PHONE] ?: ""
    }

    suspend fun setEncryptionKey(key: String) {
        // Store only in SecureKeyStore (hardware-backed). No plaintext copy in DataStore.
        secureStore.set("encryption_key", key)
    }

    /**
     * Complete migration: remove all secret values from plaintext DataStore after
     * confirming they are stored in SecureKeyStore. Called once during service startup.
     */
    suspend fun completeMigration() {
        val prefs = context.dataStore.data.first()
        context.dataStore.edit { mutable ->
            // Remove encryption key from DataStore if migrated to secure store
            if (prefs[KEY_ENCRYPTION_KEY] != null && secureStore.contains("encryption_key")) {
                mutable.remove(KEY_ENCRYPTION_KEY)
                Log.i("SettingsRepository", "Removed encryption_key from DataStore")
            }
            // Remove MQTT password from DataStore if migrated
            if (prefs[KEY_MQTT_PASSWORD] != null && secureStore.contains("mqtt_password")) {
                mutable.remove(KEY_MQTT_PASSWORD)
                Log.i("SettingsRepository", "Removed mqtt_password from DataStore")
            }
            // Remove cert pins from DataStore if migrated
            if (prefs[KEY_MQTT_CERT_PIN] != null && secureStore.contains("mqtt_cert_pin")) {
                mutable.remove(KEY_MQTT_CERT_PIN)
            }
            if (prefs[KEY_MQTT_CERT_PIN_BACKUP] != null && secureStore.contains("mqtt_cert_pin_backup")) {
                mutable.remove(KEY_MQTT_CERT_PIN_BACKUP)
            }
        }
    }

    suspend fun setEncryptionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ENCRYPTION_ENABLED] = enabled }
    }

    suspend fun setAutoDecryptSms(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_DECRYPT_SMS] = enabled }
    }

    suspend fun setMeshsatPiPhone(phone: String) {
        context.dataStore.edit { it[KEY_MESHSAT_PI_PHONE] = phone }
    }

    suspend fun setMeshtasticBleAddress(address: String) {
        context.dataStore.edit { it[KEY_MESHTASTIC_BLE_ADDR] = address }
    }

    /** The MeshSat node to reconnect to at start and after a drop; empty after Disconnect. */
    val meshtasticBleAddress: Flow<String> = context.dataStore.data.map { it[KEY_MESHTASTIC_BLE_ADDR] ?: "" }

    suspend fun clearMeshtasticBleAddress() {
        context.dataStore.edit { it.remove(KEY_MESHTASTIC_BLE_ADDR) }
    }

    /** Start the gateway after a phone restart or an app update; off unless the user chooses it (MESHSAT-1249). */
    val startOnBoot: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("start_on_boot")] ?: false }

    suspend fun setStartOnBoot(on: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("start_on_boot")] = on }
    }

    /** Take the node's 9603 while connected to a MeshSat node; off leaves it to the node. */
    val iridiumNodePipeEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_IRIDIUM_NODE_PIPE] ?: true }

    suspend fun setIridiumNodePipeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_IRIDIUM_NODE_PIPE] = enabled }
    }

    /**
     * Which RockBLOCK sits behind the MeshSat node's Iridium pipe: "auto" follows the node's own
     * word (STATUS flag bit 4 set = a 9704 speaking JSPR, clear = a 9603), "9603" and "9704"
     * override it (MESHSAT-1507).
     */
    val nodeModem: Flow<String> = context.dataStore.data.map { it[KEY_NODE_MODEM] ?: NODE_MODEM_AUTO }

    suspend fun setNodeModem(modem: String) {
        context.dataStore.edit { it[KEY_NODE_MODEM] = modem }
    }

    /** The HC-05 of a RockBLOCK 9704 this phone has connected to; empty when none ever was. */
    val iridium9704BtAddress: Flow<String> = context.dataStore.data.map { it[KEY_IRIDIUM9704_BT_ADDR] ?: "" }

    suspend fun setIridium9704BtAddress(address: String) {
        context.dataStore.edit { it[KEY_IRIDIUM9704_BT_ADDR] = address }
    }

    val msvqscEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_MSVQSC_ENABLED] ?: false
    }

    val msvqscStages: Flow<String> = context.dataStore.data.map {
        it[KEY_MSVQSC_STAGES] ?: "3"
    }

    suspend fun setMsvqscEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_MSVQSC_ENABLED] = enabled }
    }

    suspend fun setMsvqscStages(stages: String) {
        context.dataStore.edit { it[KEY_MSVQSC_STAGES] = stages }
    }

    // --- Per-channel compression (MESHSAT-203) ---
    // Values: "off", "msvqsc", "smaz2"

    /**
     * Kept only so an old export still imports. Nothing reads it for sending: a Meshtastic text
     * channel is shared with radios that are not MeshSat, so mesh text always goes out as typed
     * (OutgoingText.onMesh, MESHSAT-1286).
     */
    val compressMesh: Flow<String> = context.dataStore.data.map {
        it[KEY_COMPRESS_MESH] ?: "off"
    }
    val compressIridium: Flow<String> = context.dataStore.data.map {
        it[KEY_COMPRESS_IRIDIUM] ?: "off"   // Off by default — Hub can't decode MSVQ-SC yet
    }
    val compressSms: Flow<String> = context.dataStore.data.map {
        // Off unless the person turns it on for a MeshSat peer: an ordinary phone shows a coded
        // SMS as a line of base64, and MSVQ-SC is lossy (MESHSAT-1286).
        it[KEY_COMPRESS_SMS] ?: "off"
    }
    val compressMqtt: Flow<String> = context.dataStore.data.map {
        it[KEY_COMPRESS_MQTT] ?: "off"      // MQTT sends JSON, no compression needed
    }

    suspend fun setCompressMode(channel: String, mode: String) {
        val key = when (channel) {
            "mesh" -> KEY_COMPRESS_MESH
            "iridium" -> KEY_COMPRESS_IRIDIUM
            "sms" -> KEY_COMPRESS_SMS
            "mqtt" -> KEY_COMPRESS_MQTT
            else -> return
        }
        context.dataStore.edit { it[key] = mode }
    }

    val deadmanEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_DEADMAN_ENABLED] ?: false
    }

    val deadmanTimeoutMin: Flow<String> = context.dataStore.data.map {
        it[KEY_DEADMAN_TIMEOUT_MIN] ?: "120"
    }

    suspend fun setDeadmanEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DEADMAN_ENABLED] = enabled }
    }

    suspend fun setDeadmanTimeoutMin(minutes: String) {
        context.dataStore.edit { it[KEY_DEADMAN_TIMEOUT_MIN] = minutes }
    }

    // --- MQTT Hub settings ---

    val mqttEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_MQTT_ENABLED] ?: false
    }

    val mqttBrokerUrl: Flow<String> = context.dataStore.data.map {
        it[KEY_MQTT_BROKER_URL] ?: ""
    }

    val mqttDeviceId: Flow<String> = context.dataStore.data.map {
        it[KEY_MQTT_DEVICE_ID] ?: ""
    }

    val mqttUsername: Flow<String> = context.dataStore.data.map {
        it[KEY_MQTT_USERNAME] ?: ""
    }

    val mqttPassword: Flow<String> = context.dataStore.data.map {
        // Read from SecureKeyStore; fall back to DataStore for migration
        val secure = secureStore.get("mqtt_password")
        if (secure != null) {
            secure
        } else {
            val dsVal = it[KEY_MQTT_PASSWORD] ?: ""
            if (dsVal.isNotEmpty()) {
                secureStore.set("mqtt_password", dsVal)
            }
            dsVal
        }
    }

    suspend fun setMqttEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_MQTT_ENABLED] = enabled }
    }

    suspend fun setMqttBrokerUrl(url: String) {
        context.dataStore.edit { it[KEY_MQTT_BROKER_URL] = url }
    }

    suspend fun setMqttDeviceId(id: String) {
        context.dataStore.edit { it[KEY_MQTT_DEVICE_ID] = id }
    }

    suspend fun setMqttUsername(username: String) {
        context.dataStore.edit { it[KEY_MQTT_USERNAME] = username }
    }

    suspend fun setMqttPassword(password: String) {
        secureStore.set("mqtt_password", password)
    }

    val mqttCertPin: Flow<String> = context.dataStore.data.map {
        val secure = secureStore.get("mqtt_cert_pin")
        if (secure != null) {
            secure
        } else {
            val dsVal = it[KEY_MQTT_CERT_PIN] ?: ""
            if (dsVal.isNotEmpty()) secureStore.set("mqtt_cert_pin", dsVal)
            dsVal
        }
    }

    val mqttCertPinBackup: Flow<String> = context.dataStore.data.map {
        val secure = secureStore.get("mqtt_cert_pin_backup")
        if (secure != null) {
            secure
        } else {
            val dsVal = it[KEY_MQTT_CERT_PIN_BACKUP] ?: ""
            if (dsVal.isNotEmpty()) secureStore.set("mqtt_cert_pin_backup", dsVal)
            dsVal
        }
    }

    suspend fun setMqttCertPin(pin: String) {
        secureStore.set("mqtt_cert_pin", pin)
    }

    suspend fun setMqttCertPinBackup(pin: String) {
        secureStore.set("mqtt_cert_pin_backup", pin)
    }

    // --- APRS settings ---

    val aprsEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_APRS_ENABLED] ?: false
    }

    val aprsCallsign: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_CALLSIGN] ?: ""
    }

    val aprsSsid: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_SSID] ?: "10"
    }

    val aprsKissHost: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_KISS_HOST] ?: "localhost"
    }

    val aprsKissPort: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_KISS_PORT] ?: "8001"
    }

    val aprsFrequency: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_FREQUENCY] ?: "144.800"
    }

    suspend fun setAprsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_APRS_ENABLED] = enabled }
    }

    suspend fun setAprsCallsign(callsign: String) {
        context.dataStore.edit { it[KEY_APRS_CALLSIGN] = callsign }
    }

    suspend fun setAprsSsid(ssid: String) {
        context.dataStore.edit { it[KEY_APRS_SSID] = ssid }
    }

    suspend fun setAprsKissHost(host: String) {
        context.dataStore.edit { it[KEY_APRS_KISS_HOST] = host }
    }

    suspend fun setAprsKissPort(port: String) {
        context.dataStore.edit { it[KEY_APRS_KISS_PORT] = port }
    }

    suspend fun setAprsFrequency(freq: String) {
        context.dataStore.edit { it[KEY_APRS_FREQUENCY] = freq }
    }

    // --- APRS-IS settings (MESHSAT-230) ---

    val aprsMode: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_MODE] ?: "kiss" // "kiss" or "is"
    }

    val aprsIsServer: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_IS_SERVER] ?: "rotate.aprs2.net"
    }

    val aprsIsPort: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_IS_PORT] ?: "14580"
    }

    val aprsIsPasscode: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_IS_PASSCODE] ?: "-1"
    }

    val aprsIsFilterRange: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_IS_FILTER_RANGE] ?: "100"
    }

    suspend fun setAprsMode(mode: String) {
        context.dataStore.edit { it[KEY_APRS_MODE] = mode }
    }

    suspend fun setAprsIsServer(server: String) {
        context.dataStore.edit { it[KEY_APRS_IS_SERVER] = server }
    }

    suspend fun setAprsIsPort(port: String) {
        context.dataStore.edit { it[KEY_APRS_IS_PORT] = port }
    }

    suspend fun setAprsIsPasscode(passcode: String) {
        context.dataStore.edit { it[KEY_APRS_IS_PASSCODE] = passcode }
    }

    suspend fun setAprsIsFilterRange(range: String) {
        context.dataStore.edit { it[KEY_APRS_IS_FILTER_RANGE] = range }
    }

    val aprsIsBeaconEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_APRS_IS_BEACON_ENABLED] ?: false
    }

    val aprsIsBeaconInterval: Flow<String> = context.dataStore.data.map {
        it[KEY_APRS_IS_BEACON_INTERVAL] ?: "10"
    }

    suspend fun setAprsIsBeaconEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_APRS_IS_BEACON_ENABLED] = enabled }
    }

    suspend fun setAprsIsBeaconInterval(interval: String) {
        context.dataStore.edit { it[KEY_APRS_IS_BEACON_INTERVAL] = interval }
    }

    // --- TAK settings (MESHSAT-451) ---

    val takEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TAK_ENABLED] ?: false
    }

    val takCallsignPrefix: Flow<String> = context.dataStore.data.map {
        it[KEY_TAK_CALLSIGN_PREFIX] ?: "MESHSAT"
    }

    val takAtakBroadcast: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TAK_ATAK_BROADCAST] ?: true
    }

    val takMqttExport: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_TAK_MQTT_EXPORT] ?: true
    }

    suspend fun setTakEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TAK_ENABLED] = enabled }
    }

    suspend fun setTakCallsignPrefix(prefix: String) {
        context.dataStore.edit { it[KEY_TAK_CALLSIGN_PREFIX] = prefix }
    }

    suspend fun setTakAtakBroadcast(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TAK_ATAK_BROADCAST] = enabled }
    }

    suspend fun setTakMqttExport(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TAK_MQTT_EXPORT] = enabled }
    }

    // --- Reticulum TCP settings (MESHSAT-268) ---

    val rnsTcpEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_RNS_TCP_ENABLED] ?: false
    }

    val rnsTcpHost: Flow<String> = context.dataStore.data.map {
        it[KEY_RNS_TCP_HOST] ?: ""
    }

    val rnsTcpPort: Flow<String> = context.dataStore.data.map {
        it[KEY_RNS_TCP_PORT] ?: "4242"
    }

    suspend fun setRnsTcpEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_RNS_TCP_ENABLED] = enabled }
    }

    suspend fun setRnsTcpHost(host: String) {
        context.dataStore.edit { it[KEY_RNS_TCP_HOST] = host }
    }

    suspend fun setRnsTcpPort(port: String) {
        context.dataStore.edit { it[KEY_RNS_TCP_PORT] = port }
    }

    val rnsTcpTls: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_RNS_TCP_TLS] ?: false
    }

    suspend fun setRnsTcpTls(enabled: Boolean) {
        context.dataStore.edit { it[KEY_RNS_TCP_TLS] = enabled }
    }

    // --- Reticulum Routing settings (MESHSAT-394) ---

    val rnsAnnounceInterval: Flow<String> = context.dataStore.data.map {
        it[KEY_RNS_ANNOUNCE_INTERVAL] ?: "10"
    }

    val rnsTcpListenPort: Flow<String> = context.dataStore.data.map {
        it[KEY_RNS_TCP_LISTEN_PORT] ?: "4242"
    }

    val rnsTransportEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_RNS_TRANSPORT_ENABLED] ?: true
    }

    suspend fun setRnsAnnounceInterval(interval: String) {
        context.dataStore.edit { it[KEY_RNS_ANNOUNCE_INTERVAL] = interval }
    }

    suspend fun setRnsTcpListenPort(port: String) {
        context.dataStore.edit { it[KEY_RNS_TCP_LISTEN_PORT] = port }
    }

    /** The name other LXMF clients show for this phone; blank = the Hub bridge id or "MeshSat phone". */
    val lxmfDisplayName: Flow<String> = context.dataStore.data.map { it[KEY_LXMF_DISPLAY_NAME] ?: "" }
    suspend fun setLxmfDisplayName(name: String) { context.dataStore.edit { it[KEY_LXMF_DISPLAY_NAME] = name } }

    /** The phone listens for Reticulum TCP peers (Sideband on the same WiFi) on rns_tcp_listen_port (MESHSAT-1553 P4). */
    val rnsTcpListenEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_RNS_TCP_LISTEN_ENABLED] ?: false }
    suspend fun setRnsTcpListenEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_RNS_TCP_LISTEN_ENABLED] = enabled } }

    /** Comma-separated 32-hex destinations that may be announced and path-requested across the satellite (MESHSAT-1553 P6). */
    val rnsPaidDests: Flow<String> = context.dataStore.data.map { it[KEY_RNS_PAID_DESTS] ?: "" }
    suspend fun setRnsPaidDests(dests: String) { context.dataStore.edit { it[KEY_RNS_PAID_DESTS] = dests } }

    /** Hops added to every packet arriving over the satellite: 6 x (hops + 3) >= 2 x the one-way delay; 20 covers 60 s. */
    val rnsPaidExtraHops: Flow<String> = context.dataStore.data.map { it[KEY_RNS_PAID_EXTRA_HOPS] ?: "20" }
    suspend fun setRnsPaidExtraHops(hops: String) { context.dataStore.edit { it[KEY_RNS_PAID_EXTRA_HOPS] = hops } }

    /** Minutes between two announces of one destination across the satellite. */
    val rnsPaidMinIntervalMin: Flow<String> = context.dataStore.data.map { it[KEY_RNS_PAID_MIN_INTERVAL_MIN] ?: "5" }
    suspend fun setRnsPaidMinIntervalMin(minutes: String) { context.dataStore.edit { it[KEY_RNS_PAID_MIN_INTERVAL_MIN] = minutes } }

    /** The RNS AutoInterface on this WiFi: Sideband and rnsd with theirs on find the phone with nothing typed (MESHSAT-1553 P4). */
    val rnsAutoEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_RNS_AUTO_ENABLED] ?: false }
    suspend fun setRnsAutoEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_RNS_AUTO_ENABLED] = enabled } }

    // --- An RNode (LoRa radio) on USB OTG or Bluetooth LE as a Reticulum interface (MESHSAT-1553 P4) ---
    val rnodeEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_RNODE_ENABLED] ?: false }
    suspend fun setRnodeEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_RNODE_ENABLED] = enabled } }
    /** "usb" or "ble:<address>". */
    val rnodePort: Flow<String> = context.dataStore.data.map { it[KEY_RNODE_PORT] ?: "usb" }
    suspend fun setRnodePort(port: String) { context.dataStore.edit { it[KEY_RNODE_PORT] = port } }
    /** A preset id (CrossTalk's four) or "custom". */
    val rnodePreset: Flow<String> = context.dataStore.data.map { it[KEY_RNODE_PRESET] ?: "eu-868" }
    suspend fun setRnodePreset(id: String) { context.dataStore.edit { it[KEY_RNODE_PRESET] = id } }
    /** "frequency,bandwidth,txpower,sf,cr" for the custom preset. */
    val rnodeCustomParams: Flow<String> = context.dataStore.data.map { it[KEY_RNODE_CUSTOM_PARAMS] ?: "867200000,125000,14,8,5" }
    suspend fun setRnodeCustomParams(v: String) { context.dataStore.edit { it[KEY_RNODE_CUSTOM_PARAMS] = v } }
    /** "short,long" airtime limits in percent, 0 = not set. */
    val rnodeAirtimeLimits: Flow<String> = context.dataStore.data.map { it[KEY_RNODE_AIRTIME_LIMITS] ?: "0,0" }
    suspend fun setRnodeAirtimeLimits(v: String) { context.dataStore.edit { it[KEY_RNODE_AIRTIME_LIMITS] = v } }

    /** The radio parameters the settings resolve to, or null when the custom ones do not parse. */
    suspend fun rnodeParams(): net.meshsat.android.rnode.RnodeProtocol.Params? {
        val preset = rnodePreset.first()
        val base = if (preset == "custom") parseRnodeParams(rnodeCustomParams.first()) else net.meshsat.android.rnode.RnodeProtocol.preset(preset)?.params
        val air = rnodeAirtimeLimits.first().split(",").map { it.trim().toDoubleOrNull() ?: 0.0 }
        return base?.copy(staLock = air.getOrElse(0) { 0.0 }, ltaLock = air.getOrElse(1) { 0.0 })
    }

    /** The LXMF propagation node: "" = the nearest one heard, "none" = never, or a 32-hex lxmf.propagation address (MESHSAT-1553 P3b). */
    val lxmfPropagationNode: Flow<String> = context.dataStore.data.map { it[KEY_LXMF_PROPAGATION_NODE] ?: "" }
    suspend fun setLxmfPropagationNode(choice: String) { context.dataStore.edit { it[KEY_LXMF_PROPAGATION_NODE] = choice } }

    /** Minutes between automatic syncs from the propagation node; 0 = only by hand. */
    val lxmfSyncIntervalMin: Flow<String> = context.dataStore.data.map { it[KEY_LXMF_SYNC_INTERVAL_MIN] ?: "0" }
    suspend fun setLxmfSyncIntervalMin(minutes: String) { context.dataStore.edit { it[KEY_LXMF_SYNC_INTERVAL_MIN] = minutes } }

    suspend fun setRnsTransportEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_RNS_TRANSPORT_ENABLED] = enabled }
    }

    // --- Hub Reporter settings (MESHSAT-292) ---

    val hubEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_HUB_ENABLED] ?: false
    }

    val hubUrl: Flow<String> = context.dataStore.data.map {
        it[KEY_HUB_URL] ?: ""
    }

    val hubBridgeId: Flow<String> = context.dataStore.data.map {
        it[KEY_HUB_BRIDGE_ID] ?: ""
    }

    val hubCallsign: Flow<String> = context.dataStore.data.map {
        it[KEY_HUB_CALLSIGN] ?: ""
    }

    val hubTopicPrefix: Flow<String> = context.dataStore.data.map {
        it[KEY_HUB_TOPIC_PREFIX] ?: ""
    }

    val hubUsername: Flow<String> = context.dataStore.data.map {
        it[KEY_HUB_USERNAME] ?: ""
    }

    val hubPassword: Flow<String> = context.dataStore.data.map {
        val secure = secureStore.get("hub_password")
        if (secure != null) {
            secure
        } else {
            "" // No migration needed — Hub reporter is new
        }
    }

    val hubHealthInterval: Flow<String> = context.dataStore.data.map {
        it[KEY_HUB_HEALTH_INTERVAL] ?: "30"
    }

    suspend fun setHubEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HUB_ENABLED] = enabled }
    }

    suspend fun setHubUrl(url: String) {
        context.dataStore.edit { it[KEY_HUB_URL] = url }
    }

    suspend fun setHubBridgeId(id: String) {
        context.dataStore.edit { it[KEY_HUB_BRIDGE_ID] = id }
    }

    suspend fun setHubTopicPrefix(prefix: String) {
        context.dataStore.edit { it[KEY_HUB_TOPIC_PREFIX] = prefix }
    }

    suspend fun setHubCallsign(callsign: String) {
        context.dataStore.edit { it[KEY_HUB_CALLSIGN] = callsign }
    }

    suspend fun setHubUsername(username: String) {
        context.dataStore.edit { it[KEY_HUB_USERNAME] = username }
    }

    suspend fun setHubPassword(password: String) {
        secureStore.set("hub_password", password)
    }

    suspend fun setHubHealthInterval(interval: String) {
        context.dataStore.edit { it[KEY_HUB_HEALTH_INTERVAL] = interval }
    }

    // --- Hub relay client (MESHSAT-1157) ---
    // The relay is the fallback below LAN and RNS TCP for reaching a kit. It rides on the
    // Hub Reporter's identity (bridge id, MQTT password, Hub-issued certificate), so it is
    // on by default whenever the Hub is configured and only needs a target bridge id.

    val hubRelayEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_HUB_RELAY_ENABLED] ?: true }

    /** The bridge id the phone tunnels to. Blank = relay not started. */
    val hubRelayTarget: Flow<String> = context.dataStore.data.map { it[KEY_HUB_RELAY_TARGET] ?: "" }

    /** Hub API base URL override (https://hub.example). Blank = derived from the MQTT URL. */
    val hubRelayUrl: Flow<String> = context.dataStore.data.map { it[KEY_HUB_RELAY_URL] ?: "" }

    suspend fun setHubRelayEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_HUB_RELAY_ENABLED] = enabled } }

    /**
     * Texts through the Hub to a contact's MeshSat app (hub_msg). Off by default, as on the kits
     * (MESHSAT_HUB_MSG_ROUTE): with it off nothing subscribes and no Hub conversation is offered.
     * Read at the service's start.
     */
    val hubMsgEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_HUB_MSG_ENABLED] ?: false }
    suspend fun setHubMsgEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_HUB_MSG_ENABLED] = enabled } }
    suspend fun setHubRelayTarget(bridgeId: String) { context.dataStore.edit { it[KEY_HUB_RELAY_TARGET] = bridgeId.trim() } }
    suspend fun setHubRelayUrl(url: String) { context.dataStore.edit { it[KEY_HUB_RELAY_URL] = url.trim() } }

    // --- mTLS settings (MESHSAT-387) ---

    val hubClientCertPem: Flow<String> = context.dataStore.data.map { it[KEY_HUB_CLIENT_CERT] ?: "" }
    val hubClientKeyPem: Flow<String> = context.dataStore.data.map { secureStore.get("hub_client_key_pem") ?: "" }
    val hubCaCertPem: Flow<String> = context.dataStore.data.map { it[KEY_HUB_CA_CERT] ?: "" }

    suspend fun setHubClientCertPem(pem: String) { context.dataStore.edit { it[KEY_HUB_CLIENT_CERT] = pem } }
    suspend fun setHubClientKeyPem(pem: String) { secureStore.set("hub_client_key_pem", pem) }
    suspend fun setHubCaCertPem(pem: String) { context.dataStore.edit { it[KEY_HUB_CA_CERT] = pem } }

    // --- Protocol enhancements (MESHSAT-407) ---

    val dtnCustodyEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_DTN_CUSTODY_ENABLED] ?: false }
    val fecLoraPercent: Flow<String> = context.dataStore.data.map { it[KEY_FEC_LORA_PERCENT] ?: "30" }
    val fecSbdPercent: Flow<String> = context.dataStore.data.map { it[KEY_FEC_SBD_PERCENT] ?: "20" }
    val timeSyncEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_TIME_SYNC_ENABLED] ?: true }
    val rlncEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_RLNC_ENABLED] ?: false }

    suspend fun setDtnCustodyEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_DTN_CUSTODY_ENABLED] = enabled } }
    suspend fun setFecLoraPercent(pct: String) { context.dataStore.edit { it[KEY_FEC_LORA_PERCENT] = pct } }
    suspend fun setFecSbdPercent(pct: String) { context.dataStore.edit { it[KEY_FEC_SBD_PERCENT] = pct } }
    suspend fun setTimeSyncEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_TIME_SYNC_ENABLED] = enabled } }
    suspend fun setRlncEnabled(enabled: Boolean) { context.dataStore.edit { it[KEY_RLNC_ENABLED] = enabled } }

    // --- Offline map settings ---

    val offlineMapEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_OFFLINE_MAP_ENABLED] ?: true
    }

    val offlineMapFile: Flow<String> = context.dataStore.data.map {
        it[KEY_OFFLINE_MAP_FILE] ?: ""
    }

    suspend fun setOfflineMapEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_OFFLINE_MAP_ENABLED] = enabled }
    }

    suspend fun setOfflineMapFile(file: String) {
        context.dataStore.edit { it[KEY_OFFLINE_MAP_FILE] = file }
    }

    // --- Dashboard widget order (MESHSAT-401) ---

    val dashboardOrder: Flow<String> = context.dataStore.data.map {
        it[stringPreferencesKey("dashboard_card_order")] ?: ""
    }

    suspend fun setDashboardOrder(order: String) {
        context.dataStore.edit { it[stringPreferencesKey("dashboard_card_order")] = order }
    }

    /** Night mode: the app in red only, for night vision (MESHSAT-1249). */
    val nightMode: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("night_mode")] ?: false }

    suspend fun setNightMode(on: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("night_mode")] = on }
    }

    /** Home's "Getting started" list was hidden by the user (MESHSAT-1249). */
    val checklistDismissed: Flow<Boolean> = context.dataStore.data.map { it[booleanPreferencesKey("checklist_dismissed")] ?: false }

    suspend fun setChecklistDismissed(dismissed: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("checklist_dismissed")] = dismissed }
    }

    // --- Release telemetry (MESHSAT-494) ---

    /**
     * Master switch for local release telemetry (crash capture, heap samples,
     * health heartbeats, explicit events). When disabled, `TelemetryLogger`
     * writes nothing. Retrievable over adb via the local API's `/api/telemetry` endpoints.
     * Defaults to ON — privacy-preserving because nothing leaves the device.
     */
    val telemetryEnabled: Flow<Boolean> = context.dataStore.data.map {
        it[booleanPreferencesKey("telemetry_enabled")] ?: true
    }

    suspend fun setTelemetryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey("telemetry_enabled")] = enabled }
    }

    // --- SOS (MESHSAT-1249) ---

    /** The people an SOS goes to by SMS, from the phone's own SIM. */
    val sosContacts: Flow<List<EmergencyContact>> = context.dataStore.data.map {
        EmergencyContact.decode(it[stringPreferencesKey("sos_contacts")] ?: "")
    }

    suspend fun setSosContacts(contacts: List<EmergencyContact>) {
        context.dataStore.edit { it[stringPreferencesKey("sos_contacts")] = EmergencyContact.encode(contacts) }
    }

    /** The name an SOS gives for the person who needs help. */
    val sosName: Flow<String> = context.dataStore.data.map {
        it[stringPreferencesKey("sos_name")] ?: ""
    }

    suspend fun setSosName(name: String) {
        context.dataStore.edit { it[stringPreferencesKey("sos_name")] = name }
    }

    /** The IMEI of the last satellite modem this phone talked to: an SOS uses the satellite when there is one. */
    val lastModemImei: Flow<String> = context.dataStore.data.map {
        it[stringPreferencesKey("last_modem_imei")] ?: ""
    }

    suspend fun setLastModemImei(imei: String) {
        context.dataStore.edit { it[stringPreferencesKey("last_modem_imei")] = imei }
    }

    /** The SOS in progress or last sent, as JSON ([net.meshsat.android.sos.SosRun]), so it survives a restart. */
    val sosRun: Flow<String> = context.dataStore.data.map {
        it[stringPreferencesKey("sos_run")] ?: ""
    }

    suspend fun setSosRun(json: String) {
        context.dataStore.edit { it[stringPreferencesKey("sos_run")] = json }
    }

    /**
     * The last hub/caps the Hub sent, as it came (C10, MESHSAT-1584): kept, because an SOS may have
     * to go by satellite with no Hub session at all.
     */
    val hubCaps: Flow<String> = context.dataStore.data.map {
        it[stringPreferencesKey("hub_caps")] ?: ""
    }

    suspend fun setHubCaps(payload: String) {
        context.dataStore.edit { it[stringPreferencesKey("hub_caps")] = payload }
    }

    /**
     * Earlier SOS runs whose cancellation is still owed (C10, r16 review R4), as a JSON array of
     * [net.meshsat.android.sos.SosRun]: a new SOS does not end the last one's obligation.
     */
    val sosPrevRuns: Flow<String> = context.dataStore.data.map {
        it[stringPreferencesKey("sos_prev_runs")] ?: ""
    }

    suspend fun setSosPrevRuns(json: String) {
        context.dataStore.edit { it[stringPreferencesKey("sos_prev_runs")] = json }
    }
}

/** "frequency,bandwidth,txpower,sf,cr" to radio parameters, or null. */
fun parseRnodeParams(v: String): net.meshsat.android.rnode.RnodeProtocol.Params? {
    val f = v.split(",").map { it.trim() }
    if (f.size < 5) return null
    val freq = f[0].toLongOrNull() ?: return null
    val bw = f[1].toLongOrNull() ?: return null
    val txp = f[2].toIntOrNull() ?: return null
    val sf = f[3].toIntOrNull() ?: return null
    val cr = f[4].toIntOrNull() ?: return null
    return net.meshsat.android.rnode.RnodeProtocol.Params(freq, bw, txp, sf, cr)
}
