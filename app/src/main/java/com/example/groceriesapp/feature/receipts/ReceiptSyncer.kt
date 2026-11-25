package com.example.groceriesapp.feature.receipts

import android.content.Context
import com.example.groceriesapp.data.BioCompanyRepository
import com.example.groceriesapp.data.EdekaRepository
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.ReweRepository
import com.example.groceriesapp.data.toRawText
import com.example.groceriesapp.data.toReceiptItems
import com.example.groceriesapp.vision.parsing.ReweReceiptParser
import com.example.groceriesapp.vision.parsing.ReweReceiptPdfExtractor
import com.example.groceriesapp.vision.parsing.categoryName
import com.example.groceriesapp.vision.parsing.foodConceptId
import com.example.groceriesapp.vision.parsing.normalizedUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** What one store account contributed to a sync. */
data class SourceSyncOutcome(
    /** Receipts that were not in the archive before this sync. */
    val imported: Int = 0,
    /** Receipts the store listed but that could not be downloaded or read. */
    val failed: Int = 0,
    /** Set when the store's receipt list itself could not be loaded. */
    val error: String? = null,
)

class ReceiptSyncSource(
    val label: String,
    val isConnected: () -> Boolean,
    val sync: suspend () -> SourceSyncOutcome,
)

sealed interface ReceiptSyncState {
    data object Idle : ReceiptSyncState
    data object Running : ReceiptSyncState

    /** Held until the UI has shown [message] and calls [ReceiptSyncer.acknowledge]. */
    data class Finished(val message: String, val needsSetup: Boolean = false) : ReceiptSyncState
}

/**
 * Pulls every receipt that is not yet in the archive from all connected store accounts.
 * Sources run side by side; one store failing never stops the others.
 */
class ReceiptSyncer(private val sources: List<ReceiptSyncSource>) {
    private val _state = MutableStateFlow<ReceiptSyncState>(ReceiptSyncState.Idle)
    val state: StateFlow<ReceiptSyncState> = _state.asStateFlow()

    suspend fun syncAll() {
        if (_state.getAndUpdate { ReceiptSyncState.Running } == ReceiptSyncState.Running) return
        try {
            val connected = sources.filter { it.isConnected() }
            if (connected.isEmpty()) {
                _state.value = ReceiptSyncState.Finished(
                    message = "Connect a store account to sync receipts.",
                    needsSetup = true,
                )
                return
            }
            val outcomes = coroutineScope {
                connected.map { source ->
                    async {
                        source.label to try {
                            source.sync()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            SourceSyncOutcome(error = error.message ?: "Sync failed")
                        }
                    }
                }.awaitAll()
            }
            _state.value = ReceiptSyncState.Finished(summarize(outcomes))
        } catch (cancelled: CancellationException) {
            _state.value = ReceiptSyncState.Idle
            throw cancelled
        }
    }

    fun acknowledge() {
        _state.update { if (it is ReceiptSyncState.Finished) ReceiptSyncState.Idle else it }
    }

    companion object {
        internal fun summarize(outcomes: List<Pair<String, SourceSyncOutcome>>): String {
            val imported = outcomes.sumOf { it.second.imported }
            val problems = outcomes.flatMap { (label, outcome) ->
                listOfNotNull(
                    outcome.error?.let { "$label: $it" },
                    outcome.failed.takeIf { it > 0 }?.let { "$label: $it failed" },
                )
            }
            val headline = when {
                imported == 1 -> "Imported 1 new receipt"
                imported > 1 -> "Imported $imported new receipts"
                problems.isEmpty() -> "Receipts are up to date"
                else -> null
            }
            return (listOfNotNull(headline) + problems).joinToString(" · ")
        }
    }
}

private const val LIST_FAILED = "Couldn't load receipts"

fun storeReceiptSyncer(
    context: Context,
    groceries: GroceriesRepository,
    bioCompany: BioCompanyRepository,
    rewe: ReweRepository,
    edeka: EdekaRepository,
): ReceiptSyncer {
    val reweExtractor = ReweReceiptPdfExtractor(context)
    val reweParser = ReweReceiptParser()
    return ReceiptSyncer(
        listOf(
            ReceiptSyncSource("BIO COMPANY", { bioCompany.account.value != null }) {
                syncBioCompany(groceries, bioCompany)
            },
            ReceiptSyncSource("REWE", { rewe.account.value != null }) {
                syncRewe(groceries, rewe, reweExtractor, reweParser)
            },
            ReceiptSyncSource("EDEKA", { edeka.account.value != null }) {
                syncEdeka(groceries, edeka)
            },
        ),
    )
}

private suspend fun syncBioCompany(
    groceries: GroceriesRepository,
    bioCompany: BioCompanyRepository,
): SourceSyncOutcome = withContext(Dispatchers.IO) {
    val entries = bioCompany.fetchReceipts().getOrElse {
        val message = (bioCompany.syncState.value as? BioCompanyRepository.SyncState.ERROR)?.message
        return@withContext SourceSyncOutcome(error = message ?: LIST_FAILED)
    }
    var imported = 0
    var failed = 0
    for (entry in entries) {
        if (groceries.hasBioCompanyReceipt(entry.receiptnumber, entry.checkout)) continue
        val detail = bioCompany.fetchReceiptDetail(entry).getOrNull()
        if (detail == null) {
            failed++
            continue
        }
        groceries.importBioCompanyReceipt(
            merchant = "BIO COMPANY",
            dateIso = entry.date,
            totalCents = (entry.amount * 100).roundToInt(),
            items = detail.items.toReceiptItems(),
            bioCompanyReceiptNumber = entry.receiptnumber,
            bioCompanyCheckout = entry.checkout,
        )
        imported++
    }
    SourceSyncOutcome(imported, failed)
}

private suspend fun syncRewe(
    groceries: GroceriesRepository,
    rewe: ReweRepository,
    extractor: ReweReceiptPdfExtractor,
    parser: ReweReceiptParser,
): SourceSyncOutcome = withContext(Dispatchers.IO) {
    val entries = rewe.fetchReceipts().getOrElse {
        val message = (rewe.syncState.value as? ReweRepository.SyncState.ERROR)?.message
        return@withContext SourceSyncOutcome(error = message ?: LIST_FAILED)
    }
    var imported = 0
    var failed = 0
    for (entry in entries) {
        if (groceries.hasReweReceipt(entry.receiptId)) continue
        val items = rewe.downloadReceiptPdf(entry)
            .mapCatching { pdf -> parser.parse(extractor.extract(pdf)) }
            .getOrNull()
        if (items.isNullOrEmpty()) {
            failed++
            continue
        }
        groceries.importReweReceipt(
            merchant = entry.market?.name ?: "REWE",
            dateIso = entry.receiptTimestamp,
            totalCents = entry.receiptTotalPriceCents,
            items = items.map { item ->
                ReceiptItem(
                    name = item.name,
                    quantity = item.quantity.coerceAtLeast(0.001),
                    unit = item.normalizedUnit(),
                    priceCents = item.finalPriceCents.toInt(),
                    category = item.categoryName(),
                    foodConceptId = item.foodConceptId(),
                )
            },
            reweReceiptId = entry.receiptId,
        )
        imported++
    }
    SourceSyncOutcome(imported, failed)
}

private suspend fun syncEdeka(
    groceries: GroceriesRepository,
    edeka: EdekaRepository,
): SourceSyncOutcome = withContext(Dispatchers.IO) {
    val entries = edeka.fetchReceipts().getOrElse {
        val message = (edeka.syncState.value as? EdekaRepository.SyncState.ERROR)?.message
        return@withContext SourceSyncOutcome(error = message ?: LIST_FAILED)
    }
    var imported = 0
    var failed = 0
    for (entry in entries) {
        if (groceries.hasEdekaReceipt(entry.id)) continue
        val detail = edeka.fetchReceiptDetail(entry).getOrNull()
        if (detail == null) {
            failed++
            continue
        }
        // A cancelled receipt is not a purchase; skipping it quietly keeps it from being
        // reported as a failure on every single sync.
        if (detail.canceled) continue
        groceries.importEdekaReceipt(
            merchant = detail.shopName ?: detail.retailerName ?: entry.merchantName,
            dateIso = entry.dateAndTime,
            totalCents = detail.totalCents ?: entry.totalCents,
            currency = detail.currency.takeIf(String::isNotBlank) ?: entry.currency,
            rawText = detail.toRawText(),
            items = detail.toReceiptItems(),
            edekaReceiptId = entry.id,
        )
        imported++
    }
    SourceSyncOutcome(imported, failed)
}
