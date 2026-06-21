package app.nock.android.alarm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the observable ring-state mirror that drives the full-screen takeover's
 * self-dismissal. AlarmActivity finishes when [AlarmService.ringingEscalation]
 * emits null, so that flow MUST stay in lock-step with the legacy
 * [AlarmService.ringingEscalationId] field the engine's stopAlarm gating reads —
 * otherwise the silent, already-dismissed takeover lingers as a "ghost" alarm.
 */
class AlarmRingStateTest {

    @After fun reset() = AlarmService.clearRingingState()

    @Test fun markRinging_updates_both_the_legacy_fields_and_the_flow() {
        AlarmService.markRinging(escalationId = 42L, reminderId = 7L)
        assertEquals(42L, AlarmService.ringingEscalationId)
        assertEquals(7L, AlarmService.ringingReminderId)
        assertEquals(42L, AlarmService.ringingEscalation.value)
    }

    @Test fun clearRingingState_resets_both_the_legacy_fields_and_the_flow() {
        AlarmService.markRinging(42L, 7L)
        AlarmService.clearRingingState()
        assertNull(AlarmService.ringingEscalationId)
        assertNull(AlarmService.ringingReminderId)
        assertNull(AlarmService.ringingEscalation.value)
    }

    @Test fun markRinging_ignores_a_negative_escalation_id() {
        AlarmService.markRinging(escalationId = -1L, reminderId = 7L)
        assertNull(AlarmService.ringingEscalation.value)
        assertNull(AlarmService.ringingEscalationId)
    }

    @Test fun markRinging_normalises_a_negative_reminder_id_to_null() {
        AlarmService.markRinging(escalationId = 42L, reminderId = -1L)
        assertEquals(42L, AlarmService.ringingEscalation.value)
        assertNull(AlarmService.ringingReminderId)
    }
}
