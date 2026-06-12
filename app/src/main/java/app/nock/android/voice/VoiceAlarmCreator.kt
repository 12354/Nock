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
     * Persists [transcript] durably and hands parsing off to a background
     * [VoiceTranscriptWorker], returning as soon as the row is saved. Used by the
     * widget's foreground service so it can release the recording widget for the
     * next tap without waiting on DeepSeek; the worker surfaces the outcome as a
     * notification and survives the service's death. An empty transcript is a
     * no-op; group/parse failures are handled (and reported) by the worker.
     */
    suspend fun enqueueForBackground(transcript: String) {
        val text = transcript.trim()
        if (text.isEmpty()) return
        // Save the words now so they can't be lost if the worker is delayed or
        // killed; autoKick=false because the worker drives process() itself.
        val pendingId = processor.enqueue(text, autoKick = false)
        VoiceTranscriptWorker.enqueue(ctx, pendingId)
    }
}
