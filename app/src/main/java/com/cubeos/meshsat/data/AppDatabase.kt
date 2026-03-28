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
        TleCacheEntity::class,
        ProviderCredential::class,
        RnsTcpPeer::class,
        IridiumCreditEntry::class,
    ],
    version = 11,
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
    abstract fun tleCacheDao(): TleCacheDao
    abstract fun providerCredentialDao(): ProviderCredentialDao
    abstract fun rnsTcpPeerDao(): RnsTcpPeerDao
    abstract fun iridiumCreditDao(): IridiumCreditDao

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

        /** Migration 7→8: Phase J satellite pass predictor TLE cache table. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS tle_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        satelliteName TEXT NOT NULL,
                        line1 TEXT NOT NULL,
                        line2 TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL
                    )
                """.trimIndent())
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

        /** Migration 8→9: Provider credentials table [MESHSAT-369]. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS provider_credentials (
                        id TEXT NOT NULL PRIMARY KEY,
                        provider TEXT NOT NULL,
                        name TEXT NOT NULL,
                        cred_type TEXT NOT NULL,
                        encrypted_data BLOB NOT NULL,
                        cert_not_after TEXT,
                        cert_subject TEXT NOT NULL DEFAULT '',
                        cert_fingerprint TEXT NOT NULL DEFAULT '',
                        version INTEGER NOT NULL DEFAULT 1,
                        source TEXT NOT NULL DEFAULT 'local',
                        received_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /** Migration 9→10: TCP peers + Iridium credit log [MESHSAT-392/399]. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS rns_tcp_peers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL DEFAULT 4242,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        label TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS iridium_credit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        messageType TEXT NOT NULL,
                        costCents INTEGER NOT NULL,
                        moMsn INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /** Migration 10→11: DTN custody + fragmentation columns [MESHSAT-408]. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN custody_status TEXT")
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN custodian_hash TEXT")
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN bundle_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_md_bundle_id ON message_deliveries(bundle_id)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meshsat.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
