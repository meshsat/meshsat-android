package net.meshsat.android.map

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.NodePosition

/**
 * The newest node positions since [sinceMs], oldest first, for the map's track lines (B17).
 *
 * NodePositionDao.getAllRecentByNode orders by node and then time and stops at its limit, so once
 * the table holds more rows than that it keeps returning the same oldest ones and a track never
 * grows. This reads the newest [maxPoints] rows of the window in one pass instead, and leaves the
 * phone's own rows (node 0) out, as the DAO did.
 */
suspend fun loadRecentTracks(db: AppDatabase, sinceMs: Long, maxPoints: Int): List<NodePosition> =
    withContext(Dispatchers.IO) {
        val query = SimpleSQLiteQuery(
            "SELECT id, timestamp, nodeId, nodeName, latitude, longitude, altitude FROM node_positions " +
                "WHERE nodeId != 0 AND timestamp >= ? ORDER BY timestamp DESC LIMIT ?",
            arrayOf<Any>(sinceMs, maxPoints),
        )
        val rows = ArrayList<NodePosition>()
        db.query(query).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    NodePosition(
                        id = c.getLong(0),
                        timestamp = c.getLong(1),
                        nodeId = c.getLong(2),
                        nodeName = c.getString(3) ?: "",
                        latitude = c.getDouble(4),
                        longitude = c.getDouble(5),
                        altitude = c.getInt(6),
                    ),
                )
            }
        }
        rows.reverse()
        rows
    }
