package org.wearabs.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        BookEntity::class,
        TrackEntity::class,
        ChapterEntity::class,
        ProgressEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun tracks(): TrackDao
    abstract fun chapters(): ChapterDao
    abstract fun progress(): ProgressDao

    companion object {
        /**
         * Adds the chapters table. Written out rather than left to a destructive
         * fallback, which would drop the record of every downloaded book and
         * orphan the files already on the watch.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `chapters` (
                        `itemId` TEXT NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `start` REAL NOT NULL,
                        `end` REAL NOT NULL,
                        `title` TEXT NOT NULL,
                        PRIMARY KEY(`itemId`, `chapterIndex`)
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_chapters_itemId` ON `chapters` (`itemId`)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "wearabs.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
