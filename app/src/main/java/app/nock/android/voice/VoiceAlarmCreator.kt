package app.nock.android.voice

import android.content.Context
import app.nock.android.R
import app.nock.android.data.NockRepository
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class VoiceAlarmOutcome {
    data class Created(val reminder: Reminder) : VoiceAlarmOutcome()
    data class Failed(val message: String) : VoiceAlarmOutcome()
}

data class VoiceAlarmSpec(
    val name: String?,
    val scheduleType: String?,
    val oneShotIso: String?,
    val timesOfDay: List<String>?,
    val daysOfWeek: List<String>?,
    val dayOfMonth: Int?,
    val timeOfDay: String?,
    val intervalMinutes: Int?,
    val groupHint: String?
)

@Singleton
class VoiceAlarmCreator @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val parser: DeepSeekReminderParser,
    private val repo: NockRepository,
    private val engine: EscalationEngine,
) {
    suspend fun createFromTranscript(transcript: String): VoiceAlarmOutcome {
        val groups = repo.getGroups()
        if (groups.isEmpty()) return VoiceAlarmOutcome.Failed(ctx.getString(R.string.voice_error_no_groups))

        return when (val r = parser.parse(transcript, groups)) {
            is DeepSeekParseResult.Failed -> VoiceAlarmOutcome.Failed(r.message)
            is DeepSeekParseResult.NotConfigured -> VoiceAlarmOutcome.Failed("DeepSeek API key not set")
            is DeepSeekParseResult.Ok -> persist(r, groups)
        }
    }

    private suspend fun persist(
        spec: DeepSeekParseResult.Ok,
        groups: List<app.nock.android.domain.model.Group>,
    ): VoiceAlarmOutcome {
        val name = spec.name?.takeIf { it.isNotBlank() }
            ?: ctx.getString(R.string.default_reminder_name)
        val groupId = spec.groupHint?.let { hint ->
            groups.firstOrNull { it.name.equals(hint, ignoreCase = true) }?.id
        } ?: groups.first().id

        val nowMs = System.currentTimeMillis()
        val nextFire = spec.schedule.nextFireFrom(nowMs, null)
        val id = repo.saveReminder(
            id = 0L,
            groupId = groupId,
            name = name,
            schedule = spec.schedule,
            nextFireAt = nextFire,
            lastCompletedAt = null,
            createdAt = nowMs
        )
        engine.cancelActive(id)
        val saved = repo.getReminder(id) ?: return VoiceAlarmOutcome.Failed(ctx.getString(R.string.voice_error_save_failed))
        if (nextFire != null && spec.schedule !is Schedule.OnUnlock) {
            engine.startEscalationAt(saved, nextFire)
        }
        return VoiceAlarmOutcome.Created(saved)
    }
}
