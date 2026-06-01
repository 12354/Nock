package app.nock.android.data

import app.nock.android.data.entity.GroupEntity
import app.nock.android.data.entity.ReminderEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.data.json.ScheduleJson
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder

fun GroupEntity.toDomain(): Group = Group(
    id = id,
    name = name,
    color = color,
    icon = icon,
    overrideChain = overrideChainJson?.let { runCatching { ChainJson.decode(it) }.getOrNull() },
    pausedUntilMs = pausedUntilMs,
    telegramSilentMirror = telegramSilentMirror,
    seedKey = seedKey
)

// Returns null when the stored schedule JSON can't be decoded (corrupt or
// forward-version row). Callers drop such rows instead of letting a single bad
// reminder throw and take down the entire list query.
fun ReminderEntity.toDomain(): Reminder? {
    val schedule = runCatching { ScheduleJson.decode(scheduleJson) }.getOrNull() ?: return null
    return Reminder(
        id = id,
        groupId = groupId,
        name = name,
        schedule = schedule,
        nextFireAt = nextFireAt,
        lastCompletedAt = lastCompletedAt,
        createdAt = createdAt
    )
}
