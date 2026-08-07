package com.example.groceriesapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryItemCountableTest {

    @Test
    fun pieceQuantitiesAreCountable() {
        assertTrue(InventoryItem(name = "Joghurt", quantity = 3.0, unit = "pc").isCountable)
        assertTrue(InventoryItem(name = "Eier", quantity = 10.0, unit = "St").isCountable)
        assertTrue(InventoryItem(name = "Apfel", quantity = 4.0, unit = "Stk.").isCountable)
    }

    @Test
    fun weightAndVolumeAmountsAreNot() {
        assertFalse(InventoryItem(name = "Spinat", quantity = 500.0, unit = "g").isCountable)
        assertFalse(InventoryItem(name = "Milch", quantity = 1.0, unit = "l").isCountable)
        assertFalse(InventoryItem(name = "Hack", quantity = 0.5, unit = "kg").isCountable)
    }

    @Test
    fun unknownUnitIsNotAssumedToMeanPieces() {
        // A bare receipt line (typical for REWE eBons) could be a 500 g pack.
        assertFalse(InventoryItem(name = "Brot", quantity = 1.0, unit = null).isCountable)
        assertFalse(InventoryItem(name = "Käse", quantity = 2.0, unit = " ").isCountable)
    }

    @Test
    fun fractionalQuantitiesAreNotSteppable() {
        assertFalse(InventoryItem(name = "Kuchen", quantity = 1.5, unit = "pc").isCountable)
    }
}
