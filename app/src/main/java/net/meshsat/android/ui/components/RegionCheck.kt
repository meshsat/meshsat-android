package net.meshsat.android.ui.components

import android.content.Context
import android.telephony.TelephonyManager
import net.meshsat.android.ble.MeshtasticProtocol.LoRaRegion
import java.util.Locale

/**
 * Whether a LoRa region fits the country the phone is in (MESHSAT-1249). Only ever a warning: the
 * country comes from the SIM, else from the phone's language settings, and people travel.
 */
object RegionCheck {

    // EU and EEA members, and the European countries that use the same 868 MHz rules.
    private val europe = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT",
        "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
        "IS", "LI", "NO", "CH", "GB", "AD", "MC", "SM", "VA", "GI", "FO", "IM", "JE", "GG",
        "AL", "BA", "ME", "MK", "RS", "XK", "MD",
    )

    /** The phone's country as an upper-case ISO code ("NL"), or null when it cannot tell. */
    fun phoneCountry(context: Context): String? {
        val sim = try {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.simCountryIso
        } catch (e: Exception) {
            null
        }
        val iso = sim?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country
        return iso.uppercase(Locale.ROOT).takeIf { it.length == 2 }
    }

    /** The regions radios in [iso] normally use, or null when this app does not know. */
    fun expectedRegions(iso: String): List<LoRaRegion>? = when (iso) {
        in europe -> listOf(LoRaRegion.EU_868, LoRaRegion.EU_433)
        "US", "CA", "PR" -> listOf(LoRaRegion.US)
        "AU" -> listOf(LoRaRegion.ANZ)
        "NZ" -> listOf(LoRaRegion.ANZ, LoRaRegion.NZ_865)
        "CN" -> listOf(LoRaRegion.CN)
        "JP" -> listOf(LoRaRegion.JP)
        "KR" -> listOf(LoRaRegion.KR)
        "TW" -> listOf(LoRaRegion.TW)
        "RU" -> listOf(LoRaRegion.RU)
        "IN" -> listOf(LoRaRegion.IN)
        "TH" -> listOf(LoRaRegion.TH)
        "UA" -> listOf(LoRaRegion.UA_868, LoRaRegion.UA_433)
        "MY" -> listOf(LoRaRegion.MY_919, LoRaRegion.MY_433)
        "SG" -> listOf(LoRaRegion.SG_923)
        else -> null
    }

    private fun countryName(iso: String): String = try {
        Locale.Builder().setRegion(iso).build().getDisplayCountry(Locale.getDefault()).ifBlank { iso }
    } catch (e: Exception) {
        iso
    }

    /** A warning about [regionCode] for the phone's country [iso], or null when it fits or cannot be judged. */
    fun warning(regionCode: Int, iso: String?): String? {
        if (regionCode == LoRaRegion.LORA_24.code) return null // 2.4 GHz is allowed worldwide
        if (regionCode == LoRaRegion.Unset.code) {
            return "No region is set, so the radio does not transmit. Pick the region you are in."
        }
        val country = iso ?: return null
        val expected = expectedRegions(country) ?: return null
        if (expected.any { it.code == regionCode }) return null
        val names = expected.joinToString(" or ") { it.label }
        return "Your phone is set to ${countryName(country)}, where radios use $names. Check the region " +
            "matches where you are: the wrong one can be illegal there, and you will not hear nearby nodes."
    }
}
