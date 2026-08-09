package com.example.groceriesapp.data.household

import android.content.Context
import com.example.groceriesapp.data.GroceriesRepository
import com.example.groceriesapp.data.deleteDirectoryWithoutFollowingLinks
import com.example.groceriesapp.data.ensureDirectory
import com.example.groceriesapp.data.moveAtomicallyOrPlain
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The list of households and which one is active.
 *
 * Constructing this runs the storage migration, so it must be created before any
 * [GroceriesRepository] — a repository built against a half-migrated tree would read an empty
 * household and could persist that emptiness back over the real data.
 */
class HouseholdRepository(context: Context) {
    private val lock = Any()
    private val groceriesRoot = GroceriesRepository.groceriesRoot(context)

    private val _households: MutableStateFlow<List<Household>>
    private val _activeHouseholdId: MutableStateFlow<String>

    val households: StateFlow<List<Household>>
    val activeHouseholdId: StateFlow<String>

    init {
        ensureDirectory(householdsRoot(groceriesRoot))
        val migrated = HouseholdStorageMigration.migrateIfNeeded(groceriesRoot)
        // Parsing resolves a stale active id and drops malformed entries. Persist the result when
        // it differs from what is on disk, so the file heals once instead of being re-corrected on
        // every launch.
        val stored = runCatching {
            registryFile(groceriesRoot).readText(StandardCharsets.UTF_8)
        }.getOrNull()
        if (stored != migrated.toJson().toString()) {
            HouseholdStorageMigration.writeRegistry(groceriesRoot, migrated)
        }
        _households = MutableStateFlow(migrated.households)
        _activeHouseholdId = MutableStateFlow(migrated.activeHouseholdId)
        households = _households.asStateFlow()
        activeHouseholdId = _activeHouseholdId.asStateFlow()
        sweepTombstones()
    }

    fun createHousehold(name: String): Household = synchronized(lock) {
        val existing = _households.value
        require(householdNameError(name, existing) == null) { "Invalid household name." }
        val household = Household(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            createdAtEpochMillis = System.currentTimeMillis(),
        )
        ensureDirectory(householdRoot(groceriesRoot, household.id))
        commit(existing + household, _activeHouseholdId.value)
        household
    }

    fun renameHousehold(id: String, name: String) = synchronized(lock) {
        val existing = _households.value
        require(existing.any { it.id == id }) { "Unknown household." }
        require(householdNameError(name, existing, excludingId = id) == null) {
            "Invalid household name."
        }
        val updated = existing.map { if (it.id == id) it.copy(name = name.trim()) else it }
        commit(updated, _activeHouseholdId.value)
    }

    fun setActiveHousehold(id: String) = synchronized(lock) {
        require(_households.value.any { it.id == id }) { "Unknown household." }
        commit(_households.value, id)
    }

    /**
     * Removes a household and everything stored under it.
     *
     * The order matters: a live [GroceriesRepository] may still be pointed at this directory while
     * recomposition catches up. Writing the registry first makes the household gone as far as the
     * app is concerned, and renaming the directory aside is an atomic step that makes the data
     * unreachable before anything is actually deleted.
     *
     * The import release sits between those two steps for the same reason. Running it before the
     * registry write would unblock receipt lines for a household that still exists and still holds
     * the goods; running it after the delete would leave a crash window whose outcome is a record
     * orphaned forever, which is exactly what the release is for. Here, the worst a crash can do is
     * release slightly early on a household already out of the registry.
     */
    fun deleteHousehold(id: String) = synchronized(lock) {
        val existing = _households.value
        require(existing.any { it.id == id }) { "Unknown household." }
        require(existing.size > 1) { "The last household cannot be deleted." }

        val remaining = existing.filterNot { it.id == id }
        val active = _activeHouseholdId.value.takeUnless { it == id }
            ?: remaining.minBy(Household::createdAtEpochMillis).id
        commit(remaining, active)
        releaseImportsForDeletedHousehold(groceriesRoot, id)

        val directory = householdRoot(groceriesRoot, id)
        if (directory.isDirectory) {
            val tombstone =
                File(householdsRoot(groceriesRoot), "$TOMBSTONE_PREFIX${id}_${UUID.randomUUID()}")
            moveAtomicallyOrPlain(directory, tombstone)
            deleteDirectoryWithoutFollowingLinks(tombstone)
        }
    }

    /**
     * What [id] actually holds, so deletion can say what it is about to destroy. Receipts are not
     * counted: they are shared and survive the household.
     */
    fun storedEntryCounts(id: String): HouseholdEntryCounts {
        val root = householdRoot(groceriesRoot, id)
        return HouseholdEntryCounts(
            inventoryItems = countEntries(File(File(root, "inventory"), "index.json"), "inventory"),
            consumptionEvents = countEntries(File(File(root, "consumption"), "log.json"), "events"),
        )
    }

    private fun commit(households: List<Household>, activeHouseholdId: String) {
        val registry = HouseholdRegistry(households, activeHouseholdId)
        HouseholdStorageMigration.writeRegistry(groceriesRoot, registry)
        _households.value = households
        _activeHouseholdId.value = activeHouseholdId
    }

    /**
     * Clears directories left behind by a delete that was interrupted between the rename and the
     * recursive delete. Only the tombstone prefix is swept — a directory that is merely absent
     * from the registry is left strictly alone, because silently deleting unrecognised data is how
     * real receipts got destroyed here before.
     */
    private fun sweepTombstones() {
        val root = householdsRoot(groceriesRoot)
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(TOMBSTONE_PREFIX) }
            .forEach { runCatching { deleteDirectoryWithoutFollowingLinks(it) } }
    }
}

data class HouseholdEntryCounts(val inventoryItems: Int?, val consumptionEvents: Int?)

/** Null when the file is missing or unreadable — callers show nothing rather than a wrong zero. */
private fun countEntries(file: File, key: String): Int? {
    if (!file.isFile) return 0
    return runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }
        .getOrNull()
        ?.optJSONArray(key)
        ?.length()
}
