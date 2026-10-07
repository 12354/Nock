package app.nock.android.domain.escalation

import app.nock.android.alarm.AlarmService
import app.nock.android.domain.model.StageType
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Pause while a Bluetooth device is connected" (e.g. driving): due stages are
 * held silently while the gate is active and fire — at the stage due by then —
 * once it lifts.
 */
class EscalationEngineBluetoothPauseTest {

    private fun setRinging(id: Long?) {
        val field = AlarmService::class.java.getDeclaredField("ringingEscalationId")
        field.isAccessible = true
        field.set(null, id)
    }

    @After fun resetRinging() = setRinging(null)

    private val recheck = EscalationEngine.BLUETOOTH_RECHECK_MS

    private fun pausedHarness(): EngineHarness {
        val h = EngineHarness(now = NOW)
        h.stubReminderAndGroup(reminder(), group())
        h.bluetoothPause.active = true
        return h
    }

    @Test fun due_stage_is_held_while_paused() = runTest {
        val h = pausedHarness()
        // The loud ALARM stage is due now.
        val row = activeEntity(
            startedAtMs = NOW - 10 * MIN,
            nextStageIndex = 3,
            nextFireAtMs = NOW,
            sentTelegramMessageIdsCsv = "",
        )
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        verify(exactly = 0) { h.notifier.showAlarm(any(), any(), any()) }
        coVerify(exactly = 0) { h.telegram.send(any(), any()) }
        // Re-armed for a recheck, still as a loud stage, cursor untouched.
        verify { h.scheduler.scheduleStage(row.id, NOW + recheck, StageType.ALARM) }
        val stored = h.dao.getById(row.id)!!
        assertEquals(3, stored.nextStageIndex)
        assertEquals(NOW + recheck, stored.nextFireAtMs)
        assertEquals(mapOf(row.id to NOW + recheck), h.bluetoothPause.deferred)
    }

    @Test fun telegram_stage_is_not_sent_while_paused() = runTest {
        val h = pausedHarness()
        val row = activeEntity(startedAtMs = NOW - 5 * MIN, nextStageIndex = 1, nextFireAtMs = NOW)
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        coVerify(exactly = 0) { h.telegram.send(any(), any()) }
        verify(exactly = 0) { h.notifier.showTelegram(any(), any(), any()) }
    }

    @Test fun recheck_while_still_paused_holds_again() = runTest {
        val h = pausedHarness()
        val row = activeEntity(startedAtMs = NOW - 10 * MIN, nextStageIndex = 3, nextFireAtMs = NOW)
        h.dao.upsert(row)
        h.engine.onAlarmFired(row.id)

        h.clock.advanceBy(recheck)
        h.engine.onAlarmFired(row.id)

        verify(exactly = 0) { h.notifier.showAlarm(any(), any(), any()) }
        assertEquals(NOW + 2 * recheck, h.dao.getById(row.id)!!.nextFireAtMs)
    }

    @Test fun resume_brings_held_escalation_forward_at_the_stage_due_now() = runTest {
        val h = pausedHarness()
        // SILENT (index 0) was due when the car was connected.
        val row = activeEntity(startedAtMs = NOW + 10 * MIN, nextStageIndex = 0, nextFireAtMs = NOW)
        h.dao.upsert(row)
        h.engine.onAlarmFired(row.id)
        verify(exactly = 0) { h.notifier.showSilent(any(), any(), any()) }

        // Disconnect 30 min later: the timeline has reached the loud ALARM stage.
        h.clock.advanceBy(30 * MIN)
        h.bluetoothPause.active = false
        clearMocks(h.scheduler)
        h.engine.resumeAfterBluetoothPause()

        val fireAt = h.clock.now + 1_000L
        verify { h.scheduler.scheduleStage(row.id, fireAt, StageType.ALARM) }
        assertEquals(fireAt, h.dao.getById(row.id)!!.nextFireAtMs)
        assertTrue(h.bluetoothPause.deferred.isEmpty())

        h.clock.set(fireAt)
        h.engine.onAlarmFired(row.id)
        verify { h.notifier.showAlarm(any(), any(), row.id) }
        verify(exactly = 0) { h.notifier.showSilent(any(), any(), any()) }
    }

    @Test fun resume_does_nothing_while_another_selected_device_is_connected() = runTest {
        val h = pausedHarness()
        val row = activeEntity(startedAtMs = NOW - 10 * MIN, nextStageIndex = 3, nextFireAtMs = NOW)
        h.dao.upsert(row)
        h.engine.onAlarmFired(row.id)
        clearMocks(h.scheduler)

        h.engine.resumeAfterBluetoothPause()

        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
        assertEquals(NOW + recheck, h.dao.getById(row.id)!!.nextFireAtMs)
        assertEquals(1, h.bluetoothPause.deferred.size)
    }

    @Test fun resume_leaves_an_escalation_that_changed_since_it_was_held() = runTest {
        val h = pausedHarness()
        val row = activeEntity(startedAtMs = NOW - 10 * MIN, nextStageIndex = 3, nextFireAtMs = NOW)
        h.dao.upsert(row)
        h.engine.onAlarmFired(row.id)
        // The user snoozed from the still-posted notification meanwhile.
        val snoozedTo = NOW + 10 * MIN
        h.dao.update(h.dao.getById(row.id)!!.copy(nextFireAtMs = snoozedTo))
        h.bluetoothPause.active = false
        clearMocks(h.scheduler)

        h.engine.resumeAfterBluetoothPause()

        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
        assertEquals(snoozedTo, h.dao.getById(row.id)!!.nextFireAtMs)
    }

    @Test fun connecting_silences_a_ringing_alarm_and_its_repeat_is_held() = runTest {
        val h = pausedHarness()
        val repeatAt = NOW + 10 * MIN // last stage re-armed for its repeat
        val row = activeEntity(startedAtMs = NOW - 10 * MIN, nextStageIndex = 3, nextFireAtMs = repeatAt)
        h.dao.upsert(row)
        setRinging(row.id)

        h.engine.onBluetoothPauseStarted()

        verify { h.notifier.stopAlarm() }
        // The row keeps its repeat; nothing is re-armed early.
        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
        assertEquals(repeatAt, h.dao.getById(row.id)!!.nextFireAtMs)

        // The repeat comes due while still connected: held, not rung.
        h.clock.set(repeatAt)
        h.engine.onAlarmFired(row.id)
        verify(exactly = 0) { h.notifier.showAlarm(any(), any(), any()) }
        assertEquals(mapOf(row.id to repeatAt + recheck), h.bluetoothPause.deferred)
    }

    @Test fun connecting_with_nothing_ringing_changes_nothing() = runTest {
        val h = pausedHarness()
        val row = activeEntity(nextStageIndex = 0, nextFireAtMs = NOW + 10 * MIN)
        h.dao.upsert(row)

        h.engine.onBluetoothPauseStarted()

        verify(exactly = 0) { h.notifier.stopAlarm() }
        verify(exactly = 0) { h.scheduler.scheduleStage(any(), any(), any()) }
    }

    @Test fun no_pause_fires_normally() = runTest {
        val h = EngineHarness(now = NOW)
        h.stubReminderAndGroup(reminder(), group())
        val row = activeEntity(startedAtMs = NOW - 10 * MIN, nextStageIndex = 3, nextFireAtMs = NOW)
        h.dao.upsert(row)

        h.engine.onAlarmFired(row.id)

        verify { h.notifier.showAlarm(any(), any(), row.id) }
        assertTrue(h.bluetoothPause.deferred.isEmpty())
    }

    // --- Collected alarm -----------------------------------------------------

    private val otherId = 43L

    /**
     * Two reminders held during one drive: [REMINDER_ID] (row 1) reached only the
     * SILENT stage, [otherId] (row 2) is at the loud ALARM — so row 2 must lead.
     */
    private suspend fun twoHeld(h: EngineHarness): Pair<Long, Long> {
        h.stubReminderAndGroup(reminder(id = otherId), group())
        val quiet = activeEntity(id = 101L, reminderId = REMINDER_ID, startedAtMs = NOW + 10 * MIN, nextStageIndex = 0, nextFireAtMs = NOW)
        val loud = activeEntity(id = 102L, reminderId = otherId, startedAtMs = NOW - 10 * MIN, nextStageIndex = 3, nextFireAtMs = NOW)
        h.dao.upsert(quiet)
        h.dao.upsert(loud)
        h.engine.onAlarmFired(quiet.id)
        h.engine.onAlarmFired(loud.id)
        h.bluetoothPause.active = false
        clearMocks(h.scheduler, h.notifier)
        return quiet.id to loud.id
    }

    @Test fun several_held_alarms_are_released_as_one_collected_alarm() = runTest {
        val h = pausedHarness()
        val (quiet, loud) = twoHeld(h)

        h.engine.resumeAfterBluetoothPause()

        // Only the lead is armed; the follower's own alarm and notification go.
        verify { h.scheduler.scheduleStage(loud, NOW + 1_000L, StageType.ALARM) }
        verify(exactly = 0) { h.scheduler.scheduleStage(quiet, any(), any()) }
        verify { h.scheduler.cancel(quiet) }
        verify { h.notifier.cancel(quiet) }
        assertEquals(mapOf(quiet to loud), h.bluetoothPause.collected)

        // The lead rings once, under a title naming both reminders.
        h.clock.set(NOW + 1_000L)
        h.engine.onAlarmFired(loud)
        verify(exactly = 1) {
            h.notifier.showAlarm(match { it.name == "Reminder $otherId · Reminder $REMINDER_ID" }, any(), loud)
        }
        verify(exactly = 0) { h.notifier.showSilent(any(), any(), any()) }
    }

    @Test fun a_stray_alarm_for_a_follower_does_not_ring() = runTest {
        val h = pausedHarness()
        val (quiet, _) = twoHeld(h)
        h.engine.resumeAfterBluetoothPause()

        h.engine.onAlarmFired(quiet)

        verify(exactly = 0) { h.notifier.showSilent(any(), any(), any()) }
        verify(exactly = 0) { h.scheduler.scheduleStage(quiet, any(), any()) }
    }

    @Test fun done_on_the_collected_alarm_completes_every_reminder_in_it() = runTest {
        val h = pausedHarness()
        val (quiet, loud) = twoHeld(h)
        h.engine.resumeAfterBluetoothPause()

        h.engine.done(loud)

        // Both daily reminders moved on to their next occurrence (tomorrow).
        assertTrue(h.dao.getByReminderId(REMINDER_ID)!!.startedAtMs > NOW + 10 * MIN)
        assertTrue(h.dao.getByReminderId(otherId)!!.startedAtMs > NOW + 10 * MIN)
        assertTrue(h.bluetoothPause.collected.isEmpty())
        coVerify { h.history.done("Reminder $REMINDER_ID") }
        coVerify { h.history.done("Reminder $otherId") }
    }

    @Test fun snooze_on_the_collected_alarm_keeps_the_collection() = runTest {
        val h = pausedHarness()
        val (quiet, loud) = twoHeld(h)
        h.engine.resumeAfterBluetoothPause()

        h.engine.snooze(loud)

        assertEquals(mapOf(quiet to loud), h.bluetoothPause.collected)
        verify(exactly = 0) { h.scheduler.scheduleStage(quiet, any(), any()) }
    }

    @Test fun cancelling_the_lead_hands_the_collection_over() = runTest {
        val h = pausedHarness()
        val (quiet, loud) = twoHeld(h)
        h.engine.resumeAfterBluetoothPause()
        clearMocks(h.scheduler)

        // The lead's reminder is deleted/moved: the follower must not be stranded.
        h.engine.cancelActive(otherId)

        verify { h.scheduler.scheduleStage(quiet, NOW + 1_000L, any()) }
        assertTrue(h.bluetoothPause.collected.isEmpty())
        assertEquals(null, h.dao.getById(loud))
    }

    @Test fun reboot_does_not_rearm_followers() = runTest {
        val h = pausedHarness()
        val (quiet, loud) = twoHeld(h)
        coEvery { h.repo.getAllReminders() } returns emptyList()
        h.engine.resumeAfterBluetoothPause()
        clearMocks(h.scheduler)

        h.engine.rescheduleAll()

        verify(exactly = 0) { h.scheduler.scheduleStage(quiet, any(), any()) }
        verify { h.scheduler.scheduleStage(loud, any(), any()) }
    }
}
