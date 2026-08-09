package com.example.groceriesapp.feature.receipts

import com.example.groceriesapp.data.ImportDisposition
import com.example.groceriesapp.data.ReceiptItemImport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The per-row explanation of why an imported line cannot be ticked again. */
class ImportStatusLabelTest {

    private val names = mapOf("home" to "Home", "beach" to "Beach flat")

    private fun label(record: ReceiptItemImport?) = importStatusLabel(
        record = record,
        activeHouseholdId = "home",
        householdName = names::get,
    )

    private fun record(
        householdId: String? = "home",
        disposition: ImportDisposition = ImportDisposition.IN_INVENTORY,
    ) = ReceiptItemImport(
        receiptItemId = "a",
        householdId = householdId,
        disposition = disposition,
    )

    @Test
    fun anItemHeldHereReadsAsYours() {
        assertEquals("in your inventory", label(record()))
    }

    @Test
    fun anItemHeldElsewhereNamesThatHousehold() {
        assertEquals("in Beach flat", label(record(householdId = "beach")))
    }

    @Test
    fun consumedItemsSayWhatHappened() {
        assertEquals("used up", label(record(disposition = ImportDisposition.USED_UP)))
        assertEquals("thrown away", label(record(disposition = ImportDisposition.THROWN_AWAY)))
        assertEquals("cooked", label(record(disposition = ImportDisposition.COOKED)))
    }

    @Test
    fun consumedBeatsTheHousehold() {
        // Where it was eaten does not matter, and claiming a household would be noise.
        assertEquals(
            "used up",
            label(record(householdId = "beach", disposition = ImportDisposition.USED_UP)),
        )
    }

    @Test
    fun anUnknownRecordClaimsNothingBeyondTheImport() {
        assertEquals(
            "already imported",
            label(record(householdId = null, disposition = ImportDisposition.UNKNOWN)),
        )
    }

    @Test
    fun anUnresolvableHouseholdFallsBackRatherThanShowingAnId() {
        assertEquals("already imported", label(record(householdId = "vanished")))
    }

    @Test
    fun anUnattributedHeldItemFallsBackToo() {
        assertEquals("already imported", label(record(householdId = null)))
    }

    @Test
    fun aMissingRecordFallsBack() {
        assertEquals("already imported", label(null))
    }
}
