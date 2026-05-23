package app.nock.android.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotCodecTest {

    @Test fun roundtrip_minimal_snapshot() {
        val codec = SnapshotCodec()
        val snap = SnapshotV1(
            version = 1,
            savedAtMs = 1_700_000_000_000L,
            groups = listOf(
                GroupSnap(1L, "Pets", 0xFF7C5CFF.toInt(), "Pets", null, null, null, false, 0)
            ),
            reminders = listOf(
                ReminderSnap(10L, 1L, "Feed dog", "DAILY", """{"type":"DAILY","timesOfDayMinutes":[480,1080]}""",
                    1_700_001_000_000L, null, 1_699_999_000_000L)
            ),
            settings = mapOf("k" to "v")
        )
        val json = codec.adapter.toJson(snap)
        val back = codec.adapter.fromJson(json)!!
        assertEquals(snap.version, back.version)
        assertEquals(snap.groups, back.groups)
        assertEquals(snap.reminders, back.reminders)
        assertEquals(snap.settings, back.settings)
    }
}
