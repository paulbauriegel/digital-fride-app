package com.example.groceriesapp.data.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Looks a scanned barcode up in the Open Food Facts product database.
 *
 * Only the fetch lives here; [OpenFoodFactsProductMapper] turns the body into a product, so the
 * field precedence and quantity parsing stay testable without a web server.
 */
class OpenFoodFactsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    /**
     * Blocking; call from Dispatchers.IO. Returns the raw JSON body.
     *
     * @throws ProductNotFoundException when Open Food Facts does not know the barcode.
     * @throws IOException on any other failure, carrying a message fit to show the user.
     */
    fun fetchProductJson(barcode: String): String {
        val request = Request.Builder()
            .url("$baseUrl/api/v2/product/$barcode.json?fields=$FIELDS")
            // Open Food Facts throttles agents it cannot identify, and their policy asks for a
            // contact address in the string.
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> throw ProductNotFoundException(barcode)
                response.code == 429 ->
                    throw IOException("Open Food Facts is busy. Try again in a moment.")
                !response.isSuccessful ->
                    throw IOException("Product lookup failed (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("Open Food Facts returned no content.")
            val source = body.source()
            source.request(MAX_BYTES)
            val available = minOf(MAX_BYTES, source.buffer.size)
            return source.buffer.snapshot(available.toInt()).utf8()
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://world.openfoodfacts.org"

        /**
         * Without this filter a single product is well over 100 KB of nutrition, ingredient and
         * image metadata; with it, a few hundred bytes.
         */
        const val FIELDS = "code,product_name,product_name_de,generic_name,generic_name_de," +
            "brands,quantity,product_quantity,product_quantity_unit"

        const val MAX_BYTES = 256L * 1024
        const val USER_AGENT = "GroceriesApp/1.0 (Android; https://github.com/paulbauriegel/digital-fride-app)"
    }
}
