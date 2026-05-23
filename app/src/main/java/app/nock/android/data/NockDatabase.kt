package app.nock.android.data

import androidx.room.Database
import androidx.room.RoomDatabase
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.dao.ReminderDao
import app.nock.android.data.dao.SettingsDao
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.entity.GroupEntity
import app.nock.android.data.entity.ReminderEntity
import app.nock.android.data.entity.SettingsEntity

@Database(
    entities = [
        GroupEntity::class,
        ReminderEntity::class,
        ActiveEscalationEntity::class,
        SettingsEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class NockDatabase : RoomDatabase() {
    abstract fun groupDao(): GroupDao
    abstract fun reminderDao(): ReminderDao
    abstract fun activeEscalationDao(): ActiveEscalationDao
    abstract fun settingsDao(): SettingsDao
}
