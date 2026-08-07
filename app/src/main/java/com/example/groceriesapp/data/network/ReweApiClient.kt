package com.example.groceriesapp.data.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ReweUnauthorizedException(message: String) : IOException(message)

/**
 * Talks to the REWE mobile API. Every call needs both the mTLS client identity (TLS handshake) and
 * a bearer token, and mimics the headers the official Android client sends.
 */
class ReweApiClient(
    identity: ReweMtlsIdentity,
    private var accessToken: String,
    private val refreshAccessToken: (() -> String?)? = null,
) {
    companion object {
        private const val API_HOST = "mobile-api.rewe.de"
        private const val CLIENT_HOST = "mobile-clients-api.rewe.de"
        private const val USER_AGENT = "REWE-Mobile-Client/5.7.3 Android/14 Phone/Google_Pixel_8"
    }

    private val client = OkHttpClient.Builder()
        .sslSocketFactory(identity.sslSocketFactory, identity.trustManager)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Stable per-session device identifier, matching how the app reuses one `rdfa` value. */
    private val rdfa = UUID.randomUUID().toString()

    fun setAccessToken(token: String) {
        accessToken = token
    }

    fun getEbons(page: Int = 1, objectsPerPage: Int = 20): Result<ReweEbonPage> = runCatching {
        val url = "https://$CLIENT_HOST/api/ebons".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("objectsPerPage", objectsPerPage.toString())
            .build()
        val payload = executeWithRefresh(CLIENT_HOST, "application/json") { builder ->
            builder.url(url).get()
        }.use { response ->
            if (!response.isSuccessful) {
                throw IOException("Receipt list failed (HTTP ${response.code})")
            }
            response.body?.string() ?: throw IOException("Empty response")
        }
        parseEbons(payload)
    }

    fun downloadReceiptPdf(receiptId: String): Result<ByteArray> = runCatching {
        val url = "https://$CLIENT_HOST/api/receipts/$receiptId/pdf"
        executeWithRefresh(CLIENT_HOST, "application/pdf") { builder ->
            builder.url(url).get()
        }.use { response ->
            if (!response.isSuccessful) {
                throw IOException("Receipt download failed (HTTP ${response.code})")
            }
            response.body?.bytes() ?: throw IOException("Empty response")
        }
    }

    fun marketSearch(query: String): Result<String> = runCatching {
        val url = "https://$API_HOST/api/v3/market/search".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .build()
        executeWithRefresh(API_HOST, "application/json") { builder ->
            builder.url(url).get()
        }.use { response ->
            response.body?.string() ?: throw IOException("Empty response")
        }
    }

    private fun executeWithRefresh(
        host: String,
        accept: String,
        configure: (Request.Builder) -> Request.Builder,
    ): Response {
        val response = client.newCall(buildRequest(host, accept, configure)).execute()
        if (response.code != 401) return response

        response.close()
        val refreshed = refreshAccessToken?.invoke()
            ?: throw ReweUnauthorizedException("Session expired. Reconnect your REWE account.")
        accessToken = refreshed
        return client.newCall(buildRequest(host, accept, configure)).execute()
    }

    private fun buildRequest(
        host: String,
        accept: String,
        configure: (Request.Builder) -> Request.Builder,
    ): Request = configure(Request.Builder())
        .header("user-agent", USER_AGENT)
        .header("accept", accept)
        .header("Authorization", "Bearer $accessToken")
        .header("rdfa", rdfa)
        .header("Correlation-Id", UUID.randomUUID().toString())
        .header("rd-service-types", "UNKNOWN")
        .header("x-rd-service-types", "UNKNOWN")
        .header("rd-is-lsfk", "false")
        .header("a-b-test-groups", "productlist-citrusad")
        .build()

    private fun parseEbons(payload: String): ReweEbonPage {
        val getEbons = JSONObject(payload)
            .optJSONObject("data")
            ?.optJSONObject("getEbons")
            ?: throw IOException("Unexpected receipt list response")
        val itemsJson = getEbons.optJSONArray("items")
        val entries = buildList {
            for (index in 0 until (itemsJson?.length() ?: 0)) {
                val item = itemsJson!!.getJSONObject(index)
                val marketJson = item.optJSONObject("market")
                add(
                    ReweEbonEntry(
                        receiptId = item.optString("receiptId"),
                        receiptTimestamp = item.optString("receiptTimestamp"),
                        receiptTotalPriceCents = item.optInt("receiptTotalPrice", 0),
                        market = marketJson?.let { market ->
                            ReweMarket(
                                name = market.optString("name").takeIf(String::isNotBlank),
                                street = market.optString("street").takeIf(String::isNotBlank),
                                zipCode = market.optString("zipCode").takeIf(String::isNotBlank),
                                city = market.optString("city").takeIf(String::isNotBlank),
                                wwIdent = market.optString("wwIdent").takeIf(String::isNotBlank),
                            )
                        },
                        cancelled = item.optBoolean("cancelled", false),
                    ),
                )
            }
        }
        val pagination = getEbons.optJSONObject("pagination")
        return ReweEbonPage(
            entries = entries.filter { it.receiptId.isNotBlank() },
            currentPage = pagination?.optInt("currentPage", 1) ?: 1,
            pageCount = pagination?.optInt("pageCount", 1) ?: 1,
        )
    }
}

data class ReweEbonPage(
    val entries: List<ReweEbonEntry>,
    val currentPage: Int,
    val pageCount: Int,
)
