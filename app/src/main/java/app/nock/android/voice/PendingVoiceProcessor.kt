package app.nock.android.voice

import android.content.Context
import app.nock.android.R
import app.nock.android.data.NockRepository
import app.nock.android.data.dao.PendingVoiceReminderDao
import app.nock.android.data.entity.PendingVoiceReminderEntity
import app.nock.android.di.ApplicationScope
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Schedule
import app.nock.android.history.AlarmHistoryLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Result of running a single pending transcript to completion. */
sealed interface VoiceProcessOutcome {
    /** Transcript became a real reminder; [message] is the ready-to-show confirmation. */
    data class Added(val message: String) : VoiceProcessOutcome
    /** Processing failed (or was abandoned); [message] explains why. */
    data class Failed(val message: String) : VoiceProcessOutcome
}

/**
 * Durable queue for voice transcripts that haven't yet been turned into real reminders.
 *
 * Voice capture writes a row here synchronously so the user's words can't be lost to a
 * DeepSeek timeout. A background coroutine in [appScope] then calls DeepSeek with bounded
 * retries; on success the row is replaced with a real reminder, on transient failure the
 * row stays put with its last error so we can try again at app start or on a user tap.
 */
@Singleton
class PendingVoiceProcessor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val pendingDao: PendingVoiceReminderDao,
    private val parser: DeepSeekReminderParser,
    private val repo: NockRepository,
    private val engine: EscalationEngine,
    private val history: AlarmHistoryLogger,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val inFlightMutex = Mutex()
    private val inFlight = mutableSetOf<Long>()

    // Ready-to-show confirmation strings emitted once a transcript becomes a
    // real reminder. The UI collects these to show a toast; no replay so a
    // reminder created while no screen is listening doesn't toast on return.
    private val _added = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val added: SharedFlow<String> = _added.asSharedFlow()

    fun observePending(): Flow<List<PendingVoiceReminderEntity>> = pendingDao.observeAll()

    /**
     * Persist [transcript] durably so it can't be lost. By default this also kicks
     * off background processing; pass [autoKick] = false when the caller intends to
     * drive [process] itself (e.g. the widget service, which awaits the outcome so
     * it can keep its foreground process alive and toast the result).
     */
    suspend fun enqueue(transcript: String, autoKick: Boolean = true): Long {
        val id = pendingDao.insert(
            PendingVoiceReminderEntity(
                transcript = transcript,
                createdAt = System.currentTimeMillis(),
                lastAttemptAt = null,
                attemptCount = 0,
                lastError = null,
            )
        )
        if (autoKick) kick(id)
        return id
    }

    fun kick(id: Long) {
        appScope.launch { process(id) }
    }

    /** Re-run every unresolved entry. Sequential to avoid rate-limiting the model. */
    fun kickAll() {
        appScope.launch {
            val all = pendingDao.getAll()
            if (all.isEmpty()) return@launch
            for (entry in all) process(entry.id)
        }
    }

    suspend fun delete(id: Long) {
        pendingDao.deleteById(id)
    }

    /**
     * Run the pending entry [id] to completion and report the outcome. When
     * [emitAdded] is true a successful result is also published on [added] for
     * any in-app screen to toast; the widget path passes false and shows the
     * returned message itself.
     */
    suspend fun process(id: Long, emitAdded: Boolean = true): VoiceProcessOutcome {
        val acquired = inFlightMutex.withLock { inFlight.add(id) }
        if (!acquired) {
            return VoiceProcessOutcome.Failed(ctx.getString(R.string.voice_error_save_failed))
        }
        try {
            val groups = repo.getGroups()
            if (groups.isEmpty()) {
                return recordFailure(id, ctx.getString(R.string.voice_error_no_groups))
            }
            var attempt = 0
            var lastError: String? = null
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                // Deleted while waiting — nothing left to report.
                val entry = pendingDao.getById(id)
                    ?: return VoiceProcessOutcome.Failed(ctx.getString(R.string.voice_error_save_failed))
                pendingDao.update(
                    entry.copy(
                        attemptCount = attempt,
                        lastAttemptAt = System.currentTimeMillis()
                    )
                )
                when (val result = parser.parse(entry.transcript, groups)) {
                    is DeepSeekParseResult.Ok -> {
                        val message = persistAndComplete(id, result, groups)
                        if (emitAdded) _added.tryEmit(message)
                        return VoiceProcessOutcome.Added(message)
                    }
                    is DeepSeekParseResult.NotConfigured -> {
                        return recordFailure(id, ctx.getString(R.string.pending_voice_not_configured), attempt)
                    }
                    is DeepSeekParseResult.Failed -> {
                        lastError = result.message
                        if (!result.transient) {
                            return recordFailure(id, result.message, attempt)
                        }
                        if (attempt < MAX_ATTEMPTS) {
                            val backoffMs = BACKOFF_MS_BASE shl (attempt - 1)
                            delay(backoffMs)
                        }
                    }
                }
            }
            return recordFailure(
                id,
                lastError ?: ctx.getString(R.string.voice_error_save_failed),
                MAX_ATTEMPTS
            )
        } finally {
            inFlightMutex.withLock { inFlight.remove(id) }
        }
    }

    /** Saves the real reminder, drops the pending row, and returns the confirmation text. */
    private suspend fun persistAndComplete(
        pendingId: Long,
        spec: DeepSeekParseResult.Ok,
        groups: List<Group>,
    ): String {
        val name = spec.name?.takeIf { it.isNotBlank() }
            ?: ctx.getString(R.string.default_reminder_name)
        val groupId = spec.groupHint?.let { hint ->
            groups.firstOrNull { it.name.equals(hint, ignoreCase = true) }?.id
        } ?: groups.first().id

        val nowMs = System.currentTimeMillis()
        val nextFire = spec.schedule.nextFireFrom(nowMs, null)
        val reminderId = repo.saveReminder(
            id = 0L,
            groupId = groupId,
            name = name,
            schedule = spec.schedule,
            nextFireAt = nextFire,
            lastCompletedAt = null,
            createdAt = nowMs
        )
        engine.cancelActive(reminderId)
        val saved = repo.getReminder(reminderId)
        if (saved != null && nextFire != null && spec.schedule !is Schedule.OnUnlock) {
            engine.startEscalationAt(saved, nextFire)
        }
        val groupName = groups.firstOrNull { it.id == groupId }?.name
        history.created(name, groupName, spec.schedule, nextFire)
        pendingDao.deleteById(pendingId)
        return VoiceReminderToast.format(ctx, name, nextFire, spec.schedule)
    }

    private suspend fun recordFailure(
        id: Long,
        error: String,
        attempts: Int? = null,
    ): VoiceProcessOutcome {
        val cur = pendingDao.getById(id)
        if (cur != null) {
            pendingDao.update(
                cur.copy(
                    lastError = error,
                    lastAttemptAt = System.currentTimeMillis(),
                    attemptCount = attempts ?: cur.attemptCount,
                )
            )
        }
        return VoiceProcessOutcome.Failed(error)
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS_BASE = 1_000L
    }
}
