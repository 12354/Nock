package app.nock.android.domain.model

enum class StageType { SILENT, NORMAL, TELEGRAM, ALARM }

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
}

object DefaultChain {
    val CHAIN = EscalationChain(
        stages = listOf(
            StageConfig(StageType.SILENT, -10 * 60_000L),
            StageConfig(StageType.NORMAL, 0L),
            StageConfig(StageType.TELEGRAM, 5 * 60_000L),
            StageConfig(StageType.ALARM, 10 * 60_000L),
        ),
        repeatIntervalMs = 10 * 60_000L
    )
}
