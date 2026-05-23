package app.nock.android.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    val icon: String,
    val overrideChainJson: String?,
    val overrideRepeatIntervalMs: Long?,
    val pausedUntilMs: Long?,
    val telegramSilentMirror: Boolean,
    val sortIndex: Int
)
