package app.nock.android.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// A user-named room in the home, identified by its WiFi fingerprint samples.
// Rooms are reusable: any number of RoomAfter reminders may target one.
@Entity(tableName = "wifi_rooms")
data class WifiRoomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

// One fingerprint sample of a room: the BSSID → RSSI map of a single WiFi
// scan taken while standing in it (levelsJson, see WifiLevelsJson). A room
// accumulates several samples; matching scores a live scan against each and
// takes the best, so adding samples improves robustness.
@Entity(
    tableName = "wifi_room_samples",
    foreignKeys = [
        ForeignKey(
            entity = WifiRoomEntity::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("roomId")]
)
data class WifiRoomSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: Long,
    val capturedAt: Long,
    val levelsJson: String
)
