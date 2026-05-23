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
                StageConfig(StageType.NORMAL, 0L),
            ),
            repeatIntervalMs = 15 * 60_000L
        )
        assertEquals(1, chain.lastIndex)
    }
}
