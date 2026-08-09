package com.example.groceriesapp.data

import com.example.groceriesapp.data.network.BioCompanyReceiptDetailItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reported 2026-08-09: a "10% Bonus" loyalty-discount line came through the BIO COMPANY detail
 * API mixed in with real products, distinguishable only by a negative quantity (-1). It must not
 * become a phantom grocery item.
 */
class BioCompanyReceiptMappingTest {

    @Test
    fun negativeQuantityAdjustmentLinesAreDropped() {
        val items = listOf(
            BioCompanyReceiptDetailItem(name = "Hafermilch", quantity = 1.0, unit = "pc", priceCents = 149),
            BioCompanyReceiptDetailItem(name = "10% Bonus", quantity = -1.0, unit = null, priceCents = -20),
        )

        val receiptItems = items.toReceiptItems()

        assertEquals(listOf("Hafermilch"), receiptItems.map(ReceiptItem::name))
    }

    @Test
    fun realProductsKeepTheirExactQuantity() {
        val items = listOf(
            BioCompanyReceiptDetailItem(name = "Bananen", quantity = 0.486, unit = "kg", priceCents = 129),
        )

        val receiptItems = items.toReceiptItems()

        assertEquals(0.486, receiptItems.single().quantity, 0.0)
    }

    @Test
    fun aZeroQuantityLineIsAlsoDropped() {
        val items = listOf(
            BioCompanyReceiptDetailItem(name = "Punkte eingelöst", quantity = 0.0, unit = null, priceCents = 0),
        )

        assertEquals(emptyList<ReceiptItem>(), items.toReceiptItems())
    }
}
