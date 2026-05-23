package app.nock.android.domain.model

data class Group(
    val id: Long,
    val name: String,
    val color: Int,
    val icon: String,
    val overrideChain: EscalationChain?,
    val pausedUntilMs: Long?,
    val telegramSilentMirror: Boolean,
    val seedKey: String? = null
) {
    fun isPaused(now: Long): Boolean = pausedUntilMs != null && pausedUntilMs > now
}

data class Reminder(
    val id: Long,
    val groupId: Long,
    val name: String,
    val schedule: Schedule,
    val nextFireAt: Long?,
    val lastCompletedAt: Long?,
    val createdAt: Long
)
