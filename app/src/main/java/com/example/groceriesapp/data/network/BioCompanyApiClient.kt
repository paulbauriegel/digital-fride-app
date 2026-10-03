package com.example.groceriesapp.data.network

import android.util.Log
import com.example.groceriesapp.BuildConfig
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BioCompanyApiClient {
    companion object {
        private const val TAG = "BioCompanyApi"
        private const val BASE_URL = "https://connect.biocompany.de/"
        // AES-128-CBC key and IV of the Bio Company app API. They are not part of the public source:
        // the build reads them from local.properties (biocompany.aesKey / biocompany.aesIv, hex) or
        // the BIOCOMPANY_AES_KEY / BIOCOMPANY_AES_IV environment variables (GitHub secrets in CI).
        private val CIPHER_KEY: ByteArray? = hexOrNull(BuildConfig.BIOCOMPANY_AES_KEY)
        private val CIPHER_IV: ByteArray? = hexOrNull(BuildConfig.BIOCOMPANY_AES_IV)

        /** False in builds made without the key, e.g. a fork or a CI run without secrets. */
        val isConfigured: Boolean get() = CIPHER_KEY != null && CIPHER_IV != null

        private fun hexOrNull(hex: String): ByteArray? =
            hex.takeIf { it.length == 32 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
                ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var token: String? = null
    private var customerNumber: String? = null
    private var userEmail: String? = null
    private var userPassword: String? = null
    private var tokenExpiryEpochMillis: Long = 0

    val isLoggedIn: Boolean
        get() = token != null && System.currentTimeMillis() < tokenExpiryEpochMillis

    fun getAccount(): BioCompanyAccount? {
        if (!isLoggedIn) return null
        return BioCompanyAccount(
            email = userEmail.orEmpty(),
            customerNumber = customerNumber.orEmpty(),
            token = token.orEmpty(),
            tokenExpiryEpochMillis = tokenExpiryEpochMillis,
        )
    }

    fun login(email: String, password: String): Result<BioCompanyTokenResponse> = runCatching {
        userEmail = email
        userPassword = password
        val loginUrl = buildLoginUrl(email, password)
        val request = Request.Builder()
            .url(loginUrl)
            .get()
            .build()
        val json = client.newCall(request).execute().use { response -> JSONObject(response.body?.string() ?: throw IOException("Empty response")) }
        val respToken = json.getString("token")
        val respCustNum = json.getString("customernumber")
        token = respToken
        customerNumber = respCustNum
        tokenExpiryEpochMillis = decodeJwtExpiry(respToken)
        BioCompanyTokenResponse(token = respToken, customernumber = respCustNum)
    }

    fun getReceipts(): Result<List<BioCompanyReceiptEntry>> = runCatching {
        ensureAuth()
        val custNum = customerNumber!!
        val request = buildAuthRequest("GET", "receipts/$custNum")
        val raw = client.newCall(request).execute().use { response -> response.body?.string() ?: throw IOException("Empty response") }
        Log.d(TAG, "Receipt list response:\n$raw")
        val arr: JSONArray
        if (raw.trimStart().length > 0 && raw.trimStart()[0] == '['.toChar()) {
            arr = JSONArray(raw)
        } else {
            val obj = JSONObject(raw)
            val found = findFirstJSONArray(obj)
            arr = if (found != null) found else JSONArray().put(obj)
        }
        buildList {
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                add(BioCompanyReceiptEntry(
                    checkout = entry.optInt("checkout", 0),
                    date = entry.optString("date", ""),
                    receiptnumber = entry.optInt("receiptnumber", 0),
                    amount = entry.optDouble("amount", 0.0),
                    positions = entry.optInt("positions", 0),
                ))
            }
        }
    }

    fun getReceiptDetail(entry: BioCompanyReceiptEntry): Result<BioCompanyReceiptDetail> = runCatching {
        ensureAuth()
        val bodyJson = JSONObject().apply {
            put("checkout", entry.checkout)
            put("date", entry.date)
            put("receiptnumber", entry.receiptnumber)
            put("amount", entry.amount)
            put("positions", entry.positions)
            put("customernumber", customerNumber)
        }
        val jsonBody = bodyJson.toString().toRequestBody("application/json".toMediaType())
        val request = buildAuthRequest("POST", "receipts/detail", jsonBody)
        val response = client.newCall(request).execute().use { r ->
            r.body?.string() ?: throw IOException("Empty response")
        }
        // The list endpoint's `date` is date-only, so every imported receipt lands on midnight.
        // Logged to see whether the detail payload carries a checkout time we currently discard.
        Log.d(TAG, "Receipt detail response:\n$response")
        val detailJson = JSONObject(response)
        val items = buildList {
            val itemsArr = detailJson.optJSONArray("receipts")
                ?: detailJson.optJSONArray("positions")
                ?: detailJson.optJSONArray("items")
                ?: detailJson.optJSONArray("lines")
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    val itemJson = itemsArr.getJSONObject(i)
                    add(BioCompanyReceiptDetailItem(
                        name = itemJson.optString(
                            "articledescription",
                            itemJson.optString("name", itemJson.optString("article", itemJson.optString("bezeichnung", "Unknown"))),
                        ),
                        quantity = itemJson.optDouble("quantity", itemJson.optDouble("qty", 1.0)),
                        unit = itemJson.optString("unit", "").takeIf { it.isNotEmpty() },
                        priceCents = (
                            itemJson.optDouble(
                                "unit_price_gross",
                                itemJson.optDouble("amount", itemJson.optDouble("price", 0.0)),
                            ) * 100
                        ).toInt(),
                    ))
                }
            }
        }
        BioCompanyReceiptDetail(
            receiptnumber = detailJson.optInt("receiptnumber", entry.receiptnumber),
            checkout = detailJson.optInt("checkout", entry.checkout),
            date = detailJson.optString("date", entry.date),
            amount = detailJson.optDouble("amount", entry.amount),
            items = items,
        )
    }

    fun getCustomerPoints(): Result<BioCompanyPointsResponse> = runCatching {
        ensureAuth()
        val encrypted = encryptCustomerNumber(customerNumber!!)
        val request = buildAuthRequest("GET", "card/points/$encrypted")
        val json = JSONObject(client.newCall(request).execute().use { r -> r.body?.string() ?: throw IOException("Empty response") })
        BioCompanyPointsResponse(
            customernumber = if (json.has("customernumber")) json.get("customernumber").toString() else null,
            points = json.optInt("points", 0),
        )
    }

    fun addCardPoints(): Result<BioCompanyPointsResponse> = runCatching {
        ensureAuth()
        val payload = JSONObject().put("customernumber", customerNumber)
        val jsonBody = payload.toString().toRequestBody("application/json".toMediaType())
        val request = buildAuthRequest("POST", "card/points", jsonBody)
        val json = JSONObject(client.newCall(request).execute().use { r -> r.body?.string() ?: throw IOException("Empty response") })
        BioCompanyPointsResponse(
            customernumber = json.optString("customernumber", null),
            points = json.optInt("points", 0),
        )
    }

    fun restoreSession(
        email: String,
        password: String,
        authToken: String,
        custNumber: String,
        expiryEpochMillis: Long,
    ) {
        userEmail = email
        userPassword = password
        token = authToken
        customerNumber = custNumber
        tokenExpiryEpochMillis = expiryEpochMillis
    }

    fun clearSession() {
        token = null
        customerNumber = null
        userEmail = null
        userPassword = null
        tokenExpiryEpochMillis = 0
    }

    fun logout() {
        clearSession()
    }

    private fun ensureAuth() {
        if (!isLoggedIn) {
            if (userEmail != null && userPassword != null) {
                login(userEmail!!, userPassword!!).getOrNull()
                    ?: throw IOException("Re-authentication failed")
            } else {
                throw IOException("Not authenticated")
            }
        }
    }

    private fun decodeJwtExpiry(jwtToken: String): Long {
        return runCatching {
            val parts = jwtToken.split(".")
            if (parts.size < 2) return System.currentTimeMillis() + 10 * 60 * 1000L
            var payloadB64 = parts[1]
            payloadB64 += "=".repeat((-payloadB64.length).rem(4))
            val payload = String(
                Base64.getUrlDecoder().decode(payloadB64),
                StandardCharsets.UTF_8,
            )
            val json = JSONObject(payload)
            val exp = json.optLong("exp", 0)
            if (exp > 0) exp * 1000L else System.currentTimeMillis() + 10 * 60 * 1000L
        }.getOrDefault(System.currentTimeMillis() + 10 * 60 * 1000L)
    }

    private fun buildAuthRequest(method: String, path: String, body: RequestBody? = null): Request {
        val url = "$BASE_URL$path"
        return Request.Builder()
            .url(url)
            .method(method, body)
            .apply {
                token?.let { h -> header("Authorization", "Bearer $h") }
            }
            .build()
    }

    private fun buildLoginUrl(email: String, password: String): String {
        val builder = HttpUrl.Builder().apply {
            scheme("https")
            host("connect.biocompany.de")
            addPathSegments("user/login")
        }
        builder.addQueryParameter("username", email)
        builder.addQueryParameter("password", password)
        return builder.build().toString()
    }

    private fun findFirstJSONArray(root: JSONObject): JSONArray? {
        val names = root.names()
        if (names != null) {
            for (i in 0 until names.length()) {
                val v = root.get(names.getString(i))
                if (v is JSONArray) return v
            }
        }
        return null
    }

    private fun encryptCustomerNumber(customerNumber: String): String {
        val key = CIPHER_KEY
        val iv = CIPHER_IV
        if (key == null || iv == null) {
            throw IOException("Bio Company card points are not available in this build (no API key configured)")
        }
        val ivSpec = IvParameterSpec(iv)
        val keySpec = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(customerNumber.toByteArray(StandardCharsets.UTF_8))
        return encrypted.joinToString("") { String.format("%02x", it) }
    }
}
