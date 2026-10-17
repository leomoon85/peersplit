package com.peersplit.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.peersplit.app.data.local.dao.BlockDao
import com.peersplit.app.data.local.dao.GroupDao
import com.peersplit.app.data.local.dao.GroupKeyDao
import com.peersplit.app.data.local.dao.GroupProtocolDao
import com.peersplit.app.data.local.dao.MemberDao
import com.peersplit.app.data.local.dao.OutboxDao
import com.peersplit.app.data.local.dao.ProjectionDao
import com.peersplit.app.data.local.dao.SignedOperationDao
import com.peersplit.app.data.local.entity.BalanceProjectionEntity
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.ExpenseProjectionEntity
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.GroupKeyEntity
import com.peersplit.app.data.local.entity.GroupProtocolEntity
import com.peersplit.app.data.local.entity.MemberEntity
import com.peersplit.app.data.local.entity.OperationDependencyEntity
import com.peersplit.app.data.local.entity.OutboxOperationEntity
import com.peersplit.app.data.local.entity.RelayDeliveryEntity
import com.peersplit.app.data.local.entity.SignedOperationEntity

@Database(
    entities = [
        GroupEntity::class,
        MemberEntity::class,
        BlockEntity::class,
        SignedOperationEntity::class,
        OperationDependencyEntity::class,
        ExpenseProjectionEntity::class,
        BalanceProjectionEntity::class,
        OutboxOperationEntity::class,
        RelayDeliveryEntity::class,
        GroupProtocolEntity::class,
        GroupKeyEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun groupDao(): GroupDao
    abstract fun memberDao(): MemberDao
    abstract fun blockDao(): BlockDao
    abstract fun signedOperationDao(): SignedOperationDao
    abstract fun projectionDao(): ProjectionDao
    abstract fun outboxDao(): OutboxDao
    abstract fun groupProtocolDao(): GroupProtocolDao
    abstract fun groupKeyDao(): GroupKeyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private fun safeAddColumn(
            db: androidx.sqlite.db.SupportSQLiteDatabase,
            table: String,
            columnDef: String
        ) {
            try {
                db.execSQL("ALTER TABLE $table ADD COLUMN $columnDef")
            } catch (_: Exception) {
                // Column already exists
            }
        }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                safeAddColumn(db, "groups", "isSecret INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "groups", "secretPinHash TEXT")
                safeAddColumn(db, "groups", "isPersonalSolo INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                safeAddColumn(db, "groups", "monthlyBudget REAL NOT NULL DEFAULT 0.0")
                safeAddColumn(db, "groups", "isArchived INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                safeAddColumn(db, "members", "upiId TEXT")
            }
        }

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                safeAddColumn(db, "members", "phone TEXT")
            }
        }

        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                safeAddColumn(db, "blocks", "originPubkey TEXT")
                safeAddColumn(db, "blocks", "originTimestamp INTEGER NOT NULL DEFAULT 0")
                safeAddColumn(db, "blocks", "originSignature TEXT")
                safeAddColumn(db, "blocks", "isSynced INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Signed Operations
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `signed_operations` (
                        `operationId` TEXT NOT NULL,
                        `protocolVersion` INTEGER NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `authorPublicKey` TEXT NOT NULL,
                        `parentsJson` TEXT NOT NULL,
                        `logicalClock` INTEGER NOT NULL,
                        `payloadType` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `signature` TEXT NOT NULL,
                        `keyEpoch` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `receivedAtMillis` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `rejectionReason` TEXT,
                        PRIMARY KEY(`operationId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signed_operations_groupId_logicalClock` ON `signed_operations` (`groupId`, `logicalClock`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signed_operations_groupId_status` ON `signed_operations` (`groupId`, `status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_signed_operations_authorPublicKey` ON `signed_operations` (`authorPublicKey`)")

                // 2. Operation Dependencies
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `operation_dependencies` (
                        `groupId` TEXT NOT NULL,
                        `childOperationId` TEXT NOT NULL,
                        `parentOperationId` TEXT NOT NULL,
                        PRIMARY KEY(`childOperationId`, `parentOperationId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_operation_dependencies_groupId_parentOperationId` ON `operation_dependencies` (`groupId`, `parentOperationId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_operation_dependencies_groupId_childOperationId` ON `operation_dependencies` (`groupId`, `childOperationId`)")

                // 3. Expense Projections
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `expense_projections` (
                        `groupId` TEXT NOT NULL,
                        `expenseId` TEXT NOT NULL,
                        `originOperationId` TEXT NOT NULL,
                        `currentRevisionOperationId` TEXT NOT NULL,
                        `authorPublicKey` TEXT NOT NULL,
                        `logicalClock` INTEGER NOT NULL,
                        `amountMinor` INTEGER NOT NULL,
                        `currency` TEXT NOT NULL,
                        `paidBy` TEXT NOT NULL,
                        `payersJson` TEXT NOT NULL,
                        `splitsJson` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `splitType` TEXT NOT NULL,
                        `expenseDateMillis` INTEGER NOT NULL,
                        `isVoided` INTEGER NOT NULL,
                        `voidedByOperationId` TEXT,
                        `voidReason` TEXT,
                        PRIMARY KEY(`groupId`, `expenseId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_projections_groupId_expenseDateMillis` ON `expense_projections` (`groupId`, `expenseDateMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_expense_projections_groupId_isVoided` ON `expense_projections` (`groupId`, `isVoided`)")

                // 4. Balance Projections
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `balance_projections` (
                        `groupId` TEXT NOT NULL,
                        `memberPublicKey` TEXT NOT NULL,
                        `balanceMinor` INTEGER NOT NULL,
                        `currency` TEXT NOT NULL,
                        `stateDigest` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `memberPublicKey`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_balance_projections_groupId` ON `balance_projections` (`groupId`)")

                // 5. Outbox Operations
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `outbox_operations` (
                        `operationId` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `nostrEventId` TEXT NOT NULL,
                        `signedEventJson` TEXT NOT NULL,
                        `keyEpoch` INTEGER NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `nextAttemptAtMillis` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`operationId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_status_nextAttemptAtMillis` ON `outbox_operations` (`status`, `nextAttemptAtMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_groupId` ON `outbox_operations` (`groupId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_operations_nostrEventId` ON `outbox_operations` (`nostrEventId`)")

                // 6. Relay Deliveries
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `relay_deliveries` (
                        `operationId` TEXT NOT NULL,
                        `relayUrl` TEXT NOT NULL,
                        `nostrEventId` TEXT NOT NULL,
                        `accepted` INTEGER NOT NULL,
                        `relayMessage` TEXT,
                        `ackedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`operationId`, `relayUrl`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relay_deliveries_nostrEventId` ON `relay_deliveries` (`nostrEventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_relay_deliveries_operationId_accepted` ON `relay_deliveries` (`operationId`, `accepted`)")

                // 7. Group Protocol Metadata
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `group_protocol_metadata` (
                        `groupId` TEXT NOT NULL,
                        `protocolVersion` INTEGER NOT NULL,
                        `genesisOperationId` TEXT,
                        `currentKeyEpoch` INTEGER NOT NULL,
                        `latestStateDigest` TEXT NOT NULL,
                        `maxLogicalClock` INTEGER NOT NULL,
                        `headOperationIdsJson` TEXT NOT NULL,
                        `v1MigrationStatus` TEXT NOT NULL,
                        `v1MigratedTipHash` TEXT,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_protocol_metadata_protocolVersion` ON `group_protocol_metadata` (`protocolVersion`)")
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `group_keys` (
                        `groupId` TEXT NOT NULL,
                        `keyEpoch` INTEGER NOT NULL,
                        `groupKeyBase64` TEXT NOT NULL,
                        `distributedToPubkeysJson` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `keyEpoch`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_keys_groupId` ON `group_keys` (`groupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_keys_groupId_keyEpoch` ON `group_keys` (`groupId`, `keyEpoch`)")

                // Seed existing group keys into epoch 0
                db.execSQL("""
                    INSERT OR IGNORE INTO `group_keys` (`groupId`, `keyEpoch`, `groupKeyBase64`, `distributedToPubkeysJson`, `createdAtMillis`)
                    SELECT `id`, 0, `groupKeyBase64`, '[]', ${System.currentTimeMillis()} FROM `groups`
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peersplit_ledger.db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
