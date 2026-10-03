package com.example.groceriesapp.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class EdekaOAuthClientTest {
    @Test
    fun authorizationRequestUsesPkceAndAppTrackingParameters() {
        val request = EdekaOAuthClient().createAuthRequest()
        val url = request.authorizationUrl.toHttpUrl()
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(request.codeVerifier.toByteArray(StandardCharsets.US_ASCII)),
        )

        assertEquals("edeka-app", url.queryParameter("client_id"))
        assertEquals(EdekaOAuthClient.REDIRECT_URI, url.queryParameter("redirect_uri"))
        assertEquals(request.state, url.queryParameter("state"))
        assertEquals(expectedChallenge, url.queryParameter("code_challenge"))
        assertEquals(request.deviceId, url.queryParameter("dId"))
        assertEquals(request.sessionId, url.queryParameter("sessionId"))
        assertEquals("true", url.queryParameter("isWebView"))
        assertNotEquals(request.state, request.codeVerifier)
    }

    @Test
    fun continuationKeepsMagicLinkValuesAndAddsWebViewParameters() {
        val result = EdekaOAuthClient().continuationUrl(
            "https://login.edeka/actions/start-login-edeka-app?otp=abc&requestCodeId=def",
        ).toHttpUrl()

        assertEquals("/actions/start-login", result.encodedPath)
        assertEquals("abc", result.queryParameter("otp"))
        assertEquals("def", result.queryParameter("requestCodeId"))
        assertEquals("edeka-app", result.queryParameter("client_id"))
        assertEquals(EdekaOAuthClient.REDIRECT_URI, result.queryParameter("redirect_uri"))
        assertEquals("true", result.queryParameter("isWebView"))
    }

    @Test
    fun tokenExpiryUsesExpiresIn() {
        val client = EdekaOAuthClient(nowMillis = { 1_000L })
        val tokens = client.parseTokens(
            """{"access_token":"${jwt(999)}","refresh_token":"refresh","expires_in":60}""",
        )

        assertEquals(61_000L, tokens.expiresAtEpochMillis)
        assertEquals("refresh", tokens.refreshToken)
        assertTrue(tokens.accessToken.isNotBlank())
    }

    @Test
    fun callbackCodeRequiresMatchingState() {
        val client = EdekaOAuthClient()

        assertEquals(
            "authorization-code",
            client.codeFromCallback(
                "edeka://main/auth/redirect?state=expected&code=authorization-code",
                "expected",
            ).getOrThrow(),
        )
        assertThrows(java.io.IOException::class.java) {
            client.codeFromCallback(
                "edeka://main/auth/redirect?state=wrong&code=authorization-code",
                "expected",
            ).getOrThrow()
        }
    }

    @Test
    fun tokenExpiryFallsBackToJwtClaim() {
        val tokens = EdekaOAuthClient(nowMillis = { 1_000L }).parseTokens(
            """{"access_token":"${jwt(1234)}"}""",
        )

        assertEquals(1_234_000L, tokens.expiresAtEpochMillis)
    }

    private fun jwt(exp: Long): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{}".toByteArray())
        val payload = encoder.encodeToString("""{"exp":$exp}""".toByteArray())
        return "$header.$payload.signature"
    }
}
