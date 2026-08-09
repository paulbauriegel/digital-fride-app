package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.GroceryCategory
import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The user-taught name → concept correction layer end to end: correcting one mis-classified item
 * reclassifies every same-named row now, survives restarts via the launch normalize pass, and
 * applies to items added later.
 */
class GroceriesRepositoryConceptCorrectionTest {

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

    // A receipt string the bundled catalogue cannot classify.
    private val unmatchedName = "WUNDERTRUNK 1L"

    @Test
    fun correctingAnItemReclassifiesItAndItsSameNamedSiblings() {
        val repository = repository()
        val first = repository.addInventoryItem(InventoryItem(id = "i1", name = unmatchedName))
        repository.addInventoryItem(
            InventoryItem(id = "i2", name = unmatchedName, addedAtEpochMillis = 1L),
        )
        assertNull(first.foodConceptId)

        val corrected = repository.setInventoryItemFoodConcept("i1", "PLANT_MILK")

        assertEquals("PLANT_MILK", corrected?.foodConceptId)
        assertEquals(GroceryCategory.DAIRY.name, corrected?.category)
        val sibling = repository.inventory.value.first { it.id == "i2" }
        assertEquals("PLANT_MILK", sibling.foodConceptId)
    }

    @Test
    fun correctionSurvivesReconstructionAndAppliesToItemsAddedLater() {
        repository().apply {
            addInventoryItem(InventoryItem(id = "i1", name = unmatchedName))
            setInventoryItemFoodConcept("i1", "PLANT_MILK")
        }

        val reloaded = repository()
        assertEquals("PLANT_MILK", reloaded.inventory.value.single().foodConceptId)

        val later = reloaded.addInventoryItem(
            InventoryItem(id = "i3", name = unmatchedName.lowercase(), addedAtEpochMillis = 1L),
        )
        assertEquals("PLANT_MILK", later.foodConceptId)
    }

    @Test
    fun correctionAlsoReclassifiesStoredReceiptItems() {
        val repository = repository()
        repository.saveReceipt(
            DigitalReceipt(id = "r1", items = listOf(ReceiptItem(id = "a", name = unmatchedName))),
        )
        repository.addInventoryItem(InventoryItem(id = "i1", name = unmatchedName))

        repository.setInventoryItemFoodConcept("i1", "PLANT_MILK")

        val receiptItem = repository.receipts.value.single().items.single()
        assertEquals("PLANT_MILK", receiptItem.foodConceptId)
        assertEquals(GroceryCategory.DAIRY.name, receiptItem.category)
    }

    @Test
    fun correctionsAreSharedAcrossHouseholds() {
        repository("default").apply {
            addInventoryItem(InventoryItem(id = "i1", name = unmatchedName))
            setInventoryItemFoodConcept("i1", "PLANT_MILK")
        }

        val beach = repository("beach")
        val added = beach.addInventoryItem(InventoryItem(id = "i2", name = unmatchedName))

        assertEquals("PLANT_MILK", added.foodConceptId)
    }

    @Test
    fun correctionBeatsTheCataloguesOwnKeywordMatch() {
        val repository = repository()
        // "Käse Wunder" matches the generic cheese keyword; the user says it's actually candy.
        val item = repository.addInventoryItem(InventoryItem(id = "i1", name = "Käse Wunder"))
        assertEquals("CHEESE", item.foodConceptId)

        val corrected = repository.setInventoryItemFoodConcept("i1", "CANDY")

        assertEquals("CANDY", corrected?.foodConceptId)
        // And it stays corrected through the next launch's re-match-by-name pass.
        assertEquals("CANDY", repository().inventory.value.single().foodConceptId)
    }
}
