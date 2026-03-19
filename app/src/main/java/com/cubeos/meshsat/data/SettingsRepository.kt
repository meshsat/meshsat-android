package com.cubeos.meshsat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshsat_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_ENCRYPTION_KEY = stringPreferencesKey("encryption_key")
        val KEY_ENCRYPTION_ENABLED = booleanPreferencesKey("encryption_enabled")
        val KEY_AUTO_DECRYPT_SMS = booleanPreferencesKey("auto_decrypt_sms")
        val KEY_MESHSAT_PI_PHONE = stringPreferencesKey("meshsat_pi_phone") // phone number of Pi's modem
        val KEY_MESHTASTIC_BLE_ADDR = stringPreferencesKey("meshtastic_ble_address")
        val KEY_IRIDIUM_BT_ADDR = stringPreferencesKey("iridium_bt_address")
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

        // APRS settings
        val KEY_APRS_ENABLED = booleanPreferencesKey("aprs_enabled")
        val KEY_APRS_CALLSIGN = stringPreferencesKey("aprs_callsign")
        val KEY_APRS_SSID = stringPreferencesKey("aprs_ssid")
        val KEY_APRS_KISS_HOST = stringPreferencesKey("aprs_kiss_host")
        val KEY_APRS_KISS_PORT = stringPreferencesKey("aprs_kiss_port")
        val KEY_APRS_FREQUENCY = stringPreferencesKey("aprs_frequency_mhz")
    }

    val encryptionKey: Flow<String> = context.dataStore.data.map {
        it[KEY_ENCRYPTION_KEY] ?: ""
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
        context.dataStore.edit { it[KEY_ENCRYPTION_KEY] = key }
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
        it[KEY_MQTT_PASSWORD] ?: ""
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
        context.dataStore.edit { it[KEY_MQTT_PASSWORD] = password }
    }

    val mqttCertPin: Flow<String> = context.dataStore.data.map {
        it[KEY_MQTT_CERT_PIN] ?: ""
    }

    val mqttCertPinBackup: Flow<String> = context.dataStore.data.map {
        it[KEY_MQTT_CERT_PIN_BACKUP] ?: ""
    }

    suspend fun setMqttCertPin(pin: String) {
        context.dataStore.edit { it[KEY_MQTT_CERT_PIN] = pin }
    }

    suspend fun setMqttCertPinBackup(pin: String) {
        context.dataStore.edit { it[KEY_MQTT_CERT_PIN_BACKUP] = pin }
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
}
