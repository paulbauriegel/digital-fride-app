package com.example.groceriesapp.data.household

/**
 * One separated set of receipts, inventory, and consumption history — a flat, a holiday home, a
 * shared kitchen. Recipes, connected merchant accounts, and app appearance are deliberately not
 * scoped to a household; they stay device-wide.
 */
data class Household(
    val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
)

/**
 * The full contents of `households/registry.json`. The active id lives here rather than in
 * SharedPreferences because prefs and files are backed up and restored independently, so a split
 * across the two can resurrect an active id whose directory no longer exists.
 */
data class HouseholdRegistry(
    val households: List<Household>,
    val activeHouseholdId: String,
) {
    val active: Household
        get() = households.first { it.id == activeHouseholdId }
}
