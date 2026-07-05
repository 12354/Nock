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

    /**
     * Per-slot amplitudes paired 1:1 with [toWaveform]'s timings for the
     * `createWaveform(timings, amplitudes, -1)` overload used on devices with
     * amplitude control. Off slots (the leading wait and the inter-pulse gaps,
     * i.e. the even indices) are silent (0); every pulse buzzes at full strength
     * ([MAX_AMPLITUDE]). Relying on `DEFAULT_AMPLITUDE` via the timings-only
     * overload leaves some vendors' vibrators silent, so we set it explicitly.
     */
    fun toAmplitudes(): IntArray {
        val timings = toWaveform()
        return IntArray(timings.size) { i -> if (i % 2 == 0) 0 else MAX_AMPLITUDE }
    }

    companion object {
        const val SHORT_MS = 180L
        const val LONG_MS = 600L
        const val GAP_MS = 220L

        /** Full vibrator strength for a pulse slot (`VibrationEffect` amplitude scale is 0..255). */
        const val MAX_AMPLITUDE = 255

        /** The default a regular reminder starts with: a single short tap. */
        val DEFAULT = VibrationPattern(listOf(VibrationPulse.SHORT))

        /** Upper bound on pulses so the editor and stored pattern stay sane. */
        const val MAX_PULSES = 12
    }
}
