package com.ajay.storyboard.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun sourceToString(v: ChapterSource): String = v.name
    @TypeConverter fun stringToSource(v: String): ChapterSource = ChapterSource.valueOf(v)
    @TypeConverter fun statusToString(v: DraftStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): DraftStatus = DraftStatus.valueOf(v)
    @TypeConverter fun kindToString(v: RunKind): String = v.name
    @TypeConverter fun stringToKind(v: String): RunKind = RunKind.valueOf(v)
    @TypeConverter fun runStatusToString(v: RunStatus): String = v.name
    @TypeConverter fun stringToRunStatus(v: String): RunStatus = RunStatus.valueOf(v)
}

@Database(
    entities = [
        Book::class, Chapter::class, ChapterVersion::class, Draft::class, BibleEntry::class, AiRun::class,
        ApiProfile::class, CustomSite::class, ImageRequest::class, GalleryImage::class, ApiChatMessage::class,
        Roundtable::class, RoundtableMessage::class,
    ],
    version = 7,
    autoMigrations = [AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7)],
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun chapters(): ChapterDao
    abstract fun versions(): VersionDao
    abstract fun drafts(): DraftDao
    abstract fun bible(): BibleDao
    abstract fun runs(): AiRunDao
    abstract fun aiConfig(): AiConfigDao
    abstract fun images(): ImageDao
    abstract fun apiChat(): ApiChatDao
    abstract fun roundtables(): RoundtableDao
    abstract fun backup(): BackupDao

    companion object {
        const val NAME = "storyboard.db"
        const val VERSION = 7

        fun create(context: Context): AppDatabase {
            safetyCopy(context)
            return Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
        }

        /**
         * Before a new app version upgrades the database, the current file is copied to
         * files/db-safety/storyboard-v<old>.db (once per version), so an upgrade can never lose your stories.
         */
        private fun safetyCopy(context: Context) {
            runCatching {
                val db = context.getDatabasePath(NAME)
                if (!db.exists()) return
                val old = android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                    .use { it.version }
                if (old >= VERSION) return
                val dir = java.io.File(context.filesDir, "db-safety").apply { mkdirs() }
                val target = java.io.File(dir, "storyboard-v$old.db")
                if (target.exists()) return
                // fold the write-ahead log in first so the copy is complete
                android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                    .use { it.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { c -> c.moveToFirst() } }
                db.copyTo(target)
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN inBible INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
