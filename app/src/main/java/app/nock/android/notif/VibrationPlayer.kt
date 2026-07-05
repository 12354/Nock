package app.nock.android.notif

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService
import app.nock.android.domain.model.VibrationPattern
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a one-shot [VibrationPattern] on the device vibrator. Used by regular
 * reminders (a single gentle nudge, no escalation) and by the editor's "test"
 * preview. Distinct from [app.nock.android.alarm.AlarmService]'s looping alarm
 * vibration: this fires the pattern exactly once and never repeats.
 */
@Singleton
class VibrationPlayer @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    fun play(pattern: VibrationPattern) {
        val v = vibrator() ?: return
        if (!v.hasVibrator()) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // repeat = -1: play through once and stop. Drive explicit per-slot
            // amplitudes where the vibrator supports them — the timings-only
            // overload leans on DEFAULT_AMPLITUDE, which leaves some vendors'
            // vibrators silent (the exact "doesn't buzz" symptom this fixes). The
            // looping alarm path (AlarmService) uses the same amplitude handling.
            val timings = pattern.toWaveform()
            val effect = if (v.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(timings, pattern.toAmplitudes(), -1)
            } else {
                VibrationEffect.createWaveform(timings, -1)
            }
            v.vibrate(effect, attrs)
        } catch (_: Throwable) {
            // A vendor vibrator that rejects the effect must not crash the fire path.
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
