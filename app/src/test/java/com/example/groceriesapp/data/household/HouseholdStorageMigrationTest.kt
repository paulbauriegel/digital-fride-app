package com.example.groceriesapp.data.household

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HouseholdStorageMigrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var groceriesRoot: File

    private val defaultRoot: File
        get() = File(File(groceriesRoot, "households"), "default")

    private val globalReceipts: File
        get() = File(groceriesRoot, "receipts")

    @Before
    fun setUp() {
        groceriesRoot = temporaryFolder.newFolder("files", "groceries")
    }

    /** Builds the flat pre-household layout, with one receipt holding absolute image paths. */
    private fun writeLegacyTree(imageBytes: ByteArray = byteArrayOf(1, 2, 3, 4)) {
        val receiptDir = File(groceriesRoot, "receipts/r1")
        File(receiptDir, "tiles").mkdirs()
        File(receiptDir, "original").mkdirs()
        File(receiptDir, "receipt_preview.jpg").writeBytes(imageBytes)
        File(receiptDir, "tiles/tile_000.jpg").writeBytes(imageBytes)
        File(receiptDir, "original/frame_0001.jpg").writeBytes(imageBytes)

        val absolute = receiptDir.absolutePath
        File(groceriesRoot, "receipts/index.json").writeText(
            """
            {"schemaVersion":4,"receipts":[
              {"id":"r1",
               "previewPath":"$absolute/receipt_preview.jpg",
               "tilePaths":["$absolute/tiles/tile_000.jpg"],
               "originalFramePaths":["$absolute/original/frame_0001.jpg"]}
            ]}
            """.trimIndent(),
        )
        File(groceriesRoot, "inventory").mkdirs()
        File(groceriesRoot, "inventory/index.json")
            .writeText("""{"schemaVersion":4,"inventory":[{"id":"i1","name":"Milk"}]}""")
        File(groceriesRoot, "consumption").mkdirs()
        File(groceriesRoot, "consumption/log.json")
            .writeText("""{"schemaVersion":1,"events":[]}""")
    }

    private fun receiptIndex(): JSONObject =
        JSONObject(File(globalReceipts, "index.json").readText())

    @Test
    fun freshInstallCreatesTheDefaultHousehold() {
        val registry = HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 42L)

        assertEquals(listOf(Household("default", "Home", 42L)), registry.households)
        assertEquals("default", registry.activeHouseholdId)
        assertTrue(defaultRoot.isDirectory)
        assertTrue(File(File(groceriesRoot, "households"), "registry.json").isFile)
    }

    @Test
    fun onlyInventoryAndConsumptionMoveIntoTheHousehold() {
        writeLegacyTree()

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 1L)

        assertFalse(File(groceriesRoot, "inventory").exists())
        assertFalse(File(groceriesRoot, "consumption").exists())
        assertTrue(File(defaultRoot, "inventory/index.json").isFile)
        assertTrue(File(defaultRoot, "consumption/log.json").isFile)
    }

    @Test
    fun receiptsStayGlobalAndAreNotFiledUnderTheHousehold() {
        val bytes = byteArrayOf(9, 8, 7, 6, 5)
        writeLegacyTree(bytes)

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 1L)

        assertFalse("receipts must not move into the household", File(defaultRoot, "receipts").exists())
        assertTrue(globalReceipts.isDirectory)
        assertArrayEquals(bytes, File(globalReceipts, "r1/receipt_preview.jpg").readBytes())
        assertArrayEquals(bytes, File(globalReceipts, "r1/tiles/tile_000.jpg").readBytes())
    }

    @Test
    fun receiptIndexPathsAreRewrittenRelativeToTheGroceriesRoot() {
        writeLegacyTree()

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 1L)

        val receipt = receiptIndex().getJSONArray("receipts").getJSONObject(0)
        assertEquals(5, receiptIndex().getInt("schemaVersion"))
        assertEquals("receipts/r1/receipt_preview.jpg", receipt.getString("previewPath"))
        assertEquals(
            "receipts/r1/tiles/tile_000.jpg",
            receipt.getJSONArray("tilePaths").getString(0),
        )
    }

    @Test
    fun runningTwiceIsAByteIdenticalNoOp() {
        writeLegacyTree()
        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 1L)
        val afterFirst = snapshot()

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 999L)

        assertEquals(afterFirst, snapshot())
    }

    @Test
    fun crashAfterMovingButBeforeTheRegistryIsCompletedOnRerun() {
        writeLegacyTree()
        File(defaultRoot, "inventory").mkdirs()
        File(groceriesRoot, "inventory").copyRecursively(File(defaultRoot, "inventory"), true)
        File(groceriesRoot, "inventory").deleteRecursively()

        val registry = HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 5L)

        assertEquals(listOf("default"), registry.households.map(Household::id))
        assertTrue(File(defaultRoot, "inventory/index.json").isFile)
        assertTrue(File(defaultRoot, "consumption/log.json").isFile)
    }

    @Test
    fun corruptReceiptIndexIsLeftByteIdenticalAndDirectoriesStillMove() {
        writeLegacyTree()
        val corrupt = "{{{ not json at all"
        File(globalReceipts, "index.json").writeText(corrupt)

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 1L)

        assertEquals(corrupt, File(globalReceipts, "index.json").readText())
        assertTrue(File(defaultRoot, "inventory/index.json").isFile)
        assertTrue(File(File(groceriesRoot, "households"), "registry.json").isFile)
    }

    @Test
    fun globalRecipesDirectoryIsNeverTouched() {
        writeLegacyTree()
        File(groceriesRoot, "recipes/images").mkdirs()
        File(groceriesRoot, "recipes/user.json").writeText("""{"schemaVersion":1,"recipes":[]}""")

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 1L)

        assertTrue(File(groceriesRoot, "recipes/user.json").isFile)
        assertFalse(File(defaultRoot, "recipes").exists())
    }

    // --- Undoing the intermediate layout, where receipts were filed per household ---

    /** Recreates the layout shipped by the first households build. */
    private fun writeIntermediateLayout(householdId: String, receiptId: String, bytes: ByteArray) {
        val root = File(File(groceriesRoot, "households"), householdId)
        val receipts = File(root, "receipts")
        File(receipts, "$receiptId/tiles").mkdirs()
        File(receipts, "$receiptId/tiles/tile_000.jpg").writeBytes(bytes)
        File(receipts, "index.json").writeText(
            """
            {"schemaVersion":5,"receipts":[
              {"id":"$receiptId","previewPath":"receipts/$receiptId/tiles/tile_000.jpg"}
            ]}
            """.trimIndent(),
        )
        File(root, "inventory").mkdirs()
        File(root, "inventory/index.json").writeText("""{"schemaVersion":5,"inventory":[]}""")
    }

    @Test
    fun perHouseholdReceiptsAreLiftedBackToTheSharedDirectory() {
        val bytes = byteArrayOf(4, 5, 6)
        writeIntermediateLayout("default", "r1", bytes)
        HouseholdStorageMigration.writeRegistry(
            groceriesRoot,
            HouseholdRegistry(listOf(Household("default", "Home", 1L)), "default"),
        )

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 9L)

        assertFalse(File(defaultRoot, "receipts").exists())
        assertArrayEquals(bytes, File(globalReceipts, "r1/tiles/tile_000.jpg").readBytes())
        // Stored paths already read "receipts/…", which is correct against the groceries root.
        assertEquals(
            "receipts/r1/tiles/tile_000.jpg",
            receiptIndex().getJSONArray("receipts").getJSONObject(0).getString("previewPath"),
        )
        assertTrue("inventory must stay put", File(defaultRoot, "inventory/index.json").isFile)
    }

    @Test
    fun receiptsFromSeveralHouseholdsAreMergedWithoutLoss() {
        writeIntermediateLayout("default", "r1", byteArrayOf(1))
        writeIntermediateLayout("other1", "r2", byteArrayOf(2))
        HouseholdStorageMigration.writeRegistry(
            groceriesRoot,
            HouseholdRegistry(
                listOf(Household("default", "Home", 1L), Household("other1", "Flat", 2L)),
                "default",
            ),
        )

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 9L)

        assertArrayEquals(byteArrayOf(1), File(globalReceipts, "r1/tiles/tile_000.jpg").readBytes())
        assertArrayEquals(byteArrayOf(2), File(globalReceipts, "r2/tiles/tile_000.jpg").readBytes())
        val ids = receiptIndex().getJSONArray("receipts").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("id") }
        }
        assertEquals(setOf("r1", "r2"), ids.toSet())
    }

    @Test
    fun consolidationIsIdempotent() {
        writeIntermediateLayout("default", "r1", byteArrayOf(1))
        writeIntermediateLayout("other1", "r2", byteArrayOf(2))
        HouseholdStorageMigration.writeRegistry(
            groceriesRoot,
            HouseholdRegistry(
                listOf(Household("default", "Home", 1L), Household("other1", "Flat", 2L)),
                "default",
            ),
        )
        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 9L)
        val afterFirst = snapshot()

        HouseholdStorageMigration.migrateIfNeeded(groceriesRoot, now = 10L)

        assertEquals(afterFirst, snapshot())
    }

    /** Relative path -> content digest for every file under the groceries root. */
    private fun snapshot(): Map<String, String> =
        groceriesRoot.walkTopDown()
            .filter(File::isFile)
            .associate { it.relativeTo(groceriesRoot).path to it.readBytes().contentToString() }
}
