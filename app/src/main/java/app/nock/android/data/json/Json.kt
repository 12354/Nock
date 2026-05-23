package app.nock.android.data.json

import app.nock.android.domain.model.EscalationChain
import app.nock.android.domain.model.Schedule
import app.nock.android.domain.model.StageConfig
import app.nock.android.domain.model.StageType
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.DayOfWeek

object NockMoshi {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
}

data class ScheduleEnvelope(
    val type: String,
    val oneShotAtMs: Long? = null,
    val timesOfDayMinutes: List<Int>? = null,
    val daysOfWeek: List<Int>? = null,
    val dayOfMonth: Int? = null,
    val timeOfDayMinutes: Int? = null,
    val intervalMs: Long? = null
)

fun Schedule.toEnvelope(): ScheduleEnvelope = when (this) {
    is Schedule.OneShot -> ScheduleEnvelope("ONESHOT", oneShotAtMs = atEpochMs)
    is Schedule.Daily -> ScheduleEnvelope("DAILY", timesOfDayMinutes = timesOfDayMinutes)
    is Schedule.Weekly -> ScheduleEnvelope(
        "WEEKLY",
        daysOfWeek = daysOfWeek.map { it.value },
        timesOfDayMinutes = timesOfDayMinutes
    )
    is Schedule.Monthly -> ScheduleEnvelope("MONTHLY", dayOfMonth = dayOfMonth, timeOfDayMinutes = timeOfDayMinutes)
    is Schedule.IntervalFromLast -> ScheduleEnvelope("INTERVAL", intervalMs = intervalMs)
}

fun ScheduleEnvelope.toSchedule(): Schedule = when (type) {
    "ONESHOT" -> Schedule.OneShot(oneShotAtMs!!)
    "DAILY" -> Schedule.Daily(timesOfDayMinutes!!)
    "WEEKLY" -> Schedule.Weekly(daysOfWeek!!.map { DayOfWeek.of(it) }.toSet(), timesOfDayMinutes!!)
    "MONTHLY" -> Schedule.Monthly(dayOfMonth!!, timeOfDayMinutes!!)
    "INTERVAL" -> Schedule.IntervalFromLast(intervalMs!!)
    else -> error("Unknown schedule type: $type")
}

object ScheduleJson {
    private val adapter: JsonAdapter<ScheduleEnvelope> =
        NockMoshi.moshi.adapter(ScheduleEnvelope::class.java)

    fun encode(s: Schedule): String = adapter.toJson(s.toEnvelope())
    fun decode(json: String): Schedule = adapter.fromJson(json)!!.toSchedule()
}

data class ChainEnvelope(
    val stages: List<StageEnvelope>,
    val repeatIntervalMs: Long
)

data class StageEnvelope(val type: String, val offsetMs: Long)

object ChainJson {
    private val type = Types.newParameterizedType(List::class.java, StageEnvelope::class.java)
    private val adapter: JsonAdapter<ChainEnvelope> =
        NockMoshi.moshi.adapter(ChainEnvelope::class.java)

    fun encode(c: EscalationChain): String = adapter.toJson(
        ChainEnvelope(
            stages = c.stages.map { StageEnvelope(it.type.name, it.offsetMs) },
            repeatIntervalMs = c.repeatIntervalMs
        )
    )

    fun decode(json: String): EscalationChain {
        val env = adapter.fromJson(json)!!
        return EscalationChain(
            stages = env.stages.map { StageConfig(StageType.valueOf(it.type), it.offsetMs) },
            repeatIntervalMs = env.repeatIntervalMs
        )
    }
}
