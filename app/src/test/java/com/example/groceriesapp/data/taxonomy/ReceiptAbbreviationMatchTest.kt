package com.example.groceriesapp.data.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real product lines from BIO COMPANY and REWE receipts that the catalogue got wrong, kept as
 * regressions.
 *
 * Receipt printers truncate and abbreviate aggressively ("Traub.dun.Kernl", "Doppelbroet.hell"),
 * which breaks plain substring matching against full German words, and some names contain a word
 * that belongs to a different concept than the product does ("lamm-lachs" is a sliced cold cut,
 * not salmon; "Matchs Strawb" is a milk drink, not fruit).
 */
class ReceiptAbbreviationMatchTest {

    private fun assertMatches(receiptText: String, expectedConceptId: String) {
        val actual = FoodConceptCatalog.match(receiptText)
        assertEquals(
            "\"$receiptText\" should resolve to $expectedConceptId",
            expectedConceptId,
            actual?.id,
        )
    }

    @Test
    fun truncatedDarkSeedlessGrapesResolveToGrapes() {
        assertMatches("Traub.dun.Kernl", "GRAPES")
    }

    @Test
    fun grapeJuiceStillBeatsGrapesDespiteTheSharedStem() {
        // The reason "traub" is safe to add: the longer juice keywords still win.
        assertMatches("Roter Traubensaf", "JUICE")
        assertMatches("Traubensaft", "JUICE")
    }

    @Test
    fun matchaStrawberryMilkDrinkResolvesToMilkNotFruit() {
        assertMatches("Matchs Strawb", "WHOLE_MILK")
    }

    @Test
    fun fassbrauseResolvesToSoftDrink() {
        assertMatches("fassbra", "SOFT_DRINK")
        assertMatches("Fassbrause", "SOFT_DRINK")
    }

    @Test
    fun abbreviatedLemonFassbrauseResolvesToSoftDrink() {
        assertMatches("KROM FB ZIT NAT", "SOFT_DRINK")
    }

    @Test
    fun truncatedPinkGrapefruitJuiceResolvesToJuiceNotTheFruit() {
        assertMatches("REWE PINK GRAPEF", "JUICE")
        assertMatches("Grapefruitsaft", "JUICE")
        // The plain fruit must not be dragged along by the juice keywords.
        assertMatches("Grapefruit", "GRAPEFRUIT")
    }

    @Test
    fun toffifeeResolvesToSweets() {
        assertMatches("TOFFIFEE 15ER", "CANDY")
    }

    @Test
    fun spanishRedWineResolvesToWine() {
        assertMatches("MEDERANO TINTO", "WINE")
    }

    @Test
    fun greekTzatzikiResolvesToItsOwnConcept() {
        assertMatches("Griech-Zaziki", "TZATZIKI")
        assertMatches("Tzatziki", "TZATZIKI")
    }

    @Test
    fun grillMeatProductsResolveToGrillMeat() {
        assertMatches("GrillFackel", "GRILL_MEAT")
        assertMatches("Bio Brat/Grillt.", "GRILL_MEAT")
    }

    @Test
    fun truncatedDoubleRollsResolveToRoll() {
        assertMatches("Doppelbroet.hell", "ROLL")
    }

    @Test
    fun lammLachsIsASlicedColdCutNotSalmon() {
        // "lachs" is the longer stem and would otherwise win outright.
        assertMatches("lamm-lachs", "COLD_CUTS")
        assertMatches("Bio Lyoner", "COLD_CUTS")
    }

    @Test
    fun plainLambAndSalmonStillResolveToThemselves() {
        assertMatches("Lammkeule", "LAMB")
        assertMatches("Räucherlachs", "SALMON")
    }
}
