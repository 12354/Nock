package app.nock.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import app.nock.android.domain.model.StageType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    private val am: AlarmManager = ctx.getSystemService()!!

    fun scheduleStage(escalationId: Long, atMs: Long, stageType: StageType) {
        val isLoud = stageType == StageType.ALARM || stageType == StageType.ALARM_VIBRATE
        val pi = pendingIntent(escalationId, isLoud)
        when (stageType) {
            StageType.ALARM, StageType.ALARM_VIBRATE -> {
                val showIntent = openAppPI()
                am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, showIntent), pi)
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, atMs, pi)
                }
            }
        }
    }

    fun cancel(escalationId: Long) {
        am.cancel(pendingIntent(escalationId))
    }

    // isLoud only carries informational extras for the receiver and never
    // affects PendingIntent matching (equality ignores extras), so cancel()
    // can keep passing the default and still target the same alarm.
    private fun pendingIntent(escalationId: Long, isLoud: Boolean = false): PendingIntent {
        val intent = Intent(ctx, EscalationReceiver::class.java).apply {
            action = "${ctx.packageName}.ESCALATION_FIRE"
            putExtra(IntentExtras.EXTRA_ESCALATION_ID, escalationId)
            putExtra(IntentExtras.EXTRA_IS_LOUD_STAGE, isLoud)
        }
        return PendingIntent.getBroadcast(
            ctx,
            escalationId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppPI(): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(ctx, "app.nock.android.ui.MainActivity")
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return PendingIntent.getActivity(
            ctx, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
