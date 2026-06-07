package app.nock.android.data

import app.nock.android.data.dao.SettingsDao
import app.nock.android.data.entity.SettingsEntity
import app.nock.android.data.json.ChainJson
import app.nock.android.domain.model.DefaultChain
import app.nock.android.domain.model.EscalationChain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dao: SettingsDao
) {
    companion object {
        const val KEY_STAGE_CHAIN = "stage_chain"
        const val KEY_TELEGRAM_TOKEN = "telegram_token"
        const val KEY_TELEGRAM_CHAT = "telegram_chat"
        const val KEY_ALARM_SOUND = "alarm_sound_uri"
        const val KEY_DRIVE_ACCOUNT = "drive_account"
        const val KEY_DRIVE_LAST_SYNC_MS = "drive_last_sync_ms"
        const val KEY_DRIVE_LAST_REMOTE_MS = "drive_last_remote_ms"
        const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        const val KEY_DEEPSEEK_MODEL = "deepseek_model"
        const val KEY_DEEPSEEK_BASE_URL = "deepseek_base_url"
        const val KEY_DEEPSEEK_CONTEXT = "deepseek_context"

        // Calendar trip alarms
        const val KEY_TRIPS_ENABLED = "trips_enabled"
        const val KEY_TOMTOM_KEY = "tomtom_key"
        const val KEY_TRIP_HOME_ADDRESS = "trip_home_address"
        const val KEY_TRIP_HOME_LAT = "trip_home_lat"
        const val KEY_TRIP_HOME_LON = "trip_home_lon"
        const val KEY_TRIP_BUFFER_MIN = "trip_buffer_min"
        const val KEY_TRIP_TRAVEL_MODE = "trip_travel_mode"
        // CSV of CalendarContract calendar ids to import located events from.
        // Empty/unset means "all visible calendars".
        const val KEY_TRIP_CALENDAR_IDS = "trip_calendar_ids"

        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-v4-flash"
        const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    }

    suspend fun getStageChain(): EscalationChain {
        val v = dao.get(KEY_STAGE_CHAIN) ?: return DefaultChain.CHAIN
        return runCatching { ChainJson.decode(v) }.getOrDefault(DefaultChain.CHAIN)
    }

    suspend fun setStageChain(c: EscalationChain) {
        dao.set(SettingsEntity(KEY_STAGE_CHAIN, ChainJson.encode(c)))
    }

    suspend fun get(key: String): String? = dao.get(key)
    suspend fun set(key: String, value: String) = dao.set(SettingsEntity(key, value))
    suspend fun clear(key: String) = dao.delete(key)

    fun observeAll(): Flow<Map<String, String>> = dao.observeAll().map { items ->
        items.associate { it.key to it.value }
    }
}
