package app.nock.android.voice

import android.content.Context
import app.nock.android.R
import app.nock.android.data.NockRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class VoiceAlarmOutcome {
    data class Captured(val pendingId: Long, val message: String) : VoiceAlarmOutcome()
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
    private val repo: NockRepository,
    private val processor: PendingVoiceProcessor,
) {
    suspend fun createFromTranscript(transcript: String): VoiceAlarmOutcome {
        val text = transcript.trim()
        if (text.isEmpty()) {
            return VoiceAlarmOutcome.Failed(ctx.getString(R.string.voice_error_empty_transcript))
        }
        if (repo.getGroups().isEmpty()) {
            return VoiceAlarmOutcome.Failed(ctx.getString(R.string.voice_error_no_groups))
        }
        val pendingId = processor.enqueue(text)
        return VoiceAlarmOutcome.Captured(
            pendingId = pendingId,
            message = ctx.getString(R.string.voice_captured)
        )
    }

    /**
     * Captures [transcript] and runs it to completion, returning the user-facing
     * message to surface (e.g. as a toast). Unlike [createFromTranscript], this
     * suspends until DeepSeek parsing finishes so the caller — the widget's
     * foreground service — keeps its process alive for the duration and can report
     * the real outcome instead of opening the app.
     */
    suspend fun createAndAwait(transcript: String): String {
        val text = transcript.trim()
        if (text.isEmpty()) {
            return ctx.getString(R.string.voice_error_empty_transcript)
        }
        if (repo.getGroups().isEmpty()) {
            return ctx.getString(R.string.voice_error_no_groups)
        }
        val pendingId = processor.enqueue(text, autoKick = false)
        return when (val outcome = processor.process(pendingId, emitAdded = false)) {
            is VoiceProcessOutcome.Added -> outcome.message
            is VoiceProcessOutcome.Failed -> outcome.message
        }
    }
}
