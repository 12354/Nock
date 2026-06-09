package app.nock.android.voice

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.nock.android.R
import app.nock.android.notif.Channels
import app.nock.android.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Turns a captured voice transcript into a real reminder off the recording path.
 *
 * The widget's [app.nock.android.widget.VoiceCaptureService] persists the spoken
 * text durably and enqueues this worker, then returns the widget to Idle at once
 * — so the next tap can start a new recording while DeepSeek is still parsing the
 * previous one. Running here (not in the mic service) decouples processing from
 * the recording lifecycle and survives the service's death; the durable queue's
 * row is the backstop if this worker is delayed or killed (re-run by
 * [PendingVoiceProcessor.kickAll] at next launch).
 *
 * Runs as expedited foreground work so it gets a process slot immediately and
 * isn't killed mid-parse, and surfaces the outcome as a notification rather than
 * a toast (the widget deliberately never opens the app on its own).
 */
@HiltWorker
class VoiceTranscriptWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val processor: PendingVoiceProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext.getString(R.string.widget_voice_notification_processing))

    override suspend fun doWork(): Result {
        val pendingId = inputData.getLong(KEY_PENDING_ID, -1L)
        if (pendingId <= 0L) return Result.failure()

        // Promote to foreground so an expedited run keeps a process slot for the
        // network call; harmless if WorkManager already started us foregrounded.
        runCatching { setForeground(getForegroundInfo()) }

        val message = when (val outcome = processor.process(pendingId, emitAdded = false)) {
            is VoiceProcessOutcome.Added -> outcome.message
            is VoiceProcessOutcome.Failed -> outcome.message
        }
        notifyResult(message)
        return Result.success()
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(applicationContext, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setContentTitle(text)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notif)
        }
    }

    private fun notifyResult(message: String) {
        if (message.isBlank()) return
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val openApp = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val contentPI = android.app.PendingIntent.getActivity(
            applicationContext,
            REQ_OPEN,
            openApp,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, Channels.SERVICE)
            .setSmallIcon(R.drawable.ic_widget_mic)
            .setContentTitle(applicationContext.getString(R.string.widget_voice_result_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentPI)
            .setAutoCancel(true)
            .build()
        nm.notify(RESULT_NOTIFICATION_ID, notif)
    }

    companion object {
        private const val KEY_PENDING_ID = "pending_id"
        private const val FOREGROUND_NOTIFICATION_ID = 0xC1C3
        private const val RESULT_NOTIFICATION_ID = 0xC1C4
        private const val REQ_OPEN = 2
        private const val WORK_NAME_PREFIX = "voice_transcript_"

        /**
         * Enqueues background processing for the durable pending row [pendingId].
         * Keyed uniquely per row (KEEP) so a re-trigger can't spawn a duplicate
         * worker for the same transcript.
         */
        fun enqueue(context: Context, pendingId: Long) {
            val request = OneTimeWorkRequestBuilder<VoiceTranscriptWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(KEY_PENDING_ID to pendingId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + pendingId,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
