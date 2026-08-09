package com.example.groceriesapp.data.household

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseholdRegistryJsonTest {

    private fun registry(vararg households: Household, active: String) =
        HouseholdRegistry(households.toList(), active)

    @Test
    fun roundTripsThroughJson() {
        val original = registry(
            Household("default", "Home", 1_000L),
            Household("abc-123", "Beach flat", 2_000L),
            active = "abc-123",
        )
        val restored = JSONObject(original.toJson().toString()).toHouseholdRegistryOrNull()
        assertEquals(original, restored)
    }

    @Test
    fun unparseableEntryListYieldsNull() {
        assertNull(JSONObject("""{"schemaVersion":1}""").toHouseholdRegistryOrNull())
    }

    @Test
    fun emptyHouseholdListYieldsNull() {
        val json = JSONObject("""{"schemaVersion":1,"activeHouseholdId":"a","households":[]}""")
        assertNull(json.toHouseholdRegistryOrNull())
    }

    @Test
    fun missingActiveIdFallsBackToFirstHousehold() {
        val json = JSONObject(
            """{"schemaVersion":1,"households":[{"id":"a","name":"A"},{"id":"b","name":"B"}]}""",
        )
        assertEquals("a", json.toHouseholdRegistryOrNull()?.activeHouseholdId)
    }

    @Test
    fun activeIdNamingAnUnknownHouseholdFallsBackToFirst() {
        val json = JSONObject(
            """
            {"schemaVersion":1,"activeHouseholdId":"gone",
             "households":[{"id":"a","name":"A"},{"id":"b","name":"B"}]}
            """.trimIndent(),
        )
        assertEquals("a", json.toHouseholdRegistryOrNull()?.activeHouseholdId)
    }

    @Test
    fun malformedEntryIsSkippedWhileSiblingsSurvive() {
        val json = JSONObject(
            """
            {"schemaVersion":1,"activeHouseholdId":"b",
             "households":[{"id":"../escape","name":"Bad"},{"id":"","name":"Empty"},
                           {"id":"c","name":"   "},{"id":"b","name":"Good"}]}
            """.trimIndent(),
        )
        val parsed = json.toHouseholdRegistryOrNull()
        assertEquals(listOf(Household("b", "Good", 0L)), parsed?.households)
        assertEquals("b", parsed?.activeHouseholdId)
    }

    @Test
    fun unknownKeysAreTolerated() {
        val json = JSONObject(
            """
            {"schemaVersion":99,"activeHouseholdId":"a","futureField":true,
             "households":[{"id":"a","name":"A","createdAtEpochMillis":7,"extra":"x"}]}
            """.trimIndent(),
        )
        assertEquals(
            HouseholdRegistry(listOf(Household("a", "A", 7L)), "a"),
            json.toHouseholdRegistryOrNull(),
        )
    }

    @Test
    fun storedNamesAreTrimmed() {
        val json = JSONObject(
            """{"schemaVersion":1,"activeHouseholdId":"a","households":[{"id":"a","name":"  A  "}]}""",
        )
        assertEquals("A", json.toHouseholdRegistryOrNull()?.households?.single()?.name)
    }
}
