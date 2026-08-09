package com.example.groceriesapp.data.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a recipe page's HTML for [com.example.groceriesapp.data.recipes.RecipeJsonLd].
 *
 * Reads at most [MAX_BYTES]: a recipe page is a few hundred KB, and without a cap a mistyped URL
 * pointing at a large file would stream into memory on the device.
 */
class RecipeUrlClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    /** Blocking; call from Dispatchers.IO. */
    fun fetchHtml(url: String): String {
        val normalized = normalize(url)
        val request = Request.Builder()
            .url(normalized)
            // Some recipe sites serve a stripped page to unknown agents, which would drop the
            // JSON-LD block we are after.
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("The page could not be loaded (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("The page returned no content.")
            val contentType = body.contentType()
            if (contentType != null && contentType.type == "application" &&
                contentType.subtype !in setOf("xhtml+xml", "xml")
            ) {
                throw IOException("That link is not a web page.")
            }
            val source = body.source()
            source.request(MAX_BYTES)
            val available = minOf(MAX_BYTES, source.buffer.size)
            return source.buffer.snapshot(available.toInt()).utf8()
        }
    }

    /** Accepts a bare `example.com/recipe` paste; anything else must already carry a scheme. */
    private fun normalize(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotEmpty()) { "Enter a link to import." }
        return when {
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            else -> "https://$trimmed"
        }
    }

    private companion object {
        const val MAX_BYTES = 3L * 1024 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Mobile Safari/537.36"
    }
}
