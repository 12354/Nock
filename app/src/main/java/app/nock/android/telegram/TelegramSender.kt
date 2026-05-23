package app.nock.android.telegram

import app.nock.android.data.SettingsRepository
import app.nock.android.domain.model.Reminder
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

data class TelegramSendBody(
    val chat_id: String,
    val text: String,
    val disable_notification: Boolean = false
)

data class TelegramResult(val ok: Boolean, val errorDescription: String? = null)

@Singleton
class TelegramSender @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val bodyAdapter: JsonAdapter<TelegramSendBody> = moshi.adapter(TelegramSendBody::class.java)

    suspend fun send(reminder: Reminder, silent: Boolean): TelegramResult {
        val token = settings.get(SettingsRepository.KEY_TELEGRAM_TOKEN)?.takeIf { it.isNotBlank() }
            ?: return TelegramResult(false, "no token")
        val chat = settings.get(SettingsRepository.KEY_TELEGRAM_CHAT)?.takeIf { it.isNotBlank() }
            ?: return TelegramResult(false, "no chat id")
        return sendRaw(token, chat, "🔔 ${reminder.name}", silent)
    }

    suspend fun sendRaw(token: String, chatId: String, text: String, silent: Boolean): TelegramResult =
        withContext(Dispatchers.IO) {
            val url = "https://api.telegram.org/bot$token/sendMessage"
            val body = bodyAdapter.toJson(TelegramSendBody(chatId, text, silent))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) TelegramResult(true)
                    else TelegramResult(false, "${resp.code}: ${resp.body?.string()}")
                }
            } catch (t: Throwable) {
                TelegramResult(false, t.message)
            }
        }
}
