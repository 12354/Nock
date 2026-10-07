package app.nock.android.domain.escalation

import app.nock.android.domain.model.StageType
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectedAlarmTest {

    private fun c(id: Long, stage: StageType, startedAt: Long = 0L, simple: Boolean = false) =
        CollectedAlarm.Candidate(id, "R$id", stage, startedAt, simple)

    @Test fun lead_is_the_most_urgent_due_stage() {
        val lead = CollectedAlarm.pickLead(
            listOf(c(1, StageType.SILENT), c(2, StageType.ALARM), c(3, StageType.TELEGRAM))
        )
        assertEquals(2L, lead.escalationId)
    }

    @Test fun ties_go_to_the_one_waiting_longest() {
        val lead = CollectedAlarm.pickLead(
            listOf(c(1, StageType.ALARM, startedAt = 50), c(2, StageType.ALARM, startedAt = 10))
        )
        assertEquals(2L, lead.escalationId)
    }

    @Test fun single_vibration_reminders_never_lead_an_escalating_one() {
        val lead = CollectedAlarm.pickLead(
            listOf(c(1, StageType.VIBRATE, simple = true), c(2, StageType.SILENT))
        )
        assertEquals(2L, lead.escalationId)
    }

    @Test fun telegram_outranks_vibrate() {
        val lead = CollectedAlarm.pickLead(listOf(c(1, StageType.VIBRATE), c(2, StageType.TELEGRAM)))
        assertEquals(2L, lead.escalationId)
    }

    @Test fun title_lists_names_and_caps_the_rest() {
        assertEquals("A", CollectedAlarm.title("A", emptyList()))
        assertEquals("A · B · C", CollectedAlarm.title("A", listOf("B", "C")))
        assertEquals("A · B · C · D +2", CollectedAlarm.title("A", listOf("B", "C", "D", "E", "F")))
    }
}
