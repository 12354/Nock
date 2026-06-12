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
    data class Failed(val message: String, val transient: Boolean = false) : DeepSeekParseResult()
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
        val userContext = settings.get(SettingsRepository.KEY_DEEPSEEK_CONTEXT)?.trim().orEmpty()
        val system = buildSystemPrompt(now, zone, groups, userContext)

        return when (val r = deepSeek.complete(system, trimmed, jsonMode = true)) {
            is DeepSeekResult.Error -> DeepSeekParseResult.Failed(r.message, transient = r.transient)
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

    // DeepSeek only ever produces a single, one-shot reminder. Recurring phrasing
    // ("every day at 8") is collapsed by the prompt to the next single occurrence,
    // so any returned scheduleType is treated as ONESHOT.
    private fun toSchedule(spec: VoiceAlarmSpec, now: LocalDateTime, zone: ZoneId): Schedule {
        val iso = spec.oneShotIso ?: error(ctx.getString(R.string.voice_error_missing_field, "oneShotIso"))
        val dt = parseIso(iso, now)
        return Schedule.OneShot(dt.atZone(zone).toInstant().toEpochMilli())
    }

    private fun parseIso(iso: String, now: LocalDateTime): LocalDateTime {
        runCatching { LocalDateTime.parse(iso) }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(iso, DateTimeFormatter.ISO_DATE_TIME) }.getOrNull()?.let { return it }
        runCatching { java.time.LocalDate.parse(iso).atStartOfDay() }.getOrNull()?.let { return it }
        return now
    }

    private fun buildSystemPrompt(
        now: LocalDateTime,
        zone: ZoneId,
        groups: List<Group>,
        userContext: String,
    ): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val dow = now.dayOfWeek.name
        // The calendar-import group (Appointments / Termine) is populated from the
        // device calendar, not from voice capture, so never offer it as a target.
        val groupCatalog = groups
            .filter { it.seedKey != TRIPS_SEED_KEY }
            .joinToString("\n") { g ->
                val desc = SEED_GROUP_DESCRIPTIONS[g.seedKey]
                if (desc != null) "              - \"${g.name}\" — $desc" else "              - \"${g.name}\" (custom group)"
            }
        val personalContext = if (userContext.isNotBlank()) {
            "\n\n" + """
                Personal context provided by the user (use it to resolve names, relationships,
                pets, places, and preferences when interpreting the phrase; never let it override
                an explicit instruction in the phrase, and never invent reminders from it):
                $userContext
            """.trimIndent()
        } else ""
        return """
            You convert a spoken or typed phrase into a JSON specification for a single one-shot reminder/alarm.

            Every reminder fires exactly once, at one specific date and time. The app does not
            support recurring reminders here, so you must always resolve the phrase to a single moment.

            Output ONLY a single JSON object, no prose, with these fields:
            - name (string, required): a short imperative reminder label, max 60 chars. Do not include time-of-day.
            - scheduleType (string, required): always "ONESHOT".
            - oneShotIso (string, required): local datetime in ISO format YYYY-MM-DDTHH:mm:ss (no zone suffix, interpreted in the user's local zone).
            - groupHint (string, optional): MUST exactly match one of the group names listed below (verbatim, case-insensitive).

            Schedule rules (always ONESHOT):
            - "tomorrow at 3pm", "Friday at noon" => the single matching datetime.
            - "in N minutes/hours" => oneShotIso = now + that duration.
            - If the phrase is recurring ("every day at 8", "each morning", "every Monday", "every 4 hours"),
              do NOT try to repeat it. Pick the NEXT single occurrence from now and use that as oneShotIso.
            - If only a time of day is given with no date, use today if that time is still in the future, otherwise tomorrow.
            - Times like "8" / "8pm" / "20:00" map to a 24-hour local time.
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
        """.trimIndent() + personalContext
    }

    companion object {
        // Seed key of the calendar-import group created by TripSyncManager.
        private const val TRIPS_SEED_KEY = "trips"

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
