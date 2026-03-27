package com.cubeos.meshsat.map

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.Closeable
import java.io.File

/**
 * Read-only accessor for MBTiles (SQLite) map tile files.
 *
 * MBTiles spec: https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md
 * Tiles table uses TMS y-coordinate (origin at bottom-left), while Leaflet
 * uses Slippy Map / XYZ (origin at top-left). This class handles the conversion.
 */
class MBTilesReader private constructor(
    private val db: SQLiteDatabase,
    val mimeType: String,
    private val metadata: Map<String, String>,
) : Closeable {

    companion object {
        private const val TAG = "MBTilesReader"

        fun open(file: File): MBTilesReader {
            require(file.exists()) { "MBTiles file not found: ${file.absolutePath}" }
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            validate(db)
            val meta = readMetadata(db)
            val format = meta["format"]?.lowercase() ?: "png"
            val mime = when (format) {
                "pbf" -> "application/x-protobuf"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "image/png"
            }
            Log.i(TAG, "Opened MBTiles: ${meta["name"] ?: file.name} (format=$format)")
            return MBTilesReader(db, mime, meta)
        }

        private fun validate(db: SQLiteDatabase) {
            val cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('tiles','metadata')",
                null,
            )
            val tables = mutableSetOf<String>()
            cursor.use { while (it.moveToNext()) tables.add(it.getString(0)) }
            require("tiles" in tables) { "Not a valid MBTiles file: missing 'tiles' table" }
            require("metadata" in tables) { "Not a valid MBTiles file: missing 'metadata' table" }
        }

        private fun readMetadata(db: SQLiteDatabase): Map<String, String> {
            val map = mutableMapOf<String, String>()
            val cursor = db.rawQuery("SELECT name, value FROM metadata", null)
            cursor.use {
                while (it.moveToNext()) {
                    map[it.getString(0)] = it.getString(1)
                }
            }
            return map
        }
    }

    /** Get tile bytes for the given XYZ (Slippy Map) coordinates, or null if not found. */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? {
        // Convert XYZ (Leaflet) to TMS (MBTiles): flip y-axis
        val tmsY = (1 shl z) - 1 - y
        val cursor = db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(z.toString(), x.toString(), tmsY.toString()),
        )
        return cursor.use {
            if (it.moveToFirst()) it.getBlob(0) else null
        }
    }

    /** Return all metadata key/value pairs from the MBTiles file. */
    fun getMetadata(): Map<String, String> = metadata

    val name: String get() = metadata["name"] ?: ""
    val description: String get() = metadata["description"] ?: ""
    val minZoom: Int? get() = metadata["minzoom"]?.toIntOrNull()
    val maxZoom: Int? get() = metadata["maxzoom"]?.toIntOrNull()
    val bounds: String? get() = metadata["bounds"]
    val format: String get() = metadata["format"] ?: "png"
    val isVector: Boolean get() = format == "pbf"

    override fun close() {
        try { db.close() } catch (_: Exception) {}
    }
}
