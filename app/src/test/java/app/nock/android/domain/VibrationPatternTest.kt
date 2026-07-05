package app.nock.android.domain

import app.nock.android.data.json.VibrationPatternJson
import app.nock.android.domain.model.VibrationPattern
import app.nock.android.domain.model.VibrationPulse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VibrationPatternTest {

    @Test fun single_short_pulse_waveform_is_wait_then_on() {
        val wf = VibrationPattern(listOf(VibrationPulse.SHORT)).toWaveform()
        // [initial wait, short on]
        assertArrayEquals(longArrayOf(0L, VibrationPattern.SHORT_MS), wf)
    }

    @Test fun amplitudes_pair_with_waveform_off_slots_silent_on_slots_full() {
        val p = VibrationPattern(listOf(VibrationPulse.SHORT, VibrationPulse.LONG))
        val wf = p.toWaveform()
        val amps = p.toAmplitudes()
        // One amplitude per timing slot, off (even) silent, on (odd) full strength.
        assertEquals(wf.size, amps.size)
        assertArrayEquals(
            intArrayOf(0, VibrationPattern.MAX_AMPLITUDE, 0, VibrationPattern.MAX_AMPLITUDE),
            amps
        )
    }

    @Test fun multi_pulse_waveform_interleaves_gaps_between_pulses_only() {
        val wf = VibrationPattern(
            listOf(VibrationPulse.SHORT, VibrationPulse.LONG, VibrationPulse.SHORT)
        ).toWaveform()
        // wait, on, gap, on, gap, on — no trailing gap, no leading gap.
        assertArrayEquals(
            longArrayOf(
                0L,
                VibrationPattern.SHORT_MS,
                VibrationPattern.GAP_MS,
                VibrationPattern.LONG_MS,
                VibrationPattern.GAP_MS,
                VibrationPattern.SHORT_MS,
            ),
            wf
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun empty_pattern_is_rejected() {
        VibrationPattern(emptyList())
    }

    @Test fun csv_roundtrips() {
        val p = VibrationPattern(listOf(VibrationPulse.LONG, VibrationPulse.SHORT, VibrationPulse.LONG))
        val csv = VibrationPatternJson.encode(p)
        assertEquals("LONG,SHORT,LONG", csv)
        assertEquals(p, VibrationPatternJson.decode(csv))
    }

    @Test fun blank_or_garbage_csv_decodes_to_null() {
        assertNull(VibrationPatternJson.decode(null))
        assertNull(VibrationPatternJson.decode(""))
        assertNull(VibrationPatternJson.decode("   "))
        assertNull(VibrationPatternJson.decode("MEDIUM,HUGE"))
    }

    @Test fun garbage_tokens_are_dropped_but_valid_ones_kept() {
        assertEquals(
            VibrationPattern(listOf(VibrationPulse.SHORT, VibrationPulse.LONG)),
            VibrationPatternJson.decode("SHORT, bogus ,LONG")
        )
    }
}
