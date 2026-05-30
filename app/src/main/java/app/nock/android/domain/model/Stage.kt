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

    /**
     * Whether an escalation started at [startedAtMs] has actually begun firing by
     * [nowMs] — i.e. its earliest stage is due. Stages are ordered by ascending
     * offset, so the first stage's offset is the chain's lead time (it may be
     * negative for a pre-trigger stage). A reminder that is only *armed* for a
     * future occurrence has not started firing yet; callers use this to keep such
     * reminders in the upcoming list instead of treating them as "firing now".
     */
    fun hasStartedFiring(startedAtMs: Long, nowMs: Long): Boolean =
        nowMs >= startedAtMs + stages.first().offsetMs

    /**
     * Index of the earliest stage whose absolute fire time (startedAt + offset)
     * has not yet passed at [nowMs]. Used when arming a reminder whose trigger
     * is still in the future: pre-trigger stages (negative offsets) may already
     * be in the past if the reminder is due sooner than a stage's lead time, and
     * those must be skipped so they don't all fire at once — and re-send a
     * Telegram — the moment the reminder is saved/moved. Falls back to the last
     * stage if every stage is already in the past.
     */
    fun firstPendingStage(startedAtMs: Long, nowMs: Long): Int {
        val idx = stages.indexOfFirst { startedAtMs + it.offsetMs >= nowMs }
        return if (idx >= 0) idx else lastIndex
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
