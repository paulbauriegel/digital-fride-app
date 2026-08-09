package com.example.groceriesapp.data.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitNormalizerTest {

    @Test
    fun `mass units normalize to grams`() {
        assertEquals(NormalizedAmount(UnitKind.MASS, 500.0), UnitNormalizer.normalize(500.0, "g"))
        assertEquals(NormalizedAmount(UnitKind.MASS, 1500.0), UnitNormalizer.normalize(1.5, "kg"))
        assertEquals(NormalizedAmount(UnitKind.MASS, 250.0), UnitNormalizer.normalize(250.0, " G "))
    }

    @Test
    fun `volume units normalize to millilitres`() {
        assertEquals(NormalizedAmount(UnitKind.VOLUME, 200.0), UnitNormalizer.normalize(200.0, "ml"))
        assertEquals(NormalizedAmount(UnitKind.VOLUME, 1000.0), UnitNormalizer.normalize(1.0, "l"))
        assertEquals(NormalizedAmount(UnitKind.VOLUME, 750.0), UnitNormalizer.normalize(0.75, "Liter"))
    }

    @Test
    fun `countable units and blank units read as pieces`() {
        assertEquals(NormalizedAmount(UnitKind.COUNT, 3.0), UnitNormalizer.normalize(3.0, "pc"))
        assertEquals(NormalizedAmount(UnitKind.COUNT, 2.0), UnitNormalizer.normalize(2.0, "Stück"))
        assertEquals(NormalizedAmount(UnitKind.COUNT, 4.0), UnitNormalizer.normalize(4.0, null))
        assertEquals(NormalizedAmount(UnitKind.COUNT, 4.0), UnitNormalizer.normalize(4.0, "  "))
    }

    @Test
    fun `unrecognized units stay unknown`() {
        assertEquals(NormalizedAmount(UnitKind.UNKNOWN, 2.0), UnitNormalizer.normalize(2.0, "tbsp"))
        assertEquals(NormalizedAmount(UnitKind.UNKNOWN, 1.0), UnitNormalizer.normalize(1.0, "Bund"))
        assertEquals(UnitKind.UNKNOWN, UnitNormalizer.kindOf("Packung"))
    }

    @Test
    fun `null or non-positive amounts do not normalize`() {
        assertNull(UnitNormalizer.normalize(null, "g"))
        assertNull(UnitNormalizer.normalize(0.0, "g"))
        assertNull(UnitNormalizer.normalize(-1.0, "pc"))
        assertNull(UnitNormalizer.normalize(Double.NaN, "g"))
    }

    @Test
    fun `kindOf mirrors normalize`() {
        assertEquals(UnitKind.MASS, UnitNormalizer.kindOf("kg"))
        assertEquals(UnitKind.VOLUME, UnitNormalizer.kindOf("ml"))
        assertEquals(UnitKind.COUNT, UnitNormalizer.kindOf(null))
        assertEquals(UnitKind.COUNT, UnitNormalizer.kindOf("stk."))
    }
}
