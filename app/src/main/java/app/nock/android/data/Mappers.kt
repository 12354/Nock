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

fun ReminderEntity.toDomain(): Reminder = Reminder(
    id = id,
    groupId = groupId,
    name = name,
    schedule = ScheduleJson.decode(scheduleJson),
    nextFireAt = nextFireAt,
    lastCompletedAt = lastCompletedAt,
    createdAt = createdAt
)
