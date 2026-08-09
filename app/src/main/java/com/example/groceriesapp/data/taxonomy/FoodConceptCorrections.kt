package com.example.groceriesapp.data.taxonomy

import com.example.groceriesapp.data.atomicWrite
import java.io.File
import java.util.Locale
import org.json.JSONObject

/**
 * User-taught receipt-name → concept mappings, layered over [FoodConceptCatalog].
 *
 * The bundled catalogue deliberately carries only generic product nouns ("sojamilch", "joghurt");
 * brand spellings and merchant truncations ("ALPRO SOJA VAN.", "Heldenkaffe") would otherwise each
 * need a keyword added in code, rebuilt and reinstalled. Instead, when the user recategorizes an
 * item, its exact name is remembered here and wins over the catalogue's keyword match from then
 * on — including for already-persisted items, via the launch-time normalize pass.
 *
 * Lookup is an exact match on the normalized name, not a substring match: a correction teaches
 * what one specific receipt string is, and must never leak onto other products the way a short
 * keyword could.
 *
 * The file lives under the device-global groceries root rather than a household, because what a
 * product *is* does not depend on which household holds it.
 */
class FoodConceptCorrections(private val file: File) {

    private val lock = Any()

    /**
     * Set when the file exists but could not be parsed. Lookups then act as if no corrections
     * exist, and writes refuse instead of replacing the unreadable file with a fresh one — the
     * same stance [com.example.groceriesapp.data.GroceriesRepository] takes for its indexes.
     */
    private var storageUnreadable = false

    private val corrections: MutableMap<String, String> = load()

    /** The concept the user taught for this exact name, or null if none (or the id is stale). */
    fun conceptFor(rawName: String): FoodConcept? = synchronized(lock) {
        FoodConceptCatalog.byId(corrections[normalize(rawName)])
    }

    /**
     * Remembers that [rawName] is [conceptId]. A correction that merely restates what the
     * catalogue already matches is dropped rather than stored, so the file only ever holds names
     * the catalogue gets wrong — and a later catalogue fix isn't shadowed by a stale entry.
     */
    fun set(rawName: String, conceptId: String) {
        require(FoodConceptCatalog.byId(conceptId) != null) { "Unknown concept id $conceptId." }
        synchronized(lock) {
            val key = normalize(rawName)
            val redundant = FoodConceptCatalog.match(rawName)?.id == conceptId
            val changed = if (redundant) {
                corrections.remove(key) != null
            } else {
                corrections.put(key, conceptId) != conceptId
            }
            if (changed) persist()
        }
    }

    private fun load(): MutableMap<String, String> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val stored = JSONObject(file.readText()).getJSONObject(CORRECTIONS_KEY)
            stored.keys().asSequence().associateWithTo(mutableMapOf(), stored::getString)
        } catch (_: Exception) {
            storageUnreadable = true
            mutableMapOf()
        }
    }

    private fun persist() {
        check(!storageUnreadable) {
            "Refusing to overwrite an unreadable corrections file at ${file.absolutePath}."
        }
        val stored = JSONObject()
        corrections.forEach { (name, conceptId) -> stored.put(name, conceptId) }
        atomicWrite(
            file,
            JSONObject()
                .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                .put(CORRECTIONS_KEY, stored)
                .toString(),
        )
    }

    companion object {
        /** Must line up with [FoodConceptCatalog.match]'s lowercasing so keys compare equal. */
        private fun normalize(rawName: String): String = rawName.trim().lowercase(Locale.GERMANY)

        private const val SCHEMA_VERSION_KEY = "schemaVersion"
        private const val CORRECTIONS_KEY = "corrections"
        private const val SCHEMA_VERSION = 1
    }
}
