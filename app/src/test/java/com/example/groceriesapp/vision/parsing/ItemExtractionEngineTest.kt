package com.example.groceriesapp.vision.parsing

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemExtractionEngineTest {
    @Test
    fun fromPreferenceResolvesEveryStoredValue() {
        ItemExtractionEngine.entries.forEach { engine ->
            assertEquals(engine, ItemExtractionEngine.fromPreference(engine.preferenceValue))
        }
    }

    @Test
    fun fromPreferenceDefaultsToBuiltInRules() {
        assertEquals(
            ItemExtractionEngine.BUILT_IN_RULES,
            ItemExtractionEngine.fromPreference(null),
        )
        assertEquals(
            ItemExtractionEngine.BUILT_IN_RULES,
            ItemExtractionEngine.fromPreference("no_such_engine"),
        )
    }
}
