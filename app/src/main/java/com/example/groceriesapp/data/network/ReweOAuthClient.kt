package com.example.groceriesapp.data.network

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** One in-flight authorization-code attempt: the auth URL to show plus the PKCE verifier it needs. */
data class ReweAuthRequest(
    val authorizationUrl: String,
    val codeVerifier: String,
    val state: String,
)

/**
 * Keycloak OAuth2 against account.rewe.de. This host does not require the client certificate —
 * only the data APIs do — so a plain client is enough here.
 */
class ReweOAuthClient {
    companion object {
        const val REDIRECT_URI = "https://www.rewe.de/app-start"
        private const val CLIENT_ID = "reweandroid"
        private const val AUTH_ENDPOINT =
            "https://account.rewe.de/realms/sso/protocol/openid-connect/auth"
        private const val TOKEN_ENDPOINT =
            "https://account.rewe.de/realms/sso/protocol/openid-connect/token"
        private const val SHOP_SSO_TOKEN_ENDPOINT = "https://shop.rewe.de/api/login/sso-token"
        private const val SCOPE = "openid offline_access"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val random = SecureRandom()
    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun createAuthRequest(): ReweAuthRequest {
        val codeVerifier = randomUrlSafe(64)
        val challenge = urlEncoder.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII)),
        )
        val state = randomUrlSafe(32)
        val url = AUTH_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", randomUrlSafe(32))
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .build()
        return ReweAuthRequest(
            authorizationUrl = url.toString(),
            codeVerifier = codeVerifier,
            state = state,
        )
    }

    /**
     * Exchanges the intercepted redirect for tokens. REWE sometimes rejects the plain PKCE exchange
     * with `unauthorized_client`, in which case the shop's SSO-token bridge still hands out a token
     * for the same callback URL.
     */
    fun exchangeCode(
        callbackUrl: String,
        code: String,
        codeVerifier: String,
    ): Result<ReweTokens> = runCatching {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", codeVerifier)
            .build()
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .header("Accept", "application/json")
            .post(body)
            .build()

        val (statusCode, payload) = client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        if (statusCode == 200) {
            return@runCatching parseTokens(payload)
        }
        val error = runCatching { JSONObject(payload).optString("error") }.getOrNull()
        if (error == "unauthorized_client") {
            return@runCatching exchangeViaShopSsoToken(callbackUrl)
        }
        throw IOException("Token exchange failed (HTTP $statusCode): ${payload.take(300)}")
    }

    fun refresh(refreshToken: String): Result<ReweTokens> = runCatching {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder()
            .url(TOKEN_ENDPOINT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val (statusCode, payload) = client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        if (statusCode != 200) {
            throw IOException("Token refresh failed (HTTP $statusCode): ${payload.take(300)}")
        }
        parseTokens(payload)
    }

    private fun exchangeViaShopSsoToken(callbackUrl: String): ReweTokens {
        val url = SHOP_SSO_TOKEN_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("redirectUrl", callbackUrl)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        val (statusCode, payload) = client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        if (statusCode != 200) {
            throw IOException("SSO-token exchange failed (HTTP $statusCode): ${payload.take(300)}")
        }
        return parseTokens(payload)
    }

    private fun parseTokens(payload: String): ReweTokens {
        val json = JSONObject(payload)
        val accessToken = json.optString("access_token").ifBlank {
            json.optString("accessToken").ifBlank {
                json.optString("token")
            }
        }
        if (accessToken.isBlank()) {
            throw IOException("Token response did not contain an access token")
        }
        val refreshToken = json.optString("refresh_token").ifBlank {
            json.optString("refreshToken")
        }.takeIf(String::isNotBlank)
        val expiresIn = json.optLong("expires_in", json.optLong("expiresIn", 0L))
        val expiresAt = if (expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000L
        } else {
            decodeJwtExpiry(accessToken)
        }
        return ReweTokens(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMillis = expiresAt,
        )
    }

    private fun randomUrlSafe(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return urlEncoder.encodeToString(bytes)
    }
}

fun decodeJwtClaims(jwt: String): JSONObject? = runCatching {
    val parts = jwt.split(".")
    if (parts.size < 2) return@runCatching null
    var payload = parts[1]
    payload += "=".repeat((-payload.length).mod(4))
    JSONObject(String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8))
}.getOrNull()

private fun decodeJwtExpiry(jwt: String): Long {
    val exp = decodeJwtClaims(jwt)?.optLong("exp", 0L) ?: 0L
    return if (exp > 0) exp * 1000L else System.currentTimeMillis() + 5 * 60 * 1000L
}
