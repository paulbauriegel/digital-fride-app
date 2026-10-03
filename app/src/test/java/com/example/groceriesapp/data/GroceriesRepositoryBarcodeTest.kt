package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** How a re-scanned barcode stacks onto the row it already produced. */
class GroceriesRepositoryBarcodeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var groceriesRoot: File

    @Before
    fun setUp() {
        groceriesRoot = temporaryFolder.newFolder("files", "groceries")
    }

    private fun repository() = GroceriesRepository(
        groceriesRoot = groceriesRoot,
        householdRoot = File(File(groceriesRoot, "households"), "default"),
        shelfLifeCatalog = ShelfLifeCatalog(emptyMap()),
    )

    private val today = System.currentTimeMillis()
    private val lastWeek = today - TimeUnit.DAYS.toMillis(7)

    @Test
    fun `a re-scan the same day stacks even when the name differs`() {
        val repository = repository()
        repository.addInventoryItem(
            InventoryItem(
                name = "Vollmilch",
                quantity = 1.0,
                unit = "l",
                barcode = EAN,
                purchasedAtEpochMillis = today,
            ),
        )
        repository.addInventoryItem(
            InventoryItem(
                // Open Food Facts returns the full brand name, which the name-based merge misses.
                name = "Weihenstephan Frische Vollmilch",
                quantity = 1.0,
                unit = "l",
                barcode = EAN,
                purchasedAtEpochMillis = today,
            ),
        )

        val items = repository.inventory.value
        assertEquals(1, items.size)
        assertEquals(2.0, items.single().quantity, 0.001)
        assertEquals("Vollmilch", items.single().name)
    }

    @Test
    fun `a manually added row learns the barcode when it is first scanned`() {
        val repository = repository()
        repository.addInventoryItem(
            InventoryItem(name = "Butter", quantity = 1.0, unit = "pc", purchasedAtEpochMillis = today),
        )
        repository.addInventoryItem(
            InventoryItem(
                name = "Butter",
                quantity = 1.0,
                unit = "pc",
                barcode = EAN,
                purchasedAtEpochMillis = today,
            ),
        )

        val items = repository.inventory.value
        assertEquals(1, items.size)
        assertEquals(EAN, items.single().barcode)
        assertEquals(2.0, items.single().quantity, 0.001)
    }

    @Test
    fun `a re-scan on another day starts a fresh row`() {
        val repository = repository()
        repository.addInventoryItem(
            InventoryItem(name = "Joghurt", quantity = 1.0, barcode = EAN, purchasedAtEpochMillis = lastWeek),
        )
        repository.addInventoryItem(
            InventoryItem(name = "Joghurt", quantity = 1.0, barcode = EAN, purchasedAtEpochMillis = today),
        )

        // Stacking across days would give today's pot last week's expiry estimate.
        assertEquals(2, repository.inventory.value.size)
    }

    @Test
    fun `lookup by barcode finds the row and survives a reload`() {
        repository().addInventoryItem(
            InventoryItem(name = "Haferdrink", quantity = 1.0, unit = "l", barcode = EAN),
        )

        val reloaded = repository()
        assertEquals("Haferdrink", reloaded.inventoryItemByBarcode(EAN)?.name)
        assertNull(reloaded.inventoryItemByBarcode("0000000000000"))
    }

    @Test
    fun `scanning from the editor links the code to that row and nothing else`() {
        val repository = repository()
        val receiptRow = repository.addInventoryItem(
            InventoryItem(name = "BAR. HAFERDRINK", quantity = 1.0, unit = "l"),
        )
        val other = repository.addInventoryItem(
            InventoryItem(name = "Butter", quantity = 1.0, unit = "pc"),
        )

        assertEquals(EAN, repository.setInventoryItemBarcode(receiptRow.id, EAN)?.barcode)

        val reloaded = repository()
        assertEquals(receiptRow.id, reloaded.inventoryItemByBarcode(EAN)?.id)
        assertNull(reloaded.inventory.value.first { it.id == other.id }.barcode)
    }

    @Test
    fun `a row that learned its barcode in the editor is what the next scan stacks onto`() {
        val repository = repository()
        val existing = repository.addInventoryItem(
            InventoryItem(name = "BAR. HAFERDRINK", quantity = 1.0, unit = "l", purchasedAtEpochMillis = today),
        )
        repository.setInventoryItemBarcode(existing.id, EAN)

        repository.addInventoryItem(
            InventoryItem(
                name = "Alnatura Haferdrink Natur",
                quantity = 1.0,
                unit = "l",
                barcode = EAN,
                purchasedAtEpochMillis = today,
            ),
        )

        val items = repository.inventory.value
        assertEquals(1, items.size)
        assertEquals(existing.id, items.single().id)
        assertEquals(2.0, items.single().quantity, 0.001)
    }

    @Test
    fun `linking a barcode leaves every other field alone`() {
        val repository = repository()
        val existing = repository.addInventoryItem(
            InventoryItem(name = "Haferdrink", quantity = 0.4, unit = "l", packageSize = 1.0),
        )

        val linked = repository.setInventoryItemBarcode(existing.id, EAN)

        assertEquals(existing.copy(barcode = EAN), linked)
        // A code the row already carries is not a change, so it does not rewrite the index.
        assertEquals(linked, repository.setInventoryItemBarcode(existing.id, EAN))
        assertNull(repository.setInventoryItemBarcode("no-such-item", EAN))
    }

    private companion object {
        const val EAN = "4008400202020"
    }
}
