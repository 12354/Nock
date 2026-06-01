package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmScheduler
import app.nock.android.data.NockRepository
import app.nock.android.data.SettingsRepository
import app.nock.android.data.entity.ActiveEscalationEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.escalation.EscalationEngine
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.DayOfWeek
import kotlin.math.max
import kotlin.random.Random

/**
 * Randomized, model-based fuzzer for the whole alarm lifecycle.
 *
 * It runs a fully simulated device:
 *  - the device clock is a [FakeTimeSource] that only ever moves forward;
 *  - [AlarmScheduler] is faked into an in-memory "pending alarms" table — the
 *    one place the OS would normally hold the next fire time;
 *  - [NockRepository] / [SettingsRepository] are MockK answer-backed by live
 *    in-memory maps so the engine reads/writes a real (if tiny) data store;
 *  - the escalation DAO is the real in-memory [FakeActiveEscalationDao].
 *
 * Each iteration the driver randomly: advances time (and fires whatever alarms
 * come due, optionally reacting with Done/Snooze); jumps the device date+time
 * forward or BACKWARD (an NTP/timezone/manual change, which triggers the app's
 * ACTION_TIME_CHANGED re-arm); delivers a stale/early alarm; adds a reminder;
 * snoozes or dismisses an active one; edits an alarm's own fire time/date (the
 * user moving when it should ring); or swaps a reminder's whole schedule.
 *
 * Oracles:
 *  - INV (consistency): after EVERY mutation, the set of active escalation rows
 *    is in bijection with the pending alarms, and each pending alarm's time and
 *    stage type exactly match its row. This is the engine's core promise.
 *  - Per-fire: every alarm we deliver is logged and independently checked — the
 *    stage that was shown must equal the stage the chain says is due at that
 *    instant. A delivery that arrives before its due time (clock moved back, or
 *    a stale PendingIntent) must NOT escalate — the engine's rewind guard re-arms
 *    instead, and we assert nothing was shown.
 *  - Per-escalation timeline (checked afterwards): for any single escalation the
 *    fired stage index is monotonically non-decreasing and within the chain
 *    (holds even across clock jumps, since each re-arm is floored to now + 1s).
 *  - Per-done (completion retires the occurrence): pressing Done on a fixed-slot
 *    calendar reminder (Daily/Weekly/Monthly) must arm a DIFFERENT occurrence. A
 *    row is keyed to its occurrence by startedAtMs, so after Done no surviving row
 *    for that reminder may still point at the completed trigger — otherwise the
 *    dismissed slot re-arms and rings again. This is the invariant the
 *    pre-trigger-window bug violated: Done before the due time re-armed the same
 *    occurrence, whose past pre-trigger stage made the chain jump straight to the
 *    alarm. (Completion-anchored IntervalFromLast is excluded — see doneAndAssert.)
 *
 * OnUnlock and group pausing are intentionally excluded here (they're event- /
 * window-gated rather than part of the time-driven fire loop) and are covered
 * exhaustively by the focused tests in this package.
 */
class EscalationEngineFuzzTest {

    private data class Pending(val atMs: Long, val type: StageType)

    private data class FiredEvent(
        val step: Int,
        val escalationId: Long,
        val reminderId: Long,
        val type: StageType,
        val stageIndex: Int,
        val dueAtMs: Long,
        val firedAtMs: Long,
    )

    @Test fun fuzz_alarm_lifecycle_across_many_random_runs() = runTest {
        var totalFired = 0
        for (seed in listOf(1L, 2L, 3L, 7L, 42L, 1234L, 99_999L, 2_026_05_30L)) {
            totalFired += World(seed).run(iterations = 500)
        }
        // Across all runs the simulated clock crosses many schedules, so a large
        // number of alarms must actually have fired (typically several thousand);
        // a low count means the harness silently did almost nothing.
        assertTrue("fuzzer delivered suspiciously few alarms ($totalFired)", totalFired > 500)
    }

    /** One isolated simulated device + engine for a single random seed. */
    private inner class World(private val seed: Long) {
        private val rnd = Random(seed)
        private val clock = FakeTimeSource(NOW)
        private val dao = FakeActiveEscalationDao()
        private val pendingDeletionDao = FakePendingTelegramDeletionDao()

        private val reminders = LinkedHashMap<Long, Reminder>()
        private val groups = LinkedHashMap<Long, Group>()
        private val pending = LinkedHashMap<Long, Pending>()
        private val displayed = ArrayList<Pair<Long, StageType>>()
        private val log = ArrayList<FiredEvent>()
        private var nextReminderId = 100L
        private var telegramMessageId = 1L
        private var step = -1

        // Mirrors EscalationEngine's private SANITY_TOLERANCE_MS: a delivery more
        // than this far ahead of the stage's due time is treated as a clock
        // rewind / stale fire and must re-arm instead of escalating.
        private val sanityToleranceMs = 1_000L

        private val repo: NockRepository = mockk(relaxed = true)
        private val settings: SettingsRepository = mockk(relaxed = true)
        private val scheduler: AlarmScheduler = mockk(relaxed = true)
        private val notifier: NotificationPresenter = mockk(relaxed = true)
        private val telegram: TelegramSender = mockk(relaxed = true)
        private val history: app.nock.android.history.AlarmHistoryLogger = mockk(relaxed = true)
        private val engine = EscalationEngine(repo, dao, settings, scheduler, notifier, telegram, clock, history, pendingDeletionDao)

        init {
            // A plain group, one that mirrors silent stages to Telegram, and one
            // with a custom (shorter) override chain — so rows carry different
            // chains and the oracle has to honour each row's own snapshot.
            groups[1] = group(id = 1, telegramSilentMirror = false)
            groups[2] = group(id = 2, telegramSilentMirror = true)
            groups[3] = group(
                id = 3,
                overrideChain = EscalationChain(
                    stages = listOf(
                        StageConfig(StageType.SILENT, -5 * MIN),
                        StageConfig(StageType.ALARM, 0L),
                    ),
                    repeatIntervalMs = 15 * MIN,
                ),
            )

            // --- Repository backed by live in-memory maps ---
            coEvery { repo.getReminder(any()) } answers { reminders[firstArg<Long>()] }
            coEvery { repo.getGroup(any()) } answers { groups[firstArg<Long>()] }
            coEvery { repo.getAllReminders() } answers { reminders.values.toList() }
            coEvery { repo.effectiveChain(any()) } answers {
                firstArg<Group>().overrideChain ?: TEST_CHAIN
            }
            coEvery { repo.updateFireState(any(), any(), any()) } answers {
                val id = firstArg<Long>()
                val next = secondArg<Long?>()
                val last = thirdArg<Long?>()
                reminders[id]?.let { reminders[id] = it.copy(nextFireAt = next, lastCompletedAt = last) }
            }
            coEvery { repo.deleteReminder(any()) } answers { reminders.remove(firstArg<Reminder>().id) }

            // --- Settings ---
            coEvery { settings.getStageChain() } returns TEST_CHAIN

            // --- AlarmScheduler faked into the pending table ---
            every { scheduler.scheduleStage(any(), any(), any()) } answers {
                pending[firstArg<Long>()] = Pending(secondArg<Long>(), thirdArg<StageType>())
            }
            every { scheduler.cancel(any()) } answers { pending.remove(firstArg<Long>()) }

            // --- Notifier records the single stage it displays per fire ---
            every { notifier.showSilent(any(), any(), any(), any()) } answers {
                val id = arg<Long>(2)
                val suffix = arg<String>(3)
                // SILENT shows no suffix; the TELEGRAM stage reuses the silent
                // notification with a " (Telegram sent)" suffix.
                displayed += id to if (suffix.isEmpty()) StageType.SILENT else StageType.TELEGRAM
            }
            every { notifier.showAlarmVibrate(any(), any(), any()) } answers {
                displayed += arg<Long>(2) to StageType.ALARM_VIBRATE
            }
            every { notifier.showAlarm(any(), any(), any()) } answers {
                displayed += arg<Long>(2) to StageType.ALARM
            }

            // --- Telegram returns increasing message ids; deletes are no-ops ---
            coEvery { telegram.send(any(), any()) } answers {
                TelegramResult(ok = true, messageId = telegramMessageId++)
            }
            coEvery { telegram.deleteMessage(any()) } returns true
        }

        /** Runs the random loop and returns how many alarms were delivered. */
        suspend fun run(iterations: Int): Int {
            repeat(3) { addReminder() } // seed the world
            assertConsistent("after seeding")

            for (s in 0 until iterations) {
                step = s
                when (rnd.nextInt(100)) {
                    in 0..32 -> tick()                 // wait forward, then fire/react
                    in 33..42 -> changeDeviceClock()   // jump device date+time (often backward)
                    in 43..50 -> spuriousDelivery()    // stale / too-early alarm delivery
                    in 51..61 -> addReminder()
                    in 62..72 -> snoozeRandomActive()
                    in 73..83 -> doneRandomActive()
                    in 84..91 -> retimeRandomAlarm()   // user edits the alarm's own time/date
                    else -> modifyRandomSchedule()     // user swaps the whole schedule
                }
                assertConsistent("after step $s (action complete)")
            }

            // Flush: jump a few days forward and deliver everything that comes due.
            clock.advanceBy(3L * 24 * 60 * MIN)
            drainDueAlarms(react = false)
            assertConsistent("after final flush")

            verifyTimeline()
            return log.size
        }

        // ----- Actions ---------------------------------------------------------

        private suspend fun tick() {
            clock.advanceBy(randomDelta())
            drainDueAlarms(react = true)
        }

        /**
         * Randomly move the device date+time — frequently backward — as an NTP
         * correction, timezone shift, or manual change would. The app re-arms
         * everything on ACTION_TIME_CHANGED, so we mirror that with rescheduleAll,
         * then deliver whatever is now due.
         */
        private suspend fun changeDeviceClock() {
            val delta = when (rnd.nextInt(6)) {
                0 -> -rnd.nextLong(1, 3) * 24 * 60 * MIN  // days backward
                1 -> -rnd.nextLong(1, 360) * MIN          // minutes/hours backward
                2 -> -rnd.nextLong(1, 120) * 1_000L       // seconds backward
                3 -> rnd.nextLong(1, 360) * MIN           // minutes/hours forward
                4 -> rnd.nextLong(1, 3) * 24 * 60 * MIN   // days forward
                else -> rnd.nextLong(1, 120) * 1_000L     // seconds forward
            }
            // Keep the clock comfortably positive even after big backward jumps.
            clock.set((clock.now + delta).coerceAtLeast(NOW - 30L * 24 * 60 * MIN))
            engine.rescheduleAll()
            drainDueAlarms(react = true)
        }

        /**
         * Deliver a pending alarm that may not be due yet — models a stale
         * PendingIntent re-firing or an alarm arriving after the clock was wound
         * back. The engine must detect the inconsistency and decline to escalate.
         */
        private suspend fun spuriousDelivery() {
            val id = pending.keys.toList().randomOrNull(rnd) ?: return
            deliver(id)
        }

        private suspend fun addReminder() {
            if (reminders.size >= 8) return
            val id = nextReminderId++
            val gid = listOf(1L, 2L, 3L).random(rnd)
            reminders[id] = reminder(
                id = id,
                groupId = gid,
                schedule = randomSchedule(),
                nextFireAt = null,
                lastCompletedAt = null,
                createdAt = clock.now,
            )
            engine.scheduleNextFireForReminder(id)
        }

        private suspend fun snoozeRandomActive() {
            val id = dao.rows.keys.toList().randomOrNull(rnd) ?: return
            engine.snooze(id)
        }

        private suspend fun doneRandomActive() {
            val id = dao.rows.keys.toList().randomOrNull(rnd) ?: return
            doneAndAssertAdvanced(id)
        }

        /**
         * Complete an escalation and assert the engine's completion contract for
         * fixed-slot calendar schedules: once Done is pressed, the occurrence just
         * dismissed must be RETIRED. A row is bound to its occurrence through
         * startedAtMs (the occurrence's scheduled wall-clock trigger), so no
         * escalation still armed for this reminder may point back at the completed
         * trigger — otherwise the dismissed slot re-arms and rings again.
         *
         * This is exactly the pre-trigger-window bug: pressing Done before the due
         * time made nextFireFrom(now) hand back today's slot again, and because its
         * pre-trigger (SILENT) stage was already in the past the re-armed chain
         * jumped straight to the alarm and rang anyway.
         *
         * Scoped to Daily/Weekly/Monthly on purpose. Those are the schedules whose
         * occurrence IS a fixed timestamp, so re-arming the identical trigger is
         * unambiguously wrong. Completion-anchored schedules (IntervalFromLast) are
         * excluded: their next occurrence is "now + interval", which can legitimately
         * coincide with the abandoned trigger (e.g. Done pressed the instant after
         * arming) without being a re-ring. Checked synchronously right after done()
         * so a later backward clock jump (which legitimately re-arms past
         * occurrences via rescheduleAll) can't muddy the result.
         */
        private suspend fun doneAndAssertAdvanced(id: Long) {
            val esc = dao.getById(id) ?: return
            val reminderId = esc.reminderId
            val schedule = reminders[reminderId]?.schedule
            val completedTrigger = esc.startedAtMs
            engine.done(id)
            val isFixedSlotCalendar = schedule is Schedule.Daily ||
                schedule is Schedule.Weekly ||
                schedule is Schedule.Monthly
            if (!isFixedSlotCalendar) return
            dao.rows.values
                .filter { it.reminderId == reminderId }
                .forEach { row ->
                    assertTrue(
                        "done() re-armed the completed occurrence for reminder $reminderId: " +
                            "row.startedAtMs=${row.startedAtMs} must differ from the completed " +
                            "trigger $completedTrigger ${ctx()}",
                        row.startedAtMs != completedTrigger,
                    )
                }
        }

        /**
         * The user edits when an existing alarm should fire — changing its
         * time-of-day, weekdays, day-of-month, interval, or one-shot instant —
         * while keeping the schedule kind. Saving re-arms it (cancel + re-arm)
         * exactly as a schedule swap does.
         */
        private suspend fun retimeRandomAlarm() {
            val id = reminders.keys.toList().randomOrNull(rnd) ?: return
            val current = reminders.getValue(id)
            reminders[id] = current.copy(schedule = retime(current.schedule))
            engine.scheduleNextFireForReminder(id)
        }

        private suspend fun modifyRandomSchedule() {
            val id = reminders.keys.toList().randomOrNull(rnd) ?: return
            reminders[id] = reminders.getValue(id).copy(schedule = randomSchedule())
            // scheduleNextFireForReminder cancels any prior active escalation and
            // re-arms from the new schedule.
            engine.scheduleNextFireForReminder(id)
        }

        // ----- Firing ----------------------------------------------------------

        private suspend fun drainDueAlarms(react: Boolean) {
            var guard = 0
            while (true) {
                val due = pending.entries
                    .filter { it.value.atMs <= clock.now }
                    .minByOrNull { it.value.atMs } ?: break
                val id = due.key
                deliver(id)
                assertConsistent("after firing $id")

                if (react && dao.rows.containsKey(id) && rnd.nextBoolean()) {
                    if (rnd.nextBoolean()) engine.snooze(id) else doneAndAssertAdvanced(id)
                    assertConsistent("after reacting to $id")
                }

                if (++guard > 100_000) fail("drain did not terminate (seed $seed step $step) — alarm refiring without advancing")
            }
        }

        private suspend fun deliver(id: Long) {
            val row = dao.getById(id) ?: return
            val reminder = reminders[row.reminderId]
            if (reminder == null) {
                // Engine self-heals (deletes the orphan row); not expected in this
                // world, but don't crash the fuzzer over it.
                engine.onAlarmFired(id)
                return
            }
            val chain = chainOf(row)
            val now = clock.now
            // The engine escalates only if the delivery is at/after the due time
            // (within a 1s slack); anything earlier hits the clock-rewind guard.
            val willFire = now >= row.nextFireAtMs - sanityToleranceMs

            val before = displayed.size
            engine.onAlarmFired(id)

            if (!willFire) {
                assertEquals(
                    "early/stale delivery of $id must not escalate (now=$now due=${row.nextFireAtMs}) ${ctx()}",
                    before, displayed.size,
                )
                return
            }

            // Independent re-derivation of which stage is due — the oracle.
            val storedIdx = row.nextStageIndex.coerceIn(0, chain.lastIndex)
            val dueIdx = chain.stageDueAt(row.startedAtMs, now)
            val idx = max(storedIdx, dueIdx).coerceAtMost(chain.lastIndex)
            val expected = chain.stage(idx).type

            assertEquals(
                "exactly one stage should display per fire of $id ${ctx()}",
                before + 1, displayed.size,
            )
            val (shownId, shownType) = displayed.last()
            assertEquals("displayed wrong escalation ${ctx()}", id, shownId)
            assertEquals(
                "displayed stage != due stage for $id at now=$now ${ctx()}",
                expected, shownType,
            )

            log += FiredEvent(step, id, reminder.id, shownType, idx, row.nextFireAtMs, now)
        }

        // ----- Oracles ---------------------------------------------------------

        /**
         * The invariant the engine must uphold at all times: active rows and
         * pending alarms are in one-to-one correspondence, and each pending
         * alarm's time and type mirror its row exactly.
         */
        private fun assertConsistent(where: String) {
            assertEquals(
                "rows vs pending alarms diverged ($where) ${ctx()}",
                dao.rows.keys.toSet(), pending.keys.toSet(),
            )
            for ((id, row) in dao.rows) {
                val p = pending.getValue(id)
                val chain = chainOf(row)
                val idx = row.nextStageIndex.coerceIn(0, chain.lastIndex)
                assertEquals(
                    "pending time != row.nextFireAtMs for $id ($where) ${ctx()}",
                    row.nextFireAtMs, p.atMs,
                )
                assertEquals(
                    "pending type != row stage type for $id ($where) ${ctx()}",
                    chain.stage(idx).type, p.type,
                )
            }
        }

        /** After the run, every alarm's history must be well-formed. */
        private fun verifyTimeline() {
            log.groupBy { it.escalationId }.forEach { (id, events) ->
                var prev = -1
                events.sortedBy { it.firedAtMs }.forEach { e ->
                    assertTrue(
                        "stage index regressed for escalation $id (seed $seed): $prev -> ${e.stageIndex}",
                        e.stageIndex >= prev,
                    )
                    assertTrue("negative stage index for $id (seed $seed)", e.stageIndex >= 0)
                    prev = e.stageIndex
                }
            }
        }

        // ----- Helpers ---------------------------------------------------------

        private fun chainOf(row: ActiveEscalationEntity): EscalationChain =
            runCatching { ChainJson.decode(row.chainSnapshotJson) }.getOrElse { TEST_CHAIN }

        private fun randomDelta(): Long = when (rnd.nextInt(10)) {
            in 0..3 -> rnd.nextLong(1, 30) * MIN              // minutes
            in 4..6 -> rnd.nextLong(30, 240) * MIN            // up to a few hours
            in 7..8 -> rnd.nextLong(1, 4) * 24 * 60 * MIN     // a few days
            else -> rnd.nextLong(1, 10) * 1_000L              // a few seconds
        }

        private fun randomSchedule(): Schedule = when (rnd.nextInt(5)) {
            0 -> Schedule.Daily(randomTimes())
            1 -> Schedule.Weekly(randomDays(), randomTimes())
            2 -> Schedule.Monthly(rnd.nextInt(1, 29), rnd.nextInt(0, 1440))
            3 -> Schedule.IntervalFromLast(rnd.nextLong(1, 600) * MIN)
            else -> Schedule.OneShot(clock.now + rnd.nextLong(-120, 6_000) * MIN)
        }

        /** Change only the time/date fields of a schedule, keeping its kind. */
        private fun retime(s: Schedule): Schedule = when (s) {
            is Schedule.Daily -> Schedule.Daily(randomTimes())
            is Schedule.Weekly -> Schedule.Weekly(randomDays(), randomTimes())
            is Schedule.Monthly -> Schedule.Monthly(rnd.nextInt(1, 29), rnd.nextInt(0, 1440))
            is Schedule.IntervalFromLast -> Schedule.IntervalFromLast(rnd.nextLong(1, 600) * MIN)
            is Schedule.OneShot -> Schedule.OneShot(clock.now + rnd.nextLong(-120, 6_000) * MIN)
            is Schedule.OnUnlock -> s // not produced in this fuzzer
        }

        private fun randomTimes(): List<Int> =
            generateSequence { rnd.nextInt(0, 1440) }
                .distinct()
                .take(rnd.nextInt(1, 3))
                .sorted()
                .toList()

        private fun randomDays(): Set<DayOfWeek> {
            val all = DayOfWeek.values().toList()
            return all.shuffled(rnd).take(rnd.nextInt(1, 8)).toSet()
        }

        /** Short context string for assertion failures. */
        private fun ctx(): String {
            val tail = log.takeLast(6).joinToString("; ") {
                "[s${it.step} esc${it.escalationId} ${it.type}@${it.firedAtMs}]"
            }
            return "(seed=$seed step=$step now=${clock.now} rows=${dao.rows.size} fired=${log.size} recent: $tail)"
        }
    }
}
