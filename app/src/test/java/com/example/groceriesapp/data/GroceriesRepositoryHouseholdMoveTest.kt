package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the two inventory operations that write outside the normal add/remove path: moving a row
 * to another household, and undoing a removal. Both can lose an item if they go wrong, and neither
 * is reachable from the existing tests.
 */
class GroceriesRepositoryHouseholdMoveTest {

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

    private fun item(name: String = "Milch") = InventoryItem(
        name = name,
        quantity = 2.0,
        unit = "l",
        storageLocation = StorageLocation.FRIDGE.name,
    )

    private fun selection(receiptItemId: String) = ReceiptItemSelection(
        receiptItemId = receiptItemId,
        storageLocation = StorageLocation.FRIDGE,
        locationSource = LocationSource.SUGGESTED,
    )

    /** A receipt whose two identical lines merge into one inventory row when imported. */
    private fun mergedImport(householdId: String): InventoryItem {
        val receipt = repository().saveReceipt(
            DigitalReceipt(
                id = "r1",
                items = listOf(
                    ReceiptItem(id = "a", name = "Joghurt"),
                    ReceiptItem(id = "b", name = "Joghurt"),
                ),
            ),
        )
        return repository(householdId)
            .addReceiptItemsToInventory(receipt.id, listOf(selection("a"), selection("b")))
            .single()
    }

    private fun dispositions(householdId: String = "default") =
        repository(householdId).receipts.value.single().imports
            .associate { it.receiptItemId to (it.householdId to it.disposition) }

    @Test
    fun movingAnItemRepointsItsImportRecordsAtTheTargetHousehold() {
        val row = mergedImport("default")

        repository("default").moveInventoryItemToHousehold(row.id, "second")

        // The whole merge group moves, not just the line the row happens to name.
        assertEquals(
            mapOf(
                "a" to ("second" to ImportDisposition.IN_INVENTORY),
                "b" to ("second" to ImportDisposition.IN_INVENTORY),
            ),
            dispositions(),
        )
    }

    @Test
    fun theSourceHouseholdStillCannotReimportAMovedLine() {
        val row = mergedImport("default")
        repository("default").moveInventoryItemToHousehold(row.id, "second")

        val source = repository("default")
        assertTrue(source.addReceiptItemsToInventory("r1", listOf(selection("a"))).isEmpty())
    }

    @Test
    fun undoingARemovalPutsTheWholeMergeGroupBackInInventory() {
        val row = mergedImport("default")
        val home = repository("default")
        home.removeInventoryItem(row.id, ConsumptionReason.THROWN_AWAY)

        home.restoreRemovedInventoryItem(row)

        assertEquals(
            mapOf(
                "a" to ("default" to ImportDisposition.IN_INVENTORY),
                "b" to ("default" to ImportDisposition.IN_INVENTORY),
            ),
            dispositions(),
        )
    }

    @Test
    fun oneHouseholdCannotRestampAnothersRecords() {
        val row = mergedImport("beach")
        repository("beach").removeInventoryItem(row.id, ConsumptionReason.USED_UP)

        // Not a flow the app offers — undo always runs where the removal did — but the guard that
        // makes it a no-op is what stops a household from rewriting history it does not own.
        repository("default").restoreRemovedInventoryItem(row)

        assertEquals(
            mapOf(
                "a" to ("beach" to ImportDisposition.USED_UP),
                "b" to ("beach" to ImportDisposition.USED_UP),
            ),
            dispositions(),
        )
    }

    @Test
    fun undoingAReasonlessRemovalClearsTheUnknownDisposition() {
        val row = mergedImport("default")
        val home = repository("default")
        home.removeInventoryItem(row.id, reason = null)
        assertEquals(ImportDisposition.UNKNOWN, dispositions().getValue("a").second)

        home.restoreRemovedInventoryItem(row)

        assertEquals(ImportDisposition.IN_INVENTORY, dispositions().getValue("a").second)
    }

    @Test
    fun movingAnItemRemovesItHereAndMakesItReadableFromTheTargetHousehold() {
        val source = repository("default")
        val added = source.addInventoryItem(item())

        assertTrue(source.moveInventoryItemToHousehold(added.id, "second"))

        assertTrue(source.inventory.value.isEmpty())
        // A fresh instance, because the target is only ever loaded when it becomes active.
        val target = repository("second").inventory.value
        assertEquals(listOf(added.id), target.map(InventoryItem::id))
    }

    @Test
    fun aMovedItemKeepsEveryFieldRatherThanBeingReAdded() {
        val source = repository("default")
        val added = source.addInventoryItem(item())

        source.moveInventoryItemToHousehold(added.id, "second")

        // addInventoryItem would re-estimate expiry and could stack onto a same-day row; a move
        // must hand the item over untouched.
        assertEquals(added, repository("second").inventory.value.single())
    }

    @Test
    fun movingOntoAnExistingHouseholdKeepsWhatIsAlreadyThere() {
        val target = repository("second")
        val resident = target.addInventoryItem(item("Butter"))

        val source = repository("default")
        val moved = source.addInventoryItem(item("Milch"))
        source.moveInventoryItemToHousehold(moved.id, "second")

        val names = repository("second").inventory.value.map(InventoryItem::name)
        assertTrue(names.containsAll(listOf("Butter", "Milch")))
        assertEquals(2, names.size)
        assertTrue(repository("second").inventory.value.any { it.id == resident.id })
    }

    @Test
    fun movingToTheHouseholdItAlreadyLivesInIsRefusedAndChangesNothing() {
        val source = repository("default")
        val added = source.addInventoryItem(item())

        assertFalse(source.moveInventoryItemToHousehold(added.id, "default"))
        assertEquals(listOf(added.id), source.inventory.value.map(InventoryItem::id))
    }

    @Test
    fun movingAnUnknownItemIsRefused() {
        assertFalse(repository().moveInventoryItemToHousehold("nope", "second"))
    }

    @Test
    fun undoingARemovalRestoresTheRowAndUnLogsTheConsumptionEvent() {
        val repository = repository()
        val added = repository.addInventoryItem(item())

        repository.removeInventoryItem(added.id, ConsumptionReason.THROWN_AWAY)
        assertTrue(repository.inventory.value.isEmpty())
        assertEquals(1, repository.consumptionEvents.value.size)

        assertTrue(repository.restoreRemovedInventoryItem(added))

        assertEquals(added, repository.inventory.value.single())
        // Otherwise a mis-swipe would keep skewing the waste figures on Insights.
        assertTrue(repository.consumptionEvents.value.isEmpty())
    }

    @Test
    fun undoingTwiceDoesNotDuplicateTheRow() {
        val repository = repository()
        val added = repository.addInventoryItem(item())
        repository.removeInventoryItem(added.id, ConsumptionReason.USED_UP)

        assertTrue(repository.restoreRemovedInventoryItem(added))
        assertFalse(repository.restoreRemovedInventoryItem(added))

        assertEquals(1, repository.inventory.value.size)
    }

    @Test
    fun aRemovalWithNoReasonLeavesNothingToUnLog() {
        val repository = repository()
        val added = repository.addInventoryItem(item())
        repository.removeInventoryItem(added.id, reason = null)

        assertTrue(repository.restoreRemovedInventoryItem(added))

        assertEquals(added, repository.inventory.value.single())
        assertNull(repository.consumptionEvents.value.firstOrNull())
    }
}
