package app.nock.android.sync

import androidx.room.withTransaction
import app.nock.android.data.NockDatabase
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.dao.ActiveEscalationDao
import app.nock.android.data.dao.CalendarTripDao
import app.nock.android.data.dao.GroupDao
import app.nock.android.data.dao.ReminderDao
import app.nock.android.data.dao.SettingsDao
import app.nock.android.data.entity.GroupEntity
import app.nock.android.data.entity.ReminderEntity
import app.nock.android.data.entity.SettingsEntity
import app.nock.android.domain.escalation.EscalationEngine
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class SnapshotV1(
    val version: Int = 1,
    val savedAtMs: Long,
    val groups: List<GroupSnap>,
    val reminders: List<ReminderSnap>,
    val settings: Map<String, String>
)

data class GroupSnap(
    val id: Long,
    val name: String,
    val color: Int,
    val icon: String,
    val overrideChainJson: String?,
    val overrideRepeatIntervalMs: Long?,
    val pausedUntilMs: Long?,
    val telegramSilentMirror: Boolean,
    val sortIndex: Int,
    val seedKey: String? = null
)

data class ReminderSnap(
    val id: Long,
    val groupId: Long,
    val name: String,
    val scheduleType: String,
    val scheduleJson: String,
    val nextFireAt: Long?,
    val lastCompletedAt: Long?,
    val createdAt: Long
)

@Singleton
class SnapshotCodec @Inject constructor() {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val adapter: JsonAdapter<SnapshotV1> = moshi.adapter(SnapshotV1::class.java)
}

@Singleton
class SnapshotService @Inject constructor(
    private val db: NockDatabase,
    private val groupDao: GroupDao,
    private val reminderDao: ReminderDao,
    private val activeDao: ActiveEscalationDao,
    private val settingsDao: SettingsDao,
    private val calendarTripDao: CalendarTripDao,
    private val settings: SettingsRepository,
    private val codec: SnapshotCodec,
    private val engine: EscalationEngine,
    private val repo: NockRepository,
) {
    // The Trips group and its reminders are derived from the device's calendar
    // and rebuilt by TripSyncManager, so they are device-local: excluded from
    // the snapshot to avoid duplicating them (with stale, unmanaged copies) on
    // other devices.
    private val tripsSeedKey = "trips"

    suspend fun export(): String {
        val allGroups = groupDao.getAll()
        val tripsGroupIds = allGroups.filter { it.seedKey == tripsSeedKey }.map { it.id }.toSet()
        val groups = allGroups.filter { it.id !in tripsGroupIds }.map {
            GroupSnap(it.id, it.name, it.color, it.icon, it.overrideChainJson,
                it.overrideRepeatIntervalMs, it.pausedUntilMs, it.telegramSilentMirror, it.sortIndex,
                it.seedKey)
        }
        val reminders = reminderDao.getAll().filter { it.groupId !in tripsGroupIds }.map {
            ReminderSnap(it.id, it.groupId, it.name, it.scheduleType, it.scheduleJson,
                it.nextFireAt, it.lastCompletedAt, it.createdAt)
        }
        val settingsMap = settingsDao.observeAll().first().associate { it.key to it.value }
        val snap = SnapshotV1(
            version = 1,
            savedAtMs = System.currentTimeMillis(),
            groups = groups,
            reminders = reminders,
            settings = settingsMap
        )
        return codec.adapter.toJson(snap)
    }

    suspend fun importMerge(json: String): Boolean {
        val snap = codec.adapter.fromJson(json) ?: return false
        val remoteSettings = snap.settings
        val syncStart = System.currentTimeMillis()

        // Run the wipe-and-replace AND the alarm re-arm under the engine lock, so a
        // concurrent alarm fire can't read a row here, have its backing data wiped,
        // and then arm an orphan alarm for the now-deleted escalation id (the exact
        // ghost-alarm hazard the engine's mutex protects against). The DB write
        // itself stays transactional: a crash mid-import can't leave a half-cleared
        // store. rescheduleAll runs inside replaceAllAndRearm, after the transaction
        // commits but still under the lock, rebuilding the row↔alarm bijection.
        engine.replaceAllAndRearm {
            db.withTransaction {
            activeDao.clear()
            reminderDao.clear()
            groupDao.clear()
            settingsDao.clear()
            // Trip links point at reminders we're about to wipe; clear them so
            // TripSyncManager rebuilds trips cleanly on the next sync instead of
            // treating every event as a permanently-dismissed tombstone.
            calendarTripDao.clear()

            groupDao.upsertAll(snap.groups.map {
                GroupEntity(
                    id = it.id,
                    name = it.name,
                    color = it.color,
                    icon = it.icon,
                    overrideChainJson = it.overrideChainJson,
                    overrideRepeatIntervalMs = it.overrideRepeatIntervalMs,
                    pausedUntilMs = it.pausedUntilMs,
                    telegramSilentMirror = it.telegramSilentMirror,
                    sortIndex = it.sortIndex,
                    seedKey = it.seedKey,
                )
            })
            reminderDao.upsertAll(snap.reminders.map {
                ReminderEntity(
                    id = it.id,
                    groupId = it.groupId,
                    name = it.name,
                    scheduleType = it.scheduleType,
                    scheduleJson = it.scheduleJson,
                    nextFireAt = it.nextFireAt,
                    lastCompletedAt = it.lastCompletedAt,
                    createdAt = it.createdAt
                )
            })
            settingsDao.upsertAll(remoteSettings.map { (k, v) -> SettingsEntity(k, v) })
            settings.set(SettingsRepository.KEY_DRIVE_LAST_REMOTE_MS, snap.savedAtMs.toString())
            settings.set(SettingsRepository.KEY_DRIVE_LAST_SYNC_MS, syncStart.toString())
            }
        }
        return true
    }

    suspend fun mergeIfNewer(json: String): Boolean {
        val snap = codec.adapter.fromJson(json) ?: return false
        val localRemote = settings.get(SettingsRepository.KEY_DRIVE_LAST_REMOTE_MS)?.toLongOrNull() ?: 0L
        if (snap.savedAtMs <= localRemote) return false
        return importMerge(json)
    }
}
