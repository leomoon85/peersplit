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
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun groupDao(): GroupDao
    abstract fun memberDao(): MemberDao
    abstract fun blockDao(): BlockDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE members ADD COLUMN phone TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "peersplit_ledger.db"
                )
                    .addMigrations(MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
