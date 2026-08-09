package com.example.groceriesapp.feature.receipts

import com.example.groceriesapp.data.ReceiptItem
import com.example.groceriesapp.data.taxonomy.resolveCategory

/**
 * Bottle/container deposit lines (e.g. "Pfand Fl") are real receipt rows but not groceries, so they
 * default to unselected. Local scans already drop these during parsing (see
 * [com.example.groceriesapp.vision.parsing.ReceiptParser]'s NON_ITEM_LABELS), but imported receipts
 * such as BIO COMPANY orders carry item names verbatim from the source API and skip that filter.
 */
internal fun ReceiptItem.isDepositLine(): Boolean = name.contains("pfand", ignoreCase = true)

/**
 * Items pre-ticked for import: confidently parsed food rows. Deposit lines, rows needing review,
 * and non-food rows (household goods, unrecognized text) start unchecked — "don't add" by default,
 * one tap to include anyway.
 */
internal fun List<ReceiptItem>.defaultSelectionIds(): Set<String> = this
    .filterNot(ReceiptItem::needsReview)
    .filterNot(ReceiptItem::isDepositLine)
    .filter { resolveCategory(it.category, it.name).supportsExpiry }
    .map(ReceiptItem::id)
    .toSet()
