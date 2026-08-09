package com.example.groceriesapp.data.taxonomy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FoodConceptCorrectionsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun file() = File(temporaryFolder.root, "concept_corrections.json")

    @Test
    fun correctionsPersistAcrossInstances() {
        FoodConceptCorrections(file()).set("WUNDERTRUNK 1L", "PLANT_MILK")

        val reloaded = FoodConceptCorrections(file())
        assertEquals("PLANT_MILK", reloaded.conceptFor("WUNDERTRUNK 1L")?.id)
    }

    @Test
    fun lookupIsCaseAndPaddingInsensitiveButOtherwiseExact() {
        val corrections = FoodConceptCorrections(file())
        corrections.set("WUNDERTRUNK 1L", "PLANT_MILK")

        assertEquals("PLANT_MILK", corrections.conceptFor("  Wundertrunk 1l ")?.id)
        // Exact-match by design: a correction must not leak onto other products by substring.
        assertNull(corrections.conceptFor("WUNDERTRUNK"))
    }

    @Test
    fun aCorrectionTheCatalogueAlreadyAgreesWithIsDroppedNotStored() {
        val corrections = FoodConceptCorrections(file())
        val catalogueAnswer = checkNotNull(FoodConceptCatalog.match("Vollmilch")).id

        corrections.set("Vollmilch", catalogueAnswer)

        assertTrue("no file should be written for a redundant correction", !file().exists())
    }

    @Test
    fun reTeachingTheCataloguesOwnAnswerRemovesTheStoredCorrection() {
        val corrections = FoodConceptCorrections(file())
        corrections.set("Vollmilch Extra", "PLANT_MILK")
        assertEquals("PLANT_MILK", corrections.conceptFor("Vollmilch Extra")?.id)

        val catalogueAnswer = checkNotNull(FoodConceptCatalog.match("Vollmilch Extra")).id
        corrections.set("Vollmilch Extra", catalogueAnswer)

        // The stored entry must disappear so future catalogue fixes for this name win again.
        assertNull(corrections.conceptFor("Vollmilch Extra"))
    }

    @Test
    fun unknownConceptIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FoodConceptCorrections(file()).set("Something", "NOT_A_CONCEPT")
        }
    }

    @Test
    fun aStaleStoredIdActsAsNoCorrection() {
        file().writeText("""{"schemaVersion":1,"corrections":{"weird thing":"REMOVED_CONCEPT"}}""")

        assertNull(FoodConceptCorrections(file()).conceptFor("Weird Thing"))
    }

    @Test
    fun anUnreadableFileBlocksWritesInsteadOfBlankingIt() {
        val corrupt = "{{{ not json"
        file().writeText(corrupt)

        val corrections = FoodConceptCorrections(file())
        assertNull(corrections.conceptFor("anything"))
        assertThrows(IllegalStateException::class.java) {
            corrections.set("WUNDERTRUNK 1L", "PLANT_MILK")
        }
        assertEquals(corrupt, file().readText())
    }
}
