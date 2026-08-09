package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the repository's own file I/O. This is only possible because the class takes root
 * [File]s and a parsed catalog rather than a `Context` — the household work introduced that seam.
 */
class GroceriesRepositoryStorageTest {

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

    private fun receiptIndexJson() =
        JSONObject(File(groceriesRoot, "receipts/index.json").readText())

    private fun selection(receiptItemId: String) = ReceiptItemSelection(
        receiptItemId = receiptItemId,
        storageLocation = StorageLocation.FRIDGE,
        locationSource = LocationSource.SUGGESTED,
    )

    @Test
    fun receiptImagePathsArePersistedRelativeToTheGroceriesRoot() {
        val preview = File(groceriesRoot, "receipts/r1/receipt_preview.jpg")
        val tile = File(groceriesRoot, "receipts/r1/tiles/tile_000.jpg")

        repository().saveReceipt(
            DigitalReceipt(
                id = "r1",
                previewPath = preview.absolutePath,
                tilePaths = listOf(tile.absolutePath),
            ),
        )

        val stored = receiptIndexJson().getJSONArray("receipts").getJSONObject(0)
        assertEquals("receipts/r1/receipt_preview.jpg", stored.getString("previewPath"))
        assertEquals(
            "receipts/r1/tiles/tile_000.jpg",
            stored.getJSONArray("tilePaths").getString(0),
        )
        assertEquals(7, receiptIndexJson().getInt("schemaVersion"))
    }

    @Test
    fun reloadingResolvesStoredPathsBackToAbsoluteOnes() {
        val previewPath = File(groceriesRoot, "receipts/r1/receipt_preview.jpg").absolutePath
        repository().saveReceipt(DigitalReceipt(id = "r1", previewPath = previewPath))

        assertEquals(previewPath, repository().receipts.value.single().previewPath)
    }

    @Test
    fun legacyAbsolutePathsThatNoLongerExistAreReRooted() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":4,"receipts":[
              {"id":"r1",
               "previewPath":"/data/user/0/com.example.groceriesapp/files/groceries/receipts/r1/p.jpg"}
            ]}
            """.trimIndent(),
        )

        assertEquals(
            File(groceriesRoot, "receipts/r1/p.jpg").absolutePath,
            repository().receipts.value.single().previewPath,
        )
    }

    // --- The point of the split: receipts shared, inventory not ---

    @Test
    fun receiptsAreVisibleFromEveryHousehold() {
        repository("default").saveReceipt(DigitalReceipt(id = "r1", merchant = "REWE"))

        val other = repository("beach")

        assertEquals(listOf("r1"), other.receipts.value.map(DigitalReceipt::id))
    }

    @Test
    fun inventoryDoesNotLeakBetweenHouseholds() {
        repository("default").addInventoryItem(InventoryItem(id = "i1", name = "Milk"))

        val other = repository("beach")

        assertTrue(other.inventory.value.isEmpty())
        // …and the first household still has it.
        assertEquals(listOf("Milk"), repository("default").inventory.value.map(InventoryItem::name))
    }

    @Test
    fun oneReceiptCanStockTwoHouseholdsIndependently() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(
                id = "r1",
                items = listOf(
                    ReceiptItem(id = "a", name = "Milch"),
                    ReceiptItem(id = "b", name = "Brot"),
                ),
            ),
        )

        val home = repository("default")
        val added = home.addReceiptItemsToInventory(
            receipt.id,
            listOf(selection("a")),
        )
        assertEquals(listOf("Milch"), added.map(InventoryItem::name))

        // The other household sees the same receipt with nothing taken from it yet.
        val beach = repository("beach")
        val addedElsewhere = beach.addReceiptItemsToInventory(
            receipt.id,
            listOf(selection("b")),
        )
        assertEquals(listOf("Brot"), addedElsewhere.map(InventoryItem::name))

        assertEquals(listOf("Milch"), repository("default").inventory.value.map(InventoryItem::name))
        assertEquals(listOf("Brot"), repository("beach").inventory.value.map(InventoryItem::name))
    }

    @Test
    fun addingTheSameItemTwiceInOneHouseholdDoesNotDuplicateIt() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        val home = repository("default")
        home.addReceiptItemsToInventory(receipt.id, listOf(selection("a")))
        val second = home.addReceiptItemsToInventory(
            receipt.id,
            listOf(selection("a")),
        )

        assertTrue("already-imported item must not be added twice", second.isEmpty())
        assertEquals(1, home.inventory.value.size)
    }

    @Test
    fun deletingAReceiptLeavesInventoryItemsInPlace() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        val home = repository("default")
        home.addReceiptItemsToInventory(receipt.id, listOf(selection("a")))

        assertTrue(home.deleteReceipt("r1"))

        assertEquals(listOf("Milch"), repository("default").inventory.value.map(InventoryItem::name))
    }

    // --- Corrupt-index hardening ---

    @Test
    fun anUnreadableIndexBlocksWritesInsteadOfBlankingIt() {
        File(groceriesRoot, "households/default/inventory").mkdirs()
        val corrupt = "{{{ not json"
        val index = File(groceriesRoot, "households/default/inventory/index.json")
        index.writeText(corrupt)

        val repository = repository()
        assertTrue("corrupt index must load as empty", repository.inventory.value.isEmpty())

        assertThrows(IllegalStateException::class.java) {
            repository.addInventoryItem(InventoryItem(id = "i1", name = "Milk"))
        }
        assertEquals(corrupt, index.readText())
    }

    // --- reloadReceipts, for rewrites this instance did not make ---

    @Test
    fun reloadPicksUpAnIndexRewrittenBehindOurBack() {
        val home = repository()
        home.saveReceipt(DigitalReceipt(id = "r1", merchant = "Alnatura"))

        // What deleting a non-active household looks like from here: someone else rewrote the file.
        repository("second").saveReceipt(DigitalReceipt(id = "r2", merchant = "REWE"))
        assertEquals(listOf("r1"), home.receipts.value.map(DigitalReceipt::id))

        home.reloadReceipts()

        assertEquals(setOf("r1", "r2"), home.receipts.value.map(DigitalReceipt::id).toSet())
    }

    @Test
    fun reloadKeepsWhatIsInMemoryWhenTheIndexHasBecomeUnreadable() {
        val home = repository()
        home.saveReceipt(DigitalReceipt(id = "r1", merchant = "Alnatura"))
        val corrupt = "{{{ not json"
        val index = File(groceriesRoot, "receipts/index.json")
        index.writeText(corrupt)

        home.reloadReceipts()

        // Publishing an empty list here would be worse than the stale one it replaces.
        assertEquals(listOf("r1"), home.receipts.value.map(DigitalReceipt::id))
        assertThrows(IllegalStateException::class.java) {
            home.saveReceipt(DigitalReceipt(id = "r2"))
        }
        assertEquals(corrupt, index.readText())
    }

    @Test
    fun anAbsentIndexIsNotTreatedAsCorrupt() {
        val repository = repository()
        repository.addInventoryItem(InventoryItem(id = "i1", name = "Milk"))
        assertEquals(1, repository.inventory.value.size)
    }

    /**
     * The launch-time normalize pass can persist during construction, before later-declared
     * properties exist. A receipt item with no category forces that write.
     */
    @Test
    fun normalizeOnConstructionCanPersistWithoutTrippingOverInitialisationOrder() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":4,"receipts":[
              {"id":"r1",
               "previewPath":"${File(groceriesRoot, "receipts/r1/p.jpg").absolutePath}",
               "items":[{"id":"it1","name":"Milch","quantity":1.0}]}
            ]}
            """.trimIndent(),
        )

        assertEquals("r1", repository().receipts.value.single().id)
        assertEquals(
            "receipts/r1/p.jpg",
            receiptIndexJson().getJSONArray("receipts").getJSONObject(0).getString("previewPath"),
        )
    }
}
