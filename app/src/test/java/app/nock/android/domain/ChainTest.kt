package app.nock.android.domain

import app.nock.android.data.json.ChainJson
import app.nock.android.domain.model.DefaultChain
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun firstPendingStage_far_future_starts_at_silent() {
        val chain = DefaultChain.CHAIN
        // Reminder a full day out: even the -10min SILENT pre-stage is still in
        // the future, so we start at stage 0 as normal.
        val now = 0L
        val scheduled = 24 * 60 * 60_000L
        assertEquals(0, chain.firstPendingStage(scheduled, now))
    }

    @Test fun firstPendingStage_skips_past_pre_stage_when_due_soon() {
        // The bug: reminder due in 2min, SILENT pre-stage at -10min is already
        // 8min in the past. It must NOT fire immediately (which would mirror a
        // Telegram); we start at the first stage that hasn't passed (TELEGRAM).
        val chain = DefaultChain.CHAIN
        val now = 0L
        val scheduled = 2 * 60_000L
        assertEquals(1, chain.firstPendingStage(scheduled, now))
    }

    @Test fun firstPendingStage_at_trigger_keeps_pre_stage_when_lead_time_fits() {
        // Reminder 15min out: the -10min SILENT pre-stage is still 5min away,
        // so it is genuinely pending and we keep it.
        val chain = DefaultChain.CHAIN
        val now = 0L
        val scheduled = 15 * 60_000L
        assertEquals(0, chain.firstPendingStage(scheduled, now))
    }

    @Test fun firstPendingStage_falls_back_to_last_when_all_past() {
        // Degenerate guard: every stage already in the past -> last stage.
        val chain = DefaultChain.CHAIN
        assertEquals(chain.lastIndex, chain.firstPendingStage(0L, 24 * 60 * 60_000L))
    }

    @Test fun hasStartedFiring_false_for_reminder_armed_for_the_future() {
        // The repeating-task case: a daily reminder just advanced by Done is armed
        // for tomorrow. Even its -10min SILENT pre-stage is far in the future, so the
        // escalation has NOT started — it must stay in the upcoming list, not vanish.
        val chain = DefaultChain.CHAIN
        val now = 0L
        val startedTomorrow = 24 * 60 * 60_000L
        assertFalse(chain.hasStartedFiring(startedTomorrow, now))
    }

    @Test fun hasStartedFiring_true_once_first_pre_stage_is_due() {
        // Reminder due in 5min: the -10min SILENT pre-stage is already due, so the
        // escalation has begun and the reminder is genuinely firing.
        val chain = DefaultChain.CHAIN
        val now = 0L
        val started = 5 * 60_000L
        assertTrue(chain.hasStartedFiring(started, now))
    }

    @Test fun hasStartedFiring_true_at_and_after_trigger() {
        val chain = DefaultChain.CHAIN
        assertTrue(chain.hasStartedFiring(startedAtMs = 0L, nowMs = 0L))
        assertTrue(chain.hasStartedFiring(startedAtMs = 0L, nowMs = 30 * 60_000L))
    }
}
