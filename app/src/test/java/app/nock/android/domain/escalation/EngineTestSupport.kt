package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmScheduler
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Reminder
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import app.nock.android.notif.NotificationPresenter
import app.nock.android.telegram.TelegramResult
import app.nock.android.telegram.TelegramSender
import io.mockk.coEvery
import io.mockk.mockk
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Convenience epoch-millis builder mirroring ScheduleTest.ms() for readable dates. */
fun epochMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    LocalDateTime.of(year, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

const val MIN = 60_000L

/** Fixed "current time" used as the baseline wall clock in most tests. */
val NOW: Long = epochMs(2026, 5, 30, 9, 0)

/**
 * The production default chain, but re-declared here with explicit offsets so the
 * tests document the exact timing they exercise and stay pinned even if the app
 * default changes.
 *   SILENT      @ -10 min (pre-trigger)
 *   TELEGRAM    @  +5 min
 *   ALARM_VIBRATE @ +8 min
 *   ALARM       @ +10 min (last)
 *   repeat       = 10 min
 */
val TEST_CHAIN = EscalationChain(
    stages = listOf(
        StageConfig(StageType.SILENT, -10 * MIN),
        StageConfig(StageType.TELEGRAM, 5 * MIN),
        StageConfig(StageType.ALARM_VIBRATE, 8 * MIN),
        StageConfig(StageType.ALARM, 10 * MIN),
    ),
    repeatIntervalMs = 10 * MIN
)

const val GROUP_ID = 7L
const val REMINDER_ID = 42L

fun group(
    id: Long = GROUP_ID,
    overrideChain: EscalationChain? = null,
    pausedUntilMs: Long? = null,
    telegramSilentMirror: Boolean = false,
): Group = Group(
    id = id,
    name = "Group $id",
    color = 0,
    icon = "bell",
    overrideChain = overrideChain,
    pausedUntilMs = pausedUntilMs,
    telegramSilentMirror = telegramSilentMirror,
)

fun reminder(
    id: Long = REMINDER_ID,
    groupId: Long = GROUP_ID,
    schedule: Schedule = Schedule.Daily(listOf(9 * 60)),
    nextFireAt: Long? = null,
    lastCompletedAt: Long? = null,
    createdAt: Long = NOW,
): Reminder = Reminder(
    id = id,
    groupId = groupId,
    name = "Reminder $id",
    schedule = schedule,
    nextFireAt = nextFireAt,
    lastCompletedAt = lastCompletedAt,
    createdAt = createdAt,
)

/** Build an active-escalation row already serialized with [chain]. */
fun activeEntity(
    id: Long = 1L,
    reminderId: Long = REMINDER_ID,
    startedAtMs: Long = NOW,
    nextStageIndex: Int,
    nextFireAtMs: Long,
    chain: EscalationChain = TEST_CHAIN,
    sentTelegramMessageIdsCsv: String = "",
): ActiveEscalationEntity = ActiveEscalationEntity(
    id = id,
    reminderId = reminderId,
    startedAtMs = startedAtMs,
    nextStageIndex = nextStageIndex,
    nextFireAtMs = nextFireAtMs,
    chainSnapshotJson = ChainJson.encode(chain),
    repeatIntervalMs = chain.repeatIntervalMs,
    sentTelegramMessageIdsCsv = sentTelegramMessageIdsCsv,
)

/**
 * Wires an [EscalationEngine] with relaxed MockK mocks for the final-class
 * collaborators, a real in-memory DAO, and a [FakeTimeSource]. Returns the
 * engine plus the handles tests assert against.
 */
class EngineHarness(now: Long = NOW) {
    val clock = FakeTimeSource(now)
    val dao = FakeActiveEscalationDao()
    val repo: NockRepository = mockk(relaxed = true)
    val settings: SettingsRepository = mockk(relaxed = true)
    val scheduler: AlarmScheduler = mockk(relaxed = true)
    val notifier: NotificationPresenter = mockk(relaxed = true)
    val telegram: TelegramSender = mockk(relaxed = true)

    val engine = app.nock.android.domain.escalation.EscalationEngine(
        repo, dao, settings, scheduler, notifier, telegram, clock
    )

    init {
        // Sensible defaults; individual tests override as needed.
        coEvery { settings.getStageChain() } returns TEST_CHAIN
        coEvery { repo.effectiveChain(any()) } returns TEST_CHAIN
        coEvery { telegram.send(any(), any()) } returns TelegramResult(ok = true, messageId = null)
        coEvery { telegram.deleteMessage(any()) } returns true
    }

    /** Stub a single reminder + its group lookup. */
    fun stubReminderAndGroup(reminder: Reminder, group: Group) {
        coEvery { repo.getReminder(reminder.id) } returns reminder
        coEvery { repo.getGroup(group.id) } returns group
    }
}
