package com.sagesearch.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.sagesearch.android.LegacyIndexedImageStore

@Database(
    entities = [ApprovedSourceEntity::class, DocumentEntity::class, DocumentFtsEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class SageSearchDatabase : RoomDatabase() {
    abstract fun approvedSourceDao(): ApprovedSourceDao
    abstract fun documentDao(): DocumentDao
    abstract fun documentSearchDao(): DocumentSearchDao

    fun legacyIndexedImageStore(): LegacyIndexedImageStore = LegacyIndexedImageStore(this)

    companion object {
        const val DATABASE_NAME = "sagesearch-image-index.db"

        @Volatile
        private var instance: SageSearchDatabase? = null

        fun get(context: Context): SageSearchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SageSearchDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
