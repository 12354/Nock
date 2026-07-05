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

    @Test fun roundtrip_regular_vibration_reminder() {
        val codec = SnapshotCodec()
        val snap = SnapshotV1(
            version = 1,
            savedAtMs = 1_700_000_000_000L,
            groups = listOf(
                GroupSnap(1L, "Self-care", 0xFFB388FF.toInt(), "FavoriteBorder", null, null, null, false, 0)
            ),
            reminders = listOf(
                ReminderSnap(
                    10L, 1L, "Drink water", "DAILY",
                    """{"type":"DAILY","timesOfDayMinutes":[600]}""",
                    1_700_001_000_000L, null, 1_699_999_000_000L,
                    simpleVibration = true, vibrationPatternCsv = "SHORT,LONG,SHORT"
                )
            ),
            settings = emptyMap()
        )
        val back = codec.adapter.fromJson(codec.adapter.toJson(snap))!!
        assertEquals(snap.reminders, back.reminders)
        assertEquals(true, back.reminders.first().simpleVibration)
        assertEquals("SHORT,LONG,SHORT", back.reminders.first().vibrationPatternCsv)
    }

    @Test fun decodes_pre_feature_snapshot_without_vibration_fields() {
        val codec = SnapshotCodec()
        // A snapshot written before the regular-vibration feature has no
        // simpleVibration / vibrationPatternCsv keys; Moshi must fill the defaults.
        val legacy = """
            {"version":1,"savedAtMs":1,"groups":[],"reminders":[
              {"id":5,"groupId":1,"name":"x","scheduleType":"DAILY",
               "scheduleJson":"{\"type\":\"DAILY\",\"timesOfDayMinutes\":[600]}",
               "createdAt":2}],"settings":{}}
        """.trimIndent()
        val back = codec.adapter.fromJson(legacy)!!
        assertEquals(false, back.reminders.first().simpleVibration)
        assertEquals(null, back.reminders.first().vibrationPatternCsv)
    }
}
