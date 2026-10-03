package com.example.groceriesapp.data.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            "BUTTER CHICKEN" to "READY_MEAL",
            "KART.VF.BIO" to "POTATO",
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

    /** Reported 2026-08-08: "ALPRO SOJA VAN." (Alpro soya milk, vanilla) matched nothing. */
    @Test
    fun brandedSoyDrinkResolvesToPlantMilk() {
        assertEquals("PLANT_MILK", FoodConceptCatalog.match("ALPRO SOJA VAN.")?.id)
    }

    /**
     * Reported 2026-08-09: "Alpro soya" still matched nothing — the German-spelling fix above
     * ("alpro soja") doesn't cover Alpro's own English-language branding ("Alpro Soya").
     */
    @Test
    fun englishSpelledSoyaDrinkResolvesToPlantMilk() {
        assertEquals("PLANT_MILK", FoodConceptCatalog.match("Alpro soya")?.id)
        assertEquals("PLANT_MILK", FoodConceptCatalog.match("Soya Drink")?.id)
    }

    /** Reported 2026-08-09: "pamigani Regg" (OCR-garbled "Parmigiano Reggiano") matched nothing. */
    @Test
    fun garbledParmigianoReggianoResolvesToHardCheese() {
        assertEquals("CHEESE_HARD", FoodConceptCatalog.match("pamigani Regg")?.id)
        assertEquals("CHEESE_HARD", FoodConceptCatalog.match("Parmigiano Reggiano")?.id)
    }

    /**
     * Reported 2026-08-09: "Esquisa Meerret." (Exquisa horseradish cream cheese, OCR-misread
     * brand name) and "Bio Suesser-Butt." (sweet cream butter, truncated before "butter" itself
     * appears) both matched nothing.
     */
    @Test
    fun brandedCreamCheeseAndTruncatedButterResolveCorrectly() {
        assertEquals("CHEESE_FRESH", FoodConceptCatalog.match("Esquisa Meerret.")?.id)
        assertEquals("BUTTER", FoodConceptCatalog.match("Bio Suesser-Butt.")?.id)
    }

    /**
     * Reported 2026-08-09 from real inventory: a frozen prepared dish, a branded soft drink and a
     * branded tea all matched nothing (→ OTHER), and a potted living herb plant was classified the
     * same as cut herbs even though it keeps for weeks rather than days.
     */
    @Test
    fun brandedAndPreparedProductsFoundOnRealReceipts() {
        val samples = mapOf(
            "MEXICAN CHICKEN" to "READY_MEAL",
            "Bundaberg Ginger" to "GINGER_BEER",
            "Yogi Tea classic" to "TEA",
            "Basilikum im Topf" to "HERBS_POTTED",
            // Receipt truncation dropping the trailing "f" must still resolve correctly.
            "Basilikum im Top" to "HERBS_POTTED",
        )
        samples.forEach { (name, expectedId) ->
            assertEquals("\"$name\"", expectedId, FoodConceptCatalog.match(name)?.id)
        }
        // Cut basil (no pot) must still resolve to the shorter-lived HERBS_FRESH on its own.
        assertEquals("HERBS_FRESH", FoodConceptCatalog.match("Basilikum")?.id)
    }

    /** Reported 2026-08-09: "Pfand" (bottle deposit) needs its own category, not OTHER. */
    @Test
    fun depositLinesGetTheirOwnCategory() {
        val concept = FoodConceptCatalog.match("Pfand 0,25")
        assertEquals("PFAND", concept?.id)
        assertEquals(GroceryCategory.DEPOSIT, concept?.category)
        assertTrue("a deposit fee has no shelf life", !GroceryCategory.DEPOSIT.supportsExpiry)
    }

    /** Reported 2026-08-09: "5-Fruchtcocktail" (canned fruit pieces) matched nothing. */
    @Test
    fun cannedFruitCocktailResolvesToCannedFruitNotCompote() {
        val concept = FoodConceptCatalog.match("5-Fruchtcocktail")
        assertEquals("CANNED_FRUIT", concept?.id)
        assertEquals(GroceryCategory.CANNED_JARRED, concept?.category)
        // A distinct product from applesauce/Kompott, which must still resolve to COMPOTE alone.
        assertEquals("COMPOTE", FoodConceptCatalog.match("Kompott")?.id)
    }

    /** Reported 2026-08-09: "Mini Mix classic" (ice cream on a stick multipack) matched nothing. */
    @Test
    fun miniMixResolvesToIceCream() {
        assertEquals("ICE_CREAM", FoodConceptCatalog.match("Mini Mix classic")?.id)
    }

    /**
     * Reported 2026-08-09, a second batch from real receipts: an OCR misread, several truncated
     * or abbreviated product names, and three products (vegan sausage, Halloumi, barbecue sauce)
     * the catalogue had no concept for at all.
     */
    @Test
    fun secondBatchOfRealReceiptGaps() {
        val samples = mapOf(
            // OCR misread: "g" read as "b" in "Fruchtgummi".
            "fruchtbumi" to "CANDY",
            "Huehnerfrikasse" to "READY_MEAL",
            "Jonagold Bio" to "APPLE",
            // Receipt abbreviation for "Hähnchen Innenfilet" (chicken tenderloin).
            "HA-INNENFILET" to "CHICKEN",
            "vegane Bratw." to "VEGAN_SAUSAGE",
            "Halloumi" to "HALLOUMI",
            "Barbecue sauce" to "BARBECUE_SAUCE",
            // Receipt abbreviation for "Leergut Mehrweg Verpackung Stück" (returnable packaging).
            "Leerg. MW V. ST" to "PFAND",
            // Truncated "Mehrkornbaguette".
            "MehrkornBag" to "BAGUETTE",
            "Stabfeuerzeug" to "LIGHTER",
            "Bionade Holunder" to "SOFT_DRINK",
        )
        samples.forEach { (name, expectedId) ->
            assertEquals("\"$name\"", expectedId, FoodConceptCatalog.match(name)?.id)
        }
        // A truncated *real* bratwurst — no "vegane" qualifier — must not be caught as vegan.
        assertNull(FoodConceptCatalog.match("Bratw."))
        assertEquals("SAUSAGE", FoodConceptCatalog.match("Bratwurst")?.id)
        // A generic tenderloin cut, with no "HA-" prefix, must not default to chicken.
        assertNull(FoodConceptCatalog.match("Innenfilet"))
    }

    /**
     * Reported 2026-08-18 from real receipt rows whose flavour, grade or brand word beat the noun
     * that says what the product is: a yogurt drink read as fresh mango, a tin of paprika powder
     * as a bell pepper, ground nutmeg as a bag of nuts, and a cream cheese as nothing at all.
     */
    @Test
    fun flavourAndGradeWordsDoNotBeatTheProductNoun() {
        val samples = mapOf(
            "MANGO AYRAN" to "YOGURT",
            "Ayran" to "YOGURT",
            "PAPRIKA EDELSUES" to "SPICE_MIX",
            "Paprika edelsüß" to "SPICE_MIX",
            "MuSKATNUSS GEM." to "SPICE_MIX",
            // Miree is a cream-cheese brand; the flavour half of the name is a product of its own.
            "Miree Meerrett." to "CHEESE_FRESH",
            "Miree Meerrettich" to "CHEESE_FRESH",
        )
        samples.forEach { (name, expectedId) ->
            assertEquals("\"$name\"", expectedId, FoodConceptCatalog.match(name)?.id)
        }
        // The plain vegetable and the plain nut keep their own concepts.
        assertEquals("BELL_PEPPER", FoodConceptCatalog.match("Paprika rot")?.id)
        assertEquals("NUTS", FoodConceptCatalog.match("Walnusskerne")?.id)
        // Horseradish on its own is not a cream cheese — only the brand was taught. (It lands on
        // RADISH, via that concept's "rettich" keyword, which is a separate pre-existing gap.)
        assertNotEquals("CHEESE_FRESH", FoodConceptCatalog.match("Meerrettich")?.id)
    }

    /**
     * Reported 2026-08-26 from a real REWE receipt: three rows the catalogue matched nothing at
     * all. "Trau. Rot KL Bio" is red grapes behind REWE's dotted abbreviation, "Fun Box Haribo"
     * names only the brand, and "Rewe Bio Holund." is elderberry cordial — a product the
     * catalogue had no concept for.
     */
    @Test
    fun reweReceiptAbbreviationsAndBrandsResolve() {
        val samples = mapOf(
            "Trau. Rot KL Bio" to "GRAPES",
            "Fun Box Haribo" to "CANDY",
            "Rewe Bio Holund." to "SYRUP",
            "Holunder Sirup" to "SYRUP",
        )
        samples.forEach { (name, expectedId) ->
            assertEquals("\"$name\"", expectedId, FoodConceptCatalog.match(name)?.id)
        }
        // The grape abbreviation must not swallow grape juice, whose own keyword is longer.
        assertEquals("JUICE", FoodConceptCatalog.match("Roter Traubensaft")?.id)
        // Nor must the cordial swallow the branded elderflower lemonade.
        assertEquals("SOFT_DRINK", FoodConceptCatalog.match("Bionade Holunder")?.id)
    }
}
