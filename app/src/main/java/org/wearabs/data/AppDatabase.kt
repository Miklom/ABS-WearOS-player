package org.wearabs.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookEntity::class, TrackEntity::class, ProgressEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun tracks(): TrackDao
    abstract fun progress(): ProgressDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "wearabs.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
