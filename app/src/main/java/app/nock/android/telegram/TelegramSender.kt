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

    /**
     * Attempts to delete a previously-sent Telegram message.
     *
     * Returns whether the deletion is **resolved** — i.e. it should be removed
     * from the durable retry queue and never attempted again — rather than raw
     * HTTP success. A deletion is resolved when:
     *  - the API confirms success, OR
     *  - the message is permanently un-deletable: a 4xx other than 429 (e.g. 400
     *    "message to delete not found" / "message can't be deleted" — already
     *    gone, too old, or no rights), so retrying can never succeed, OR
     *  - Telegram is not configured (no token/chat), so there is nothing to do.
     *
     * Returns false (keep retrying) only for transient failures: a network
     * exception, a 5xx, or 429 rate limiting.
     */
    suspend fun deleteMessage(messageId: Long): Boolean {
        val token = settings.get(SettingsRepository.KEY_TELEGRAM_TOKEN)?.takeIf { it.isNotBlank() }
            ?: return true
        val chat = settings.get(SettingsRepository.KEY_TELEGRAM_CHAT)?.takeIf { it.isNotBlank() }
            ?: return true
        return deleteMessageRaw(token, chat, messageId)
    }

    private suspend fun deleteMessageRaw(token: String, chatId: String, messageId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val url = "https://api.telegram.org/bot$token/deleteMessage"
            val body = deleteBodyAdapter.toJson(TelegramDeleteBody(chatId, messageId))
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(url).post(body).build()
            try {
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.isSuccessful -> true
                        // Permanent client errors (message already gone / not
                        // deletable / bad request) can never be fixed by retrying;
                        // treat as resolved. 429 is rate limiting — retry later.
                        resp.code in 400..499 && resp.code != 429 -> true
                        else -> false
                    }
                }
            } catch (_: Throwable) {
                false
            }
        }
}
