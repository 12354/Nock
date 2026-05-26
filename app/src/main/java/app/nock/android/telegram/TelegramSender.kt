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

data class TelegramDeleteBody(
    val chat_id: String,
    val message_id: Long
)

private data class TelegramSendResponse(
    val ok: Boolean,
    val result: TelegramMessageResult?,
    val description: String?
)

private data class TelegramMessageResult(val message_id: Long)

data class TelegramResult(
    val ok: Boolean,
    val errorDescription: String? = null,
    val messageId: Long? = null
)

@Singleton
class TelegramSender @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val sendBodyAdapter: JsonAdapter<TelegramSendBody> = moshi.adapter(TelegramSendBody::class.java)
    private val deleteBodyAdapter: JsonAdapter<TelegramDeleteBody> = moshi.adapter(TelegramDeleteBody::class.java)
    private val sendResponseAdapter: JsonAdapter<TelegramSendResponse> = moshi.adapter(TelegramSendResponse::class.java)

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
            val body = sendBodyAdapter.toJson(TelegramSendBody(chatId, text, silent))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            try {
                client.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string()
                    if (resp.isSuccessful) {
                        val parsed = raw?.let { runCatching { sendResponseAdapter.fromJson(it) }.getOrNull() }
                        TelegramResult(ok = true, messageId = parsed?.result?.message_id)
                    } else {
                        TelegramResult(false, "${resp.code}: $raw")
                    }
                }
            } catch (t: Throwable) {
                TelegramResult(false, t.message)
            }
        }

    suspend fun deleteMessage(messageId: Long): Boolean {
        val token = settings.get(SettingsRepository.KEY_TELEGRAM_TOKEN)?.takeIf { it.isNotBlank() }
            ?: return false
        val chat = settings.get(SettingsRepository.KEY_TELEGRAM_CHAT)?.takeIf { it.isNotBlank() }
            ?: return false
        return deleteMessageRaw(token, chat, messageId)
    }

    private suspend fun deleteMessageRaw(token: String, chatId: String, messageId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val url = "https://api.telegram.org/bot$token/deleteMessage"
            val body = deleteBodyAdapter.toJson(TelegramDeleteBody(chatId, messageId))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            try {
                client.newCall(req).execute().use { resp -> resp.isSuccessful }
            } catch (_: Throwable) {
                false
            }
        }
}
