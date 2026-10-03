package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Opening one of several sealed pieces ("3 pc" of plant drink) must split the row, not stamp the
 * whole row opened: only the split-off piece gets the opened mark, the shorter opened expiry
 * window, and a fill to track — and the split must survive the launch normalize pass, whose
 * duplicate-merge would otherwise glue the two rows straight back together.
 */
class GroceriesRepositoryOpenOneTest {

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
        // Empty concept rules still leave ShelfLifeCatalog.CATEGORY_FALLBACKS in play, so a DAIRY
        // item's opened window genuinely differs from its unopened one.
        shelfLifeCatalog = ShelfLifeCatalog(emptyMap()),
    )

    private fun threePack() = InventoryItem(
        name = "Alpro Soja Vanille",
        category = "DAIRY",
        quantity = 3.0,
        unit = "pc",
        priceCents = 599,
        storageLocation = StorageLocation.FRIDGE.name,
        locationSource = LocationSource.USER.name,
    )

    @Test
    fun opensExactlyOnePieceAndLeavesTheRestSealed() {
        val repo = repository()
        val added = repo.addInventoryItem(threePack())
        val sealedExpiry = repo.inventory.value.first { it.id == added.id }.expiresAtEpochMillis

        val opened = repo.openOneInventoryItem(added.id)

        assertNotNull(opened)
        assertNotEquals(added.id, opened!!.id)
        assertEquals(1.0, opened.quantity, 0.0)
        assertEquals(1.0, opened.packageSize!!, 0.0)
        assertNotNull(opened.openedAtEpochMillis)
        // DAIRY's opened window is shorter than its unopened one, so the date must move.
        assertNotEquals(sealedExpiry, opened.expiresAtEpochMillis)

        val remainder = repo.inventory.value.first { it.id == added.id }
        assertEquals(2.0, remainder.quantity, 0.0)
        assertNull(remainder.packageSize)
        assertNull(remainder.openedAtEpochMillis)
        assertEquals(sealedExpiry, remainder.expiresAtEpochMillis)
    }

    @Test
    fun splitsThePriceWithoutLosingACent() {
        val repo = repository()
        val added = repo.addInventoryItem(threePack())

        val opened = repo.openOneInventoryItem(added.id)!!
        val remainder = repo.inventory.value.first { it.id == added.id }

        assertEquals(599 / 3, opened.priceCents)
        assertEquals(599, opened.priceCents!! + remainder.priceCents!!)
    }

    @Test
    fun theSplitSurvivesTheLaunchNormalizePass() {
        val repo = repository()
        val added = repo.addInventoryItem(threePack())
        val opened = repo.openOneInventoryItem(added.id)!!

        // Constructing a second repository over the same root is what an app relaunch does:
        // init { normalizeStoredDataIfNeeded() } runs the duplicate-merge again.
        val relaunched = repository()

        val rows = relaunched.inventory.value.filter { it.name == added.name }
        assertEquals(2, rows.size)
        val (sealed, tracked) = rows.partition { it.openedAtEpochMillis == null }
        assertEquals(2.0, sealed.single().quantity, 0.0)
        assertEquals(opened.id, tracked.single().id)
        assertEquals(1.0, tracked.single().quantity, 0.0)
    }

    @Test
    fun aSinglePieceJustOpensInPlace() {
        val repo = repository()
        val added = repo.addInventoryItem(threePack().copy(quantity = 1.0))

        val opened = repo.openOneInventoryItem(added.id)

        assertEquals(added.id, opened?.id)
        assertNotNull(opened?.openedAtEpochMillis)
        assertEquals(1, repo.inventory.value.count { it.name == added.name })
    }

    @Test
    fun anUncountableAmountJustOpensInPlace() {
        val repo = repository()
        val added = repo.addInventoryItem(threePack().copy(quantity = 1.5, unit = "l"))

        val opened = repo.openOneInventoryItem(added.id)

        assertEquals(added.id, opened?.id)
        assertNotNull(opened?.openedAtEpochMillis)
        assertEquals(1, repo.inventory.value.count { it.name == added.name })
    }
}
