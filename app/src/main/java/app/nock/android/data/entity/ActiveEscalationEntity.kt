package app.nock.android.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "active_escalations",
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["reminderId"], unique = true)]
)
data class ActiveEscalationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reminderId: Long,
    // The occurrence this escalation fires for: the reminder's scheduled wall-clock
    // trigger. The row is keyed to its occurrence through this field — move-detection
    // and the row<->occurrence binding compare it against the reminder's nextFireAt —
    // so it is NEVER shifted once the row is armed (not even by snooze).
    val startedAtMs: Long,
    // The escalation TIMELINE anchor: stage N is due at anchorMs + stage.offsetMs.
    // Equal to startedAtMs while the chain runs on its original schedule; snooze
    // shifts it forward so the whole remaining chain is delayed by the snooze
    // interval without disturbing startedAtMs (and thus without looking "moved").
    val anchorMs: Long,
    val nextStageIndex: Int,
    val nextFireAtMs: Long,
    val chainSnapshotJson: String,
    val repeatIntervalMs: Long,
    val sentTelegramMessageIdsCsv: String = ""
)
