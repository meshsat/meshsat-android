package net.meshsat.android.ble

import java.security.MessageDigest

/**
 * A firmware image for a MeshSat node's update over Bluetooth (MESHSAT-1672), checked before
 * anything is sent: it must be the plain ESP32 app image (`firmware-<env>-<version>.bin`, the one
 * the OTA loader writes to app0), never a `.factory.bin` (bootloader, partition table and app from
 * offset 0), it must fit the app slot, and it must have been built for the node's own PlatformIO
 * env, which the node reports in MyNodeInfo.pio_env. A stock Meshtastic image would drop the
 * Iridium pipe, so an image without the node's env name is refused.
 */
class NodeFirmwareImage private constructor(
    val bytes: ByteArray,
    val sha256: ByteArray,
    /** The version string from the image's esp_app_desc, e.g. "2.8.0.fc4f3bb"; may be empty. */
    val version: String,
    /** The project name from the image's esp_app_desc. */
    val project: String,
) {
    val size: Int get() = bytes.size
    val sha256Hex: String get() = sha256.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    sealed class Check {
        data class Ok(val image: NodeFirmwareImage) : Check()
        data class Refused(val reason: String) : Check()
    }

    companion object {
        /** app0 and app1 of the 8 MB table the MeshSat T-Beam images use (default_8MB.csv): 0x330000 each. */
        const val APP_SLOT_BYTES = 0x330000

        /** The smallest app image worth sending; anything smaller is not firmware. */
        const val MIN_IMAGE_BYTES = 64 * 1024

        private const val ESP_IMAGE_MAGIC = 0xE9

        /** esp_app_desc_t follows the 24-byte image header and the first 8-byte segment header. */
        private const val APP_DESC_OFFSET = 32
        private const val APP_DESC_MAGIC = 0xABCD5432.toInt()
        private const val APP_DESC_VERSION_OFFSET = APP_DESC_OFFSET + 16
        private const val APP_DESC_PROJECT_OFFSET = APP_DESC_OFFSET + 48

        /** The checks, in order; [nodeEnv] is the node's pio_env (blank: the node did not say). */
        fun check(bytes: ByteArray, nodeEnv: String): Check {
            if (bytes.size < MIN_IMAGE_BYTES) return Check.Refused("The file is too small to be firmware (${bytes.size} bytes).")
            if (bytes.size > APP_SLOT_BYTES) {
                return Check.Refused(
                    "The file is larger than the node's firmware slot. A .factory.bin is for a cable flash; pick the plain firmware-….bin.",
                )
            }
            if ((bytes[0].toInt() and 0xFF) != ESP_IMAGE_MAGIC) return Check.Refused("The file is not an ESP32 firmware image.")
            if (le32(bytes, APP_DESC_OFFSET) != APP_DESC_MAGIC) {
                return Check.Refused("The file is not an app image. A .factory.bin is for a cable flash; pick the plain firmware-….bin.")
            }
            if (nodeEnv.isBlank()) {
                return Check.Refused("The node did not say which firmware build it runs; reconnect to the node and try again.")
            }
            if (!contains(bytes, nodeEnv.toByteArray(Charsets.US_ASCII))) {
                return Check.Refused("This firmware was not built for this node ($nodeEnv). Pick the file named firmware-$nodeEnv-….bin.")
            }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            return Check.Ok(
                NodeFirmwareImage(
                    bytes = bytes,
                    sha256 = sha,
                    version = cString(bytes, APP_DESC_VERSION_OFFSET, 32),
                    project = cString(bytes, APP_DESC_PROJECT_OFFSET, 32),
                ),
            )
        }

        private fun le32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

        private fun cString(b: ByteArray, at: Int, max: Int): String {
            if (at + max > b.size) return ""
            var end = at
            while (end < at + max && b[end].toInt() != 0) end++
            return String(b, at, end - at, Charsets.US_ASCII).trim()
        }

        internal fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
            if (needle.isEmpty() || needle.size > haystack.size) return false
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
    }
}
