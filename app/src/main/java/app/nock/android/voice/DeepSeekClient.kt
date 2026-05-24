package app.nock.android.voice

import app.nock.android.data.SettingsRepository
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

sealed class DeepSeekResult {
    data class Ok(val content: String) : DeepSeekResult()
    data class Error(val message: String) : DeepSeekResult()
}

data class DeepSeekMessage(val role: String, val content: String)

data class DeepSeekResponseFormat(val type: String)

data class DeepSeekRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    val temperature: Double = 0.1,
    val response_format: DeepSeekResponseFormat? = null
)

data class DeepSeekChoice(val message: DeepSeekMessage?)

data class DeepSeekResponse(val choices: List<DeepSeekChoice>?)

@Singleton
class DeepSeekClient @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsRepository
) {
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter: JsonAdapter<DeepSeekRequest> = moshi.adapter(DeepSeekRequest::class.java)
    private val responseAdapter: JsonAdapter<DeepSeekResponse> = moshi.adapter(DeepSeekResponse::class.java)

    suspend fun complete(
        systemPrompt: String,
        userPrompt: String,
        jsonMode: Boolean = true
    ): DeepSeekResult = withContext(Dispatchers.IO) {
        val apiKey = settings.get(SettingsRepository.KEY_DEEPSEEK_API_KEY)?.takeIf { it.isNotBlank() }
            ?: return@withContext DeepSeekResult.Error("DeepSeek API key not set")
        val model = settings.get(SettingsRepository.KEY_DEEPSEEK_MODEL)?.takeIf { it.isNotBlank() }
            ?: SettingsRepository.DEFAULT_DEEPSEEK_MODEL
        val baseUrl = settings.get(SettingsRepository.KEY_DEEPSEEK_BASE_URL)?.takeIf { it.isNotBlank() }
            ?: SettingsRepository.DEFAULT_DEEPSEEK_BASE_URL

        val req = DeepSeekRequest(
            model = model,
            messages = listOf(
                DeepSeekMessage("system", systemPrompt),
                DeepSeekMessage("user", userPrompt)
            ),
            response_format = if (jsonMode) DeepSeekResponseFormat("json_object") else null
        )
        val bodyJson = requestAdapter.toJson(req)
        val url = baseUrl.trimEnd('/') + "/v1/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext DeepSeekResult.Error("HTTP ${resp.code}: ${raw.take(200)}")
                }
                val parsed = runCatching { responseAdapter.fromJson(raw) }.getOrNull()
                val content = parsed?.choices?.firstOrNull()?.message?.content
                if (content.isNullOrBlank()) {
                    DeepSeekResult.Error("Empty response from model")
                } else {
                    DeepSeekResult.Ok(content)
                }
            }
        } catch (t: Throwable) {
            DeepSeekResult.Error(t.message ?: t::class.simpleName ?: "unknown")
        }
    }
}
