package app.nock.android.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.CalendarTripDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.dao.PendingTelegramDeletionDao
import app.nock.android.data.dao.PendingVoiceReminderDao
import app.nock.android.data.dao.ReminderDao
import app.nock.android.data.dao.SettingsDao
import app.nock.android.data.dao.WifiRoomDao
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.entity.CalendarTripEntity
import app.nock.android.data.entity.GroupEntity
import app.nock.android.data.entity.PendingTelegramDeletionEntity
import app.nock.android.data.entity.PendingVoiceReminderEntity
import app.nock.android.data.entity.ReminderEntity
import app.nock.android.data.entity.SettingsEntity
import app.nock.android.data.entity.WifiRoomEntity
import app.nock.android.data.entity.WifiRoomSampleEntity

@Database(
    entities = [
        GroupEntity::class,
        ReminderEntity::class,
        ActiveEscalationEntity::class,
        SettingsEntity::class,
        PendingVoiceReminderEntity::class,
        PendingTelegramDeletionEntity::class,
        CalendarTripEntity::class,
        WifiRoomEntity::class,
        WifiRoomSampleEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class NockDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun reminderDao(): ReminderDao
    abstract fun activeEscalationDao(): ActiveEscalationDao
    abstract fun settingsDao(): SettingsDao
    abstract fun pendingVoiceReminderDao(): PendingVoiceReminderDao
    abstract fun pendingTelegramDeletionDao(): PendingTelegramDeletionDao
    abstract fun calendarTripDao(): CalendarTripDao
    abstract fun wifiRoomDao(): WifiRoomDao
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

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calendar_trips` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`reminderId` INTEGER NOT NULL, " +
                "`calendarId` INTEGER NOT NULL, " +
                "`eventId` INTEGER NOT NULL, " +
                "`eventStartMs` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`location` TEXT NOT NULL, " +
                "`originAddress` TEXT, " +
                "`travelMode` TEXT NOT NULL, " +
                "`bufferMs` INTEGER NOT NULL, " +
                "`originLat` REAL, " +
                "`originLon` REAL, " +
                "`destLat` REAL, " +
                "`destLon` REAL, " +
                "`lastTravelMs` INTEGER, " +
                "`lastComputedAtMs` INTEGER" +
                ")"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_calendar_trips_reminderId` " +
                "ON `calendar_trips` (`reminderId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_calendar_trips_eventId` " +
                "ON `calendar_trips` (`eventId`)"
        )
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `wifi_rooms` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL" +
                ")"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `wifi_room_samples` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`roomId` INTEGER NOT NULL, " +
                "`capturedAt` INTEGER NOT NULL, " +
                "`levelsJson` TEXT NOT NULL, " +
                "FOREIGN KEY(`roomId`) REFERENCES `wifi_rooms`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE" +
                ")"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_wifi_room_samples_roomId` " +
                "ON `wifi_room_samples` (`roomId`)"
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The escalation timeline anchor (shifted by snooze). Back-fill existing
        // rows with their startedAtMs, which is the anchor for any non-snoozed chain.
        db.execSQL(
            "ALTER TABLE active_escalations ADD COLUMN anchorMs INTEGER NOT NULL DEFAULT 0"
        )
        db.execSQL("UPDATE active_escalations SET anchorMs = startedAtMs")
    }
}
