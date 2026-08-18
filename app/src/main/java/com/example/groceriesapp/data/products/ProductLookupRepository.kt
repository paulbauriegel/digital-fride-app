package com.example.groceriesapp.data.products

import com.example.groceriesapp.data.network.OpenFoodFactsClient
import com.example.groceriesapp.data.network.OpenFoodFactsProduct
import com.example.groceriesapp.data.network.OpenFoodFactsProductMapper
import com.example.groceriesapp.data.network.ProductNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException

/** Where a barcode lookup has got to. Every terminal state still lets the user add the item. */
sealed class ProductLookupState {
    data object Idle : ProductLookupState()
    data class Loading(val barcode: String) : ProductLookupState()
    data class Found(val product: OpenFoodFactsProduct) : ProductLookupState()
    data class NotFound(val barcode: String) : ProductLookupState()
    data class Error(val barcode: String, val message: String) : ProductLookupState()
}

/**
 * Resolves scanned barcodes against Open Food Facts.
 *
 * Kept out of GroceriesRepository on purpose: that class is built from plain files so it runs in
 * JVM tests, and it is rebuilt on every household switch — a long-lived scope and an HTTP client
 * in there would leak one scope per switch and drag a network dependency into the storage layer.
 * Barcodes mean the same thing in every household, so one instance outlives them all.
 */
class ProductLookupRepository(
    private val client: OpenFoodFactsClient = OpenFoodFactsClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFlow = MutableStateFlow<ProductLookupState>(ProductLookupState.Idle)
    val state: StateFlow<ProductLookupState> = stateFlow.asStateFlow()

    private var inFlight: Job? = null

    /** The last product resolved, so re-opening the dialog after a rotation costs no request. */
    private var cached: OpenFoodFactsProduct? = null

    fun lookup(barcode: String) {
        inFlight?.cancel()
        cached?.takeIf { it.barcode == barcode }?.let {
            stateFlow.value = ProductLookupState.Found(it)
            return
        }
        // ML Kit happily decodes the QR codes printed next to a barcode, whose payload is a URL.
        // Those are not product codes and must never reach Open Food Facts.
        if (!barcode.isProductCode()) {
            stateFlow.value = ProductLookupState.NotFound(barcode)
            return
        }
        stateFlow.value = ProductLookupState.Loading(barcode)
        inFlight = scope.launch {
            stateFlow.value = try {
                val product = fetch(barcode)
                cached = product
                ProductLookupState.Found(product)
            } catch (notFound: ProductNotFoundException) {
                ProductLookupState.NotFound(barcode)
            } catch (malformed: JSONException) {
                ProductLookupState.Error(barcode, "Open Food Facts sent an unreadable answer.")
            } catch (failure: IOException) {
                ProductLookupState.Error(
                    barcode,
                    failure.message ?: "No connection to Open Food Facts.",
                )
            }
        }
    }

    fun clear() {
        inFlight?.cancel()
        stateFlow.value = ProductLookupState.Idle
    }

    private fun fetch(barcode: String): OpenFoodFactsProduct = try {
        OpenFoodFactsProductMapper.map(client.fetchProductJson(barcode), barcode)
    } catch (notFound: ProductNotFoundException) {
        // Open Food Facts stores UPC-A as the 13-digit EAN the rest of the world prints, so a bare
        // 12-digit US code misses until it is padded. A miss arrives as either a 404 or a
        // status-0 body, hence the retry sitting above both.
        val padded = barcode.takeIf { it.length == 12 } ?: throw notFound
        OpenFoodFactsProductMapper.map(client.fetchProductJson("0$padded"), barcode)
    }

    /** EAN-8 through GTIN-14, the range Open Food Facts indexes. */
    private fun String.isProductCode(): Boolean = length in 8..14 && all(Char::isDigit)
}
