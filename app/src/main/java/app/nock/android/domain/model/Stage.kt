package app.nock.android.domain.model

enum class StageType { SILENT, TELEGRAM, VIBRATE, NOTIFICATION, ALARM_VIBRATE, ALARM }

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
    /**
     * Alarm-clock-shaped escalation: the louder stages build up *before* the
     * scheduled time so the final ALARM rings exactly at the task's time
     * (offset 0), like a normal alarm clock — just with gentler nudges leading
     * up to it. Everything before the ALARM is a lead-up warning:
     *
     *   VIBRATE        @ -10 min  silent heads-up that buzzes the phone
     *   TELEGRAM       @  -5 min  silent notification + message mirrored to Telegram
     *   ALARM_VIBRATE  @  -2 min  vibrate-only alarm (last quiet warning)
     *   ALARM          @   0 min  full loud alarm, rings at the scheduled time
     *
     * (A NOTIFICATION stage — an audible heads-up on the phone — is available to
     * add but isn't in the default lead-up.)
     *
     * If the alarm goes unacknowledged it keeps re-ringing every repeat
     * interval until the user marks it done.
     */
    val CHAIN = EscalationChain(
        stages = listOf(
            StageConfig(StageType.VIBRATE, -10 * 60_000L),
            StageConfig(StageType.TELEGRAM, -5 * 60_000L),
            StageConfig(StageType.ALARM_VIBRATE, -2 * 60_000L),
            StageConfig(StageType.ALARM, 0L),
        ),
        repeatIntervalMs = 10 * 60_000L
    )
}
