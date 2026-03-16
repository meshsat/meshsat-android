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
    ],
    version = 6,
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meshsat.db"
                )
                    .addMigrations(MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
