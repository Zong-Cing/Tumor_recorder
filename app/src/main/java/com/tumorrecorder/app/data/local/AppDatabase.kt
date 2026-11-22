package com.tumorrecorder.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CallRecordEntity::class,
        PromptTemplateEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun callRecordDao(): CallRecordDao
    abstract fun promptTemplateDao(): PromptTemplateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE call_records ADD COLUMN promptTemplateName TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS prompt_templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        promptContent TEXT NOT NULL,
                        isDefault INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE call_records ADD COLUMN supplementAudioFilePath TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE call_records ADD COLUMN supplementDurationSeconds INTEGER NOT NULL DEFAULT 0"
                )
                // Seed the new single-sided read-back template once for existing users without disturbing custom templates
                val t = com.tumorrecorder.app.domain.DefaultPromptTemplates.singleSideReadbackTemplate()
                db.execSQL("UPDATE prompt_templates SET isDefault = 0")
                db.execSQL(
                    """
                    INSERT INTO prompt_templates (name, description, promptContent, isDefault, updatedAtMillis)
                    VALUES (?, ?, ?, 1, ?)
                    """.trimIndent(),
                    arrayOf<Any>(t.name, t.description, t.promptContent, t.updatedAtMillis)
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tumor_recorder.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
