package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Hand-editing a scanned receipt's line items. OCR mangles names, folds quantities into them, and
 * picks up payment lines that were never groceries, so every line has to be correctable,
 * removable, and replaceable by one typed in by hand.
 */
class ReceiptItemEditingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var groceriesRoot: File

    @Before
    fun setUp() {
        groceriesRoot = temporaryFolder.newFolder("files", "groceries")
    }

    private fun repository(householdId: String = "default") = GroceriesRepository(
        groceriesRoot = groceriesRoot,
        householdRoot = File(File(groceriesRoot, "households"), householdId),
        shelfLifeCatalog = ShelfLifeCatalog(emptyMap()),
    )

    private fun receiptWith(vararg items: ReceiptItem) = DigitalReceipt(
        id = "r1",
        merchant = "Edeka",
        totalCents = 616,
        items = items.toList(),
    )

    @Test
    fun editingALineKeepsItsIdAndRederivesTheConcept() {
        val repository = repository()
        repository.saveReceipt(receiptWith(ReceiptItem(id = "a", name = "G&g don.vol1m 0,55 e x 2")))
        val stored = repository.receipts.value.single().items.single()

        assertTrue(repository.updateReceiptItem("r1", stored.copy(name = "Vollmilch", priceCents = 110)))

        val edited = repository.receipts.value.single().items.single()
        assertEquals("a", edited.id)
        assertEquals("Vollmilch", edited.name)
        assertEquals(110, edited.priceCents)
        // Corrected on the way in, not at the next launch's normalize pass, so the row's icon and
        // category follow the new name immediately.
        assertNotNull(edited.foodConceptId)
    }

    @Test
    fun editsAndAdditionsSurviveAReload() {
        repository().apply {
            saveReceipt(receiptWith(ReceiptItem(id = "a", name = "Bio hand1 kaminwu")))
            updateReceiptItem("r1", ReceiptItem(id = "a", name = "Bio Handkäse", priceCents = 259))
            addReceiptItem("r1", ReceiptItem(id = "b", name = "Eiskaffee", quantity = 2.0, priceCents = 169))
        }

        val items = repository().receipts.value.single().items
        assertEquals(listOf("Bio Handkäse", "Eiskaffee"), items.map(ReceiptItem::name))
        assertEquals(listOf(259, 169), items.map(ReceiptItem::priceCents))
        assertEquals(2.0, items.last().quantity, 0.0)
    }

    @Test
    fun removingALineDropsItsImportRecordButNotTheInventoryItRaised() {
        val repository = repository()
        repository.saveReceipt(
            receiptWith(
                ReceiptItem(id = "a", name = "Joghurt"),
                ReceiptItem(id = "b", name = "Visa"),
            ),
        )
        repository.addReceiptItemsToInventory(
            "r1",
            listOf(
                ReceiptItemSelection("a", StorageLocation.FRIDGE, LocationSource.SUGGESTED),
                ReceiptItemSelection("b", StorageLocation.PANTRY, LocationSource.SUGGESTED),
            ),
        )
        assertEquals(setOf("a", "b"), repository.receipts.value.single().importedItemIds)

        assertTrue(repository.deleteReceiptItem("r1", "b"))

        val receipt = repository.receipts.value.single()
        assertEquals(listOf("a"), receipt.items.map(ReceiptItem::id))
        // The record only ever existed to stop a second import of that line; with the line gone it
        // would just make the imported/not-imported split count something no longer on the receipt.
        assertEquals(setOf("a"), receipt.importedItemIds)
        assertNull(receipt.importOf("b"))
        // The goods were bought either way — a scan artefact is no reason to bin a shelf row.
        assertTrue(repository.inventory.value.any { it.name == "Visa" })
    }

    @Test
    fun editingOrRemovingAnUnknownLineIsARefusal() {
        val repository = repository()
        repository.saveReceipt(receiptWith(ReceiptItem(id = "a", name = "Joghurt")))

        assertFalse(repository.updateReceiptItem("r1", ReceiptItem(id = "ghost", name = "Brot")))
        assertFalse(repository.deleteReceiptItem("r1", "ghost"))
        assertFalse(repository.addReceiptItem("missing-receipt", ReceiptItem(name = "Brot")))

        assertEquals(listOf("Joghurt"), repository.receipts.value.single().items.map(ReceiptItem::name))
    }
}
