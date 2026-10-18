package net.meshsat.android.service

import androidx.room.withTransaction
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.MessageDao
import net.meshsat.android.data.MessageDeliveryEntity

/**
 * A satellite chat message's label as the worker's hooks and the Hub's receipt write it
 * (MESHSAT-1243, MESHSAT-1246). The label only rises: an uncertain local result never takes back
 * "The Hub has it" (review 2026-10-19 00:36, FE-R5). An earlier session's receipt can land while a
 * later attempt of the same delivery is still out, and that attempt may then end uncertain.
 */
object SatelliteChat {

    /**
     * A satellite send reported a failure after the upload: "May have been sent", unless the Hub
     * already has the message. One guarded statement, so a receipt that commits in between stays.
     */
    suspend fun unconfirmed(messages: MessageDao, del: MessageDeliveryEntity) {
        if (del.channel != "iridium_0" || !del.msgRef.startsWith("msg:")) return
        val id = del.msgRef.removePrefix("msg:").toLongOrNull() ?: return
        messages.setForwardedToUnlessDelivered(id, GatewayService.IRIDIUM_UNCONFIRMED)
    }

    /**
     * The Hub has the message sent in satellite session [ref] ("imei:momsn"): its deliveries are
     * acknowledged and their chat messages get the second tick, in one transaction. Returns the
     * deliveries confirmed.
     */
    suspend fun hubReceipt(db: AppDatabase, ref: String): Int = db.withTransaction {
        val deliveries = db.messageDeliveryDao()
        val rows = deliveries.getBySatRef(ref)
        if (rows.isNotEmpty()) {
            deliveries.markAckedBySatRef(ref)
            for (del in rows) {
                val id = del.msgRef.removePrefix("msg:").toLongOrNull()
                if (del.msgRef.startsWith("msg:") && id != null) db.messageDao().setForwardedTo(id, GatewayService.IRIDIUM_DELIVERED)
            }
        }
        rows.size
    }
}
