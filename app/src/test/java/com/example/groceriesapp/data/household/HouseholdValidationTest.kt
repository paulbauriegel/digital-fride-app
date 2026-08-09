package com.example.groceriesapp.data.household

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HouseholdValidationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val existing = listOf(
        Household("a", "Home", 1L),
        Household("b", "Beach flat", 2L),
    )

    @Test
    fun blankNamesAreRejected() {
        assertEquals("Enter a name.", householdNameError("", existing))
        assertEquals("Enter a name.", householdNameError("   ", existing))
    }

    @Test
    fun nameLengthBoundaryIsFortyCharacters() {
        assertNull(householdNameError("x".repeat(40), existing))
        assertNotNull(householdNameError("x".repeat(41), existing))
    }

    @Test
    fun leadingAndTrailingSpaceDoesNotCountTowardTheLimit() {
        assertNull(householdNameError("  " + "x".repeat(40) + "  ", existing))
    }

    @Test
    fun duplicateNamesAreRejectedIgnoringCaseAndPadding() {
        assertEquals(
            "A household with this name already exists.",
            householdNameError("  hOmE ", existing),
        )
    }

    @Test
    fun renamingAHouseholdToItsOwnNameIsAllowed() {
        assertNull(householdNameError("Home", existing, excludingId = "a"))
        assertNotNull(householdNameError("Home", existing, excludingId = "b"))
    }

    @Test
    fun idPatternRejectsSeparatorsAndDots() {
        // '.' must be rejected so no household can ever collide with a deletion tombstone.
        listOf("", "..", "a/b", "a.b", "a b", ".deleted_x", "x".repeat(65)).forEach { id ->
            assertEquals("expected '$id' to be rejected", false, HOUSEHOLD_ID_PATTERN.matches(id))
        }
        listOf("default", "a", "A-1_b", "x".repeat(64)).forEach { id ->
            assertEquals("expected '$id' to be accepted", true, HOUSEHOLD_ID_PATTERN.matches(id))
        }
    }

    @Test
    fun householdRootRefusesToEscapeTheHouseholdsDirectory() {
        val root = temporaryFolder.newFolder("groceries")
        listOf("..", "a/b", "a.b", "").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) { householdRoot(root, id) }
        }
    }

    @Test
    fun householdRootResolvesInsideHouseholds() {
        val root = temporaryFolder.newFolder("groceries")
        assertEquals(
            File(File(root, "households"), "default").canonicalFile,
            householdRoot(root, "default").canonicalFile,
        )
    }
}
