package com.example.groceriesapp.feature.receipts

import com.example.groceriesapp.data.ImportDisposition
import com.example.groceriesapp.data.ReceiptItemImport

/**
 * Why an already-imported receipt line cannot be ticked again.
 *
 * Worth spelling out per row because the blocking is device-wide while inventory is not: the line
 * may be sitting in a household you are not currently looking at, or the goods may be long eaten.
 * "Already in your inventory" — what this screen used to say for every case — is wrong in both.
 *
 * Falls back to the bare "already imported" whenever the record cannot support a stronger claim:
 * no record, an UNKNOWN one, or a household id that resolves to no name. A vague label is a much
 * smaller problem than a confident wrong one.
 */
internal fun importStatusLabel(
    record: ReceiptItemImport?,
    activeHouseholdId: String,
    householdName: (String) -> String?,
): String = when (record?.disposition) {
    ImportDisposition.USED_UP -> "used up"
    ImportDisposition.THROWN_AWAY -> "thrown away"
    ImportDisposition.COOKED -> "cooked"
    ImportDisposition.IN_INVENTORY -> when (record.householdId) {
        activeHouseholdId -> "in your inventory"
        // Deleting a household releases its IN_INVENTORY records, so an id that resolves to
        // nothing means the release was refused rather than that the household vanished.
        null -> ALREADY_IMPORTED
        else -> record.householdId.let(householdName)?.let { "in $it" } ?: ALREADY_IMPORTED
    }
    else -> ALREADY_IMPORTED
}

private const val ALREADY_IMPORTED = "already imported"
