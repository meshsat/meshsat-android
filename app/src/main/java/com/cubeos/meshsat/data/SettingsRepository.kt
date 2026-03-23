package com.cubeos.meshsat.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cubeos.meshsat.crypto.SecureKeyStore
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
        val KEY_IRIDIUM_BT_ADDR = stringPreferencesKey("iridium_bt_address")
        val KEY_IRIDIUM9704_BT_ADDR = stringPreferencesKey("iridium9704_bt_address")
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

        // Hub Reporter settings (MESHSAT-292)
        val KEY_HUB_ENABLED = booleanPreferencesKey("hub_enabled")
        val KEY_HUB_URL = stringPreferencesKey("hub_url")
        val KEY_HUB_BRIDGE_ID = stringPreferencesKey("hub_bridge_id")
        val KEY_HUB_CALLSIGN = stringPreferencesKey("hub_callsign")
        val KEY_HUB_USERNAME = stringPreferencesKey("hub_username")
        val KEY_HUB_HEALTH_INTERVAL = stringPreferencesKey("hub_health_interval") // seconds as string

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

    suspend fun setIridiumBtAddress(address: String) {
        context.dataStore.edit { it[KEY_IRIDIUM_BT_ADDR] = address }
    }

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

    val compressMesh: Flow<String> = context.dataStore.data.map {
        it[KEY_COMPRESS_MESH] ?: "msvqsc"
    }
    val compressIridium: Flow<String> = context.dataStore.data.map {
        it[KEY_COMPRESS_IRIDIUM] ?: "off"   // Off by default — Hub can't decode MSVQ-SC yet
    }
    val compressSms: Flow<String> = context.dataStore.data.map {
        it[KEY_COMPRESS_SMS] ?: "msvqsc"
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
}
