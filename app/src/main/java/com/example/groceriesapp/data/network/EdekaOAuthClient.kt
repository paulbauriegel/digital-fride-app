package com.example.groceriesapp.data.network

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

class EdekaOAuthClient(
    private val client: OkHttpClient = defaultClient(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val REDIRECT_URI = "edeka://main/auth/redirect"
        const val LOGIN_LINK_PREFIX = "https://login.edeka/actions/start-login-edeka-app"
        private const val LOGIN_CONTINUE_URL = "https://login.edeka/actions/start-login"
        private const val CLIENT_ID = "edeka-app"
        private const val AUTH_ENDPOINT =
            "https://login.edeka/auth/realms/b2c/protocol/openid-connect/auth"
        private const val TOKEN_ENDPOINT =
            "https://login.edeka/auth/realms/b2c/protocol/openid-connect/token"
        private const val SCOPE = "profile email offline_access"

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun createAuthRequest(): EdekaAuthRequest {
        val verifier = randomUrlSafe(64)
        val challenge = encoder.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
        )
        val state = randomUrlSafe(16)
        val deviceId = UUID.randomUUID().toString()
        val sessionId = UUID.randomUUID().toString()
        val url = AUTH_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("prompt", "login")
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", randomUrlSafe(16))
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("dId", deviceId)
            .addQueryParameter("sessionId", sessionId)
            .addQueryParameter("isWebView", "true")
            .addQueryParameter("loginFlow", "v1")
            .build()
        return EdekaAuthRequest(
            authorizationUrl = url.toString(),
            codeVerifier = verifier,
            state = state,
            deviceId = deviceId,
            sessionId = sessionId,
        )
    }

    fun continuationUrl(intermediateUrl: String): String {
        val source = intermediateUrl.toHttpUrl()
        val destination = LOGIN_CONTINUE_URL.toHttpUrl().newBuilder()
        for (index in 0 until source.querySize) {
            destination.addQueryParameter(
                source.queryParameterName(index),
                source.queryParameterValue(index),
            )
        }
        if (source.queryParameter("client_id") == null) {
            destination.addQueryParameter("client_id", CLIENT_ID)
        }
        if (source.queryParameter("redirect_uri") == null) {
            destination.addQueryParameter("redirect_uri", REDIRECT_URI)
        }
        destination.setQueryParameter("isWebView", "true")
        return destination.build().toString()
    }

    fun codeFromCallback(callbackUrl: String, expectedState: String): Result<String> = runCatching {
        val parameters = URI(callbackUrl).rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotBlank)
            .associate { part ->
                val (name, value) = part.split('=', limit = 2).let {
                    it.first() to it.getOrElse(1) { "" }
                }
                URLDecoder.decode(name, StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            }
        parameters["error"]?.let { error ->
            throw IOException(parameters["error_description"] ?: error)
        }
        if (parameters["state"] != expectedState) {
            throw IOException("EDEKA sign-in state did not match. Start again.")
        }
        parameters["code"]?.takeIf(String::isNotBlank)
            ?: throw IOException("EDEKA did not return an authorization code.")
    }

    fun exchangeCode(code: String, requestState: EdekaAuthRequest): Result<EdekaTokens> =
        requestTokens(
            body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CLIENT_ID)
                .add("redirect_uri", REDIRECT_URI)
                .add("code", code)
                .add("code_verifier", requestState.codeVerifier)
                .build(),
            deviceId = requestState.deviceId,
            sessionId = requestState.sessionId,
        )

    fun refresh(refreshToken: String): Result<EdekaTokens> = requestTokens(
        body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build(),
        deviceId = null,
        sessionId = null,
    )

    private fun requestTokens(
        body: okhttp3.RequestBody,
        deviceId: String?,
        sessionId: String?,
    ): Result<EdekaTokens> = runCatching {
        val url = TOKEN_ENDPOINT.toHttpUrl().newBuilder().apply {
            deviceId?.let { addQueryParameter("dId", it) }
            sessionId?.let { addQueryParameter("sessionId", it) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val (status, payload) = client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        if (status != 200) {
            val detail = runCatching {
                JSONObject(payload).optString("error_description").ifBlank {
                    JSONObject(payload).optString("error")
                }
            }.getOrNull().orEmpty()
            throw IOException("EDEKA token request failed (HTTP $status): ${detail.take(200)}")
        }
        parseTokens(payload)
    }

    internal fun parseTokens(payload: String, previousRefreshToken: String? = null): EdekaTokens {
        val json = JSONObject(payload)
        val accessToken = json.optString("access_token")
        if (accessToken.isBlank()) throw IOException("EDEKA token response has no access token")
        val refreshToken = json.optString("refresh_token")
            .takeIf(String::isNotBlank)
            ?: previousRefreshToken
        val expiresIn = json.optLong("expires_in", 0L)
        val expiresAt = if (expiresIn > 0) {
            nowMillis() + expiresIn * 1000L
        } else {
            decodeJwtClaims(accessToken)?.optLong("exp", 0L)?.times(1000L)
                ?.takeIf { it > 0L }
                ?: nowMillis() + 5 * 60 * 1000L
        }
        return EdekaTokens(accessToken, refreshToken, expiresAt)
    }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }
}
