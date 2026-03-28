package com.cubeos.meshsat.map

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Manages MBTiles file import, listing, deletion, and reader lifecycle.
 * Files are stored in internal app storage at filesDir/mbtiles/.
 */
object MBTilesManager {
    private const val TAG = "MBTilesManager"
    private const val DIR_NAME = "mbtiles"

    private val readers = mutableMapOf<String, MBTilesReader>()

    data class MBTilesInfo(
        val filename: String,
        val sizeBytes: Long,
        val name: String,
        val format: String,
        val isVector: Boolean,
        val minZoom: Int?,
        val maxZoom: Int?,
        val bounds: String?,
    )

    const val BUNDLED_WORLD_MAP = "world.mbtiles"

    private fun mbtilesDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Synchronous version — safe to call from remember{} blocks.
     * Only does work on first call (copies 244KB file from assets).
     */
    fun ensureBundledMapSync(context: Context) = ensureBundledMap(context)

    /**
     * Ensure the bundled world map (from assets) is extracted to internal storage.
     * Only copies on first launch or if the file is missing.
     */
    fun ensureBundledMap(context: Context) {
        val dest = File(mbtilesDir(context), BUNDLED_WORLD_MAP)
        if (dest.exists()) return
        try {
            context.assets.open(BUNDLED_WORLD_MAP).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
            Log.i(TAG, "Extracted bundled world map (${dest.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "No bundled world map in assets: ${e.message}")
        }
    }

    /**
     * Import an MBTiles file from a content URI into internal storage.
     * Returns the filename of the imported file.
     */
    fun import(context: Context, uri: Uri): String {
        val displayName = resolveFilename(context, uri)
        val filename = sanitizeFilename(displayName)
        val destFile = File(mbtilesDir(context), filename)

        context.contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        } ?: throw IllegalStateException("Cannot open file from URI")

        // Validate the imported file is actually MBTiles
        try {
            val reader = MBTilesReader.open(destFile)
            reader.close()
        } catch (e: Exception) {
            destFile.delete()
            throw IllegalArgumentException("Invalid MBTiles file: ${e.message}")
        }

        Log.i(TAG, "Imported MBTiles: $filename (${destFile.length()} bytes)")
        return filename
    }

    /** List all imported MBTiles files with metadata. */
    fun listFiles(context: Context): List<MBTilesInfo> {
        val dir = mbtilesDir(context)
        return dir.listFiles()?.filter { it.extension == "mbtiles" }?.mapNotNull { file ->
            try {
                val reader = MBTilesReader.open(file)
                val info = MBTilesInfo(
                    filename = file.name,
                    sizeBytes = file.length(),
                    name = reader.name.ifBlank { file.nameWithoutExtension },
                    format = reader.format,
                    isVector = reader.isVector,
                    minZoom = reader.minZoom,
                    maxZoom = reader.maxZoom,
                    bounds = reader.bounds,
                )
                reader.close()
                info
            } catch (e: Exception) {
                Log.w(TAG, "Skipping invalid MBTiles: ${file.name}: ${e.message}")
                null
            }
        }?.sortedBy { it.name } ?: emptyList()
    }

    /** Delete an imported MBTiles file. */
    fun delete(context: Context, filename: String) {
        readers.remove(filename)?.close()
        val file = File(mbtilesDir(context), filename)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Deleted MBTiles: $filename")
        }
    }

    /** Get or open a cached MBTilesReader for the given file. */
    fun getReader(context: Context, filename: String): MBTilesReader? {
        readers[filename]?.let { return it }
        val file = File(mbtilesDir(context), filename)
        if (!file.exists()) return null
        return try {
            MBTilesReader.open(file).also { readers[filename] = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open MBTiles: $filename: ${e.message}")
            null
        }
    }

    /** Get the File path for an MBTiles file (for osmdroid tile provider). */
    fun getMBTilesFile(context: Context, filename: String): File? {
        val file = File(mbtilesDir(context), filename)
        return if (file.exists()) file else null
    }

    /** Close all open readers. */
    fun closeAll() {
        readers.values.forEach { it.close() }
        readers.clear()
    }

    private fun resolveFilename(context: Context, uri: Uri): String {
        var name = "map.mbtiles"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name
    }

    private fun sanitizeFilename(name: String): String {
        val base = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (base.endsWith(".mbtiles")) base else "$base.mbtiles"
    }
}
