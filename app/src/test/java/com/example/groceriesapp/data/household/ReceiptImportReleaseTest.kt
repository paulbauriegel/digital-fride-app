package com.example.groceriesapp.data.household

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The release that runs when a household is deleted. Pure JSON in, JSON out, like
 * [ReceiptIndexPathRewriteTest] — the rewrite has to be right on files this build did not write.
 */
class ReceiptImportReleaseTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun index(vararg records: String, mirror: List<String>) =
        """
        {"schemaVersion":7,"receipts":[
          {"id":"r1",
           "items":[{"id":"held","name":"Milch","quantity":1.0,"category":"DAIRY"},
                    {"id":"eaten","name":"Brot","quantity":1.0,"category":"BAKERY"}],
           "imports":[${records.joinToString(",")}],
           "importedItemIds":[${mirror.joinToString(",") { "\"$it\"" }}]}
        ]}
        """.trimIndent()

    private fun record(
        id: String,
        household: String?,
        disposition: String,
    ) = """{"receiptItemId":"$id",""" +
        (household?.let { """"householdId":"$it",""" } ?: """"householdId":null,""") +
        """"disposition":"$disposition"}"""

    private fun receiptOf(json: String) =
        JSONObject(json).getJSONArray("receipts").getJSONObject(0)

    private fun importsOf(json: String): Map<String, String?> {
        val array = receiptOf(json).getJSONArray("imports")
        return buildMap {
            repeat(array.length()) { position ->
                val record = array.getJSONObject(position)
                put(
                    record.getString("receiptItemId"),
                    record.takeUnless { it.isNull("householdId") }?.getString("householdId"),
                )
            }
        }
    }

    private fun mirrorOf(json: String): List<String> {
        val array = receiptOf(json).getJSONArray("importedItemIds")
        return List(array.length()) { array.getString(it) }
    }

    @Test
    fun goodsStillHeldByTheDeletedHouseholdAreReleasedFromBothRepresentations() {
        val rewritten = requireNotNull(
            releaseImportsForDeletedHouseholdJson(
                index(record("held", "beach", "IN_INVENTORY"), mirror = listOf("held")),
                "beach",
            ),
        )

        assertTrue(importsOf(rewritten).isEmpty())
        // The mirror is what an older build reads; leaving the id there would keep it blocked.
        assertTrue(mirrorOf(rewritten).isEmpty())
    }

    @Test
    fun consumedGoodsAreKeptButUnowned() {
        val rewritten = requireNotNull(
            releaseImportsForDeletedHouseholdJson(
                index(record("eaten", "beach", "USED_UP"), mirror = listOf("eaten")),
                "beach",
            ),
        )

        assertEquals(mapOf("eaten" to null), importsOf(rewritten))
        assertEquals(listOf("eaten"), mirrorOf(rewritten))
    }

    @Test
    fun thrownAwayAndCookedAreKeptToo() {
        listOf("THROWN_AWAY", "COOKED").forEach { disposition ->
            val rewritten = requireNotNull(
                releaseImportsForDeletedHouseholdJson(
                    index(record("eaten", "beach", disposition), mirror = listOf("eaten")),
                    "beach",
                ),
            )
            assertEquals(disposition, mapOf("eaten" to null), importsOf(rewritten))
        }
    }

    @Test
    fun anUnknownRecordOwnedByTheDeletedHouseholdIsReleased() {
        val rewritten = requireNotNull(
            releaseImportsForDeletedHouseholdJson(
                index(record("held", "beach", "UNKNOWN"), mirror = listOf("held")),
                "beach",
            ),
        )

        assertTrue(importsOf(rewritten).isEmpty())
    }

    @Test
    fun otherHouseholdsAreUntouched() {
        val rewritten = requireNotNull(
            releaseImportsForDeletedHouseholdJson(
                index(
                    record("held", "home", "IN_INVENTORY"),
                    record("eaten", "beach", "IN_INVENTORY"),
                    mirror = listOf("held", "eaten"),
                ),
                "beach",
            ),
        )

        assertEquals(mapOf("held" to "home"), importsOf(rewritten))
        assertEquals(listOf("held"), mirrorOf(rewritten))
    }

    @Test
    fun mirrorIdsWithNoRecordSurvive() {
        val rewritten = requireNotNull(
            releaseImportsForDeletedHouseholdJson(
                index(record("held", "beach", "IN_INVENTORY"), mirror = listOf("held", "legacy")),
                "beach",
            ),
        )

        // Nothing here says whose "legacy" was or where those goods went, so it stays blocked.
        assertEquals(listOf("legacy"), mirrorOf(rewritten))
    }

    @Test
    fun nothingToReleaseReturnsNull() {
        assertNull(
            releaseImportsForDeletedHouseholdJson(
                index(record("held", "home", "IN_INVENTORY"), mirror = listOf("held")),
                "beach",
            ),
        )
    }

    @Test
    fun aSchemaWithoutRecordsIsRefused() {
        assertNull(
            releaseImportsForDeletedHouseholdJson(
                """{"schemaVersion":6,"receipts":[{"id":"r1","importedItemIds":["held"]}]}""",
                "beach",
            ),
        )
    }

    @Test
    fun aSchemaNewerThanThisBuildIsRefused() {
        assertNull(
            releaseImportsForDeletedHouseholdJson(
                index(record("held", "beach", "IN_INVENTORY"), mirror = listOf("held"))
                    .replace("\"schemaVersion\":7", "\"schemaVersion\":8"),
                "beach",
            ),
        )
    }

    @Test
    fun anUnparseableIndexIsRefused() {
        assertNull(releaseImportsForDeletedHouseholdJson("{not json", "beach"))
    }

    @Test
    fun aSecondRunIsANoOp() {
        val once = requireNotNull(
            releaseImportsForDeletedHouseholdJson(
                index(
                    record("held", "beach", "IN_INVENTORY"),
                    record("eaten", "beach", "USED_UP"),
                    mirror = listOf("held", "eaten"),
                ),
                "beach",
            ),
        )

        assertNull(releaseImportsForDeletedHouseholdJson(once, "beach"))
    }

    @Test
    fun theFileWrapperRewritesInPlaceAndToleratesAMissingIndex() {
        val groceriesRoot = temporaryFolder.newFolder("files", "groceries")
        // No index yet: must be a silent no-op rather than creating or throwing.
        releaseImportsForDeletedHousehold(groceriesRoot, "beach")
        assertTrue(!File(groceriesRoot, "receipts/index.json").exists())

        File(groceriesRoot, "receipts").mkdirs()
        val index = File(groceriesRoot, "receipts/index.json")
        index.writeText(index(record("held", "beach", "IN_INVENTORY"), mirror = listOf("held")))

        releaseImportsForDeletedHousehold(groceriesRoot, "beach")

        assertTrue(importsOf(index.readText()).isEmpty())
    }
}
