package com.example.groceriesapp.data

import com.example.groceriesapp.data.taxonomy.ExpirySource
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
 * Guards the one-way door the "Edit item" screen opens: hand-set expiry dates.
 *
 * Every path here re-estimates for an ordinary item, and the launch normalize pass does it on every
 * single construction of the repository — so before [ExpirySource.USER] existed, a date the user
 * typed would have survived exactly until the next app start or household switch, then vanished
 * with no trace and no undo. Each test therefore asserts both halves: the user's date is untouched,
 * *and* an otherwise identical estimated item still moves.
 */
class GroceriesRepositoryExpiryStickinessTest {

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
        // item gets a real estimated window to contrast the user's date against.
        shelfLifeCatalog = ShelfLifeCatalog(emptyMap()),
    )

    private val userDate = 1_800_000_000_000L

    /** An item whose expiry the user set by hand. */
    private fun userDatedItem(name: String = "Frische Milch") = InventoryItem(
        name = name,
        category = "DAIRY",
        quantity = 1.0,
        unit = "l",
        storageLocation = StorageLocation.FRIDGE.name,
        locationSource = LocationSource.USER.name,
        expiresAtEpochMillis = userDate,
        expiryEstimateSource = ExpirySource.USER.name,
    )

    /** The same product with an ordinary estimate, as the control in each test. */
    private fun estimatedItem(name: String = "Joghurt") = InventoryItem(
        name = name,
        category = "DAIRY",
        quantity = 1.0,
        unit = "l",
        storageLocation = StorageLocation.FRIDGE.name,
        locationSource = LocationSource.USER.name,
    )

    private fun expiryOf(repo: GroceriesRepository, id: String) =
        repo.inventory.value.first { it.id == id }.expiresAtEpochMillis

    @Test
    fun aUserDateSurvivesTheLaunchNormalizePass() {
        val repo = repository()
        val user = repo.addInventoryItem(userDatedItem())
        val estimated = repo.addInventoryItem(estimatedItem())
        val estimatedBefore = expiryOf(repo, estimated.id)

        // Constructing a second repository over the same root is what an app relaunch (or a
        // household switch) does: init { normalizeStoredDataIfNeeded() } runs again.
        val relaunched = repository()

        assertEquals(userDate, expiryOf(relaunched, user.id))
        assertEquals(ExpirySource.USER.name, relaunched.inventory.value.first { it.id == user.id }.expiryEstimateSource)
        assertEquals(estimatedBefore, expiryOf(relaunched, estimated.id))
    }

    @Test
    fun aUserDateSurvivesBeingMarkedOpened() {
        val repo = repository()
        val user = repo.addInventoryItem(userDatedItem())
        val estimated = repo.addInventoryItem(estimatedItem())
        val estimatedBefore = expiryOf(repo, estimated.id)

        val openedUser = repo.markInventoryItemOpened(user.id)
        val openedEstimated = repo.markInventoryItemOpened(estimated.id)

        assertEquals(userDate, openedUser?.expiresAtEpochMillis)
        // Opening still records the fact, it just doesn't touch the date.
        assertNotNull(openedUser?.openedAtEpochMillis)
        // DAIRY's opened window (3..5 days) is shorter than its unopened one (7..10), so the
        // control genuinely moves — otherwise this test would pass with the guard applied to both.
        assertNotEquals(estimatedBefore, openedEstimated?.expiresAtEpochMillis)
    }

    @Test
    fun aUserDateSurvivesAStorageMove() {
        val repo = repository()
        val user = repo.addInventoryItem(userDatedItem())
        val estimated = repo.addInventoryItem(estimatedItem())
        val estimatedBefore = expiryOf(repo, estimated.id)

        val movedUser = repo.setInventoryItemStorageLocation(user.id, StorageLocation.FREEZER)
        val movedEstimated =
            repo.setInventoryItemStorageLocation(estimated.id, StorageLocation.FREEZER)

        assertEquals(userDate, movedUser?.expiresAtEpochMillis)
        assertEquals(StorageLocation.FREEZER.name, movedUser?.storageLocation)
        assertNotEquals(estimatedBefore, movedEstimated?.expiresAtEpochMillis)
    }

    @Test
    fun aUserDateSurvivesAConceptCorrection() {
        val repo = repository()
        val user = repo.addInventoryItem(userDatedItem())

        // Re-normalizes the whole inventory, which is the path that recomputes every estimate.
        repo.setInventoryItemFoodConcept(user.id, "WHOLE_MILK")

        assertEquals(userDate, expiryOf(repo, user.id))
    }

    @Test
    fun aUserDateSurvivesAMoveToAnotherHousehold() {
        val repo = repository()
        val user = repo.addInventoryItem(userDatedItem())

        repo.moveInventoryItemToHousehold(user.id, "other")

        // The target household parses index.json directly rather than going through the normalize
        // pass, so this checks the read path as much as the guard.
        val other = GroceriesRepository(
            groceriesRoot = groceriesRoot,
            householdRoot = File(File(groceriesRoot, "households"), "other"),
            shelfLifeCatalog = ShelfLifeCatalog(emptyMap()),
        )

        assertEquals(userDate, other.inventory.value.single().expiresAtEpochMillis)
        assertEquals(ExpirySource.USER.name, other.inventory.value.single().expiryEstimateSource)
        assertNull(repo.inventory.value.firstOrNull { it.id == user.id })
    }
}
