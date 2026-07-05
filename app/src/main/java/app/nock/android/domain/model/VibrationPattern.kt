package app.nock.android.domain.model

/** A single buzz in a [VibrationPattern]: a brief tap or a longer pulse. */
enum class VibrationPulse { SHORT, LONG }

/**
 * An ordered arrangement of short and long buzzes played once when a "regular"
 * reminder fires. A regular reminder is a single gentle nudge — no escalation
 * chain, no alarm, no Done required — so the pattern is the whole notification.
 * It's a per-reminder setting the user arranges by adding short/long pulses.
 */
data class VibrationPattern(val pulses: List<VibrationPulse>) {
    init {
        require(pulses.isNotEmpty()) { "Vibration pattern must contain at least one pulse" }
    }

    /**
     * Waveform timings for `VibrationEffect.createWaveform(timings, -1)`: an
     * alternating off/on list that starts with a 0 ms wait, then renders each
     * pulse as (gap, on-duration). The leading 0 keeps the first pulse immediate;
     * every later pulse is separated from the previous one by [GAP_MS].
     */
    fun toWaveform(): LongArray {
        val timings = ArrayList<Long>(pulses.size * 2 + 1)
        timings.add(0L) // initial wait — the first entry is always an "off" slot
        pulses.forEachIndexed { i, pulse ->
            if (i > 0) timings.add(GAP_MS)
            timings.add(if (pulse == VibrationPulse.LONG) LONG_MS else SHORT_MS)
        }
        return timings.toLongArray()
    }

    companion object {
        const val SHORT_MS = 180L
        const val LONG_MS = 600L
        const val GAP_MS = 220L

        /** The default a regular reminder starts with: a single short tap. */
        val DEFAULT = VibrationPattern(listOf(VibrationPulse.SHORT))

        /** Upper bound on pulses so the editor and stored pattern stay sane. */
        const val MAX_PULSES = 12
    }
}
