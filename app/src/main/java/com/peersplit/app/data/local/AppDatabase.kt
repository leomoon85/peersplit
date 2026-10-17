package com.peersplit.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.peersplit.app.data.local.dao.BlockDao
import com.peersplit.app.data.local.dao.GroupDao
import com.peersplit.app.data.local.dao.MemberDao
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.MemberEntity

@Database(
    entities = [
        GroupEntity::class,
        MemberEntity::class,
        BlockEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun groupDao(): GroupDao
    abstract fun memberDao(): MemberDao
    abstract fun blockDao(): BlockDao

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
                        MIGRATION_5_6
                    )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
