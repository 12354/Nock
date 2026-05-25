package app.nock.android.voice

import android.content.Context
import app.nock.android.R
import app.nock.android.data.SettingsRepository
import app.nock.android.domain.model.Group
import app.nock.android.domain.model.Schedule
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

sealed class DeepSeekParseResult {
    data class Ok(
        val name: String?,
        val schedule: Schedule,
        val groupHint: String?,
    ) : DeepSeekParseResult()
    object NotConfigured : DeepSeekParseResult()
    data class Failed(val message: String) : DeepSeekParseResult()
}

@Singleton
class DeepSeekReminderParser @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val deepSeek: DeepSeekClient,
    private val settings: SettingsRepository,
) {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val specAdapter: JsonAdapter<VoiceAlarmSpec> =
        moshi.adapter(VoiceAlarmSpec::class.java).lenient()

    suspend fun isConfigured(): Boolean =
        !settings.get(SettingsRepository.KEY_DEEPSEEK_API_KEY).isNullOrBlank()

    suspend fun parse(text: String, groups: List<Group>): DeepSeekParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DeepSeekParseResult.Failed(ctx.getString(R.string.voice_error_empty_transcript))
        if (groups.isEmpty()) return DeepSeekParseResult.Failed(ctx.getString(R.string.voice_error_no_groups))
        if (!isConfigured()) return DeepSeekParseResult.NotConfigured

        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val system = buildSystemPrompt(now, zone, groups)

        return when (val r = deepSeek.complete(system, trimmed, jsonMode = true)) {
            is DeepSeekResult.Error -> DeepSeekParseResult.Failed(r.message)
            is DeepSeekResult.Ok -> buildResult(r.content, now, zone)
        }
    }

    private fun buildResult(json: String, now: LocalDateTime, zone: ZoneId): DeepSeekParseResult {
        val spec = runCatching { specAdapter.fromJson(json) }.getOrNull()
            ?: return DeepSeekParseResult.Failed(ctx.getString(R.string.voice_error_bad_json))
        val schedule = runCatching { toSchedule(spec, now, zone) }.getOrElse { t ->
            return DeepSeekParseResult.Failed(t.message ?: ctx.getString(R.string.voice_error_bad_schedule))
        }
        return DeepSeekParseResult.Ok(
            name = spec.name?.trim()?.takeIf { it.isNotBlank() },
            schedule = schedule,
            groupHint = spec.groupHint?.trim()?.takeIf { it.isNotBlank() },
        )
    }

    private fun toSchedule(spec: VoiceAlarmSpec, now: LocalDateTime, zone: ZoneId): Schedule {
        val type = spec.scheduleType?.uppercase()?.replace('-', '_') ?: "ONESHOT"
        return when (type) {
            "ONESHOT", "ONCE", "ONE_SHOT" -> {
                val iso = spec.oneShotIso ?: error(ctx.getString(R.string.voice_error_missing_field, "oneShotIso"))
                val dt = parseIso(iso, now)
                Schedule.OneShot(dt.atZone(zone).toInstant().toEpochMilli())
            }
            "DAILY" -> Schedule.Daily(parseTimesOfDay(spec.timesOfDay))
            "WEEKLY" -> Schedule.Weekly(
                daysOfWeek = parseDaysOfWeek(spec.daysOfWeek),
                timesOfDayMinutes = parseTimesOfDay(spec.timesOfDay)
            )
            "MONTHLY" -> Schedule.Monthly(
                dayOfMonth = spec.dayOfMonth?.coerceIn(1, 31)
                    ?: error(ctx.getString(R.string.voice_error_missing_field, "dayOfMonth")),
                timeOfDayMinutes = parseSingleTime(spec.timeOfDay)
            )
            "INTERVAL", "INTERVAL_FROM_LAST" -> {
                val mins = spec.intervalMinutes?.coerceAtLeast(1)
                    ?: error(ctx.getString(R.string.voice_error_missing_field, "intervalMinutes"))
                Schedule.IntervalFromLast(mins * 60_000L)
            }
            "ON_UNLOCK", "ONUNLOCK" -> Schedule.OnUnlock(System.currentTimeMillis())
            else -> error(ctx.getString(R.string.voice_error_unknown_schedule, type))
        }
    }

    private fun parseIso(iso: String, now: LocalDateTime): LocalDateTime {
        runCatching { LocalDateTime.parse(iso) }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME) }.getOrNull()?.let { return it }
        runCatching { java.time.LocalDate.parse(iso).atStartOfDay() }.getOrNull()?.let { return it }
        return now
    }

    private fun parseTimesOfDay(values: List<String>?): List<Int> {
        val list = values?.mapNotNull { parseTimeStringOrNull(it) }?.distinct()?.sorted().orEmpty()
        if (list.isEmpty()) error(ctx.getString(R.string.voice_error_missing_field, "timesOfDay"))
        return list
    }

    private fun parseSingleTime(value: String?): Int {
        return value?.let { parseTimeStringOrNull(it) }
            ?: error(ctx.getString(R.string.voice_error_missing_field, "timeOfDay"))
    }

    private fun parseTimeStringOrNull(s: String): Int? {
        val parts = s.trim().split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    private fun parseDaysOfWeek(values: List<String>?): Set<DayOfWeek> {
        val out = values?.mapNotNull { runCatching { DayOfWeek.valueOf(it.uppercase()) }.getOrNull() }?.toSet().orEmpty()
        if (out.isEmpty()) error(ctx.getString(R.string.voice_error_missing_field, "daysOfWeek"))
        return out
    }

    private fun buildSystemPrompt(now: LocalDateTime, zone: ZoneId, groups: List<Group>): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val dow = now.dayOfWeek.name
        val groupCatalog = groups.joinToString("\n") { g ->
            val desc = SEED_GROUP_DESCRIPTIONS[g.seedKey]
            if (desc != null) "              - \"${g.name}\" — $desc" else "              - \"${g.name}\" (custom group)"
        }
        return """
            You convert a spoken or typed phrase into a JSON specification for a single reminder/alarm.

            Output ONLY a single JSON object, no prose, with these fields (only include fields relevant to the chosen scheduleType):
            - name (string, required): a short imperative reminder label, max 60 chars. Do not include time-of-day.
            - scheduleType (string, required): one of ONESHOT, DAILY, WEEKLY, MONTHLY, INTERVAL, ON_UNLOCK.
            - oneShotIso (string, required when scheduleType=ONESHOT): local datetime in ISO format YYYY-MM-DDTHH:mm:ss (no zone suffix, interpreted in the user's local zone).
            - timesOfDay (array of "HH:mm" strings, required for DAILY and WEEKLY): 24-hour clock.
            - daysOfWeek (array of strings, required for WEEKLY): MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY.
            - dayOfMonth (integer 1-31, required for MONTHLY).
            - timeOfDay ("HH:mm" string, required for MONTHLY): 24-hour clock.
            - intervalMinutes (integer >= 1, required for INTERVAL): minutes between firings, measured from the user's last "Done".
            - groupHint (string, optional): MUST exactly match one of the group names listed below (verbatim, case-insensitive).

            Schedule rules:
            - Prefer ONESHOT for an unambiguous single event ("tomorrow at 3pm", "in 20 minutes").
            - Prefer DAILY / WEEKLY when the user says "every day", "each morning", "every Monday and Wednesday", etc.
            - Prefer INTERVAL when the user says "every 4 hours" or "every 30 minutes" (the time between firings, not a fixed time of day).
            - Prefer ON_UNLOCK only when the user explicitly says something like "next time I unlock my phone".
            - "in N minutes/hours" => ONESHOT, oneShotIso = now + that duration.
            - Times like "8" / "8pm" / "20:00" => "20:00" (HH:mm).
            - All times are interpreted in the user's local time zone.

            Group selection (be careful — this is often the wrong field):
            - Before emitting JSON, think about the SUBJECT of the reminder (what is being done, for whom/what), not the wording.
            - Match that subject to the best group from the catalog below using the descriptions, not just name overlap.
            - Heuristics: take a pill / vitamin / insulin / inhaler => meds. Walk / feed / vet / groom an animal => pets.
              Stretch / drink water / meditate / sleep / hygiene / journal => self-care.
              Buy / pick up / shop / appointment / pharmacy / bank => errands.
              Vacuum / laundry / dishes / trash / water plants / bills => household.
              Meeting / call / deadline / report / email / study => work.
            - If the user explicitly names a group ("add to Pets"), use that name verbatim and ignore the heuristics.
            - If no group clearly fits, OMIT groupHint entirely — do NOT pick a random one and do NOT invent a name.

            Context:
            - Current local datetime: ${now.format(fmt)} (${zone.id})
            - Day of week today: $dow
            - Available groups:
$groupCatalog
        """.trimIndent()
    }

    companion object {
        private val SEED_GROUP_DESCRIPTIONS = mapOf(
            "pets" to "anything about pets or animals: feeding, walking, vet visits, grooming, litter box, cage cleaning.",
            "meds" to "medication and supplements: taking pills, vitamins, insulin, inhaler, prescription refills, doctor-prescribed treatments.",
            "selfcare" to "personal wellness and habits: exercise, stretching, hydration, meditation, hygiene, skincare, sleep, journaling.",
            "household" to "chores done at home: cleaning, laundry, dishes, trash, watering plants, repairs, recurring home bills.",
            "work" to "job and study tasks: meetings, calls, deadlines, projects, emails, reports, classes.",
            "errands" to "tasks done outside the home: shopping, pickups, appointments, deliveries, banking, post office.",
        )
    }
}
