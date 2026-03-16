package com.cubeos.meshsat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Message::class,
        ForwardingRuleEntity::class,
        SignalRecord::class,
        NodePosition::class,
        ConversationKey::class,
        AccessRuleEntity::class,
        ObjectGroupEntity::class,
        FailoverGroupEntity::class,
        FailoverMemberEntity::class,
        MessageDeliveryEntity::class,
        AuditLogEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun forwardingRuleDao(): ForwardingRuleDao
    abstract fun signalDao(): SignalDao
    abstract fun nodePositionDao(): NodePositionDao
    abstract fun conversationKeyDao(): ConversationKeyDao
    abstract fun accessRuleDao(): AccessRuleDao
    abstract fun objectGroupDao(): ObjectGroupDao
    abstract fun failoverGroupDao(): FailoverGroupDao
    abstract fun messageDeliveryDao(): MessageDeliveryDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Migration 5→6: Phase C transport hardening columns on message_deliveries. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN held_at INTEGER")
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN seq_num INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN ack_status TEXT")
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN ack_timestamp INTEGER")
            }
        }

        /** Migration 6→7: Phase F audit log table for signing service hash chain. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp TEXT NOT NULL,
                        interface_id TEXT,
                        direction TEXT,
                        event_type TEXT NOT NULL,
                        delivery_id INTEGER,
                        rule_id INTEGER,
                        detail TEXT NOT NULL DEFAULT '',
                        prev_hash TEXT NOT NULL DEFAULT '',
                        hash TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_ts ON audit_log(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_iface ON audit_log(interface_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_event ON audit_log(event_type)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meshsat.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
