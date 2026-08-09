package com.example.groceriesapp.vision.parsing

enum class ItemExtractionEngine(
    val preferenceValue: String,
    val displayName: String,
) {
    BUILT_IN_RULES("built_in_rules", "Built-in rules"),
    GRANITE("granite", "Granite"),
    OPENAI("openai", "OpenAI"),
    ;

    companion object {
        fun fromPreference(value: String?): ItemExtractionEngine =
            entries.firstOrNull { it.preferenceValue == value } ?: BUILT_IN_RULES
    }
}
