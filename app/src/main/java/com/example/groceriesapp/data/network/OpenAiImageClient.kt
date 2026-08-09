package com.example.groceriesapp.data.network

import android.util.Base64
import com.example.groceriesapp.data.OpenAiKeyStore
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Minimal OpenAI Images client. Generation regularly takes 10–20 s, hence the long read timeout.
 * Reuses [OpenAiAuthException]/[OpenAiHttpException] from the chat client for uniform handling.
 */
class OpenAiImageClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
    private val endpoint: String = "${OpenAiKeyStore.DEFAULT_API_BASE}/images/generations",
) {
    /** Returns the generated image bytes (PNG). Blocking; call from Dispatchers.IO. */
    fun generate(
        apiKey: String,
        prompt: String,
        model: String = OpenAiKeyStore.DEFAULT_IMAGE_MODEL,
        size: String = "1024x1024",
    ): ByteArray {
        val body = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", size)
            .put("n", 1)
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
                    "OpenAI image request failed (HTTP ${response.code}): ${responseBody.take(300)}",
                )
            }
            val encoded = JSONObject(responseBody)
                .optJSONArray("data")
                ?.optJSONObject(0)
                ?.optString("b64_json")
                ?.takeIf(String::isNotEmpty)
                ?: throw OpenAiHttpException(response.code, "OpenAI image response had no data.")
            return Base64.decode(encoded, Base64.DEFAULT)
        }
    }
}
