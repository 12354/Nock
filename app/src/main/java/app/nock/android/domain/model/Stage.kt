package app.nock.android.domain.model

enum class StageType { SILENT, TELEGRAM, ALARM_VIBRATE, ALARM }

data class StageConfig(
    val type: StageType,
    val offsetMs: Long
)

data class EscalationChain(
    val stages: List<StageConfig>,
    val repeatIntervalMs: Long
) {
    init {
        require(stages.isNotEmpty()) { "Chain must contain at least one stage" }
        require(stages.map { it.type }.toSet().size == stages.size) {
            "Each stage type may appear at most once"
        }
    }

    val lastIndex: Int get() = stages.size - 1

    fun stage(index: Int): StageConfig = stages[index]

    /**
     * Index of the latest stage whose offset is <= elapsed time since startedAt.
     * If no stage has yet come due (we're earlier than every offset), returns 0
     * so the chain starts at the earliest stage rather than failing.
     */
    fun stageDueAt(startedAtMs: Long, nowMs: Long): Int {
        val elapsed = nowMs - startedAtMs
        var idx = 0
        for (i in stages.indices) {
            if (stages[i].offsetMs <= elapsed) idx = i
        }
        return idx
    }
}

object DefaultChain {
    val CHAIN = EscalationChain(
        stages = listOf(
            StageConfig(StageType.SILENT, -10 * 60_000L),
            StageConfig(StageType.TELEGRAM, 5 * 60_000L),
            StageConfig(StageType.ALARM_VIBRATE, 8 * 60_000L),
            StageConfig(StageType.ALARM, 10 * 60_000L),
        ),
        repeatIntervalMs = 10 * 60_000L
    )
}
