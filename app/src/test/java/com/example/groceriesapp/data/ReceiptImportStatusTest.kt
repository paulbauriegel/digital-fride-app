package com.example.groceriesapp.data

import com.example.groceriesapp.data.household.releaseImportsForDeletedHousehold
import com.example.groceriesapp.data.recipes.CookConsumption
import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Persistence of [DigitalReceipt.importedItemIds] — the schema-6 addition. */
class ReceiptImportStatusTest {

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

    private fun selection(receiptItemId: String) = ReceiptItemSelection(
        receiptItemId = receiptItemId,
        storageLocation = StorageLocation.FRIDGE,
        locationSource = LocationSource.SUGGESTED,
    )

    @Test
    fun importMarksAllLinesOfAMergedGroupAsImported() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(
                id = "r1",
                items = listOf(
                    ReceiptItem(id = "a", name = "Joghurt"),
                    ReceiptItem(id = "b", name = "Joghurt"),
                    ReceiptItem(id = "c", name = "Brot"),
                ),
            ),
        )
        val home = repository("default")

        home.addReceiptItemsToInventory(receipt.id, listOf(selection("a"), selection("b")))

        // The two yogurt lines merged into one inventory row, yet both count as imported.
        assertEquals(setOf("a", "b"), home.receipts.value.single().importedItemIds)
    }

    @Test
    fun importStatusSurvivesReload() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        repository("default").addReceiptItemsToInventory(receipt.id, listOf(selection("a")))

        assertEquals(setOf("a"), repository("default").receipts.value.single().importedItemIds)
    }

    @Test
    fun importStatusSurvivesRemovingTheInventoryItem() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        val home = repository("default")
        val added = home.addReceiptItemsToInventory(receipt.id, listOf(selection("a"))).single()

        home.removeInventoryItem(added.id, ConsumptionReason.THROWN_AWAY)

        assertTrue(home.inventory.value.isEmpty())
        assertEquals(setOf("a"), home.receipts.value.single().importedItemIds)
        // …and a fresh instance (which re-runs the backfill against the now-empty inventory)
        // must not lose it either.
        assertEquals(setOf("a"), repository("default").receipts.value.single().importedItemIds)
    }

    @Test
    fun schema5IndexWithoutTheKeyLoadsAsNotImported() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":5,"receipts":[
              {"id":"r1","items":[{"id":"a","name":"Milch","quantity":1.0,"category":"DAIRY"}]}
            ]}
            """.trimIndent(),
        )

        assertTrue(repository().receipts.value.single().importedItemIds.isEmpty())
    }

    @Test
    fun backfillInfersImportFromExistingInventoryByReceiptItemId() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":5,"receipts":[
              {"id":"r1","items":[
                {"id":"a","name":"Milch","quantity":1.0,"category":"DAIRY"},
                {"id":"b","name":"Brot","quantity":1.0,"category":"BAKERY"}
              ]}
            ]}
            """.trimIndent(),
        )
        val inventoryDir = File(groceriesRoot, "households/default/inventory")
        inventoryDir.mkdirs()
        File(inventoryDir, "index.json").writeText(
            """
            {"schemaVersion":5,"inventory":[
              {"id":"i1","name":"Milch","quantity":1.0,"receiptId":"r1","receiptItemId":"a",
               "addedAtEpochMillis":1700000000000}
            ]}
            """.trimIndent(),
        )

        assertEquals(setOf("a"), repository("default").receipts.value.single().importedItemIds)
    }

    @Test
    fun backfillInfersMergedImportsByNameAndUnit() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":5,"receipts":[
              {"id":"r1","items":[
                {"id":"a","name":"Joghurt","quantity":1.0,"category":"DAIRY"},
                {"id":"b","name":"Joghurt","quantity":1.0,"category":"DAIRY"}
              ]}
            ]}
            """.trimIndent(),
        )
        val inventoryDir = File(groceriesRoot, "households/default/inventory")
        inventoryDir.mkdirs()
        // Pre-schema-6 merged import: one row carrying only the first line's receiptItemId.
        File(inventoryDir, "index.json").writeText(
            """
            {"schemaVersion":5,"inventory":[
              {"id":"i1","name":"Joghurt","quantity":2.0,"receiptId":"r1","receiptItemId":"a",
               "addedAtEpochMillis":1700000000000}
            ]}
            """.trimIndent(),
        )

        assertEquals(setOf("a", "b"), repository("default").receipts.value.single().importedItemIds)
    }

    // --- provenance (schema 7) ---

    private fun recordFor(receiptItemId: String, householdId: String = "default") =
        repository(householdId).receipts.value.single().importOf(receiptItemId)

    @Test
    fun importStampsEveryLineOfAMergedGroupWithTheImportingHousehold() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(
                id = "r1",
                items = listOf(
                    ReceiptItem(id = "a", name = "Joghurt"),
                    ReceiptItem(id = "b", name = "Joghurt"),
                ),
            ),
        )
        val home = repository("beach")

        home.addReceiptItemsToInventory(receipt.id, listOf(selection("a"), selection("b")))

        listOf("a", "b").forEach { id ->
            val record = requireNotNull(recordFor(id, "beach"))
            assertEquals("beach", record.householdId)
            assertEquals(ImportDisposition.IN_INVENTORY, record.disposition)
            assertNotNull(record.importedAtEpochMillis)
        }
    }

    @Test
    fun usingUpOneRowMovesTheWholeMergeGroupToUsedUp() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(
                id = "r1",
                items = listOf(
                    ReceiptItem(id = "a", name = "Joghurt"),
                    ReceiptItem(id = "b", name = "Joghurt"),
                ),
            ),
        )
        val home = repository("default")
        // Both lines merged into a single row carrying only "a" as its receiptItemId.
        val row = home.addReceiptItemsToInventory(
            receipt.id,
            listOf(selection("a"), selection("b")),
        ).single()

        home.removeInventoryItem(row.id, ConsumptionReason.USED_UP)

        assertEquals(ImportDisposition.USED_UP, recordFor("a")?.disposition)
        assertEquals(ImportDisposition.USED_UP, recordFor("b")?.disposition)
    }

    @Test
    fun throwingAwayIsRecordedSeparatelyFromUsingUp() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        val home = repository("default")
        val added = home.addReceiptItemsToInventory(receipt.id, listOf(selection("a"))).single()

        home.removeInventoryItem(added.id, ConsumptionReason.THROWN_AWAY)

        assertEquals(ImportDisposition.THROWN_AWAY, recordFor("a")?.disposition)
    }

    @Test
    fun cookingAnItemAwayRecordsItAsCooked() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        val home = repository("default")
        val added = home.addReceiptItemsToInventory(receipt.id, listOf(selection("a"))).single()

        home.consumeForRecipe("recipe", "Pfannkuchen", listOf(CookConsumption.RemoveAll(added.id)))

        assertEquals(ImportDisposition.COOKED, recordFor("a")?.disposition)
    }

    @Test
    fun aPartialCookLeavesTheItemInInventory() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(
                id = "r1",
                items = listOf(ReceiptItem(id = "a", name = "Milch", quantity = 3.0)),
            ),
        )
        val home = repository("default")
        val added = home.addReceiptItemsToInventory(receipt.id, listOf(selection("a"))).single()

        home.consumeForRecipe(
            "recipe",
            "Pfannkuchen",
            listOf(CookConsumption.Decrement(added.id, 1.0)),
        )

        assertEquals(ImportDisposition.IN_INVENTORY, recordFor("a")?.disposition)
    }

    @Test
    fun theIndexCarriesBothTheRecordsAndTheLegacyMirror() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        repository("default").addReceiptItemsToInventory(receipt.id, listOf(selection("a")))

        val root = JSONObject(File(groceriesRoot, "receipts/index.json").readText())
        assertEquals(7, root.getInt("schemaVersion"))
        val stored = root.getJSONArray("receipts").getJSONObject(0)
        assertEquals("a", stored.getJSONArray("imports").getJSONObject(0).getString("receiptItemId"))
        assertEquals("a", stored.getJSONArray("importedItemIds").getString(0))
    }

    @Test
    fun aSchema6MirrorLoadsAsUnknownAndStillBlocksReimport() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":6,"receipts":[
              {"id":"r1","importedItemIds":["a"],
               "items":[{"id":"a","name":"Milch","quantity":1.0,"category":"DAIRY"}]}
            ]}
            """.trimIndent(),
        )

        // "second" holds no inventory, so the backfill cannot attribute the record to anyone.
        val other = repository("second")
        assertEquals(ImportDisposition.UNKNOWN, other.receipts.value.single().importOf("a")?.disposition)
        assertTrue(other.addReceiptItemsToInventory("r1", listOf(selection("a"))).isEmpty())
    }

    @Test
    fun anIdCarriedOnlyByTheMirrorStillBlocksReimport() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":7,"receipts":[
              {"id":"r1","imports":[],"importedItemIds":["a"],
               "items":[{"id":"a","name":"Milch","quantity":1.0,"category":"DAIRY"}]}
            ]}
            """.trimIndent(),
        )

        val other = repository("second")
        assertEquals(setOf("a"), other.receipts.value.single().importedItemIds)
        assertTrue(other.addReceiptItemsToInventory("r1", listOf(selection("a"))).isEmpty())
    }

    @Test
    fun aMalformedRecordCostsNeitherTheReceiptNorTheBlock() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":7,"receipts":[
              {"id":"r1","imports":[{"householdId":"gone"}],"importedItemIds":["a"],
               "items":[{"id":"a","name":"Milch","quantity":1.0,"category":"DAIRY"}]}
            ]}
            """.trimIndent(),
        )

        val other = repository("second")
        // The receipt survives — a throwing parse would have dropped it from the index entirely.
        assertEquals("r1", other.receipts.value.single().id)
        assertEquals(setOf("a"), other.receipts.value.single().importedItemIds)
    }

    @Test
    fun backfillPromotesAnUnattributedRecordItCanProve() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":6,"receipts":[
              {"id":"r1","importedItemIds":["a"],
               "items":[{"id":"a","name":"Milch","quantity":1.0,"category":"DAIRY"}]}
            ]}
            """.trimIndent(),
        )
        val inventoryDir = File(groceriesRoot, "households/default/inventory")
        inventoryDir.mkdirs()
        File(inventoryDir, "index.json").writeText(
            """
            {"schemaVersion":6,"inventory":[
              {"id":"i1","name":"Milch","quantity":1.0,"receiptId":"r1","receiptItemId":"a",
               "addedAtEpochMillis":1700000000000}
            ]}
            """.trimIndent(),
        )

        val record = requireNotNull(recordFor("a"))
        assertEquals("default", record.householdId)
        assertEquals(ImportDisposition.IN_INVENTORY, record.disposition)
    }

    @Test
    fun backfillNeverDowngradesAKnownDisposition() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        val home = repository("default")
        val added = home.addReceiptItemsToInventory(receipt.id, listOf(selection("a"))).single()
        home.removeInventoryItem(added.id, ConsumptionReason.THROWN_AWAY)

        // A fresh instance re-runs the backfill against a now-empty inventory.
        assertEquals(ImportDisposition.THROWN_AWAY, recordFor("a")?.disposition)
    }

    @Test
    fun backfillIsIdempotent() {
        File(groceriesRoot, "receipts").mkdirs()
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":6,"receipts":[
              {"id":"r1","importedItemIds":["a"],
               "items":[{"id":"a","name":"Milch","quantity":1.0,"category":"DAIRY"}]}
            ]}
            """.trimIndent(),
        )
        val inventoryDir = File(groceriesRoot, "households/default/inventory")
        inventoryDir.mkdirs()
        File(inventoryDir, "index.json").writeText(
            """
            {"schemaVersion":6,"inventory":[
              {"id":"i1","name":"Milch","quantity":1.0,"receiptId":"r1","receiptItemId":"a",
               "addedAtEpochMillis":1700000000000}
            ]}
            """.trimIndent(),
        )

        repository("default")
        val afterFirst = File(groceriesRoot, "receipts/index.json").readText()
        repository("default")

        assertEquals(afterFirst, File(groceriesRoot, "receipts/index.json").readText())
    }

    @Test
    fun backfillDoesNotAttributeAnotherHouseholdsRows() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        repository("beach").addReceiptItemsToInventory(receipt.id, listOf(selection("a")))

        // "default" holds nothing, so constructing it must leave the beach's record alone.
        repository("default")

        assertEquals("beach", recordFor("a", "beach")?.householdId)
    }

    @Test
    fun releasingADeletedHouseholdFreesHeldGoodsButNotConsumedOnes() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(
                id = "r1",
                items = listOf(
                    ReceiptItem(id = "held", name = "Milch"),
                    ReceiptItem(id = "eaten", name = "Brot"),
                ),
            ),
        )
        val beach = repository("beach")
        beach.addReceiptItemsToInventory(receipt.id, listOf(selection("held"), selection("eaten")))
        val eatenRow = beach.inventory.value.single { it.receiptItemId == "eaten" }
        beach.removeInventoryItem(eatenRow.id, ConsumptionReason.USED_UP)

        releaseImportsForDeletedHousehold(groceriesRoot, "beach")

        val home = repository("default")
        // The goods that were still on the beach's shelf are no longer tracked anywhere.
        assertEquals(1, home.addReceiptItemsToInventory("r1", listOf(selection("held"))).size)
        // The ones that were eaten are gone for good and stay blocked.
        assertTrue(home.addReceiptItemsToInventory("r1", listOf(selection("eaten"))).isEmpty())
    }

    @Test
    fun launchReconcileHealsAHouseholdDeletedWithoutARelease() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        repository("beach").addReceiptItemsToInventory(receipt.id, listOf(selection("a")))
        // A registry that never knew the beach — what a pre-release build's delete leaves behind.
        File(groceriesRoot, "households").mkdirs()
        File(groceriesRoot, "households/registry.json").writeText(
            """
            {"schemaVersion":1,"activeHouseholdId":"default","households":[
              {"id":"default","name":"Home","createdAtEpochMillis":1700000000000}
            ]}
            """.trimIndent(),
        )

        val home = repository("default")

        assertTrue(home.receipts.value.single().imports.isEmpty())
        assertEquals(1, home.addReceiptItemsToInventory("r1", listOf(selection("a"))).size)
    }

    @Test
    fun theReconcileLeavesRecordsAloneWithoutAReadableRegistry() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        repository("beach").addReceiptItemsToInventory(receipt.id, listOf(selection("a")))

        // No registry file at all: "no household exists" must not be inferred from that.
        assertEquals("beach", recordFor("a", "default")?.householdId)
    }

    @Test
    fun reimportOfAnAlreadyImportedItemIsANoOp() {
        val receipt = repository().saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = "Milch"))),
        )
        val home = repository("default")
        val added = home.addReceiptItemsToInventory(receipt.id, listOf(selection("a")))
        assertEquals(1, added.size)

        // Even after the inventory row is gone, the persisted status blocks re-adding silently…
        home.removeInventoryItem(added.single().id, ConsumptionReason.USED_UP)
        val again = home.addReceiptItemsToInventory(receipt.id, listOf(selection("a")))

        assertTrue(again.isEmpty())
        assertTrue(home.inventory.value.isEmpty())
    }
}
