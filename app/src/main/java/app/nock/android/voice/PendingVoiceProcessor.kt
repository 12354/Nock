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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

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
    private val logger: VoiceLogger,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val inFlightMutex = Mutex()
    private val inFlight = mutableSetOf<Long>()

    fun observePending(): Flow<List<PendingVoiceReminderEntity>> = pendingDao.observeAll()

    suspend fun enqueue(transcript: String): Long {
        val id = pendingDao.insert(
            PendingVoiceReminderEntity(
                transcript = transcript,
                createdAt = System.currentTimeMillis(),
                lastAttemptAt = null,
                attemptCount = 0,
                lastError = null,
            )
        )
        logger.log(TAG, "enqueue pending=$id transcript=\"$transcript\"")
        kick(id)
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
            logger.log(TAG, "kickAll size=${all.size}")
            for (entry in all) process(entry.id)
        }
    }

    suspend fun delete(id: Long) {
        pendingDao.deleteById(id)
        logger.log(TAG, "delete pending=$id")
    }

    private suspend fun process(id: Long) {
        val acquired = inFlightMutex.withLock { inFlight.add(id) }
        if (!acquired) {
            logger.log(TAG, "skip pending=$id — already in flight")
            return
        }
        try {
            val groups = repo.getGroups()
            if (groups.isEmpty()) {
                recordFailure(id, ctx.getString(R.string.voice_error_no_groups))
                return
            }
            var attempt = 0
            var lastError: String? = null
            while (attempt < MAX_ATTEMPTS) {
                attempt++
                val entry = pendingDao.getById(id) ?: return // deleted while waiting
                pendingDao.update(
                    entry.copy(
                        attemptCount = attempt,
                        lastAttemptAt = System.currentTimeMillis()
                    )
                )
                logger.log(TAG, "process pending=$id attempt=$attempt/$MAX_ATTEMPTS")
                when (val result = parser.parse(entry.transcript, groups)) {
                    is DeepSeekParseResult.Ok -> {
                        persistAndComplete(id, result, groups)
                        logger.log(TAG, "pending=$id resolved on attempt=$attempt")
                        return
                    }
                    is DeepSeekParseResult.NotConfigured -> {
                        recordFailure(id, ctx.getString(R.string.pending_voice_not_configured), attempt)
                        return
                    }
                    is DeepSeekParseResult.Failed -> {
                        lastError = result.message
                        logger.log(
                            TAG,
                            "pending=$id attempt=$attempt failed: ${result.message}" +
                                " (transient=${result.transient})"
                        )
                        if (!result.transient) {
                            recordFailure(id, result.message, attempt)
                            return
                        }
                        if (attempt < MAX_ATTEMPTS) {
                            val backoffMs = BACKOFF_MS_BASE shl (attempt - 1)
                            delay(backoffMs)
                        }
                    }
                }
            }
            recordFailure(id, lastError ?: ctx.getString(R.string.voice_error_save_failed), MAX_ATTEMPTS)
        } finally {
            inFlightMutex.withLock { inFlight.remove(id) }
        }
    }

    private suspend fun persistAndComplete(
        pendingId: Long,
        spec: DeepSeekParseResult.Ok,
        groups: List<Group>,
    ) {
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
        pendingDao.deleteById(pendingId)
    }

    private suspend fun recordFailure(id: Long, error: String, attempts: Int? = null) {
        val cur = pendingDao.getById(id) ?: return
        pendingDao.update(
            cur.copy(
                lastError = error,
                lastAttemptAt = System.currentTimeMillis(),
                attemptCount = attempts ?: cur.attemptCount,
            )
        )
    }

    private companion object {
        const val TAG = "Pending"
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS_BASE = 1_000L
    }
}
