package com.example.groceriesapp.data.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The container heuristic. It only picks a drawing, so being wrong is cheap — but the four cases
 * the mockup shows should each land on their own shape, and the units that carry no vessel at all
 * should land somewhere neutral rather than pretending.
 */
class ContainerTypeTest {

    @Test
    fun bottledDrinksGetABottle() {
        assertEquals(
            ContainerType.BOTTLE,
            resolveContainerType(GroceryCategory.DRINKS.name, "l", "Apfelsaft"),
        )
    }

    @Test
    fun otherLiquidsGetACarton() {
        assertEquals(
            ContainerType.CARTON,
            resolveContainerType(GroceryCategory.DAIRY.name, "l", "Frische Milch"),
        )
    }

    @Test
    fun weighedProduceGetsABulkSack() {
        assertEquals(
            ContainerType.BULK,
            resolveContainerType(GroceryCategory.VEGETABLES.name, "kg", "Kartoffeln"),
        )
    }

    @Test
    fun packagedDryGoodsGetAPack() {
        assertEquals(
            ContainerType.PACK,
            resolveContainerType(GroceryCategory.GRAINS_PASTA_RICE.name, "g", "Penne Rigate"),
        )
    }

    @Test
    fun countsAndUnknownUnitsFallBackToTheNeutralShape() {
        // A bare receipt line's unit is unknown, not assumed — there is no vessel to infer.
        assertEquals(ContainerType.PACK, resolveContainerType("DAIRY", "pc", "Joghurt"))
        assertEquals(ContainerType.PACK, resolveContainerType("DAIRY", null, "Joghurt"))
        assertEquals(ContainerType.PACK, resolveContainerType("DAIRY", "Becher", "Joghurt"))
    }

    @Test
    fun theCategoryIsResolvedFromTheNameWhenItIsMissing() {
        // Legacy rows carry no category string; the name still routes them.
        assertEquals(ContainerType.BULK, resolveContainerType(null, "kg", "Kartoffeln"))
    }
}
