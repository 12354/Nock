package app.nock.android.domain

import app.nock.android.data.json.ChainJson
import app.nock.android.domain.model.DefaultChain
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChainTest {

    @Test fun roundtrip_default_chain() {
        val json = ChainJson.encode(DefaultChain.CHAIN)
        val parsed = ChainJson.decode(json)
        assertEquals(DefaultChain.CHAIN.stages.size, parsed.stages.size)
        assertEquals(DefaultChain.CHAIN.stages, parsed.stages)
        assertEquals(DefaultChain.CHAIN.repeatIntervalMs, parsed.repeatIntervalMs)
    }

    @Test fun cannot_repeat_a_stage_type() {
        assertThrows(IllegalArgumentException::class.java) {
            EscalationChain(
                stages = listOf(
                    StageConfig(StageType.SILENT, -10 * 60_000L),
                    StageConfig(StageType.SILENT, 0L)
                ),
                repeatIntervalMs = 10 * 60_000L
            )
        }
    }

    @Test fun supports_partial_chain() {
        val chain = EscalationChain(
            stages = listOf(
                StageConfig(StageType.SILENT, -5 * 60_000L),
                StageConfig(StageType.ALARM, 0L),
            ),
            repeatIntervalMs = 15 * 60_000L
        )
        assertEquals(1, chain.lastIndex)
    }

    @Test fun stageDueAt_returns_0_before_first_stage() {
        // Scheduled at t=0, now at t=-30min (chain not yet at first offset of -10min)
        val chain = DefaultChain.CHAIN
        assertEquals(0, chain.stageDueAt(startedAtMs = 0L, nowMs = -30 * 60_000L))
    }

    @Test fun stageDueAt_picks_silent_when_elapsed_matches_first_offset() {
        val chain = DefaultChain.CHAIN
        // 10 min before scheduled time -> SILENT stage (index 0)
        assertEquals(0, chain.stageDueAt(startedAtMs = 0L, nowMs = -10 * 60_000L))
        // Right before TELEGRAM (still SILENT)
        assertEquals(0, chain.stageDueAt(startedAtMs = 0L, nowMs = -1L))
    }

    @Test fun stageDueAt_picks_telegram_at_plus_5() {
        val chain = DefaultChain.CHAIN
        assertEquals(1, chain.stageDueAt(startedAtMs = 0L, nowMs = 5 * 60_000L))
    }

    @Test fun stageDueAt_picks_alarm_vibrate_at_plus_8() {
        val chain = DefaultChain.CHAIN
        assertEquals(2, chain.stageDueAt(startedAtMs = 0L, nowMs = 8 * 60_000L))
    }

    @Test fun stageDueAt_picks_alarm_at_plus_10() {
        val chain = DefaultChain.CHAIN
        assertEquals(3, chain.stageDueAt(startedAtMs = 0L, nowMs = 10 * 60_000L))
    }

    @Test fun stageDueAt_jumps_to_last_stage_when_very_late() {
        // The exact scenario from the user's report: scheduled 09:50, now 10:06.
        // Elapsed = 16 min, well past ALARM (+10 min).
        val chain = DefaultChain.CHAIN
        val scheduled = 0L
        val now = 16 * 60_000L
        assertEquals(3, chain.stageDueAt(scheduled, now))
    }

    @Test fun stageDueAt_caps_at_last_stage_for_arbitrarily_late_fire() {
        val chain = DefaultChain.CHAIN
        // 24h late still maps to the last (ALARM) stage, not an OOB index.
        assertEquals(chain.lastIndex, chain.stageDueAt(0L, 24 * 60 * 60_000L))
    }
}
