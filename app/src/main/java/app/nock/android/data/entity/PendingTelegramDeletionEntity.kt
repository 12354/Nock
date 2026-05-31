package app.nock.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_telegram_deletions")
data class PendingTelegramDeletionEntity(
    @PrimaryKey val messageId: Long,
)
