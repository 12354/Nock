package app.nock.android.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId"), Index("nextFireAt")]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val name: String,
    val scheduleType: String,
    val scheduleJson: String,
    val nextFireAt: Long?,
    val lastCompletedAt: Long?,
    val createdAt: Long,
    // "Regular" reminder: single gentle vibration nudge, no escalation chain.
    // vibrationPatternCsv holds the arranged short/long pulses (e.g. "SHORT,LONG").
    val simpleVibration: Boolean = false,
    val vibrationPatternCsv: String? = null
)
