package app.nock.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_voice_reminders")
data class PendingVoiceReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transcript: String,
    val createdAt: Long,
    val lastAttemptAt: Long?,
    val attemptCount: Int,
    val lastError: String?,
)
