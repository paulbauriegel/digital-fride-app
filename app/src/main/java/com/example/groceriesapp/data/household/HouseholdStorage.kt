package com.example.groceriesapp.data.household

import java.io.File

internal const val HOUSEHOLDS_DIRECTORY = "households"
internal const val REGISTRY_FILE = "registry.json"

/** Shared with the receipt-index rewrites, which reach into that file without a repository. */
internal const val INDEX_FILE = "index.json"
internal const val RECEIPTS_KEY = "receipts"

/**
 * Prefix for a household directory that has been unlinked from the registry and is awaiting
 * deletion. `.` is excluded from [HOUSEHOLD_ID_PATTERN] precisely so no real household can ever
 * collide with — or be mistaken for — a tombstone.
 */
internal const val TOMBSTONE_PREFIX = ".deleted_"

internal val HOUSEHOLD_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

internal const val MAX_HOUSEHOLD_NAME_LENGTH = 40

internal fun householdsRoot(groceriesRoot: File): File = File(groceriesRoot, HOUSEHOLDS_DIRECTORY)

internal fun registryFile(groceriesRoot: File): File =
    File(householdsRoot(groceriesRoot), REGISTRY_FILE)

/**
 * Resolves a household's directory, refusing anything that would escape `households/`. Mirrors the
 * containment check `GroceriesRepository.receiptDirectory` applies to receipt IDs: the pattern
 * alone already excludes separators, but the normalized-parent assertion is what makes that
 * guarantee independent of the pattern.
 */
internal fun householdRoot(groceriesRoot: File, id: String): File {
    require(HOUSEHOLD_ID_PATTERN.matches(id)) {
        "Household IDs may contain only letters, numbers, underscores, and hyphens."
    }
    val rootPath = householdsRoot(groceriesRoot).canonicalFile.toPath()
    val path = rootPath.resolve(id).normalize()
    require(path.parent == rootPath) { "Household directory is outside repository storage." }
    return path.toFile()
}
