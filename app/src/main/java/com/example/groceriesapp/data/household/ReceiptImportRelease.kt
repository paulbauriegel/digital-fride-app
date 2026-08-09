package com.example.groceriesapp.data.household

import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.ImportDisposition
import com.example.groceriesapp.data.atomicWrite
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Releases a deleted household's claim on the receipt lines it imported.
 *
 * Receipts are shared device-wide while inventory is per household, so an import record outlives
 * the household that wrote it. Left alone it would block that line in every household forever, for
 * goods nothing tracks any more — which is the bug this file exists to prevent.
 *
 * The rule turns on whether the goods might still exist:
 *
 *  - still on a shelf ([ImportDisposition.IN_INVENTORY], or an unattributable record) — drop the
 *    record, because the household that held them is gone and re-importing them is legitimate;
 *  - eaten, binned or cooked — keep the record, merely un-owned, because the goods are gone for
 *    good and must stay blocked.
 *
 * Dropping is the only thing that unblocks: a line stays blocked for as long as any record names
 * it, so nulling the household would release nothing.
 */
private fun receiptIndexFile(groceriesRoot: File): File =
    File(File(groceriesRoot, HouseholdStorageMigration.RECEIPTS_DIRECTORY), INDEX_FILE)

internal fun releaseImportsForDeletedHousehold(groceriesRoot: File, householdId: String) {
    val index = receiptIndexFile(groceriesRoot)
    if (!index.isFile) return
    val original = runCatching { index.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return
    val rewritten = releaseImportsForDeletedHouseholdJson(original, householdId) ?: return
    atomicWrite(index, rewritten)
}

/**
 * Rewrites a receipt index to drop [householdId]'s claims, or returns null to mean "leave the file
 * exactly as it is".
 *
 * Null covers everything that is not a readable index of a schema known to carry import records —
 * including a schema newer than this build understands — and also the case where there is simply
 * nothing to release, so the caller never writes needlessly. Same refuse-rather-than-guess contract
 * as [rewriteReceiptIndexPathsJson], and the reason is the same: the phone holds the only copy.
 *
 * Both representations are rewritten. The schema-6 `importedItemIds` mirror is what an older build
 * reads, so releasing a record without also dropping its mirrored id would leave the line blocked
 * and the whole operation silently pointless.
 */
internal fun releaseImportsForDeletedHouseholdJson(
    indexJson: String,
    householdId: String,
): String? {
    val root = runCatching { JSONObject(indexJson) }.getOrNull() ?: return null
    val version = root.optInt("schemaVersion", -1)
    if (version !in IMPORT_RECORDS_SCHEMA_VERSION..GroceriesRepository.SCHEMA_VERSION) return null
    val receipts = root.optJSONArray(RECEIPTS_KEY) ?: return null

    var changed = false
    repeat(receipts.length()) { index ->
        val receipt = receipts.optJSONObject(index) ?: return@repeat
        val records = receipt.optJSONArray(IMPORTS_KEY) ?: return@repeat

        val kept = JSONArray()
        var receiptChanged = false
        repeat(records.length()) { position ->
            val record = records.optJSONObject(position) ?: return@repeat
            val owner = record.takeUnless { it.isNull(HOUSEHOLD_ID_KEY) }
                ?.optString(HOUSEHOLD_ID_KEY, "")
                ?.takeIf(String::isNotEmpty)
            if (owner != householdId) {
                kept.put(record)
                return@repeat
            }
            val disposition = ImportDisposition.entries
                .firstOrNull { it.name == record.optString(DISPOSITION_KEY) }
                ?: ImportDisposition.UNKNOWN
            receiptChanged = true
            if (disposition.goodsAreGone) {
                kept.put(record.put(HOUSEHOLD_ID_KEY, JSONObject.NULL))
            }
        }
        if (!receiptChanged) return@repeat

        // Ids the mirror carries but no record does are legacy and unattributable; they stay,
        // because nothing here proves whose they were or where the goods went.
        val keptIds = buildSet {
            repeat(kept.length()) { position ->
                kept.optJSONObject(position)?.optString(RECEIPT_ITEM_ID_KEY)
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }
        val recordedIds = buildSet {
            repeat(records.length()) { position ->
                records.optJSONObject(position)?.optString(RECEIPT_ITEM_ID_KEY)
                    ?.takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }
        val mirror = JSONArray()
        val legacyIds = receipt.optJSONArray(IMPORTED_ITEM_IDS_KEY)
        repeat(legacyIds?.length() ?: 0) { position ->
            val id = legacyIds?.optString(position, "").orEmpty()
            if (id.isNotEmpty() && (id in keptIds || id !in recordedIds)) mirror.put(id)
        }

        receipt.put(IMPORTS_KEY, kept)
        receipt.put(IMPORTED_ITEM_IDS_KEY, mirror)
        changed = true
    }

    return if (changed) root.toString() else null
}

/**
 * The oldest schema whose receipts carry import records. Schema 6 stores only the bare id mirror,
 * which names no household, so there is nothing there that can be attributed to a deletion — such
 * an index is refused and left for the launch-time reconcile in `GroceriesRepository` to heal once
 * it has rewritten the file at the current schema.
 */
private const val IMPORT_RECORDS_SCHEMA_VERSION = 7

private const val IMPORTS_KEY = "imports"
private const val IMPORTED_ITEM_IDS_KEY = "importedItemIds"
private const val RECEIPT_ITEM_ID_KEY = "receiptItemId"
private const val HOUSEHOLD_ID_KEY = "householdId"
private const val DISPOSITION_KEY = "disposition"
