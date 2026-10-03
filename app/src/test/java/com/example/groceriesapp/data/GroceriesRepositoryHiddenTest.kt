package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Parking a row in the inventory list's "Hidden" section, and getting it back. */
class GroceriesRepositoryHiddenTest {

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

    private fun GroceriesRepository.addCinnamon(): InventoryItem =
        addInventoryItem(InventoryItem(name = "Zimt", quantity = 1.0, unit = "pc"))

    @Test
    fun `hiding an item survives a reload`() {
        val repository = repository()
        val item = repository.addCinnamon()
        assertFalse(item.hidden)

        assertTrue(repository.setInventoryItemHidden(item.id, true)!!.hidden)

        // A second repository over the same directory reads the index back off disk.
        assertTrue(repository().inventory.value.single().hidden)
    }

    @Test
    fun `unhiding puts it back`() {
        val repository = repository()
        val item = repository.addCinnamon()
        repository.setInventoryItemHidden(item.id, true)

        assertFalse(repository.setInventoryItemHidden(item.id, false)!!.hidden)
        assertFalse(repository().inventory.value.single().hidden)
    }

    @Test
    fun `setting the flag it already has is a no-op`() {
        val repository = repository()
        val item = repository.addCinnamon()

        assertEquals(item, repository.setInventoryItemHidden(item.id, false))
    }

    @Test
    fun `an unknown id changes nothing`() {
        val repository = repository()
        repository.addCinnamon()

        assertNull(repository.setInventoryItemHidden("no-such-item", true))
        assertFalse(repository.inventory.value.single().hidden)
    }

    @Test
    fun `opening one piece keeps both halves hidden`() {
        val repository = repository()
        val item = repository.addInventoryItem(
            InventoryItem(name = "Vanilleschote", quantity = 3.0, unit = "pc"),
        )
        repository.setInventoryItemHidden(item.id, true)

        repository.openOneInventoryItem(item.id)

        val items = repository.inventory.value
        assertEquals(2, items.size)
        assertTrue(items.all(InventoryItem::hidden))
    }
}
