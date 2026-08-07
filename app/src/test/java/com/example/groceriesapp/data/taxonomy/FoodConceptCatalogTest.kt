package com.example.groceriesapp.data.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodConceptCatalogTest {

    @Test
    fun conceptIdsAreUnique() {
        val duplicates = FoodConceptCatalog.all
            .groupingBy(FoodConcept::id)
            .eachCount()
            .filterValues { it > 1 }
        assertTrue("duplicate concept ids: ${duplicates.keys}", duplicates.isEmpty())
    }

    @Test
    fun keywordsAreLowercaseNonBlankAndUnique() {
        val seen = mutableMapOf<String, String>()
        FoodConceptCatalog.all.forEach { concept ->
            assertTrue("${concept.id} has no keywords", concept.keywords.isNotEmpty())
            concept.keywords.forEach { keyword ->
                assertTrue("blank keyword in ${concept.id}", keyword.isNotBlank())
                assertEquals("keyword must be lowercase", keyword.lowercase(), keyword)
                val previous = seen.put(keyword, concept.id)
                assertNull("keyword \"$keyword\" claimed by $previous and ${concept.id}", previous)
            }
        }
    }

    @Test
    fun everyKeywordResolvesToItsOwnConcept() {
        FoodConceptCatalog.all.forEach { concept ->
            (concept.keywords + concept.priorityKeywords).forEach { keyword ->
                assertEquals(
                    "keyword \"$keyword\" is shadowed",
                    concept.id,
                    FoodConceptCatalog.match(keyword)?.id,
                )
            }
        }
    }

    @Test
    fun priorityKeywordsAreASubsetOfTheConceptsOwnKeywords() {
        FoodConceptCatalog.all.forEach { concept ->
            val missing = concept.priorityKeywords - concept.keywords.toSet()
            assertTrue(
                "${concept.id} priority keywords $missing are not in its keyword list",
                missing.isEmpty(),
            )
        }
    }

    @Test
    fun byIdRoundTripsAndRejectsUnknownIds() {
        FoodConceptCatalog.all.forEach { concept ->
            assertEquals(concept, FoodConceptCatalog.byId(concept.id))
        }
        assertNull(FoodConceptCatalog.byId("NOT_A_CONCEPT"))
        assertNull(FoodConceptCatalog.byId(null))
    }

    /** The categories the previous hardcoded keyword table produced must not change. */
    @Test
    fun preservesLegacyKeywordCategories() {
        val legacy = mapOf(
            "apfel" to GroceryCategory.FRUIT,
            "banane" to GroceryCategory.FRUIT,
            "zitrone" to GroceryCategory.FRUIT,
            "beere" to GroceryCategory.FRUIT,
            "traube" to GroceryCategory.FRUIT,
            "tomate" to GroceryCategory.VEGETABLES,
            "gurke" to GroceryCategory.VEGETABLES,
            "paprika" to GroceryCategory.VEGETABLES,
            "brokkoli" to GroceryCategory.VEGETABLES,
            "spinat" to GroceryCategory.VEGETABLES,
            "salat" to GroceryCategory.VEGETABLES,
            "kartoffel" to GroceryCategory.VEGETABLES,
            "zwiebel" to GroceryCategory.VEGETABLES,
            "milch" to GroceryCategory.DAIRY,
            "joghurt" to GroceryCategory.DAIRY,
            "quark" to GroceryCategory.DAIRY,
            "käse" to GroceryCategory.DAIRY,
            "butter" to GroceryCategory.DAIRY,
            "sahne" to GroceryCategory.DAIRY,
            "huhn" to GroceryCategory.MEAT,
            "hähnchen" to GroceryCategory.MEAT,
            "rind" to GroceryCategory.MEAT,
            "schwein" to GroceryCategory.MEAT,
            "wurst" to GroceryCategory.MEAT,
            "lachs" to GroceryCategory.FISH_SEAFOOD,
            "fisch" to GroceryCategory.FISH_SEAFOOD,
            "brot" to GroceryCategory.BAKERY,
            "brötchen" to GroceryCategory.BAKERY,
            "toast" to GroceryCategory.BAKERY,
            "baguette" to GroceryCategory.BAKERY,
            "wasser" to GroceryCategory.DRINKS,
            "saft" to GroceryCategory.DRINKS,
            "cola" to GroceryCategory.DRINKS,
            "limonade" to GroceryCategory.DRINKS,
            "bier" to GroceryCategory.DRINKS,
            "wein" to GroceryCategory.DRINKS,
        )
        legacy.forEach { (keyword, expected) ->
            val concept = FoodConceptCatalog.match(keyword)
            assertNotNull("\"$keyword\" no longer matches anything", concept)
            assertEquals("\"$keyword\"", expected, concept?.category)
        }
    }

    /**
     * Real receipt strings where a shorter generic keyword could shadow the correct concept
     * ("butter" inside "Buttermilch", "wein" inside "Schweinefleisch").
     */
    @Test
    fun specificTermsBeatTheGenericTermsTheyContain() {
        val samples = mapOf(
            "Bio Milch" to GroceryCategory.DAIRY,
            "Buttermilch" to GroceryCategory.DAIRY,
            "Butterkeks" to GroceryCategory.SNACKS_SWEETS,
            "Erdnussbutter" to GroceryCategory.BREAKFAST_CEREAL,
            "Schweinefleisch" to GroceryCategory.MEAT,
            "Rotwein" to GroceryCategory.DRINKS,
            "Weintrauben" to GroceryCategory.FRUIT,
            "Granatapfel" to GroceryCategory.FRUIT,
            "Preiselbeeren" to GroceryCategory.FRUIT,
            "Kokosmilch" to GroceryCategory.CANNED_JARRED,
            "Seelachs" to GroceryCategory.FISH_SEAFOOD,
            "Fischstäbchen" to GroceryCategory.FISH_SEAFOOD,
            "Blumenkohl" to GroceryCategory.VEGETABLES,
            "Rosenkohl" to GroceryCategory.VEGETABLES,
            "Kichererbsen" to GroceryCategory.GRAINS_PASTA_RICE,
            "Eiernudeln" to GroceryCategory.GRAINS_PASTA_RICE,
            "Kartoffelsalat" to GroceryCategory.VEGETABLES,
            "Salzstangen" to GroceryCategory.SNACKS_SWEETS,
            "Essiggurken" to GroceryCategory.VEGETABLES,
            "Tomatenmark" to GroceryCategory.CONDIMENTS_SAUCES,
            "Apfelmus" to GroceryCategory.CANNED_JARRED,
            "Olivenöl" to GroceryCategory.CONDIMENTS_SAUCES,
        )
        samples.forEach { (name, expected) ->
            val concept = FoodConceptCatalog.match(name)
            assertNotNull("\"$name\" matched nothing", concept)
            assertEquals("\"$name\"", expected, concept?.category)
        }
    }

    @Test
    fun unknownTextMatchesNothing() {
        assertNull(FoodConceptCatalog.match("Zzyzx Qwertz"))
    }

    /**
     * Reported 2026-08-07: a fruit/flavour word inside a compound product name previously beat the
     * word that actually defines the product, because it happened to be a longer substring.
     */
    @Test
    fun compoundWordsResolveToTheProductTheyActuallyAre() {
        val samples = mapOf(
            "Milchreis" to "MILCHREIS",
            "H-Milch" to "HMILCH",
            "H-Milch 1,5%" to "HMILCH",
            "Haltbare Milch" to "HMILCH",
            "Bio Vollmilch" to "WHOLE_MILK",
            "Apfelsaft" to "JUICE",
            "Roter Traubensaft" to "JUICE",
            "Zitronensaft" to "JUICE",
            "Orangensaft" to "JUICE",
            "Multivitaminsaft" to "JUICE",
            "Ginger Beer" to "GINGER_BEER",
        )
        samples.forEach { (name, expectedId) ->
            assertEquals("\"$name\"", expectedId, FoodConceptCatalog.match(name)?.id)
        }
    }

    /**
     * Found 2026-08-07 auditing a real 48-item BIO COMPANY receipt after the fixes above landed.
     * BIO COMPANY truncates item names to 16 characters, which can cut a compound keyword's last
     * letter — "Roter Traubensaft" arrives as "Roter Traubensaf" — and "Lauch Flammkuchen" (a
     * leek tarte flambée, a prepared dish) was matching the raw-vegetable "Lauch" (leek) keyword
     * since "flammkuchen" wasn't in the catalogue at all. "vly Barista" and "Kurkuma Chai" matched
     * nothing (→ OTHER) because "Barista"-line oat milks and "Chai" drinks are common enough
     * product-name patterns to be worth their own keywords.
     */
    @Test
    fun realReceiptDataFoundFurtherGaps() {
        val samples = mapOf(
            "Roter Traubensaf" to "JUICE",
            "Lauch Flammkuche" to "FLAMMKUCHEN",
            "vly Barista  1Lt" to "PLANT_MILK",
            "Kurkuma Chai" to "TEA",
        )
        samples.forEach { (name, expectedId) ->
            assertEquals("\"$name\"", expectedId, FoodConceptCatalog.match(name)?.id)
        }
        // The plain leek keyword must still resolve to LEEK on its own.
        assertEquals("LEEK", FoodConceptCatalog.match("Lauch")?.id)
    }

    /**
     * Reported 2026-08-07: "Joghurt Erdbeere" (strawberry yogurt) matched the fruit ("erdbeere",
     * 8 chars) instead of the product it actually is ("joghurt", 7 chars) under plain
     * longest-match. [FoodConcept.priorityKeywords] fixes this for dairy carrier nouns.
     */
    @Test
    fun dairyCarrierNounsWinOverTheirFlavour() {
        val samples = mapOf(
            "Joghurt Erdbeere" to "YOGURT",
            "Fruchtjoghurt Kirsche" to "YOGURT",
            "Quark Kirsche" to "QUARK",
            "Skyr Himbeere" to "SKYR",
        )
        samples.forEach { (name, expectedId) ->
            assertEquals("\"$name\"", expectedId, FoodConceptCatalog.match(name)?.id)
        }
        // The flavour word alone, with no carrier noun present, must still resolve to the fruit.
        assertEquals("STRAWBERRY", FoodConceptCatalog.match("Erdbeeren")?.id)
        assertEquals("CHERRY", FoodConceptCatalog.match("Kirschen")?.id)
    }

    /**
     * Reported 2026-08-08 from real BIO COMPANY and REWE inventory. Two distinct failure modes:
     * receipt truncation cutting a keyword short ("MINI Wassermel.", "Heldenkaffe",
     * "Romarispentom-"), and prepared products matching a raw ingredient they merely contain
     * ("Kartoffelbrot" → potato, "Mangostücke" → fresh mango, "ERBSENPROT. DR." → peas,
     * "rotes curry haeh"/"bratkrt.Haeh.PFA" → curry powder/potato).
     */
    @Test
    fun truncatedAndPreparedProductNamesResolveCorrectly() {
        val samples = mapOf(
            "MINI Wassermel." to "WATERMELON",
            "Actimel Drink" to "YOGURT",
            "Heldenkaffe" to "COFFEE",
            "Mangostücke" to "DRIED_MANGO",
            "Kartoffelbrot" to "BREAD",
            "Romarispentom-" to "TOMATO",
            "ERBSENPROT. DR." to "PLANT_MILK",
            "rotes curry haeh" to "READY_MEAL",
            "bratkrt.Haeh.PFA" to "READY_MEAL",
            "BAMI GORENG" to "READY_MEAL",
            "Nasi Goreng" to "READY_MEAL",
            "Phantasia" to "CANDY",
        )
        samples.forEach { (name, expectedId) ->
            assertEquals("\"$name\"", expectedId, FoodConceptCatalog.match(name)?.id)
        }
        // The raw ingredients these shadow must still resolve to themselves on their own.
        assertEquals("POTATO", FoodConceptCatalog.match("Kartoffeln")?.id)
        assertEquals("MANGO", FoodConceptCatalog.match("Mango")?.id)
        assertEquals("PEAS", FoodConceptCatalog.match("Erbsen")?.id)
        assertEquals("SPICE_MIX", FoodConceptCatalog.match("Curry")?.id)
    }
}
