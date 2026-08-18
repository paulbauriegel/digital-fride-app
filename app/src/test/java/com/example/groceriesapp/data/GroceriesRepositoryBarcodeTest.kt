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

    private companion object {
        const val EAN = "4008400202020"
    }
}
