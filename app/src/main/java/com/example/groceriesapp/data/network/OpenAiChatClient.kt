package com.example.groceriesapp.data.network

import com.example.groceriesapp.data.OpenAiKeyStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenAiAuthException(message: String) : IOException(message)

class OpenAiHttpException(val code: Int, message: String) : IOException(message)

/**
 * Minimal OpenAI Chat Completions client with strict structured outputs.
 *
 * Chat Completions rather than the Responses API is a standing choice, not a measured one — the
 * previously claimed "garbage-token bug with structured outputs on Responses" was never backed by
 * a test or a linked issue here, so treat it as unverified rather than as a constraint.
 */
class OpenAiChatClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = "${OpenAiKeyStore.DEFAULT_API_BASE}/chat/completions",
) {
    /** Returns the assistant message content. Blocking; call from Dispatchers.IO. */
    fun completeStructured(
        apiKey: String,
        model: String,
        systemPrompt: String,
        userContent: String,
        schemaName: String,
        schema: JSONObject,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", userContent)),
            )
            .put(
                "response_format",
                JSONObject()
                    .put("type", "json_schema")
                    .put(
                        "json_schema",
                        JSONObject()
                            .put("name", schemaName)
                            .put("strict", true)
                            .put("schema", schema),
                    ),
            )
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) {
                throw OpenAiAuthException("The OpenAI API key was rejected (HTTP ${response.code}).")
            }
            if (!response.isSuccessful) {
                throw OpenAiHttpException(
                    response.code,
                    "OpenAI request failed (HTTP ${response.code}): ${responseBody.take(300)}",
                )
            }
            val message = JSONObject(responseBody)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?: throw OpenAiHttpException(response.code, "OpenAI response had no message.")
            // Not optString: Android's org.json stringifies a JSON null to "null" instead of
            // falling back, so `"refusal": null` — present on every successful answer — would
            // read as a refusal. The JVM org.json used in unit tests returns the fallback here,
            // so this divergence only ever shows up on device.
            message.stringOrNull("refusal")?.let { refusal ->
                throw OpenAiHttpException(response.code, "OpenAI refused the request: $refusal")
            }
            return message.stringOrNull("content")
                ?: throw OpenAiHttpException(response.code, "OpenAI response had no content.")
        }
    }
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)
