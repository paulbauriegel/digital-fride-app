package com.example.groceriesapp.vision.parsing

import com.example.groceriesapp.vision.parsing.ReweReceiptPdfExtractor.Companion.reconstructIndentation
import com.example.groceriesapp.vision.parsing.ReweReceiptPdfExtractor.PositionedLine
import org.junit.Assert.assertEquals
import org.junit.Test

class ReweReceiptPdfExtractorTest {

    // Positions mirror a real eBon: items start at the left margin (x=20) while quantity
    // continuation lines' first non-blank glyph sits far right of it (x=74/98).
    @Test
    fun indentsLinesWhoseFirstGlyphSitsRightOfTheMargin() {
        val text = reconstructIndentation(
            listOf(
                PositionedLine("UID Nr.: DE217623660", 20f),
                PositionedLine("MANGO AYRAN                      1,70 B", 20f),
                PositionedLine("2 Stk x    0,85", 98f),
                PositionedLine("ZWETSCHGE                        2,07 B", 20f),
                PositionedLine("1,558 kg x   1,33 EUR/kg", 74f),
                PositionedLine("", null),
            ),
        )

        assertEquals(
            """
            |UID Nr.: DE217623660
            |MANGO AYRAN                      1,70 B
            | 2 Stk x    0,85
            |ZWETSCHGE                        2,07 B
            | 1,558 kg x   1,33 EUR/kg
            |
            """.trimMargin(),
            text,
        )
    }

    @Test
    fun reconstructedTextCarriesQuantitiesThroughTheParser() {
        val text = reconstructIndentation(
            listOf(
                PositionedLine("UID Nr.: DE217623660", 20f),
                PositionedLine("MANGO AYRAN                      1,70 B", 20f),
                PositionedLine("2 Stk x    0,85", 98f),
                PositionedLine("ZWETSCHGE                        2,07 B", 20f),
                PositionedLine("1,558 kg x   1,33 EUR/kg", 74f),
                PositionedLine("ALPRO SOJA VAN.                  7,47 A", 20f),
                PositionedLine("3 Stk x    2,49", 98f),
                PositionedLine("--------------------------------------", 21f),
            ),
        )
        val items = ReweReceiptParser().parse(text)

        assertEquals(listOf("MANGO AYRAN", "ZWETSCHGE", "ALPRO SOJA VAN."), items.map { it.name })
        assertEquals(2.0, items[0].quantity, 0.001)
        assertEquals("pc", items[0].normalizedUnit())
        assertEquals(1.558, items[1].quantity, 0.001)
        assertEquals("kg", items[1].normalizedUnit())
        assertEquals(3.0, items[2].quantity, 0.001)
        assertEquals("pc", items[2].normalizedUnit())
    }
}
