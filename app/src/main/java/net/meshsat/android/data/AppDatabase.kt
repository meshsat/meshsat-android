package net.meshsat.android.data

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
        HembBondGroupEntity::class,
        BridgeTrustEntity::class,
        TelemetryEntity::class,
        ContactEntity::class,
        SmsPartReport::class,
        SmsSend::class,
        HubOpEntity::class,
        HubOpEventEntity::class,
        HubOpMissingEntity::class,
        HubInboxEntity::class,
        LxmfMidEntity::class,
        MessageAttachmentEntity::class,
    ],
    version = 23,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun contactDao(): ContactDao
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
    abstract fun hembBondGroupDao(): HembBondGroupDao
    abstract fun bridgeTrustDao(): BridgeTrustDao
    abstract fun telemetryDao(): TelemetryDao
    abstract fun smsPartReportDao(): SmsPartReportDao
    abstract fun hubMsgDao(): HubMsgDao
    abstract fun lxmfMidDao(): LxmfMidDao
    abstract fun messageAttachmentDao(): MessageAttachmentDao

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

        /** Migration 11→12: HeMB bond group table [MESHSAT-431]. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS hemb_bond_groups (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL DEFAULT '',
                        members TEXT NOT NULL DEFAULT '[]',
                        costBudget REAL NOT NULL DEFAULT 0.0,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        /** Migration 12→13: Bridge TOFU trust table [MESHSAT-495]. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bridge_trust (
                        bridgeHash TEXT NOT NULL PRIMARY KEY,
                        pubkey BLOB NOT NULL,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        importCount INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /** Migration 13→14: Release telemetry ring buffer [MESHSAT-494]. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS telemetry (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        message TEXT NOT NULL,
                        detail TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telemetry_timestamp ON telemetry(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_telemetry_type ON telemetry(type)")
            }
        }

        /** Migration 14→15: a delivery can name its recipient, for SOS SMS to each emergency contact [MESHSAT-1249]. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN recipient TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Migration 15→16: the satellite session a delivery went out in, for the Hub's receipt [MESHSAT-1246]. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN sat_ref TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Migration 17→18: who a queued message came from, for the Hub [MESHSAT-1274]. */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message_deliveries ADD COLUMN origin TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Migration 18→19: each part's SMS delivery report, so a long SMS is delivered only when every part is [MESHSAT-1584]. */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room's own CREATE statement for SmsPartReport (AppDatabase_Impl), as for contacts below.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sms_part_reports` (" +
                        "`attempt` TEXT NOT NULL, `part` INTEGER NOT NULL, `parts` INTEGER NOT NULL, " +
                        "`message_id` INTEGER NOT NULL, `delivery_id` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`attempt`, `part`))"
                )
            }
        }

        /** Migration 19→20: each SMS send's part count and owner, fixed before it goes out [MESHSAT-1584]. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room's own CREATE statement for SmsSend (AppDatabase_Impl), as for contacts below.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sms_sends` (" +
                        "`attempt` TEXT NOT NULL, `parts` INTEGER NOT NULL, " +
                        "`message_id` INTEGER NOT NULL, `delivery_id` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, PRIMARY KEY(`attempt`))"
                )
            }
        }

        /**
         * The Hub app route hub_msg (MESHSAT-1584): its operations, their events, the events found
         * missing, the receiver's inbox. Room's own CREATE statements (AppDatabase_Impl), as for
         * contacts below: a mismatch would fall back to a destructive migration.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hub_ops` (`op_id` TEXT NOT NULL, `mid` TEXT NOT NULL, `recipient` TEXT NOT NULL, " +
                        "`message_id` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`submission` TEXT NOT NULL, `published_at` INTEGER NOT NULL, `replay_at` INTEGER NOT NULL, " +
                        "`replay_given_up_at` INTEGER NOT NULL, PRIMARY KEY(`op_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_ops_expires_at` ON `hub_ops` (`expires_at`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hub_op_events` (`event_id` TEXT NOT NULL, `op_id` TEXT NOT NULL, `mid` TEXT NOT NULL, " +
                        "`recipient` TEXT NOT NULL, `rev` INTEGER NOT NULL, `class` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, `authority` TEXT NOT NULL, `scope` TEXT NOT NULL, `detail` TEXT NOT NULL, " +
                        "`at` TEXT NOT NULL, `source` TEXT NOT NULL, `valid` INTEGER NOT NULL, `received_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`event_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_op_events_op_id` ON `hub_op_events` (`op_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hub_op_missing` (`op_id` TEXT NOT NULL, `event_id` TEXT NOT NULL, " +
                        "`since` INTEGER NOT NULL, PRIMARY KEY(`op_id`, `event_id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hub_inbox` (`origin` TEXT NOT NULL, `op_id` TEXT NOT NULL, `mid` TEXT NOT NULL, " +
                        "`recipient` TEXT NOT NULL, `content_type` TEXT NOT NULL, `mid_expires_at` INTEGER NOT NULL, " +
                        "`expires_at` INTEGER NOT NULL, `content_sha256` TEXT NOT NULL, `message_id` INTEGER NOT NULL, " +
                        "`committed_at` INTEGER NOT NULL, `within_skew` INTEGER NOT NULL, `unjudged` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`origin`, `op_id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_inbox_origin_mid` ON `hub_inbox` (`origin`, `mid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_hub_inbox_expires_at` ON `hub_inbox` (`expires_at`)")
            }
        }

        /**
         * The LXMF message ids taken in (r40 review R40-R2, MESHSAT-1641): filed in one transaction
         * with the message. Room's own CREATE statements for LxmfMidEntity; nothing else changes.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lxmf_mids` (`origin` TEXT NOT NULL, `mid` TEXT NOT NULL, " +
                        "`received_at` INTEGER NOT NULL, PRIMARY KEY(`origin`, `mid`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lxmf_mids_received_at` ON `lxmf_mids` (`received_at`)")
            }
        }

        /**
         * Voice notes and photos kept with their messages (MESHSAT-1735). Room's own CREATE
         * statements for MessageAttachmentEntity, as for contacts below; nothing else changes.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`message_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `mode` INTEGER NOT NULL, `format` TEXT NOT NULL, " +
                        "`size` INTEGER NOT NULL, `data` BLOB NOT NULL, `created_at` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_attachments_message_id` ON `message_attachments` (`message_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_attachments_created_at` ON `message_attachments` (`created_at`)")
            }
        }

        /** Cards handed over by QR code (MESHSAT-566, 575); nothing else changes. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Room's own CREATE statement for ContactEntity, copied from the exported
                // schema (app/schemas/.../17.json) rather than written by hand: it compares
                // columns, defaults and the primary key on open, and this builder falls back
                // to a destructive migration, which would take the messages with it.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contacts` (" +
                        "`fingerprint` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`signing_pub` TEXT NOT NULL, `mesh_node_id` TEXT NOT NULL, " +
                        "`bridge_id` TEXT NOT NULL, `trust` TEXT NOT NULL, " +
                        "`issued_at` INTEGER NOT NULL, `added_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`fingerprint`))"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context, "meshsat.db").also { INSTANCE = it }
            }
        }

        /** The app's database under [name], with every migration; tests open their own file. */
        fun build(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .addMigrations(
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                    MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
                    MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
                )
                .fallbackToDestructiveMigration()
                .build()
    }
}
