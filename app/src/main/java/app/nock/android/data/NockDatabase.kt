package app.nock.android.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.dao.PendingTelegramDeletionDao
import app.nock.android.data.dao.PendingVoiceReminderDao
import app.nock.android.data.dao.ReminderDao
import app.nock.android.data.dao.SettingsDao
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.entity.GroupEntity
import app.nock.android.data.entity.PendingTelegramDeletionEntity
import app.nock.android.data.entity.PendingVoiceReminderEntity
import app.nock.android.data.entity.ReminderEntity
import app.nock.android.data.entity.SettingsEntity

@Database(
    entities = [
        GroupEntity::class,
        ReminderEntity::class,
        ActiveEscalationEntity::class,
        SettingsEntity::class,
        PendingVoiceReminderEntity::class,
        PendingTelegramDeletionEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class NockDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun reminderDao(): ReminderDao
    abstract fun activeEscalationDao(): ActiveEscalationDao
    abstract fun settingsDao(): SettingsDao
    abstract fun pendingVoiceReminderDao(): PendingVoiceReminderDao
    abstract fun pendingTelegramDeletionDao(): PendingTelegramDeletionDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE groups ADD COLUMN seedKey TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_voice_reminders` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`transcript` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastAttemptAt` INTEGER, " +
                "`attemptCount` INTEGER NOT NULL, " +
                "`lastError` TEXT" +
                ")"
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE active_escalations ADD COLUMN sentTelegramMessageIdsCsv TEXT NOT NULL DEFAULT ''"
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_telegram_deletions` (" +
                "`messageId` INTEGER NOT NULL, " +
                "PRIMARY KEY(`messageId`)" +
                ")"
        )
    }
}
