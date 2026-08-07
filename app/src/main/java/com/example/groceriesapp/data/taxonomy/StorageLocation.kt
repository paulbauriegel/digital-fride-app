package com.example.groceriesapp.data.taxonomy

/**
 * Where an inventory item is kept. Drives which [ShelfLifeRule] window applies, so moving an item
 * between locations changes its expiry estimate.
 */
enum class StorageLocation(val displayName: String, val emojiFallback: String) {
    FRIDGE("Fridge", "🧊"),
    FREEZER("Freezer", "❄️"),
    PANTRY("Pantry", "🧺");

    companion object {
        fun fromStorageValue(raw: String?): StorageLocation? =
            entries.firstOrNull { it.name == raw }
    }
}
