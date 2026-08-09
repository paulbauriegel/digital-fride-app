package com.example.groceriesapp.data.household

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

private const val SCHEMA_VERSION_KEY = "schemaVersion"
private const val HOUSEHOLDS_KEY = "households"
private const val ACTIVE_KEY = "activeHouseholdId"
internal const val REGISTRY_SCHEMA_VERSION = 1

internal fun HouseholdRegistry.toJson(): JSONObject = JSONObject()
    .put(SCHEMA_VERSION_KEY, REGISTRY_SCHEMA_VERSION)
    .put(ACTIVE_KEY, activeHouseholdId)
    .put(
        HOUSEHOLDS_KEY,
        JSONArray().also { array ->
            households.forEach { household ->
                array.put(
                    JSONObject()
                        .put("id", household.id)
                        .put("name", household.name)
                        .put("createdAtEpochMillis", household.createdAtEpochMillis),
                )
            }
        },
    )

/**
 * Parses the registry, or returns null when there is nothing usable to work with — an unparseable
 * file, or one that names no valid household. Null tells the caller to treat storage as
 * unmigrated and rebuild the registry, which is safe because rebuilding never touches the data
 * directories themselves.
 *
 * Individual malformed entries are skipped rather than failing the whole parse, so one bad row
 * cannot make the user's other households disappear.
 */
internal fun JSONObject.toHouseholdRegistryOrNull(): HouseholdRegistry? {
    val array = optJSONArray(HOUSEHOLDS_KEY) ?: return null
    val households = buildList {
        repeat(array.length()) { index ->
            array.optJSONObject(index)?.toHouseholdOrNull()?.let(::add)
        }
    }
    if (households.isEmpty()) return null

    // Same shape as MainActivity's "stored preference is no longer valid, fall back" handling: a
    // stale active id must never make the app unusable.
    val stored = if (isNull(ACTIVE_KEY)) null else optString(ACTIVE_KEY, "").ifEmpty { null }
    val active = stored?.takeIf { id -> households.any { it.id == id } }
        ?: households.first().id
    return HouseholdRegistry(households = households, activeHouseholdId = active)
}

private fun JSONObject.toHouseholdOrNull(): Household? {
    val id = optString("id", "")
    if (!HOUSEHOLD_ID_PATTERN.matches(id)) return null
    val name = optString("name", "").trim()
    if (name.isEmpty()) return null
    return Household(
        id = id,
        name = name,
        createdAtEpochMillis = optLong("createdAtEpochMillis", 0L),
    )
}

/**
 * Null when [name] is acceptable, otherwise the message to show under the text field. Kept pure so
 * the dialog can validate as the user types and the repository can assert the same rule.
 */
internal fun householdNameError(
    name: String,
    existing: List<Household>,
    excludingId: String? = null,
): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Enter a name."
    if (trimmed.length > MAX_HOUSEHOLD_NAME_LENGTH) {
        return "Names can be at most $MAX_HOUSEHOLD_NAME_LENGTH characters."
    }
    val key = trimmed.lowercase(Locale.GERMAN)
    val clash = existing.any { it.id != excludingId && it.name.lowercase(Locale.GERMAN) == key }
    if (clash) return "A household with this name already exists."
    return null
}
