package com.example.groceriesapp.data

import com.example.groceriesapp.data.network.EdekaFormattedReceipt
import com.example.groceriesapp.data.network.EdekaFormattedReceiptLine
import com.example.groceriesapp.data.network.EdekaReceiptLineType.DISCOUNT
import com.example.groceriesapp.data.network.EdekaReceiptLineType.NORMAL
import com.example.groceriesapp.data.network.EdekaReceiptLineType.SEPARATOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdekaReceiptMappingTest {
    @Test
    fun multiplierLineBecomesTheQuantityOfTheProductBelowIt() {
        val items = receipt(
            line(NORMAL, "2.000 x 0,29€"),
            line(NORMAL, "Harry Dopp.Bröt.", "0,58€"),
            line(SEPARATOR),
        ).toReceiptItems()

        val rolls = items.single()
        assertEquals("Harry Dopp.Bröt.", rolls.name)
        assertEquals(2.0, rolls.quantity, 0.0)
        assertNull(rolls.unit)
        assertEquals(58, rolls.priceCents)
    }

    @Test
    fun fractionalMultiplierIsReadAsAWeightInKilograms() {
        val grapes = receipt(
            line(NORMAL, "1.164 x 4,15€"),
            line(NORMAL, "TRAUBEN hell kernl", "4,83€"),
            line(SEPARATOR),
        ).toReceiptItems().single()

        assertEquals(1.164, grapes.quantity, 1e-9)
        assertEquals("kg", grapes.unit)
        assertEquals(483, grapes.priceCents)
    }

    @Test
    fun couponInTheBlockIsSubtractedFromItsOwnPosition() {
        val items = receipt(
            line(NORMAL, "1.164 x 4,15€"),
            line(NORMAL, "TRAUBEN hell kernl", "4,83€"),
            line(DISCOUNT, "Coupon App Tafeltrauben", "-0,95€"),
            line(SEPARATOR),
            line(NORMAL, "BIOE Nektarinen", "2,99€"),
            line(SEPARATOR),
        ).toReceiptItems()

        assertEquals(listOf("TRAUBEN hell kernl", "BIOE Nektarinen"), items.map(ReceiptItem::name))
        assertEquals(listOf(388, 299), items.map(ReceiptItem::priceCents))
    }

    @Test
    fun repeatedIdenticalPositionsMergeIntoOneQuantity() {
        val items = receipt(
            line(NORMAL, "Alpro Soya Drink1l", "2,49€"),
            line(SEPARATOR),
            line(NORMAL, "Alpro Soya Drink1l", "2,49€"),
            line(SEPARATOR),
        ).toReceiptItems()

        val alpro = items.single()
        assertEquals("Alpro Soya Drink1l", alpro.name)
        assertEquals(2.0, alpro.quantity, 0.0)
        assertEquals(498, alpro.priceCents)
    }

    @Test
    fun differentlyPricedPositionsOfTheSameNameStaySeparate() {
        val items = receipt(
            line(NORMAL, "Tomaten", "1,99€"),
            line(SEPARATOR),
            line(NORMAL, "Tomaten", "2,49€"),
            line(SEPARATOR),
        ).toReceiptItems()

        assertEquals(listOf(199, 249), items.map(ReceiptItem::priceCents))
        assertEquals(listOf(1.0, 1.0), items.map(ReceiptItem::quantity))
    }

    /** The whole receipt from 2026-08-27, id 756442500, as the SOAP backend returns it. */
    @Test
    fun realReceiptWithRepeatedAndMultipliedPositionsSumsToItsTotal() {
        val items = receipt(
            line(NORMAL, "Alpro Soya Drink1l", "2,49€"),
            line(SEPARATOR),
            line(NORMAL, "Alpro Soya Drink1l", "2,49€"),
            line(SEPARATOR),
            line(NORMAL, "2.000 x 0,29€"),
            line(NORMAL, "Harry Dopp.Bröt.", "0,58€"),
            line(SEPARATOR),
            line(NORMAL, "Bio E.Inst.Kaffee", "4,45€"),
            line(SEPARATOR),
            line(NORMAL, "E.Festes Shampoo", "2,99€"),
            line(SEPARATOR),
            line(NORMAL, "Bio Hamf.Butterm.", "1,49€"),
            line(SEPARATOR),
            total = 1449,
        ).toReceiptItems()

        assertEquals(
            listOf(
                "Alpro Soya Drink1l" to 498,
                "Harry Dopp.Bröt." to 58,
                "Bio E.Inst.Kaffee" to 445,
                "E.Festes Shampoo" to 299,
                "Bio Hamf.Butterm." to 149,
            ),
            items.map { it.name to it.priceCents },
        )
        assertEquals(listOf(2.0, 2.0, 1.0, 1.0, 1.0), items.map(ReceiptItem::quantity))
        assertEquals(1449, items.sumOf { it.priceCents ?: 0 })
    }

    /** The whole receipt from 2026-08-20, id 752316197: one weighed position plus its coupon. */
    @Test
    fun realReceiptWithCouponSumsToItsTotal() {
        val items = receipt(
            line(NORMAL, "Bio E.Mangostr.", "1,95€"),
            line(SEPARATOR),
            line(NORMAL, "1.164 x 4,15€"),
            line(NORMAL, "TRAUBEN hell kernl", "4,83€"),
            line(DISCOUNT, "Coupon App Tafeltrauben", "-0,95€"),
            line(SEPARATOR),
            line(NORMAL, "BIOE Nektarinen", "2,99€"),
            line(SEPARATOR),
            line(NORMAL, "2.000 x 0,29€"),
            line(NORMAL, "BO Doppelbrötchen", "0,58€"),
            line(SEPARATOR),
            total = 940,
        ).toReceiptItems()

        assertEquals(
            listOf("Bio E.Mangostr.", "TRAUBEN hell kernl", "BIOE Nektarinen", "BO Doppelbrötchen"),
            items.map(ReceiptItem::name),
        )
        assertEquals(listOf(195, 388, 299, 58), items.map(ReceiptItem::priceCents))
        assertEquals(listOf(null, "kg", null, null), items.map(ReceiptItem::unit))
        assertEquals(940, items.sumOf { it.priceCents ?: 0 })
    }

    @Test
    fun danglingMultiplierWithoutAProductIsDropped() {
        assertEquals(
            emptyList<ReceiptItem>(),
            receipt(line(NORMAL, "2.000 x 0,29€"), line(SEPARATOR)).toReceiptItems(),
        )
    }

    @Test
    fun standaloneNegativeLineDoesNotDiscountTheBlockBefore() {
        val items = receipt(
            line(NORMAL, "Pfandrückgabe", "-0,25€"),
            line(SEPARATOR),
            line(NORMAL, "Bio Milch", "1,49€"),
            line(SEPARATOR),
        ).toReceiptItems()

        assertEquals(listOf("Pfandrückgabe", "Bio Milch"), items.map(ReceiptItem::name))
        assertEquals(listOf(-25, 149), items.map(ReceiptItem::priceCents))
    }

    @Test
    fun dotAndCommaDecimalPricesAreBothAccepted() {
        val items = receipt(
            line(NORMAL, "Milch", "1,29"),
            line(SEPARATOR),
            line(NORMAL, "Brot", "2.49"),
        ).toReceiptItems()

        assertEquals(listOf(129, 249), items.map(ReceiptItem::priceCents))
    }

    @Test
    fun malformedPriceDoesNotDropTheProduct() {
        val item = receipt(line(NORMAL, "Pfandbon", "---")).toReceiptItems().single()

        assertEquals("Pfandbon", item.name)
        assertNull(item.priceCents)
    }

    @Test
    fun rawTextKeepsMultiplierAndDiscountLinesButNotSeparators() {
        val detail = receipt(
            line(NORMAL, "2.000 x 0,29€"),
            line(NORMAL, "Harry Dopp.Bröt.", "0,58€"),
            line(DISCOUNT, "App-Rabatt", "-0,20€"),
            line(SEPARATOR),
        )

        assertEquals(
            "2.000 x 0,29€\nHarry Dopp.Bröt.  0,58€\nApp-Rabatt  -0,20€",
            detail.toRawText(),
        )
    }

    private fun line(
        type: com.example.groceriesapp.data.network.EdekaReceiptLineType,
        left: String = "",
        right: String = "",
    ) = EdekaFormattedReceiptLine(type, left, right)

    private fun receipt(
        vararg lines: EdekaFormattedReceiptLine,
        total: Int? = 129,
    ) = EdekaFormattedReceipt(
        id = 12,
        canceled = false,
        totalCents = total,
        currency = "EUR",
        retailerName = "EDEKA",
        shopName = null,
        lines = lines.toList(),
    )
}
