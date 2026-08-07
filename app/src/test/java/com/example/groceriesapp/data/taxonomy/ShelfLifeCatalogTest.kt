package com.example.groceriesapp.data.taxonomy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfLifeCatalogTest {

    private val bundled: Map<String, ShelfLifeRule> by lazy {
        // Assets are not on the unit-test classpath, so read the bundled file from the source tree.
        // The working directory is the module dir under Gradle but the repo root in some IDE runs.
        val candidates = listOf(
            "src/main/assets/shelf_life_rules.json",
            "app/src/main/assets/shelf_life_rules.json",
        )
        val file = candidates.map(::File).firstOrNull(File::isFile)
        assertTrue(
            "shelf_life_rules.json not found; looked in $candidates relative to " +
                File("").absolutePath,
            file != null,
        )
        ShelfLifeCatalog.parse(file!!.readText())
    }

    @Test
    fun bundledRulesParseAndReferenceRealConcepts() {
        assertTrue("no rules parsed", bundled.isNotEmpty())
        val conceptIds = FoodConceptCatalog.all.map(FoodConcept::id).toSet()
        val unknown = bundled.keys - conceptIds
        assertTrue("rules reference unknown concepts: $unknown", unknown.isEmpty())
    }

    @Test
    fun bundledRangesAreOrderedAndPositive() {
        bundled.forEach { (id, rule) ->
            listOf(
                "unopenedFridge" to rule.unopenedFridgeDays,
                "openedFridge" to rule.openedFridgeDays,
                "pantry" to rule.pantryDays,
                "freezer" to rule.freezerDays,
            ).forEach { (label, range) ->
                if (range != null) {
                    assertTrue("$id.$label starts at ${range.first}", range.first >= 1)
                    assertTrue("$id.$label is inverted", range.last >= range.first)
                }
            }
        }
    }

    @Test
    fun everyFoodCategoryHasAFallback() {
        GroceryCategory.entries.filter(GroceryCategory::supportsExpiry).forEach { category ->
            assertNotNull(
                "no fallback for $category",
                ShelfLifeCatalog.CATEGORY_FALLBACKS[category],
            )
        }
    }

    @Test
    fun nonFoodCategoriesHaveNoRule() {
        val catalog = ShelfLifeCatalog(bundled)
        assertNull(catalog.categoryRule(GroceryCategory.HOUSEHOLD))
        assertNull(catalog.categoryRule(GroceryCategory.OTHER))
    }

    @Test
    fun emptyRulesAreTreatedAsAbsent() {
        val catalog = ShelfLifeCatalog(mapOf("EMPTY" to ShelfLifeRule()))
        assertNull(catalog.conceptRule("EMPTY"))
        assertNull(catalog.conceptRule("MISSING"))
    }

    @Test
    fun malformedJsonYieldsNoRules() {
        assertEquals(emptyMap<String, ShelfLifeRule>(), ShelfLifeCatalog.parse("not json"))
        assertEquals(emptyMap<String, ShelfLifeRule>(), ShelfLifeCatalog.parse("{}"))
    }

    /** German overrides must actually be stricter than the US source they replace. */
    @Test
    fun mincedMeatUsesTheStricterGermanWindow() {
        val rule = bundled["MINCED_MEAT"]
        assertNotNull(rule)
        assertEquals(1..1, rule?.unopenedFridgeDays)
    }

    /**
     * Reported 2026-08-07: H-Milch (UHT/long-life milk) was being estimated like fresh milk.
     * FoodKeeper has no UHT-specific entry, so HMILCH carries a hand-estimated, shelf-stable
     * pantry window instead — it must be dramatically longer than fresh milk's fridge window.
     */
    @Test
    fun uhtMilkHasAMuchLongerUnopenedWindowThanFreshMilk() {
        val hmilch = bundled["HMILCH"]
        assertNotNull(hmilch)
        val hmilchDays = hmilch?.pantryDays ?: hmilch?.unopenedFridgeDays
        assertNotNull("HMILCH has no unopened window at all", hmilchDays)
        val freshMilkFallback = ShelfLifeCatalog.CATEGORY_FALLBACKS.getValue(GroceryCategory.DAIRY)
            .unopenedFridgeDays!!
        assertTrue(
            "UHT milk (${hmilchDays!!.first} days) should far outlast fresh milk " +
                "(${freshMilkFallback.first} days)",
            hmilchDays.first > freshMilkFallback.last * 3,
        )
    }

    /** Milchreis is sold as dry rice grains, so it should keep like rice, not like fresh milk. */
    @Test
    fun milchreisKeepsLikeDryRiceNotLikeFreshMilk() {
        val rule = bundled["MILCHREIS"]
        assertNotNull(rule)
        assertNotNull("MILCHREIS should have a long pantry window like rice", rule?.pantryDays)
        assertTrue(rule!!.pantryDays!!.first >= 180)
    }
}
