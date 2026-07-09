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

    @Test fun decodes_legacy_normal_token_as_notification() {
        // A chain persisted by an older build named the audible heads-up stage
        // "NORMAL"; the enum now calls it NOTIFICATION. Decoding must map the
        // legacy token instead of throwing (which, at the group-override decode
        // site, would silently drop the whole chain and fall back to the loud
        // global default). This is the exact gentle "errands" chain from the
        // field report: a notification at the due time, ALARM only 10 min later.
        val json = """
            {"stages":[
              {"type":"SILENT","offsetMs":-600000},
              {"type":"NORMAL","offsetMs":0},
              {"type":"TELEGRAM","offsetMs":300000},
              {"type":"ALARM","offsetMs":600000}
            ],"repeatIntervalMs":600000}
        """.trimIndent()

        val chain = ChainJson.decode(json)

        assertEquals(
            listOf(
                StageConfig(StageType.SILENT, -600000L),
                StageConfig(StageType.NOTIFICATION, 0L),
                StageConfig(StageType.TELEGRAM, 300000L),
                StageConfig(StageType.ALARM, 600000L),
            ),
            chain.stages
        )
        // The stage due at the scheduled time is the gentle NOTIFICATION, not ALARM.
        assertEquals(StageType.NOTIFICATION, chain.stage(chain.stageDueAt(0L, 0L)).type)
    }

    @Test fun skips_unknown_stage_token_instead_of_dropping_chain() {
        // A single unrecognized token must not take valid stages down with it.
        val json = """
            {"stages":[
              {"type":"SILENT","offsetMs":-600000},
              {"type":"FLUX_CAPACITOR","offsetMs":-300000},
              {"type":"ALARM","offsetMs":0}
            ],"repeatIntervalMs":600000}
        """.trimIndent()

        val chain = ChainJson.decode(json)

        assertEquals(
            listOf(
                StageConfig(StageType.SILENT, -600000L),
                StageConfig(StageType.ALARM, 0L),
            ),
            chain.stages
        )
    }

    @Test fun all_unknown_tokens_throw_so_callers_fall_back() {
        // A chain with nothing decodable yields an empty stage list, which
        // EscalationChain rejects — callers (Mappers / engine) catch this and
        // fall back to the default chain rather than arming an empty one.
        val json = """{"stages":[{"type":"BOGUS","offsetMs":0}],"repeatIntervalMs":600000}"""
        assertThrows(IllegalArgumentException::class.java) { ChainJson.decode(json) }
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

    @Test fun stageDueAt_picks_vibrate_at_first_offset() {
        val chain = DefaultChain.CHAIN
        // 10 min before scheduled time -> VIBRATE stage (index 0)
        assertEquals(0, chain.stageDueAt(startedAtMs = 0L, nowMs = -10 * 60_000L))
        // 6 min before -> still VIBRATE (TELEGRAM is at -5 min)
        assertEquals(0, chain.stageDueAt(startedAtMs = 0L, nowMs = -6 * 60_000L))
    }

    @Test fun stageDueAt_picks_telegram_at_minus_5() {
        val chain = DefaultChain.CHAIN
        // 5 min before scheduled time -> TELEGRAM stage (index 1)
        assertEquals(1, chain.stageDueAt(startedAtMs = 0L, nowMs = -5 * 60_000L))
    }

    @Test fun stageDueAt_picks_alarm_vibrate_at_minus_2() {
        val chain = DefaultChain.CHAIN
        // 2 min before scheduled time -> ALARM_VIBRATE stage (index 2)
        assertEquals(2, chain.stageDueAt(startedAtMs = 0L, nowMs = -2 * 60_000L))
    }

    @Test fun stageDueAt_picks_alarm_at_scheduled_time() {
        val chain = DefaultChain.CHAIN
        // The ALARM rings exactly at the scheduled time (offset 0) -> last stage.
        assertEquals(3, chain.stageDueAt(startedAtMs = 0L, nowMs = 0L))
    }

    @Test fun stageDueAt_jumps_to_last_stage_when_very_late() {
        // Scheduled 09:50, now 10:06: elapsed = 16 min, well past ALARM (0 min).
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

    @Test fun firstPendingStage_far_future_starts_at_vibrate() {
        val chain = DefaultChain.CHAIN
        // Reminder a full day out: even the -10min VIBRATE pre-stage is still in
        // the future, so we start at stage 0 as normal.
        val now = 0L
        val scheduled = 24 * 60 * 60_000L
        assertEquals(0, chain.firstPendingStage(scheduled, now))
    }

    @Test fun firstPendingStage_skips_past_pre_stages_when_due_soon() {
        // Reminder due in 2min: the VIBRATE (-10) and TELEGRAM (-5) pre-stages
        // are already in the past. They must NOT all fire immediately (which
        // would mirror a Telegram); we start at the first stage that hasn't
        // passed (ALARM_VIBRATE at -2 min, due exactly now).
        val chain = DefaultChain.CHAIN
        val now = 0L
        val scheduled = 2 * 60_000L
        assertEquals(2, chain.firstPendingStage(scheduled, now))
    }

    @Test fun firstPendingStage_at_trigger_keeps_pre_stage_when_lead_time_fits() {
        // Reminder 15min out: the -10min VIBRATE pre-stage is still 5min away,
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
}
