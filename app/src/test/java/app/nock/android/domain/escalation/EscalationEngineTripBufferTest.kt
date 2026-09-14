package app.nock.android.domain.escalation

import app.nock.android.data.entity.CalendarTripEntity
import app.nock.android.domain.model.StageType
import app.nock.android.domain.model.Schedule
import io.mockk.coEvery
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A calendar-imported trip reminder escalates on its OWN per-reminder buffer
 * (stored on its [CalendarTripEntity]), not the shared Trips-group chain. This is
 * what makes the editable buffer truly per-reminder.
 */
class EscalationEngineTripBufferTest {

    private fun tripRow(reminderId: Long, bufferMs: Long) = CalendarTripEntity(
        id = 1,
        reminderId = reminderId,
        calendarId = 1,
        eventId = 1,
        eventStartMs = NOW,
        title = "Dentist",
        location = "Main St 1",
        originAddress = null,
        travelMode = "car",
        bufferMs = bufferMs,
        originLat = null, originLon = null,
        destLat = null, destLon = null,
        lastTravelMs = null, lastComputedAtMs = null,
    )

    @Test fun trip_reminder_uses_its_own_buffer_for_the_silent_lead() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        // The on-demand calendar-import group; trips live here.
        h.stubReminderAndGroup(r, group().copy(seedKey = "trips"))
        // This trip carries a 20-min buffer of its own.
        coEvery { h.calendarTripDao.getByReminderId(r.id) } returns tripRow(r.id, 20 * MIN)

        // Far-future leave-by so the first (SILENT) stage is still ahead.
        val leaveBy = NOW + 24 * 60 * MIN
        h.engine.startEscalationAt(r, leaveBy)

        val row = h.dao.rows.values.single()
        assertEquals(0, row.nextStageIndex)
        // The loud alarm leads departure by 20 min, with SILENT another 20 min earlier.
        assertEquals(leaveBy - 40 * MIN, row.nextFireAtMs)
    }

    @Test fun non_trip_reminder_in_trips_group_falls_back_to_group_chain() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group().copy(seedKey = "trips"))
        // No trip row for this reminder → the group's effective chain is used.
        coEvery { h.calendarTripDao.getByReminderId(r.id) } returns null

        val leaveBy = NOW + 24 * 60 * MIN
        h.engine.startEscalationAt(r, leaveBy)

        val row = h.dao.rows.values.single()
        // TEST_CHAIN's SILENT pre-stage is at -10 min.
        assertEquals(leaveBy - 10 * MIN, row.nextFireAtMs)
    }

    @Test fun editing_the_buffer_re_anchors_the_silent_lead() = runTest {
        val h = EngineHarness(now = NOW)
        val r = reminder()
        h.stubReminderAndGroup(r, group().copy(seedKey = "trips"))
        val leaveBy = NOW + 24 * 60 * MIN

        // Armed first with a 45-min buffer…
        coEvery { h.calendarTripDao.getByReminderId(r.id) } returns tripRow(r.id, 45 * MIN)
        h.engine.startEscalationAt(r, leaveBy)
        assertEquals(leaveBy - 90 * MIN, h.dao.rows.values.single().nextFireAtMs)

        // …then the user shortens it to 15 min and it is re-armed.
        coEvery { h.calendarTripDao.getByReminderId(r.id) } returns tripRow(r.id, 15 * MIN)
        h.engine.startEscalationAt(r, leaveBy)
        assertEquals(leaveBy - 30 * MIN, h.dao.rows.values.single().nextFireAtMs)
        // And only the freshly-armed chain remains.
        assertEquals(1, h.dao.rows.size)
        assertEquals(StageType.SILENT, h.dao.rows.values.single().let {
            app.nock.android.data.json.ChainJson.decode(it.chainSnapshotJson).stages.first().type
        })
    }

    @Test fun loudAlarm_ringsAt0937_andRepeatsBefore1007Departure() = runTest {
        val leaveBy = epochMs(2026, 9, 15, 10, 7)
        val alarmAt = epochMs(2026, 9, 15, 9, 37)
        val h = EngineHarness(now = epochMs(2026, 9, 15, 8, 0))
        val r = reminder(schedule = Schedule.OneShot(leaveBy), nextFireAt = leaveBy)
        h.stubReminderAndGroup(r, group().copy(seedKey = "trips"))
        coEvery { h.calendarTripDao.getByReminderId(r.id) } returns tripRow(r.id, 30 * MIN)
        h.engine.startEscalationAt(r, leaveBy)

        // Deliver both quiet stages at their scheduled times.
        repeat(2) {
            val row = h.dao.rows.values.single()
            h.clock.now = row.nextFireAtMs
            h.engine.onAlarmFired(row.id)
        }
        val alarm = h.dao.rows.values.single()
        assertEquals(alarmAt, alarm.nextFireAtMs)
        assertEquals(leaveBy, alarm.startedAtMs)
        verify { h.scheduler.scheduleStage(alarm.id, alarmAt, StageType.ALARM) }

        h.clock.now = alarmAt
        h.engine.onAlarmFired(alarm.id)
        verify { h.notifier.showAlarm(any(), any(), alarm.id) }
        assertEquals(alarmAt + 10 * MIN, h.dao.rows.values.single().nextFireAtMs)
        assertEquals(leaveBy, r.nextFireAt)
    }
}
