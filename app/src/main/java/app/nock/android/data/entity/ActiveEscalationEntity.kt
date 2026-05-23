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
    val startedAtMs: Long,
    val nextStageIndex: Int,
    val nextFireAtMs: Long,
    val chainSnapshotJson: String,
    val repeatIntervalMs: Long
)
