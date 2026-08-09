package com.example.groceriesapp.data.household

import com.example.groceriesapp.data.atomicWrite
import com.example.groceriesapp.data.ensureDirectory
import com.example.groceriesapp.data.moveAtomicallyOrPlain
import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Brings storage to the household layout: receipts stay device-wide, while inventory and the
 * consumption log move under `households/<id>/`.
 *
 * The phone holds the only copy of this data, so the whole procedure is built to be re-runnable:
 * it only ever renames directories, never copies-then-deletes, and never deletes anything holding
 * data. The one step that parses a file leaves that file untouched if the parse fails. A process
 * that dies part-way through leaves storage in a state the next launch completes rather than
 * compounds.
 */
internal object HouseholdStorageMigration {
    const val DEFAULT_HOUSEHOLD_ID = "default"
    const val DEFAULT_HOUSEHOLD_NAME = "Home"

    internal const val RECEIPTS_DIRECTORY = "receipts"

    /**
     * Directories a household owns. Receipts are deliberately absent — one shop can stock either
     * household, so the receipt record is shared and only what a household holds is split out.
     * `recipes/` is absent for the same reason: it is global.
     */
    private val HOUSEHOLD_DIRECTORIES = listOf("inventory", "consumption")

    /**
     * Returns the registry to work from, running the migration first if it has not completed.
     *
     * [now] is injected so tests do not depend on the clock.
     */
    fun migrateIfNeeded(groceriesRoot: File, now: Long = System.currentTimeMillis()): HouseholdRegistry {
        val existing = readRegistry(groceriesRoot)
        if (existing != null) {
            // An earlier build of the households feature filed receipts under each household.
            // Undo that wherever it happened, then leave the registry alone.
            consolidateReceipts(groceriesRoot, existing)
            return existing
        }

        val defaultRoot = householdRoot(groceriesRoot, DEFAULT_HOUSEHOLD_ID)
        ensureDirectory(defaultRoot)

        HOUSEHOLD_DIRECTORIES.forEach { name ->
            val legacy = File(groceriesRoot, name)
            if (!legacy.isDirectory) return@forEach
            val target = File(defaultRoot, name)
            if (target.exists()) {
                // Unreachable in a normal run: the registry is written last, so a completed
                // migration would have returned above. Rename aside rather than merge or
                // overwrite, so whatever is here survives for manual recovery.
                moveAtomicallyOrPlain(legacy, File(groceriesRoot, "$name.premigration"))
                return@forEach
            }
            moveAtomicallyOrPlain(legacy, target)
        }

        rewriteReceiptIndexPaths(groceriesRoot)

        val registry = HouseholdRegistry(
            households = listOf(
                Household(
                    id = DEFAULT_HOUSEHOLD_ID,
                    name = DEFAULT_HOUSEHOLD_NAME,
                    createdAtEpochMillis = now,
                ),
            ),
            activeHouseholdId = DEFAULT_HOUSEHOLD_ID,
        )
        // Written last, and its presence is the sole "migration complete" marker.
        writeRegistry(groceriesRoot, registry)
        return registry
    }

    /**
     * Lifts per-household receipt directories back up to the shared one.
     *
     * Stored paths need no rewriting: they were already relative and already began with
     * `receipts/`, which is exactly where they now sit relative to the groceries root.
     */
    private fun consolidateReceipts(groceriesRoot: File, registry: HouseholdRegistry) {
        val global = File(groceriesRoot, RECEIPTS_DIRECTORY)
        registry.households.forEach { household ->
            val perHousehold = File(householdRoot(groceriesRoot, household.id), RECEIPTS_DIRECTORY)
            if (!perHousehold.isDirectory) return@forEach
            if (!global.exists()) {
                moveAtomicallyOrPlain(perHousehold, global)
                return@forEach
            }
            mergeReceiptsInto(global, perHousehold)
        }
    }

    /**
     * Folds [source] into [target] receipt by receipt. Receipt IDs are UUIDs, so a collision means
     * the same receipt is already filed globally and the copy here is redundant — it is left in
     * place rather than merged, because guessing which of two same-ID directories is authoritative
     * is exactly the kind of decision that loses data.
     */
    private fun mergeReceiptsInto(target: File, source: File) {
        val sourceIndex = File(source, INDEX_FILE)
        val movedIds = mutableSetOf<String>()
        source.listFiles().orEmpty()
            .filter { it.isDirectory && !File(target, it.name).exists() }
            .forEach { directory ->
                moveAtomicallyOrPlain(directory, File(target, directory.name))
                movedIds += directory.name
            }
        if (sourceIndex.isFile) {
            mergeReceiptIndexes(File(target, INDEX_FILE), sourceIndex, movedIds)
            // Preserve the source index rather than deleting it: it is the only record of what
            // this household believed it held.
            moveAtomicallyOrPlain(sourceIndex, File(source, "$INDEX_FILE.premigration"))
        }
        // Only ever removes a directory that no longer holds anything.
        if (source.listFiles().orEmpty().isEmpty()) source.delete()
    }

    private fun mergeReceiptIndexes(targetIndex: File, sourceIndex: File, allowedIds: Set<String>) {
        if (allowedIds.isEmpty()) return
        val target = readIndexObject(targetIndex) ?: return
        val source = readIndexObject(sourceIndex) ?: return
        val targetArray = target.optJSONArray(RECEIPTS_KEY) ?: return
        val sourceArray = source.optJSONArray(RECEIPTS_KEY) ?: return
        val known = buildSet {
            repeat(targetArray.length()) { index ->
                targetArray.optJSONObject(index)?.optString("id")?.let(::add)
            }
        }
        var appended = false
        repeat(sourceArray.length()) { index ->
            val receipt = sourceArray.optJSONObject(index) ?: return@repeat
            val id = receipt.optString("id", "")
            if (id in allowedIds && id !in known) {
                targetArray.put(receipt)
                appended = true
            }
        }
        if (appended) atomicWrite(targetIndex, target.toString())
    }

    private fun readIndexObject(file: File): JSONObject? {
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull()
    }

    internal fun readRegistry(groceriesRoot: File): HouseholdRegistry? {
        val file = registryFile(groceriesRoot)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }
            .getOrNull()
            ?.toHouseholdRegistryOrNull()
    }

    internal fun writeRegistry(groceriesRoot: File, registry: HouseholdRegistry) {
        atomicWrite(registryFile(groceriesRoot), registry.toJson().toString())
    }

    private fun rewriteReceiptIndexPaths(groceriesRoot: File) {
        val index = File(File(groceriesRoot, RECEIPTS_DIRECTORY), INDEX_FILE)
        if (!index.isFile) return
        val original = runCatching { index.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return
        val rewritten = rewriteReceiptIndexPathsJson(original, groceriesRoot.absolutePath) ?: return
        atomicWrite(index, rewritten)
    }
}

/** Path fields in a stored receipt. Everything else in the index is location-independent. */
private val PATH_FIELDS = listOf("previewPath", "sourcePreviewPath")
private val PATH_ARRAY_FIELDS = listOf("tilePaths", "sourceTilePaths", "originalFramePaths")

/**
 * Rewrites the absolute image paths in a receipt index to ones relative to the groceries root, or
 * returns null to mean "leave the file exactly as it is".
 *
 * Null is returned for anything not recognisably a readable index of a known schema — including a
 * schema newer than this build understands, which a downgrade could produce. Refusing rather than
 * guessing is what keeps a migration from mangling a file it does not understand, and closes the
 * gap where `schemaVersion` was written on every save but never once read back.
 */
internal fun rewriteReceiptIndexPathsJson(indexJson: String, groceriesRootPath: String): String? {
    val root = runCatching { JSONObject(indexJson) }.getOrNull() ?: return null
    val version = root.optInt("schemaVersion", -1)
    if (version !in 1..RELATIVE_PATHS_SCHEMA_VERSION) return null
    val receipts = root.optJSONArray(RECEIPTS_KEY) ?: return null

    repeat(receipts.length()) { index ->
        val receipt = receipts.optJSONObject(index) ?: return@repeat
        PATH_FIELDS.forEach { field ->
            if (receipt.isNull(field)) return@forEach
            val value = receipt.optString(field, "")
            if (value.isNotEmpty()) {
                receipt.put(field, relativizeStoredPath(value, groceriesRootPath))
            }
        }
        PATH_ARRAY_FIELDS.forEach { field ->
            val array = receipt.optJSONArray(field) ?: return@forEach
            val mapped = JSONArray()
            repeat(array.length()) { position ->
                mapped.put(relativizeStoredPath(array.optString(position, ""), groceriesRootPath))
            }
            receipt.put(field, mapped)
        }
    }

    root.put("schemaVersion", RELATIVE_PATHS_SCHEMA_VERSION)
    return root.toString()
}

/**
 * The schema in which receipt paths became relative, and the newest this rewrite knows how to read.
 * Schemas 1-4 stored absolute paths; 5 is accepted too so that re-running after an interrupted
 * migration is a no-op rather than a refusal.
 */
private const val RELATIVE_PATHS_SCHEMA_VERSION = 5

/**
 * Re-roots one stored path onto the groceries directory. Already-relative values pass through
 * untouched, which is what makes the whole rewrite idempotent — re-running it after a crash is a
 * no-op rather than a second round of mangling.
 */
internal fun relativizeStoredPath(value: String, groceriesRootPath: String): String {
    if (!value.startsWith("/")) return value
    val prefix = "$groceriesRootPath/"
    if (value.startsWith(prefix)) return value.removePrefix(prefix)
    // Absolute path written by an older build, possibly under the other spelling of filesDir.
    val marker = "/receipts/"
    val at = value.lastIndexOf(marker)
    if (at >= 0) return "receipts/" + value.substring(at + marker.length)
    return value
}
