package com.example.groceriesapp.data

import android.content.Context
import com.example.groceriesapp.data.household.Household
import com.example.groceriesapp.data.household.HouseholdStorageMigration
import com.example.groceriesapp.data.household.householdRoot
import com.example.groceriesapp.data.recipes.CookConsumption
import com.example.groceriesapp.data.recipes.CookOutcome
import com.example.groceriesapp.data.recipes.CookPlanner
import com.example.groceriesapp.data.taxonomy.ExpiryEstimator
import com.example.groceriesapp.data.taxonomy.ExpirySource
import com.example.groceriesapp.data.taxonomy.FoodConcept
import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.FoodConceptCorrections
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.StorageLocationSuggester
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the device's receipts together with one household's inventory and consumption log.
 *
 * Receipts are deliberately **not** per-household: a single shop is one purchase, and its items may
 * end up stocking either household. They therefore live under [groceriesRoot] and are visible from
 * every household. What a household actually holds — inventory and the consumption history derived
 * from it — lives under [householdRoot]. Adding receipt items to inventory targets the household
 * this instance was opened for, so splitting one shop across two households means adding what
 * belongs to each while that household is active.
 *
 * Switching households means constructing a new instance over a different [householdRoot] rather
 * than mutating this one. Taking [File] roots and a parsed [ShelfLifeCatalog] instead of a
 * `Context` keeps the class free of Android dependencies so it can be exercised in plain JVM tests;
 * use [forHousehold] from app code.
 */
class GroceriesRepository(
    private val groceriesRoot: File,
    householdRoot: File,
    private val shelfLifeCatalog: ShelfLifeCatalog,
    /**
     * Which household this instance speaks for, stamped onto the import records it writes. Defaults
     * to the directory name because that is exactly what [householdRoot] is built from, so every
     * existing caller gets the right id without passing it.
     */
    private val householdId: String = householdRoot.name,
) {
    private val lock = Any()
    private val receiptsRoot = File(groceriesRoot, RECEIPTS_DIRECTORY)
        .also(::ensureDirectory)
    private val inventoryRoot = File(householdRoot, INVENTORY_DIRECTORY)
        .also(::ensureDirectory)
    private val consumptionRoot = File(householdRoot, CONSUMPTION_DIRECTORY)
        .also(::ensureDirectory)
    private val receiptIndexFile = File(receiptsRoot, INDEX_FILE)
    private val inventoryIndexFile = File(inventoryRoot, INDEX_FILE)
    private val consumptionLogFile = File(consumptionRoot, CONSUMPTION_LOG_FILE)
    private val activeScanFile = File(receiptsRoot, ACTIVE_SCAN_FILE)

    private val expiryEstimator = ExpiryEstimator(shelfLifeCatalog)

    // Declared before the load/normalize properties below for the same initialization-order
    // reason as [storageRootPrefixes]: the launch-time normalize pass resolves concepts.
    private val foodConceptCorrections =
        FoodConceptCorrections(File(groceriesRoot, CONCEPT_CORRECTIONS_FILE))

    /**
     * Set when an index file exists but could not be parsed. The load path yields an empty list in
     * that case, so writing it back would replace real data with nothing — every persist call
     * refuses instead, leaving the unreadable file for manual recovery.
     */
    private var storageUnreadable = false

    /**
     * Both spellings of the groceries root. `filesDir` is typically `/data/user/0/<pkg>/files`
     * while the same directory canonicalizes to `/data/data/<pkg>/files`, so matching only one of
     * them would silently keep storing absolute paths.
     *
     * Declared above the load and normalize passes: the launch-time normalize can persist, and a
     * `by lazy` further down the class would not have been initialized by then.
     */
    private val storageRootPrefixes: List<String> =
        listOfNotNull(
            groceriesRoot.absolutePath,
            runCatching { groceriesRoot.canonicalPath }.getOrNull(),
        ).distinct().map { "$it/" }

    private val _receipts = MutableStateFlow(loadReceipts())
    val receipts: StateFlow<List<DigitalReceipt>> = _receipts.asStateFlow()

    private val _inventory = MutableStateFlow(loadInventory())
    val inventory: StateFlow<List<InventoryItem>> = _inventory.asStateFlow()

    private val _consumptionEvents = MutableStateFlow(loadConsumptionEvents())
    val consumptionEvents: StateFlow<List<ConsumptionEvent>> = _consumptionEvents.asStateFlow()

    init {
        normalizeStoredDataIfNeeded()
    }

    /**
     * Rewrites items still holding legacy category strings, and backfills concepts and expiry
     * estimates for items saved before the taxonomy existed. Runs once, on the first load after
     * the update, then leaves the files alone.
     */
    private fun normalizeStoredDataIfNeeded() {
        synchronized(lock) {
            val receipts = _receipts.value
            val normalizedReceipts = receipts.map { receipt ->
                receipt.copy(items = receipt.items.map(::normalizeReceiptItem))
            }
            if (normalizedReceipts != receipts) {
                persistReceipts(normalizedReceipts)
                _receipts.value = normalizedReceipts
            }

            val inventory = _inventory.value
            val normalizedInventory = mergeDuplicateInventoryItems(inventory)
                .map(::normalizeInventoryItem)
            if (normalizedInventory != inventory) {
                persistInventory(normalizedInventory)
                _inventory.value = normalizedInventory
            }

            backfillImportRecords()
            // After the backfill, so a promotion and a release in the same launch cannot fight.
            reconcileImportsWithRegistry()
        }
    }

    /**
     * Repairs import records left behind by a household that no longer exists — the eager release
     * in `deleteHousehold` crashing between the registry write and the rewrite, or a household
     * deleted by a build that predates the release entirely.
     *
     * Same rule as the eager path: records for goods still on a shelf are dropped, because nothing
     * tracks those goods any more and re-importing them is legitimate; records for goods that were
     * eaten, binned or cooked are kept and merely un-owned, because the goods are gone for good.
     *
     * Does nothing at all without a readable registry. Every unit test builds a repository over a
     * bare temp directory with no registry file, and treating that as "no household exists" would
     * release every record on construction.
     */
    private fun reconcileImportsWithRegistry() {
        val known = HouseholdStorageMigration.readRegistry(groceriesRoot)
            ?.households
            ?.mapTo(mutableSetOf(), Household::id)
            ?: return
        val reconciled = _receipts.value.map { receipt ->
            val kept = receipt.imports.mapNotNull { record ->
                val owner = record.householdId
                when {
                    owner == null || owner in known -> record
                    record.disposition.goodsAreGone -> record.copy(householdId = null)
                    else -> null
                }
            }
            if (kept == receipt.imports) receipt else receipt.copy(imports = kept)
        }
        if (reconciled != _receipts.value) {
            persistReceipts(reconciled)
            _receipts.value = reconciled
        }
    }

    /**
     * Reconstructs import records that predate schema 7 — a schema-5 receipt has none at all, and a
     * schema-6 one loads as bare UNKNOWN records with no household. Both are still discoverable
     * while the inventory rows exist, via the same receiptItemId/merge-key probes
     * [addReceiptItemsToInventory] uses.
     *
     * Exactly two moves, and nothing else: add a record for a covered line that has none, and
     * promote a record that is both unattributed and UNKNOWN. Promotion is evidence, not a guess —
     * it fires only where this household demonstrably holds a row pointing at that line, and a
     * record that reads UNKNOWN because the goods were thrown away has no such row. Nothing richer
     * is ever downgraded, no record already naming a household is re-attributed, and nothing is
     * ever removed, because a consumed item must keep counting as imported.
     *
     * Additive and idempotent, so each household contributes its own matches as it becomes active.
     * Items imported and fully consumed before the update are unrecoverable and stay "not
     * imported"; re-importing them is legitimate since the goods are gone.
     */
    private fun backfillImportRecords() {
        val inventory = _inventory.value
        val bySourceReceipt = inventory.filter { it.receiptId != null }.groupBy { it.receiptId }
        val backfilled = _receipts.value.map { receipt ->
            val imported = bySourceReceipt[receipt.id] ?: return@map receipt
            val sourceIds = imported.mapNotNull(InventoryItem::receiptItemId).toSet()
            val importKeys = imported.map { mergeKeyOf(it.name, it.unit) }.toSet()
            val covered = receipt.items
                .filter { it.id in sourceIds || mergeKeyOf(it.name, it.unit) in importKeys }
                .mapTo(mutableSetOf(), ReceiptItem::id)
            if (covered.isEmpty()) return@map receipt

            // When the goods were actually bought beats "now" — these rows are historical.
            val importedAt = imported.minOf { it.purchasedAtEpochMillis ?: it.addedAtEpochMillis }
            val promoted = receipt.imports.map { record ->
                if (record.receiptItemId in covered &&
                    record.householdId == null &&
                    record.disposition == ImportDisposition.UNKNOWN
                ) {
                    record.copy(
                        householdId = householdId,
                        importedAtEpochMillis = record.importedAtEpochMillis ?: importedAt,
                        disposition = ImportDisposition.IN_INVENTORY,
                    )
                } else {
                    record
                }
            }
            val known = receipt.importedItemIds
            val added = covered.filterNot { it in known }.map { id ->
                ReceiptItemImport(
                    receiptItemId = id,
                    householdId = householdId,
                    importedAtEpochMillis = importedAt,
                    disposition = ImportDisposition.IN_INVENTORY,
                )
            }
            if (added.isEmpty() && promoted == receipt.imports) {
                receipt
            } else {
                receipt.copy(imports = promoted + added)
            }
        }
        if (backfilled != _receipts.value) {
            persistReceipts(backfilled)
            _receipts.value = backfilled
        }
    }

    /**
     * Re-stamps the import records behind [item]. A merged import is one inventory row standing for
     * several receipt lines — "3 × Joghurt" — and all of them were marked imported, so a
     * disposition change has to move the whole group together.
     *
     * The group key comes from the *receipt* line rather than the inventory row: normalize passes
     * rewrite row fields on every launch and [mergeDuplicateInventoryItems] can elect a different
     * row as the survivor, while a receipt line's name and unit never change. Only records this
     * household owns (or that no one owns) are touched — holding the row that points at the line is
     * what makes them ours.
     *
     * Callers must already hold [lock].
     */
    private fun restampImports(
        item: InventoryItem,
        transform: (ReceiptItemImport) -> ReceiptItemImport,
    ) {
        val receiptId = item.receiptId ?: return
        val current = _receipts.value
        val receipt = current.firstOrNull { it.id == receiptId } ?: return

        val anchorLine = item.receiptItemId?.let { id -> receipt.items.firstOrNull { it.id == id } }
        val groupKey = anchorLine
            ?.let { mergeKeyOf(it.name, it.unit) }
            ?: mergeKeyOf(item.name, item.unit)
        val targetIds = receipt.items
            .filter { it.id == item.receiptItemId || mergeKeyOf(it.name, it.unit) == groupKey }
            .mapTo(mutableSetOf(), ReceiptItem::id)

        val updatedReceipt = receipt.copy(
            imports = receipt.imports.map { record ->
                val ours = record.householdId == null || record.householdId == householdId
                if (record.receiptItemId in targetIds && ours) transform(record) else record
            },
        )
        if (updatedReceipt == receipt) return
        val updated = current.map { if (it.id == receiptId) updatedReceipt else it }
        persistReceipts(updated)
        _receipts.value = updated
    }

    private fun ReceiptItemImport.movedTo(
        disposition: ImportDisposition,
        householdId: String?,
        atEpochMillis: Long,
    ): ReceiptItemImport = copy(
        householdId = householdId,
        disposition = disposition,
        dispositionChangedAtEpochMillis = atEpochMillis,
    )

    /**
     * Resolves what a product name means, in trust order: what the user taught
     * ([FoodConceptCorrections]) beats the bundled catalogue's keyword match, which beats a
     * previously-stored id. Re-matching by name before trusting the stored id lets catalog and
     * correction fixes retroactively reclassify already-persisted items — e.g. a keyword fix that
     * moves "Traubensaft" from GRAPES to JUICE applies to existing inventory on next launch, not
     * just newly-scanned items. [FoodConceptCatalog.byId] is only a fallback for names that no
     * longer match anything (so a still-valid past classification isn't dropped to OTHER).
     */
    private fun resolveConcept(name: String, storedConceptId: String?): FoodConcept? =
        foodConceptCorrections.conceptFor(name)
            ?: FoodConceptCatalog.match(name)
            ?: FoodConceptCatalog.byId(storedConceptId)

    private fun normalizeReceiptItem(item: ReceiptItem): ReceiptItem {
        val concept = resolveConcept(item.name, item.foodConceptId)
        val category = concept?.category
            ?: GroceryCategory.fromStorageValue(item.category, item.name)
        return item.copy(category = category.name, foodConceptId = concept?.id)
    }

    private fun normalizeInventoryItem(item: InventoryItem): InventoryItem {
        val concept = resolveConcept(item.name, item.foodConceptId)
        // The concept is resolved either way — it drives the icon and the shelf-life rule. A
        // user-picked category just stops the coarse bucket tracking it, the same way
        // LocationSource.USER stops the location tracking the suggester.
        val category = if (item.categorySource == CategorySource.USER.name) {
            GroceryCategory.fromStorageValue(item.category, item.name)
        } else {
            concept?.category ?: GroceryCategory.fromStorageValue(item.category, item.name)
        }
        val normalized = item.copy(category = category.name, foodConceptId = concept?.id)
            .withSuggestedLocationUnlessUserSet(category, concept?.id)
        // Recompute on every pass so items self-heal when the data behind the estimate improves —
        // not just a classification fix, but a shelf-life data fix too, e.g. a concept gaining a
        // proper rule where it used to fall back to its category.
        //
        // This runs on every construction, so it is the pass that would silently erase hand-set
        // expiry dates. It doesn't, because withEstimatedExpiry returns USER-sourced items
        // untouched — the same contract LocationSource.USER has for storage locations, which is
        // why the location below is an input to the estimate rather than something it overwrites.
        // Once printed-date OCR lands, this stays safe from the other direction as well:
        // ExpiryEstimator checks printedDateEpochMillis first, so a real printed date still wins
        // over the algorithmic guess.
        return normalized.withEstimatedExpiry(
            purchasedAtEpochMillis = normalized.purchasedAtEpochMillis
                ?: normalized.addedAtEpochMillis,
        )
    }

    /**
     * Collapses duplicate rows of the same product bought the same day (same receipt, or both
     * manual) into one entry with a summed quantity — "3 × Joghurt" imported as three lines by an
     * older app version becomes one row with quantity 3. Runs in the launch normalize pass, so
     * pre-merge data heals itself; new imports already arrive merged.
     */
    private fun mergeDuplicateInventoryItems(items: List<InventoryItem>): List<InventoryItem> {
        fun keyOf(item: InventoryItem) = Triple(
            item.receiptId,
            mergeKeyOf(item.name, item.unit),
            java.time.Instant
                .ofEpochMilli(item.purchasedAtEpochMillis ?: item.addedAtEpochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate(),
        )

        val groups = items.groupBy(::keyOf)
        if (groups.size == items.size) return items
        return items.mapNotNull { item ->
            val group = groups.getValue(keyOf(item))
            when {
                group.size == 1 -> item
                // Only the group's first occurrence survives, carrying the combined state.
                group.first().id != item.id -> null
                else -> {
                    val userLocated = group.firstOrNull {
                        it.locationSource == LocationSource.USER.name
                    }
                    item.copy(
                        quantity = group.sumOf(InventoryItem::quantity),
                        priceCents = group.mapNotNull(InventoryItem::priceCents)
                            .takeIf { it.isNotEmpty() }?.sum(),
                        openedAtEpochMillis = group
                            .mapNotNull(InventoryItem::openedAtEpochMillis).minOrNull(),
                        // Summed, not first-non-null: the quantities above are summed, so the
                        // denominator has to follow or three merged half-full rows would read as
                        // 150% of one pack.
                        packageSize = group.mapNotNull(InventoryItem::packageSize)
                            .takeIf { it.isNotEmpty() }?.sum(),
                        storageLocation = userLocated?.storageLocation ?: item.storageLocation,
                        locationSource = userLocated?.locationSource ?: item.locationSource,
                        locationChangedAtEpochMillis = userLocated?.locationChangedAtEpochMillis
                            ?: item.locationChangedAtEpochMillis,
                    )
                }
            }
        }
    }

    /**
     * Assigns (or re-suggests) a storage location. Locations the user picked are never touched;
     * SUGGESTED ones track the current suggestion so catalogue fixes propagate — the same
     * philosophy as the re-match-by-name normalization above.
     */
    private fun InventoryItem.withSuggestedLocationUnlessUserSet(
        category: GroceryCategory,
        conceptId: String?,
    ): InventoryItem {
        if (locationSource == LocationSource.USER.name) return this
        val suggested = StorageLocationSuggester.suggest(
            category = category,
            conceptRule = shelfLifeCatalog.conceptRule(conceptId),
        )
        if (storageLocation == suggested.name) return this
        return copy(
            storageLocation = suggested.name,
            locationSource = LocationSource.SUGGESTED.name,
        )
    }

    /** A date the user set by hand is theirs; no estimate ever overwrites it. */
    /**
     * The floor a tracked amount is held above. [InventoryItem] requires a positive quantity, and
     * an item sitting empty in the fridge is still an item — it leaves through removal, which asks
     * what became of it, not by silently reaching zero.
     */
    private val MINIMUM_TRACKED_AMOUNT = 0.001

    private val InventoryItem.hasUserExpiry: Boolean
        get() = expiryEstimateSource == ExpirySource.USER.name

    /**
     * Re-anchors the expiry estimate after something that changes it — opening the item, or moving
     * it to a location with a different storage life. Returns the receiver untouched when the date
     * is the user's, and keeps the original date when nothing better could be derived.
     */
    private fun InventoryItem.reestimatedExpiry(): InventoryItem {
        if (hasUserExpiry) return this
        return copy(expiresAtEpochMillis = null, expiryRangeEndEpochMillis = null)
            .withEstimatedExpiry(purchasedAtEpochMillis ?: addedAtEpochMillis)
            .let { if (it.expiresAtEpochMillis == null) this else it }
    }

    private fun InventoryItem.withEstimatedExpiry(
        purchasedAtEpochMillis: Long?,
    ): InventoryItem {
        if (hasUserExpiry) return this
        val estimate = expiryEstimator.estimate(
            foodConceptId = foodConceptId,
            categoryStorageValue = category,
            nameHint = name,
            purchasedAtEpochMillis = purchasedAtEpochMillis,
            openedAtEpochMillis = openedAtEpochMillis,
            printedDateEpochMillis = printedDateEpochMillis,
            location = StorageLocation.fromStorageValue(storageLocation),
            locationChangedAtEpochMillis = locationChangedAtEpochMillis,
        ) ?: return this
        return copy(
            expiresAtEpochMillis = estimate.expiresAtEpochMillis,
            expiryRangeEndEpochMillis = estimate.rangeEndEpochMillis,
            expiryEstimateSource = estimate.source.name,
        )
    }

    /**
     * Records that an item has been opened and re-estimates its window, since opened storage life
     * is typically much shorter than unopened. An expiry date the user set by hand is left alone.
     */
    fun markInventoryItemOpened(id: String): InventoryItem? {
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            if (existing.openedAtEpochMillis != null) return existing

            val opened = existing.copy(openedAtEpochMillis = System.currentTimeMillis())
            val reestimated = opened.reestimatedExpiry()

            val updated = current.map { if (it.id == id) reestimated else it }
            persistInventory(updated)
            _inventory.value = updated
            return reestimated
        }
    }

    /** Where an item with this classification is normally kept; used to pre-fill import review. */
    fun suggestStorageLocation(
        foodConceptId: String?,
        categoryStorageValue: String?,
        nameHint: String? = null,
    ): StorageLocation {
        val concept = nameHint?.let(foodConceptCorrections::conceptFor)
            ?: FoodConceptCatalog.byId(foodConceptId)
            ?: nameHint?.let(FoodConceptCatalog::match)
        val category = concept?.category
            ?: GroceryCategory.fromStorageValue(categoryStorageValue, nameHint)
        return StorageLocationSuggester.suggest(
            category = category,
            conceptRule = shelfLifeCatalog.conceptRule(concept?.id),
        )
    }

    /**
     * Moves an item between locations and re-estimates its window from the move date, since the
     * destination's storage life differs (freezing extends it, thawing shortens it). An expiry
     * date the user set by hand survives the move — the screen says so rather than silently
     * keeping the old date.
     */
    fun setInventoryItemStorageLocation(id: String, location: StorageLocation): InventoryItem? {
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            if (existing.storageLocation == location.name) return existing

            val moved = existing.copy(
                storageLocation = location.name,
                locationSource = LocationSource.USER.name,
                locationChangedAtEpochMillis = System.currentTimeMillis(),
            )
            val reestimated = moved.reestimatedExpiry()

            val updated = current.map { if (it.id == id) reestimated else it }
            persistInventory(updated)
            _inventory.value = updated
            return reestimated
        }
    }

    /**
     * Steps a countable item's quantity, e.g. one of three yogurts taken out. Floors at 1: the
     * last piece leaves through removal instead, so the used-up/thrown-away answer isn't lost.
     */
    /**
     * Records that the item's name means [conceptId] — the user's answer when the catalogue got a
     * receipt string wrong ("ALPRO SOJA VAN." is a plant drink). The correction is stored by name
     * in [FoodConceptCorrections], so it also applies to every other row of the same product, to
     * stored receipts, and to everything scanned later; the immediate re-normalize below makes
     * that visible now rather than at next launch. Re-estimating expiry from the new concept is
     * deliberate — the old estimate came from the wrong classification.
     */
    fun setInventoryItemFoodConcept(id: String, conceptId: String): InventoryItem? {
        synchronized(lock) {
            val existing = _inventory.value.firstOrNull { it.id == id } ?: return null
            foodConceptCorrections.set(existing.name, conceptId)

            val inventory = _inventory.value.map(::normalizeInventoryItem)
            if (inventory != _inventory.value) {
                persistInventory(inventory)
                _inventory.value = inventory
            }
            val receipts = _receipts.value.map { receipt ->
                receipt.copy(items = receipt.items.map(::normalizeReceiptItem))
            }
            if (receipts != _receipts.value) {
                persistReceipts(receipts)
                _receipts.value = receipts
            }
            return inventory.firstOrNull { it.id == id }
        }
    }

    fun adjustInventoryItemQuantity(id: String, delta: Double): InventoryItem? {
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            val newQuantity = (existing.quantity + delta).coerceAtLeast(1.0)
            if (newQuantity == existing.quantity) return existing

            val changed = existing.copy(quantity = newQuantity)
            val updated = current.map { if (it.id == id) changed else it }
            persistInventory(updated)
            _inventory.value = updated
            return changed
        }
    }

    /**
     * Sets how much is left, in the item's own unit. The remaining amount is a display hint about
     * stock, not about freshness, so expiry is unaffected.
     *
     * A value above the pack size raises the pack size to match rather than clamping — a refilled
     * canister or a topped-up bulk jar genuinely holds more than the bag it came from. Zero is
     * floored just above it, because an item still physically present must keep a positive
     * quantity; a genuinely finished item leaves through [removeInventoryItem], which asks whether
     * it was eaten or binned.
     */
    fun setInventoryItemAmount(id: String, quantity: Double): InventoryItem? {
        require(quantity.isFinite() && quantity >= 0.0) { "Amount must be finite and non-negative." }
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            val newQuantity = quantity.coerceAtLeast(MINIMUM_TRACKED_AMOUNT)
            val newPackageSize = existing.packageSize?.coerceAtLeast(newQuantity)
            if (newQuantity == existing.quantity && newPackageSize == existing.packageSize) {
                return existing
            }

            val changed = existing.copy(quantity = newQuantity, packageSize = newPackageSize)
            val updated = current.map { if (it.id == id) changed else it }
            persistInventory(updated)
            _inventory.value = updated
            return changed
        }
    }

    /**
     * Sets the "of 1 L" the remaining amount is read against, and optionally re-labels the unit
     * both are expressed in. Null stops tracking the amount altogether — the state a row is in
     * before the user ever touches it, and the way back out of tracking.
     *
     * Changing the unit does not convert the numbers: the caller is telling the app what the
     * existing figures mean, not asking for a conversion.
     */
    fun setInventoryItemPackageSize(
        id: String,
        packageSize: Double?,
        unit: String? = null,
    ): InventoryItem? {
        require(packageSize == null || (packageSize.isFinite() && packageSize > 0.0)) {
            "Package size must be finite and positive when set."
        }
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            val newUnit = unit?.trim()?.ifBlank { null } ?: existing.unit
            if (existing.packageSize == packageSize && existing.unit == newUnit) return existing

            val changed = existing.copy(
                packageSize = packageSize,
                unit = newUnit,
                // A shrinking pack can't hold more than it is; a growing one leaves the remainder
                // where it was rather than silently topping the item up.
                quantity = packageSize?.let { existing.quantity.coerceAtMost(it) }
                    ?: existing.quantity,
            )
            val updated = current.map { if (it.id == id) changed else it }
            persistInventory(updated)
            _inventory.value = updated
            return changed
        }
    }

    /**
     * Hand-sets the expiry date, stamping [ExpirySource.USER] so nothing estimates over it again —
     * not the launch normalize pass, not opening the item, not moving it to the freezer.
     *
     * Null hands the date back to the estimator and immediately recomputes, so the row shows a
     * fresh estimate straight away rather than "Expiry unknown" until the next launch.
     */
    fun setInventoryItemExpiryDate(id: String, expiresAtEpochMillis: Long?): InventoryItem? {
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null

            val changed = if (expiresAtEpochMillis == null) {
                existing.copy(
                    expiresAtEpochMillis = null,
                    expiryRangeEndEpochMillis = null,
                    expiryEstimateSource = null,
                ).withEstimatedExpiry(existing.purchasedAtEpochMillis ?: existing.addedAtEpochMillis)
            } else {
                existing.copy(
                    expiresAtEpochMillis = expiresAtEpochMillis,
                    // A hand-set date names one day; carrying a range end would render it as a
                    // window the user never gave.
                    expiryRangeEndEpochMillis = null,
                    expiryEstimateSource = ExpirySource.USER.name,
                )
            }
            if (changed == existing) return existing

            val updated = current.map { if (it.id == id) changed else it }
            persistInventory(updated)
            _inventory.value = updated
            return changed
        }
    }

    /**
     * Moves one row into a different coarse category. Unlike [setInventoryItemFoodConcept], which
     * teaches the app what a product *is* and therefore applies by name everywhere, this is a
     * per-row filing decision and stays on the row.
     *
     * Stamping [CategorySource.USER] is what makes it stick: the launch normalize pass otherwise
     * re-derives the category from the name-matched concept. Note that filing something under a
     * category with no shelf life ([GroceryCategory.OTHER], [GroceryCategory.HOUSEHOLD]) also
     * switches off expiry estimation for that row.
     */
    fun setInventoryItemCategory(id: String, category: GroceryCategory): InventoryItem? {
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            if (existing.category == category.name &&
                existing.categorySource == CategorySource.USER.name
            ) {
                return existing
            }

            val changed = existing.copy(
                category = category.name,
                categorySource = CategorySource.USER.name,
            ).reestimatedExpiry()
            val updated = current.map { if (it.id == id) changed else it }
            persistInventory(updated)
            _inventory.value = updated
            return changed
        }
    }

    /**
     * Renames one row — the receipt string was unreadable, or the user wants "Oat drink" rather
     * than "BAR. HAFERDRINK".
     *
     * Two things have to be carried across, because both the classifier and the import ledger are
     * keyed by name rather than by row:
     *
     * 1. **Classification.** A rename onto a name the app already understands is taken at face
     *    value and re-classifies the row — renaming is how the user says "this is not a mango,
     *    it is an ayran", and the icon, category and shelf life have to follow. Only when the new
     *    name matches nothing is the current concept re-taught under it, so that a purely
     *    cosmetic rename ("BAR. HAFERDRINK" → "Oat drink") keeps what the row already knew
     *    instead of falling back to an unclassified row.
     * 2. **Import provenance.** [restampImports] falls back to a name key for rows with no
     *    `receiptItemId` — which includes the extra lines of a merged group, not just manual rows.
     *    A concrete id is adopted *before* the name changes, so removing the item still marks its
     *    receipt line used up.
     *
     * One consequence is left deliberately unhandled: renaming a row onto another row's name lets
     * the launch merge pass combine them. That is usually what the user meant, and the amount
     * merging keeps the result coherent.
     */
    fun renameInventoryItem(id: String, name: String): InventoryItem? {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Inventory item name must not be blank." }
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            if (existing.name == trimmed) return existing

            val anchored = existing.copy(
                receiptItemId = existing.receiptItemId ?: anchorReceiptItemId(existing),
            )
            // Pin the current classification under the new name only when the new name is not
            // itself a statement about what the product is — see the note on classification above.
            val newNameClassifies = foodConceptCorrections.conceptFor(trimmed) != null ||
                FoodConceptCatalog.match(trimmed) != null
            if (!newNameClassifies) {
                anchored.foodConceptId?.let { conceptId ->
                    foodConceptCorrections.set(trimmed, conceptId)
                }
            }

            val changed = normalizeInventoryItem(anchored.copy(name = trimmed))
            val updated = current.map { if (it.id == id) changed else it }
            persistInventory(updated)
            _inventory.value = updated
            return changed
        }
    }

    /**
     * The receipt line this row most likely came from, for rows that carry a receipt but no line
     * id (older imports, and the non-primary members of a merged group). Resolved by the *current*
     * name, so it must be called before a rename rewrites it.
     */
    private fun anchorReceiptItemId(item: InventoryItem): String? {
        val receiptId = item.receiptId ?: return null
        val receipt = _receipts.value.firstOrNull { it.id == receiptId } ?: return null
        val key = mergeKeyOf(item.name, item.unit)
        return receipt.items.firstOrNull { mergeKeyOf(it.name, it.unit) == key }?.id
    }

    fun createScanSession(id: String = UUID.randomUUID().toString()): ScanDraft {
        synchronized(lock) {
            require(RECEIPT_ID_PATTERN.matches(id)) { "Invalid scan session ID." }
            val directory = receiptDirectory(id)
            check(!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "A scan session with this ID already exists."
            }

            ensureDirectory(directory)
            ensureDirectory(File(directory, ORIGINAL_DIRECTORY))
            ensureDirectory(File(directory, TILES_DIRECTORY))
            val draft = ScanDraft(
                session = ScanSession(
                    id = id,
                    directoryPath = directory.absolutePath,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
                acceptedFramePaths = emptyList(),
            )
            persistScanDraft(draft)
            setActiveScanId(id)
            return draft
        }
    }

    fun openOrCreateScanSession(id: String): ScanDraft {
        synchronized(lock) {
            check(_receipts.value.none { it.id == id }) {
                "This scan has already been saved as a receipt."
            }
            loadScanDraft(id)?.let { draft ->
                setActiveScanId(id)
                return draft
            }
            return createScanSession(id)
        }
    }

    fun activeScanSessionId(): String? {
        synchronized(lock) {
            val markedId = activeScanFile
                .takeIf(File::isFile)
                ?.let { file ->
                    runCatching {
                        JSONObject(file.readText(StandardCharsets.UTF_8)).getString("id")
                    }.getOrNull()
                }
            val markedDraft = markedId
                ?.takeIf(RECEIPT_ID_PATTERN::matches)
                ?.takeIf { id -> _receipts.value.none { it.id == id } }
                ?.let(::loadScanDraft)
            if (markedDraft != null) return markedDraft.session.id

            if (markedId != null && RECEIPT_ID_PATTERN.matches(markedId)) {
                if (_receipts.value.any { it.id == markedId }) {
                    File(receiptDirectory(markedId), SCAN_DRAFT_FILE).delete()
                }
            }
            if (activeScanFile.exists()) {
                activeScanFile.delete()
            }

            val recovered = findLatestRecoverableScanDraft() ?: return null
            setActiveScanId(recovered.session.id)
            return recovered.session.id
        }
    }

    fun updateScanDraft(
        session: ScanSession,
        acceptedFramePaths: List<String>,
    ): ScanDraft {
        synchronized(lock) {
            val sessionDirectory = receiptDirectory(session.id).canonicalFile
            require(File(session.directoryPath).canonicalFile == sessionDirectory) {
                "The scan session does not belong to this repository."
            }
            val originalDirectory = File(sessionDirectory, ORIGINAL_DIRECTORY).canonicalFile
            val validatedPaths = acceptedFramePaths.map { path ->
                File(path).canonicalFile.also { frame ->
                    require(frame.parentFile == originalDirectory && frame.isFile) {
                        "Captured frame is outside this scan session."
                    }
                }.absolutePath
            }
            val draft = ScanDraft(
                session = session,
                acceptedFramePaths = validatedPaths,
            )
            persistScanDraft(draft)
            return draft
        }
    }

    fun discardScanSession(session: ScanSession) {
        synchronized(lock) {
            check(_receipts.value.none { it.id == session.id }) {
                "A saved receipt must be deleted through the receipt archive."
            }
            val directory = receiptDirectory(session.id)
            require(File(session.directoryPath).canonicalFile == directory.canonicalFile) {
                "The scan session does not belong to this repository."
            }
            deleteDirectoryWithoutFollowingLinks(directory)
            clearActiveScanId(session.id)
        }
    }

    private fun loadScanDraft(id: String): ScanDraft? {
        if (!RECEIPT_ID_PATTERN.matches(id)) return null
        val directory = receiptDirectory(id)
        val manifest = File(directory, SCAN_DRAFT_FILE)
        if (!manifest.isFile) return null
        return runCatching {
            val json = JSONObject(manifest.readText(StandardCharsets.UTF_8))
            check(json.getString("id") == id)
            val originalDirectory = File(directory, ORIGINAL_DIRECTORY).canonicalFile
            val paths = json.optJSONArray("acceptedFrames").toStringList().map { name ->
                require(FRAME_FILE_PATTERN.matches(name))
                File(originalDirectory, name).canonicalFile.also { file ->
                    require(file.parentFile == originalDirectory && file.isFile)
                }.absolutePath
            }
            ScanDraft(
                session = ScanSession(
                    id = id,
                    directoryPath = directory.absolutePath,
                    createdAtEpochMillis = json.getLong("createdAtEpochMillis"),
                ),
                acceptedFramePaths = paths,
            )
        }.getOrNull()
    }

    private fun findLatestRecoverableScanDraft(): ScanDraft? =
        receiptsRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { candidate ->
                RECEIPT_ID_PATTERN.matches(candidate.name) &&
                    Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    _receipts.value.none { it.id == candidate.name }
            }
            .mapNotNull { loadScanDraft(it.name) }
            .maxByOrNull { it.session.createdAtEpochMillis }

    private fun persistScanDraft(draft: ScanDraft) {
        val manifest = File(receiptDirectory(draft.session.id), SCAN_DRAFT_FILE)
        val json = JSONObject()
            .put("id", draft.session.id)
            .put("createdAtEpochMillis", draft.session.createdAtEpochMillis)
            .put(
                "acceptedFrames",
                draft.acceptedFramePaths.map { File(it).name }.toStringJsonArray(),
            )
        atomicWrite(manifest, json.toString())
    }

    private fun setActiveScanId(id: String) {
        atomicWrite(activeScanFile, JSONObject().put("id", id).toString())
    }

    private fun clearActiveScanId(id: String) {
        if (!activeScanFile.isFile) return
        val activeId = runCatching {
            JSONObject(activeScanFile.readText(StandardCharsets.UTF_8)).optString("id")
        }.getOrNull()
        if (activeId == id) activeScanFile.delete()
    }

    fun nextFrameFile(session: ScanSession, index: Int): File {
        require(index >= 0) { "Frame index must not be negative." }
        synchronized(lock) {
            val directory = receiptDirectory(session.id)
            require(File(session.directoryPath).canonicalFile == directory.canonicalFile) {
                "The scan session does not belong to this repository."
            }
            ensureDirectory(directory)
            val originalDirectory = File(directory, ORIGINAL_DIRECTORY).also(::ensureDirectory)
            return File(originalDirectory, "frame_${index.toString().padStart(4, '0')}.jpg")
        }
    }

    fun saveReceipt(receipt: DigitalReceipt): DigitalReceipt {
        val storedReceipt = receipt.copy(
            tilePaths = receipt.tilePaths.toList(),
            sourceTilePaths = receipt.sourceTilePaths.toList(),
            sourceRegions = receipt.sourceRegions.toList(),
            originalFramePaths = receipt.originalFramePaths.toList(),
            regions = receipt.regions.toList(),
            items = receipt.items.toList(),
        )
        synchronized(lock) {
            ensureDirectory(receiptDirectory(storedReceipt.id))
            val updated = _receipts.value
                .filterNot { it.id == storedReceipt.id }
                .plus(storedReceipt)
                .sortedByDescending(DigitalReceipt::sortEpochMillis)
            persistReceipts(updated)
            _receipts.value = updated
            File(receiptDirectory(storedReceipt.id), SCAN_DRAFT_FILE).delete()
            clearActiveScanId(storedReceipt.id)
        }
        return storedReceipt
    }

    fun getReceipt(id: String): DigitalReceipt? =
        synchronized(lock) {
            _receipts.value.firstOrNull { it.id == id }
        }

    fun deleteReceipt(id: String): Boolean {
        synchronized(lock) {
            val current = _receipts.value
            if (current.none { it.id == id }) return false

            val updated = current.filterNot { it.id == id }
            persistReceipts(updated)
            _receipts.value = updated
            deleteDirectoryWithoutFollowingLinks(receiptDirectory(id))
            return true
        }
    }

    /**
     * Moves one item out of this household's inventory and into [targetHouseholdId]'s.
     *
     * Written straight to the target's index file rather than through a second repository: only
     * the active household is held in memory, and the target reloads from disk the next time it
     * becomes active. The row keeps its id and every field — a move is a change of shelf, not a
     * new purchase — so it deliberately skips [addInventoryItem]'s normalize-and-stack path, which
     * would re-estimate expiry and could merge it into an unrelated same-day row.
     *
     * The target is written before the source row is dropped. If the second write fails the item
     * exists in both households, which the user can see and fix; the other order could lose it.
     */
    fun moveInventoryItemToHousehold(id: String, targetHouseholdId: String): Boolean {
        synchronized(lock) {
            checkStorageReadable()
            val current = _inventory.value
            val item = current.firstOrNull { it.id == id } ?: return false

            val targetDirectory =
                File(householdRoot(groceriesRoot, targetHouseholdId), INVENTORY_DIRECTORY)
                    .also(::ensureDirectory)
            val targetFile = File(targetDirectory, INDEX_FILE)
            if (targetFile.canonicalFile == inventoryIndexFile.canonicalFile) return false

            // Read the target's own file instead of assuming empty: overwriting an index that
            // exists but does not parse would wipe that household's inventory.
            val existing = if (targetFile.isFile) {
                val root = runCatching {
                    JSONObject(targetFile.readText(StandardCharsets.UTF_8))
                }.getOrNull() ?: return false
                val array = root.optJSONArray(INVENTORY_KEY)
                buildList {
                    repeat(array?.length() ?: 0) { index ->
                        val value = array?.optJSONObject(index) ?: return@repeat
                        runCatching { value.toInventoryItem() }.getOrNull()?.let(::add)
                    }
                }
            } else {
                emptyList()
            }
            if (existing.any { it.id == item.id }) return false

            atomicWrite(
                targetFile,
                JSONObject()
                    .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
                    .put(
                        INVENTORY_KEY,
                        (listOf(item) + existing).toJsonArray(InventoryItem::toJson),
                    )
                    .toString(),
            )

            val updated = current.filterNot { it.id == id }
            persistInventory(updated)
            _inventory.value = updated
            // Last, and deliberately so: if this step fails the goods are in the target household
            // wearing the source's name — a wrong label, never a lost item or a wrong block.
            restampImports(item) {
                it.movedTo(
                    disposition = ImportDisposition.IN_INVENTORY,
                    householdId = targetHouseholdId,
                    atEpochMillis = System.currentTimeMillis(),
                )
            }
            return true
        }
    }

    /**
     * Reverses a [removeInventoryItem] call, for undoing an accidental swipe.
     *
     * Deliberately not [addInventoryItem]: that one normalizes, re-estimates expiry and stacks
     * onto same-day duplicates, so undo would hand back a subtly different row. This re-inserts
     * the snapshot verbatim and drops the consumption event the removal wrote, so a mis-swipe
     * leaves no trace in the waste figures on Insights.
     *
     * Returns false if an item with this id is already present, which makes a double-undo a no-op.
     */
    fun restoreRemovedInventoryItem(item: InventoryItem): Boolean {
        synchronized(lock) {
            val current = _inventory.value
            if (current.any { it.id == item.id }) return false
            // The list is newest-first everywhere it is read, and the restored row is the most
            // recent thing the user touched.
            val updated = listOf(item) + current
            persistInventory(updated)
            _inventory.value = updated

            val events = _consumptionEvents.value
            val undoneEvent = events.lastOrNull { it.itemId == item.id }
            if (undoneEvent != null) {
                val remaining = events - undoneEvent
                persistConsumptionEvents(remaining)
                _consumptionEvents.value = remaining
            }
            // The row is unambiguously back on this household's shelf, so re-point the ownership
            // as well as the disposition — the removal being undone may have been someone else's.
            restampImports(item) {
                it.movedTo(
                    disposition = ImportDisposition.IN_INVENTORY,
                    householdId = householdId,
                    atEpochMillis = System.currentTimeMillis(),
                )
            }
            return true
        }
    }

    /**
     * The row a re-scan of [barcode] should reuse, or null when this pack is new to the inventory.
     * The list is newest-first, so a repeat purchase resolves to the most recent one.
     */
    fun inventoryItemByBarcode(barcode: String): InventoryItem? =
        _inventory.value.firstOrNull { it.barcode == barcode }

    fun addInventoryItem(item: InventoryItem): InventoryItem {
        synchronized(lock) {
            // The same product bought again the same day stacks onto the existing entry instead
            // of cluttering the list — three yogurts become one row with quantity 3.
            val anchorEpochMillis0 = item.purchasedAtEpochMillis ?: item.addedAtEpochMillis
            val stackTarget = _inventory.value.firstOrNull { existing ->
                // A scanned barcode identifies the product more reliably than its name does — the
                // same pack can arrive as "Vollmilch" by hand and "Weihenstephan Frische Vollmilch"
                // from Open Food Facts.
                val sameProduct = (item.barcode != null && existing.barcode == item.barcode) ||
                    mergeKeyOf(existing.name, existing.unit) == mergeKeyOf(item.name, item.unit)
                sameProduct && isSameCalendarDay(
                    existing.purchasedAtEpochMillis ?: existing.addedAtEpochMillis,
                    anchorEpochMillis0,
                )
            }
            if (stackTarget != null) {
                val stacked = stackTarget.copy(
                    quantity = stackTarget.quantity + item.quantity,
                    // A row first added by hand learns its barcode the first time it is scanned.
                    barcode = stackTarget.barcode ?: item.barcode,
                )
                val updated = _inventory.value.map { if (it.id == stacked.id) stacked else it }
                persistInventory(updated)
                _inventory.value = updated
                return stacked
            }

            val concept = foodConceptCorrections.conceptFor(item.name)
                ?: FoodConceptCatalog.byId(item.foodConceptId)
                ?: FoodConceptCatalog.match(item.name)
            val resolvedCategoryName = item.category ?: concept?.category?.name
            val category = concept?.category
                ?: GroceryCategory.fromStorageValue(resolvedCategoryName, item.name)
            val anchorEpochMillis = item.purchasedAtEpochMillis ?: item.addedAtEpochMillis
            val withConcept = item.copy(
                foodConceptId = concept?.id ?: item.foodConceptId,
                category = resolvedCategoryName,
                openedAtEpochMillis = item.openedAtEpochMillis
                    ?: if (category.opensOnPurchase) anchorEpochMillis else null,
            ).withSuggestedLocationUnlessUserSet(category, concept?.id)
            val storedItem = if (withConcept.expiresAtEpochMillis != null) {
                withConcept
            } else {
                withConcept.withEstimatedExpiry(anchorEpochMillis)
            }
            val updated = _inventory.value
                .filterNot { it.id == storedItem.id }
                .plus(storedItem)
                .sortedByDescending(InventoryItem::addedAtEpochMillis)
            persistInventory(updated)
            _inventory.value = updated
            return storedItem
        }
    }

    /**
     * Removes an item. A non-null [reason] additionally records a consumption event — the data
     * behind future waste/spending insights. "Just remove" corrections pass null and leave no
     * trace, so data-entry mistakes don't pollute the log.
     */
    fun removeInventoryItem(id: String, reason: ConsumptionReason? = null): Boolean {
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return false
            val now = System.currentTimeMillis()

            if (reason != null) {
                val event = ConsumptionEvent(
                    itemId = existing.id,
                    name = existing.name,
                    category = existing.category,
                    foodConceptId = existing.foodConceptId,
                    quantity = existing.quantity,
                    unit = existing.unit,
                    priceCents = existing.priceCents,
                    packageSize = existing.packageSize,
                    reason = reason,
                    addedAtEpochMillis = existing.addedAtEpochMillis,
                    expiresAtEpochMillis = existing.expiresAtEpochMillis,
                    removedAtEpochMillis = now,
                )
                val events = _consumptionEvents.value + event
                persistConsumptionEvents(events)
                _consumptionEvents.value = events
            }

            val updated = current.filterNot { it.id == id }
            persistInventory(updated)
            _inventory.value = updated
            restampImports(existing) {
                it.movedTo(
                    disposition = when (reason) {
                        ConsumptionReason.USED_UP -> ImportDisposition.USED_UP
                        ConsumptionReason.THROWN_AWAY -> ImportDisposition.THROWN_AWAY
                        ConsumptionReason.COOKED -> ImportDisposition.COOKED
                        // No UI path produces this any more; the API keeps it for undo tests.
                        null -> ImportDisposition.UNKNOWN
                    },
                    householdId = householdId,
                    atEpochMillis = now,
                )
            }
            return true
        }
    }

    /**
     * Applies a cook plan: decrements quantities, steps down remaining amounts, and removes
     * used-up items. Partial decrements log an event carrying only the consumed amount with no
     * price (the item's full price is logged when the row finally leaves); removals log a full
     * snapshot. Amount steps log nothing — the remaining amount is a display hint about stock,
     * consistent with [setInventoryItemAmount]. Returns the events written.
     */
    fun consumeForRecipe(
        recipeId: String,
        recipeTitle: String,
        consumptions: List<CookConsumption>,
    ): List<ConsumptionEvent> {
        synchronized(lock) {
            var inventory = _inventory.value
            val newEvents = mutableListOf<ConsumptionEvent>()
            val removed = mutableListOf<InventoryItem>()
            val now = System.currentTimeMillis()
            for (consumption in consumptions) {
                val existing = inventory.firstOrNull { it.id == consumption.itemId } ?: continue
                when (val outcome = CookPlanner.applyConsumption(existing, consumption)) {
                    is CookOutcome.UpdateQuantity -> {
                        inventory = inventory.map {
                            if (it.id == existing.id) it.copy(quantity = outcome.newQuantity) else it
                        }
                        newEvents += cookedEvent(
                            item = existing,
                            quantity = existing.quantity - outcome.newQuantity,
                            priceCents = null,
                            recipeId = recipeId,
                            recipeTitle = recipeTitle,
                            removedAtEpochMillis = now,
                        )
                    }
                    is CookOutcome.SetAmount -> {
                        inventory = inventory.map {
                            if (it.id == existing.id) {
                                it.copy(
                                    quantity = outcome.newQuantity,
                                    packageSize = outcome.packageSize,
                                )
                            } else {
                                it
                            }
                        }
                    }
                    is CookOutcome.Remove -> {
                        inventory = inventory.filterNot { it.id == existing.id }
                        removed += existing
                        newEvents += cookedEvent(
                            item = existing,
                            quantity = existing.quantity,
                            priceCents = existing.priceCents,
                            recipeId = recipeId,
                            recipeTitle = recipeTitle,
                            removedAtEpochMillis = now,
                        )
                    }
                }
            }
            if (newEvents.isNotEmpty()) {
                val events = _consumptionEvents.value + newEvents
                persistConsumptionEvents(events)
                _consumptionEvents.value = events
            }
            persistInventory(inventory)
            _inventory.value = inventory
            // Only rows that actually left carry a disposition change; a partial decrement or a
            // fill-level step leaves the row on the shelf, so its records stay IN_INVENTORY.
            removed.forEach { item ->
                restampImports(item) {
                    it.movedTo(ImportDisposition.COOKED, householdId, now)
                }
            }
            return newEvents
        }
    }

    private fun cookedEvent(
        item: InventoryItem,
        quantity: Double,
        priceCents: Int?,
        recipeId: String,
        recipeTitle: String,
        removedAtEpochMillis: Long,
    ): ConsumptionEvent = ConsumptionEvent(
        itemId = item.id,
        name = item.name,
        category = item.category,
        foodConceptId = item.foodConceptId,
        quantity = quantity,
        unit = item.unit,
        priceCents = priceCents,
        packageSize = item.packageSize,
        reason = ConsumptionReason.COOKED,
        recipeId = recipeId,
        recipeTitle = recipeTitle,
        addedAtEpochMillis = item.addedAtEpochMillis,
        expiresAtEpochMillis = item.expiresAtEpochMillis,
        removedAtEpochMillis = removedAtEpochMillis,
    )

    fun importBioCompanyReceipt(
        merchant: String?,
        dateIso: String,
        totalCents: Int?,
        items: List<ReceiptItem>,
        bioCompanyReceiptNumber: Int?,
        bioCompanyCheckout: Int?,
    ): DigitalReceipt {
        val now = System.currentTimeMillis()
        val epochMillis = parseIsoDateToEpochMillis(dateIso)
        val receipt = DigitalReceipt(
            merchant = (if (merchant != null && merchant.isNotBlank()) merchant else "BIO COMPANY"),
            dateEpochMillis = epochMillis,
            totalCents = totalCents,
            currency = "EUR",
            items = items,
            status = ReceiptStatus.READY,
            createdAtEpochMillis = now,
            source = ReceiptSource.BIO_COMPANY,
            bioCompanyReceiptNumber = bioCompanyReceiptNumber,
            bioCompanyCheckout = bioCompanyCheckout,
        )
        synchronized(lock) {
            val exists = _receipts.value.any { r ->
                r.bioCompanyReceiptNumber == bioCompanyReceiptNumber &&
                    r.bioCompanyCheckout == bioCompanyCheckout
            }
            if (exists) return _receipts.value.first { r ->
                r.bioCompanyReceiptNumber == bioCompanyReceiptNumber &&
                    r.bioCompanyCheckout == bioCompanyCheckout
            }
            val updated = _receipts.value
                .plus(receipt)
                .sortedByDescending(DigitalReceipt::sortEpochMillis)
            persistReceipts(updated)
            _receipts.value = updated
        }
        return receipt
    }

    /** REWE stamps receipts in UTC ("2025-12-17T16:35:28Z"). */
    private fun parseInstantToEpochMillis(timestamp: String): Long? =
        runCatching { java.time.Instant.parse(timestamp).toEpochMilli() }
            .getOrElse { parseIsoDateToEpochMillis(timestamp) }

    private fun parseIsoDateToEpochMillis(dateIso: String): Long? {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.parse(dateIso)?.time
        }.getOrDefault(runCatching {
            val sdf2 = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf2.parse(dateIso)?.time
        }.getOrNull())
    }

    fun importReweReceipt(
        merchant: String?,
        dateIso: String,
        totalCents: Int?,
        items: List<ReceiptItem>,
        reweReceiptId: String,
    ): DigitalReceipt {
        val receipt = DigitalReceipt(
            merchant = merchant?.takeIf(String::isNotBlank) ?: "REWE",
            dateEpochMillis = parseInstantToEpochMillis(dateIso),
            totalCents = totalCents,
            currency = "EUR",
            items = items,
            status = ReceiptStatus.READY,
            createdAtEpochMillis = System.currentTimeMillis(),
            source = ReceiptSource.REWE,
            reweReceiptId = reweReceiptId,
        )
        synchronized(lock) {
            _receipts.value.firstOrNull { it.reweReceiptId == reweReceiptId }?.let { return it }
            val updated = _receipts.value
                .plus(receipt)
                .sortedByDescending(DigitalReceipt::sortEpochMillis)
            persistReceipts(updated)
            _receipts.value = updated
        }
        return receipt
    }

    fun hasReweReceipt(reweReceiptId: String): Boolean = synchronized(lock) {
        _receipts.value.any { it.reweReceiptId == reweReceiptId }
    }

    fun hasBioCompanyReceipt(receiptNumber: Int?, checkout: Int?): Boolean {
        return synchronized(lock) {
            receiptNumber != null && checkout != null &&
                _receipts.value.any { r ->
                    r.bioCompanyReceiptNumber == receiptNumber &&
                        r.bioCompanyCheckout == checkout
                }
        }
    }

    fun addReceiptItemsToInventory(
        receiptId: String,
        selections: List<ReceiptItemSelection>,
    ): List<InventoryItem> {
        if (selections.isEmpty()) return emptyList()
        synchronized(lock) {
            val receipt = _receipts.value.firstOrNull { it.id == receiptId } ?: return emptyList()
            val selectionsById = selections.associateBy(ReceiptItemSelection::receiptItemId)
            val existingSourceIds = _inventory.value
                .asSequence()
                .filter { it.receiptId == receiptId }
                .mapNotNull(InventoryItem::receiptItemId)
                .toSet()
            // Merged imports keep only their first line's receiptItemId, so already-imported
            // detection also matches by name+unit — otherwise the extra lines of "3 × Joghurt"
            // would look unimported forever and re-add on every visit.
            val existingImportKeys = _inventory.value
                .asSequence()
                .filter { it.receiptId == receiptId }
                .map { mergeKeyOf(it.name, it.unit) }
                .toSet()
            val addedAt = System.currentTimeMillis()
            val importGroups = receipt.items
                .filter {
                    it.id in selectionsById && it.id !in existingSourceIds &&
                        it.id !in receipt.importedItemIds &&
                        mergeKeyOf(it.name, it.unit) !in existingImportKeys
                }
                // The same product rung up as several receipt lines becomes one inventory entry
                // with a summed quantity ("3 × Joghurt" → one row, quantity 3).
                .groupBy { mergeKeyOf(it.name, it.unit) }
            val addedItems = importGroups
                .map { (_, lines) ->
                    val primary = lines.first()
                    // Prefer a line the user explicitly re-homed, else the suggestion.
                    val selection = lines
                        .mapNotNull { selectionsById[it.id] }
                        .firstOrNull { it.locationSource == LocationSource.USER }
                        ?: selectionsById.getValue(primary.id)
                    val anchorEpochMillis = receipt.dateEpochMillis ?: addedAt
                    val category = GroceryCategory.fromStorageValue(primary.category, primary.name)
                    InventoryItem(
                        name = primary.name,
                        category = primary.category,
                        foodConceptId = primary.foodConceptId,
                        quantity = lines.sumOf(ReceiptItem::quantity),
                        unit = primary.unit,
                        priceCents = primary.priceCents?.let { unitPrice ->
                            lines.sumOf { it.priceCents ?: unitPrice }
                        },
                        storageLocation = selection.storageLocation.name,
                        locationSource = selection.locationSource.name,
                        purchasedAtEpochMillis = receipt.dateEpochMillis,
                        openedAtEpochMillis = if (category.opensOnPurchase) anchorEpochMillis else null,
                        receiptId = receipt.id,
                        receiptItemId = primary.id,
                        addedAtEpochMillis = addedAt,
                    ).withEstimatedExpiry(anchorEpochMillis)
                }

            if (addedItems.isEmpty()) return emptyList()
            val updated = (_inventory.value + addedItems)
                .sortedByDescending(InventoryItem::addedAtEpochMillis)
            persistInventory(updated)
            _inventory.value = updated
            // Every line of an imported merge group counts as imported, not just the primary —
            // the extra lines of "3 × Joghurt" are in the fridge too. Persisted on the receipt,
            // which is shared device-wide, so the status holds in every household and survives the
            // inventory rows later being consumed or removed.
            val coveredIds = importGroups.values.flatten().map(ReceiptItem::id).toSet()
            val updatedReceipts = _receipts.value.map { stored ->
                if (stored.id == receiptId) {
                    // filterNot keeps this idempotent now that the records are a list: a line that
                    // already has a record keeps the one it has.
                    val known = stored.importedItemIds
                    stored.copy(
                        imports = stored.imports + coveredIds
                            .filterNot { it in known }
                            .map { id ->
                                ReceiptItemImport(
                                    receiptItemId = id,
                                    householdId = householdId,
                                    importedAtEpochMillis = addedAt,
                                    disposition = ImportDisposition.IN_INVENTORY,
                                    dispositionChangedAtEpochMillis = addedAt,
                                )
                            },
                    )
                } else {
                    stored
                }
            }
            persistReceipts(updatedReceipts)
            _receipts.value = updatedReceipts
            return addedItems
        }
    }

    /**
     * Turns a stored path into one callers can open. Paths are persisted relative to
     * [groceriesRoot] (so they read `receipts/<id>/…`), but every consumer — the image loaders, the
     * OCR re-runner, the editor's walk back up to the receipt directory — works in absolute paths,
     * so the conversion happens here and nowhere else.
     *
     * Absolute values predate the relative layout. One that still resolves is handed back
     * untouched; one that does not is re-rooted at the current receipts directory, which heals a
     * receipt whose index escaped the migration's rewrite.
     */
    private fun resolvePath(stored: String): String {
        if (!stored.startsWith("/")) return File(groceriesRoot, stored).absolutePath
        if (File(stored).exists()) return stored
        val marker = "/$RECEIPTS_DIRECTORY/"
        val at = stored.lastIndexOf(marker)
        if (at < 0) return stored
        return File(receiptsRoot, stored.substring(at + marker.length)).absolutePath
    }

    /**
     * Inverse of [resolvePath]. Compares against both the plain and the canonical root because
     * `filesDir` is typically `/data/user/0/<pkg>/files` while the same directory canonicalizes to
     * `/data/data/<pkg>/files` — matching only one of them would silently keep storing absolutes.
     */
    private fun storePath(path: String): String {
        if (!path.startsWith("/")) return path
        for (root in storageRootPrefixes) {
            if (path.startsWith(root)) return path.removePrefix(root)
        }
        return path
    }

    private fun loadReceipts(): List<DigitalReceipt> =
        loadArray(receiptIndexFile, RECEIPTS_KEY) { it.toDigitalReceipt(::resolvePath) }
            .sortedByDescending(DigitalReceipt::sortEpochMillis)

    /**
     * Re-reads the shared receipt index after someone else rewrote it — today, the import release
     * that runs when a household is deleted.
     *
     * Necessary because deleting a household that is not the active one does not rebuild this
     * repository: the in-memory receipts would stay stale and the next persist would write them
     * straight back over the release, silently undoing it.
     *
     * Unlike [loadReceipts] this never publishes an empty list over good data. A file that has
     * become unparseable latches [storageUnreadable] to block writes, but what is already in memory
     * is the best copy left and is kept.
     */
    fun reloadReceipts() {
        synchronized(lock) {
            if (!receiptIndexFile.isFile) return
            val root = runCatching {
                JSONObject(receiptIndexFile.readText(StandardCharsets.UTF_8))
            }.getOrNull()
            if (root == null) {
                storageUnreadable = true
                return
            }
            _receipts.value = root.optJSONArray(RECEIPTS_KEY)
                .toObjectList { runCatching { it.toDigitalReceipt(::resolvePath) }.getOrNull() }
                .filterNotNull()
                .sortedByDescending(DigitalReceipt::sortEpochMillis)
        }
    }

    private fun loadInventory(): List<InventoryItem> =
        loadArray(inventoryIndexFile, INVENTORY_KEY) { it.toInventoryItem() }
            .sortedByDescending(InventoryItem::addedAtEpochMillis)

    private fun loadConsumptionEvents(): List<ConsumptionEvent> =
        loadArray(consumptionLogFile, CONSUMPTION_EVENTS_KEY) { it.toConsumptionEventOrNull() }
            .filterNotNull()

    /**
     * An absent file legitimately means "nothing stored yet". A file that exists but does not parse
     * means something else entirely, and returning an empty list for it would let the next write
     * replace real data with nothing — so that case latches [storageUnreadable] and blocks writes
     * instead. Individual entries that fail to parse are still skipped, as before: one bad row
     * should not hide the rest.
     */
    private fun <T> loadArray(
        file: File,
        key: String,
        transform: (JSONObject) -> T,
    ): List<T> {
        if (!file.isFile) return emptyList()
        val root = runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull()
        if (root == null) {
            storageUnreadable = true
            return emptyList()
        }
        val array = root.optJSONArray(key) ?: return emptyList()
        return buildList {
            repeat(array.length()) { index ->
                val value = array.optJSONObject(index) ?: return@repeat
                runCatching { transform(value) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun checkStorageReadable() {
        check(!storageUnreadable) {
            "Refusing to write: stored data under ${groceriesRoot.absolutePath} could not be " +
                "read, and overwriting it would discard it."
        }
    }

    private fun persistReceipts(receipts: List<DigitalReceipt>) {
        checkStorageReadable()
        val root = JSONObject()
            .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            .put(RECEIPTS_KEY, receipts.toJsonArray { it.toJson(::storePath) })
        atomicWrite(receiptIndexFile, root.toString())
    }

    private fun persistInventory(inventory: List<InventoryItem>) {
        checkStorageReadable()
        val root = JSONObject()
            .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            .put(INVENTORY_KEY, inventory.toJsonArray(InventoryItem::toJson))
        atomicWrite(inventoryIndexFile, root.toString())
    }

    // Whole-file rewrite like every other persist path. At roughly ten removals a day this stays
    // around a megabyte per year; revisit with an append-only format if the log ever grows hot.
    private fun persistConsumptionEvents(events: List<ConsumptionEvent>) {
        checkStorageReadable()
        val root = JSONObject()
            .put(SCHEMA_VERSION_KEY, CONSUMPTION_SCHEMA_VERSION)
            .put(CONSUMPTION_EVENTS_KEY, events.toJsonArray(ConsumptionEvent::toJson))
        atomicWrite(consumptionLogFile, root.toString())
    }

    private fun receiptDirectory(id: String): File {
        require(RECEIPT_ID_PATTERN.matches(id)) {
            "Receipt IDs may contain only letters, numbers, underscores, and hyphens."
        }
        val rootPath = receiptsRoot.canonicalFile.toPath()
        val receiptPath = rootPath.resolve(id).normalize()
        require(receiptPath.parent == rootPath) { "Receipt directory is outside repository storage." }
        return receiptPath.toFile()
    }

    companion object {
        /**
         * Opens the repository for one household. [shelfLifeCatalog] is a parameter rather than a
         * field read so callers that switch households can parse the asset once and reuse it.
         */
        fun forHousehold(
            context: Context,
            householdId: String,
            shelfLifeCatalog: ShelfLifeCatalog =
                ShelfLifeCatalog.fromAssets(context.applicationContext),
        ): GroceriesRepository {
            val root = groceriesRoot(context)
            return GroceriesRepository(
                groceriesRoot = root,
                householdRoot = householdRoot(root, householdId),
                shelfLifeCatalog = shelfLifeCatalog,
                // Passed rather than left to the directory-name default, so the coupling between
                // the id and the path is stated once here instead of being incidental.
                householdId = householdId,
            )
        }

        /** The shared root that holds `households/` alongside the global `recipes/` directory. */
        internal fun groceriesRoot(context: Context): File =
            File(context.applicationContext.filesDir, STORAGE_DIRECTORY)

        internal const val STORAGE_DIRECTORY = "groceries"
        private const val CONCEPT_CORRECTIONS_FILE = "concept_corrections.json"
        private const val RECEIPTS_DIRECTORY = "receipts"
        private const val INVENTORY_DIRECTORY = "inventory"
        private const val CONSUMPTION_DIRECTORY = "consumption"
        private const val CONSUMPTION_LOG_FILE = "log.json"
        private const val CONSUMPTION_EVENTS_KEY = "events"
        private const val CONSUMPTION_SCHEMA_VERSION = 1
        private const val ORIGINAL_DIRECTORY = "original"
        private const val TILES_DIRECTORY = "tiles"
        private const val INDEX_FILE = "index.json"
        private const val ACTIVE_SCAN_FILE = "active_scan.json"
        private const val SCAN_DRAFT_FILE = "scan_draft.json"
        private const val SCHEMA_VERSION_KEY = "schemaVersion"
        private const val RECEIPTS_KEY = "receipts"
        private const val INVENTORY_KEY = "inventory"
        /**
         * 5 stores receipt image paths relative to the household root; 4 and below stored them
         * absolute. 6 adds per-item import status as a bare id array (`importedItemIds`). 7 adds
         * full import provenance ([DigitalReceipt.imports]) — which household took the line, when,
         * and what became of it.
         *
         * 7 keeps writing 6's `importedItemIds` alongside the records, and the reader unions the
         * two. `adb install -r` of an older build is the documented safe reinstall here, and that
         * build reads only the id array; dropping it would make every imported line look fresh and
         * duplicate real inventory. The mirror also means one malformed record can never silently
         * unblock a line. The obligation it creates: anything that releases a record must rewrite
         * the mirror too.
         *
         * The same constant versions the inventory index, which no one reads a version from.
         */
        internal const val SCHEMA_VERSION = 7
        private val RECEIPT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val FRAME_FILE_PATTERN = Regex("frame_\\d{4,}\\.jpg")
    }
}

private val DigitalReceipt.sortEpochMillis: Long
    get() = dateEpochMillis ?: createdAtEpochMillis

/** Two entries describe the same product when name and unit match, ignoring case and padding. */
private fun mergeKeyOf(name: String, unit: String?): String =
    name.trim().lowercase(Locale.GERMAN) + "|" + unit?.trim()?.lowercase(Locale.GERMAN).orEmpty()

private fun isSameCalendarDay(firstEpochMillis: Long, secondEpochMillis: Long): Boolean {
    val zone = java.time.ZoneId.systemDefault()
    return java.time.Instant.ofEpochMilli(firstEpochMillis).atZone(zone).toLocalDate() ==
        java.time.Instant.ofEpochMilli(secondEpochMillis).atZone(zone).toLocalDate()
}

private fun DigitalReceipt.toJson(storePath: (String) -> String): JSONObject = JSONObject()
    .put("id", id)
    .putNullable("merchant", merchant)
    .putNullable("dateEpochMillis", dateEpochMillis)
    .putNullable("totalCents", totalCents)
    .put("currency", currency)
    .putNullable("previewPath", previewPath?.let(storePath))
    .put("tilePaths", tilePaths.map(storePath).toStringJsonArray())
    .putNullable("sourcePreviewPath", sourcePreviewPath?.let(storePath))
    .put("sourceTilePaths", sourceTilePaths.map(storePath).toStringJsonArray())
    .put("sourceRegions", sourceRegions.toJsonArray(ReceiptTextRegion::toJson))
    .put("imageEdit", imageEdit.toJson())
    .put("originalFramePaths", originalFramePaths.map(storePath).toStringJsonArray())
    .put("rawText", rawText)
    .put("regions", regions.toJsonArray(ReceiptTextRegion::toJson))
    .put("items", items.toJsonArray(ReceiptItem::toJson))
    .put("status", status.name)
    .put("createdAtEpochMillis", createdAtEpochMillis)
    .put("source", source.name)
    .putNullable("bioCompanyReceiptNumber", bioCompanyReceiptNumber)
    .putNullable("bioCompanyCheckout", bioCompanyCheckout)
    .putNullable("reweReceiptId", reweReceiptId)
    .put("imports", imports.toJsonArray(ReceiptItemImport::toJson))
    // Schema-6 mirror, deliberately still written — see the SCHEMA_VERSION note.
    .put("importedItemIds", imports.map(ReceiptItemImport::receiptItemId).toStringJsonArray())

private fun ReceiptItemImport.toJson(): JSONObject = JSONObject()
    .put("receiptItemId", receiptItemId)
    .putNullable("householdId", householdId)
    .putNullable("importedAtEpochMillis", importedAtEpochMillis)
    .put("disposition", disposition.name)
    .putNullable("dispositionChangedAtEpochMillis", dispositionChangedAtEpochMillis)

private fun ReceiptTextRegion.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("text", text)
    .put("globalLeft", globalLeft.toDouble())
    .put("globalTop", globalTop.toDouble())
    .put("globalRight", globalRight.toDouble())
    .put("globalBottom", globalBottom.toDouble())
    .putNullable("confidence", confidence?.toDouble())
    .put("sourceTileIndex", sourceTileIndex)

private fun ReceiptItem.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .put("quantity", quantity)
    .putNullable("unit", unit)
    .putNullable("priceCents", priceCents)
    .putNullable("category", category)
    .putNullable("foodConceptId", foodConceptId)
    .putNullable("textRegionId", textRegionId)
    .put("needsReview", needsReview)

private fun ReceiptImageEdit.toJson(): JSONObject = JSONObject()
    .put("rotationDegrees", rotationDegrees.toDouble())
    .put("cropLeft", cropLeft.toDouble())
    .put("cropTop", cropTop.toDouble())
    .put("cropRight", cropRight.toDouble())
    .put("cropBottom", cropBottom.toDouble())

internal fun InventoryItem.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .putNullable("category", category)
    .putNullable("categorySource", categorySource)
    .putNullable("foodConceptId", foodConceptId)
    .put("quantity", quantity)
    .putNullable("unit", unit)
    .putNullable("priceCents", priceCents)
    .putNullable("storageLocation", storageLocation)
    .putNullable("locationSource", locationSource)
    .putNullable("locationChangedAtEpochMillis", locationChangedAtEpochMillis)
    .putNullable("packageSize", packageSize)
    .putNullable("purchasedAtEpochMillis", purchasedAtEpochMillis)
    .putNullable("expiresAtEpochMillis", expiresAtEpochMillis)
    .putNullable("expiryRangeEndEpochMillis", expiryRangeEndEpochMillis)
    .putNullable("expiryEstimateSource", expiryEstimateSource)
    .putNullable("openedAtEpochMillis", openedAtEpochMillis)
    .putNullable("printedDateEpochMillis", printedDateEpochMillis)
    .putNullable("printedDateType", printedDateType)
    .putNullable("receiptId", receiptId)
    .putNullable("receiptItemId", receiptItemId)
    .putNullable("barcode", barcode)
    .put("addedAtEpochMillis", addedAtEpochMillis)

private fun JSONObject.toDigitalReceipt(resolvePath: (String) -> String): DigitalReceipt = DigitalReceipt(
    id = optString("id", ""),
    merchant = nullableString("merchant"),
    dateEpochMillis = nullableLong("dateEpochMillis"),
    totalCents = nullableInt("totalCents"),
    currency = optString("currency", "EUR"),
    previewPath = nullableString("previewPath")?.let(resolvePath),
    tilePaths = optJSONArray("tilePaths").toStringList().map(resolvePath),
    sourcePreviewPath = nullableString("sourcePreviewPath")?.let(resolvePath),
    sourceTilePaths = optJSONArray("sourceTilePaths").toStringList().map(resolvePath),
    sourceRegions =
        optJSONArray("sourceRegions").toObjectList(JSONObject::toReceiptTextRegion),
    imageEdit = optJSONObject("imageEdit")?.toReceiptImageEdit() ?: ReceiptImageEdit(),
    originalFramePaths = optJSONArray("originalFramePaths").toStringList().map(resolvePath),
    rawText = optString("rawText", ""),
    regions = optJSONArray("regions").toObjectList(JSONObject::toReceiptTextRegion),
    items = optJSONArray("items").toObjectList(JSONObject::toReceiptItem),
    status = enumValueOrDefault(optString("status"), ReceiptStatus.PROCESSING),
    createdAtEpochMillis = optLong("createdAtEpochMillis", 0L),
    source = enumValueOrDefault(optString("source", "CAPTURED"), ReceiptSource.CAPTURED),
    bioCompanyReceiptNumber = nullableInt("bioCompanyReceiptNumber"),
    bioCompanyCheckout = nullableInt("bioCompanyCheckout"),
    reweReceiptId = nullableString("reweReceiptId"),
    imports = readImports(
        records = optJSONArray("imports"),
        legacyIds = optJSONArray("importedItemIds").toStringList(),
    ),
)

/**
 * Records win; any id carried only by the schema-6 mirror becomes an UNKNOWN record.
 *
 * A union rather than a fallback, in both directions: an index an older build rewrote keeps its
 * block (with the provenance lost), and a single unparseable record cannot quietly unblock a line
 * that the mirror still names. Duplicates on disk collapse to the last one rather than throwing.
 */
private fun readImports(
    records: JSONArray?,
    legacyIds: List<String>,
): List<ReceiptItemImport> {
    val byId = LinkedHashMap<String, ReceiptItemImport>()
    records.toObjectList(JSONObject::toReceiptItemImportOrNull)
        .filterNotNull()
        .forEach { byId[it.receiptItemId] = it }
    legacyIds.forEach { id -> byId.getOrPut(id) { ReceiptItemImport(receiptItemId = id) } }
    return byId.values.toList()
}

/**
 * Tolerant like [toConsumptionEventOrNull], and for a sharper reason: [loadArray] drops the whole
 * receipt when a parse throws, so one bad record here would cost the user a receipt.
 */
private fun JSONObject.toReceiptItemImportOrNull(): ReceiptItemImport? {
    val receiptItemId = nullableString("receiptItemId")?.takeIf(String::isNotBlank) ?: return null
    return ReceiptItemImport(
        receiptItemId = receiptItemId,
        householdId = nullableString("householdId"),
        importedAtEpochMillis = nullableLong("importedAtEpochMillis"),
        disposition = enumValueOrDefault(optString("disposition"), ImportDisposition.UNKNOWN),
        dispositionChangedAtEpochMillis = nullableLong("dispositionChangedAtEpochMillis"),
    )
}

private fun JSONObject.toReceiptImageEdit(): ReceiptImageEdit {
    val left = optDouble("cropLeft", 0.0).toFloat().coerceIn(0f, 0.95f)
    val top = optDouble("cropTop", 0.0).toFloat().coerceIn(0f, 0.95f)
    val right = optDouble("cropRight", 1.0).toFloat().coerceIn(left + 0.05f, 1f)
    val bottom = optDouble("cropBottom", 1.0).toFloat().coerceIn(top + 0.05f, 1f)
    val migratedRotation = if (has("rotationDegrees")) {
        optDouble("rotationDegrees", 0.0)
    } else {
        val quarterTurns = optInt("quarterTurnsClockwise", 0).mod(4)
        val fine = optDouble("fineRotationDegrees", 0.0)
        val legacyDegrees = quarterTurns * 90.0 + fine
        when {
            legacyDegrees > 180.0 -> legacyDegrees - 360.0
            else -> legacyDegrees
        }
    }
    return ReceiptImageEdit(
        rotationDegrees = normalizeRotationDegrees(migratedRotation),
        cropLeft = left,
        cropTop = top,
        cropRight = right,
        cropBottom = bottom,
    )
}

/** Wraps any angle into (-180, 180], the canonical range for quarter-turn plus fine rotation. */
private fun normalizeRotationDegrees(value: Double): Float {
    val wrapped = ((value % 360.0) + 360.0) % 360.0
    return (if (wrapped > 180.0) wrapped - 360.0 else wrapped).toFloat()
}

private fun JSONObject.toReceiptTextRegion(): ReceiptTextRegion = ReceiptTextRegion(
    id = getString("id"),
    text = getString("text"),
    globalLeft = getDouble("globalLeft").toFloat(),
    globalTop = getDouble("globalTop").toFloat(),
    globalRight = getDouble("globalRight").toFloat(),
    globalBottom = getDouble("globalBottom").toFloat(),
    confidence = nullableDouble("confidence")?.toFloat(),
    sourceTileIndex = optInt("sourceTileIndex", 0),
)

private fun JSONObject.toReceiptItem(): ReceiptItem = ReceiptItem(
    id = getString("id"),
    name = getString("name"),
    quantity = optDouble("quantity", 1.0),
    unit = nullableString("unit"),
    priceCents = nullableInt("priceCents"),
    category = nullableString("category"),
    foodConceptId = nullableString("foodConceptId"),
    textRegionId = nullableString("textRegionId"),
    needsReview = optBoolean("needsReview", false),
)

/**
 * Fractions the four retired coarse fill buckets stood for. They match what the old ring drew, so
 * an upgrade doesn't visibly move any item's indicator.
 *
 * `EMPTY` keeps its 0.05 rather than dropping to zero because [InventoryItem] requires a positive
 * quantity; "nearly empty" is the honest reading of a bucket that never meant a measured zero.
 */
private val LEGACY_FILL_LEVEL_FRACTIONS = mapOf(
    "FULL" to 1.0,
    "HALF" to 0.5,
    "LOW" to 0.25,
    "EMPTY" to 0.05,
)

/**
 * Reads the continuous amount, migrating a pre-[InventoryItem.packageSize] row on the way.
 *
 * This lives on the read path rather than in the launch normalize pass because
 * `moveInventoryItemToHousehold` parses the target household's index directly and never normalizes
 * — a migration there would skip every item that arrived by a move. It is idempotent: once
 * `packageSize` is present, the legacy key is ignored, so re-reading a migrated file is a no-op.
 *
 * A row with no `fillLevel` at all stays untracked. Back-filling those to "full" would be the more
 * obvious move and the wrong one: the old UI drew nothing for a null fill level, so it would put a
 * fill indicator on every row in the list overnight.
 */
private fun JSONObject.readAmount(quantity: Double, unit: String?): Pair<Double, Double?> {
    nullableDouble("packageSize")?.let { return quantity to it }
    val fraction = nullableString("fillLevel")
        ?.let(LEGACY_FILL_LEVEL_FRACTIONS::get)
        ?: return quantity to null
    // Counts don't scale — halving "3 pc" into 1.5 yogurts would invent a fractional object.
    if (unit?.trim()?.lowercase() in COUNTABLE_UNITS) return quantity to quantity
    return quantity * fraction to quantity
}

internal fun JSONObject.toInventoryItem(): InventoryItem {
    val storedQuantity = optDouble("quantity", 1.0)
    val storedUnit = nullableString("unit")
    val (quantity, packageSize) = readAmount(storedQuantity, storedUnit)
    return toInventoryItem(quantity, packageSize, storedUnit)
}

private fun JSONObject.toInventoryItem(
    quantity: Double,
    packageSize: Double?,
    unit: String?,
): InventoryItem = InventoryItem(
    id = getString("id"),
    name = getString("name"),
    category = nullableString("category"),
    categorySource = nullableString("categorySource"),
    foodConceptId = nullableString("foodConceptId"),
    quantity = quantity,
    unit = unit,
    priceCents = nullableInt("priceCents"),
    storageLocation = nullableString("storageLocation"),
    locationSource = nullableString("locationSource"),
    locationChangedAtEpochMillis = nullableLong("locationChangedAtEpochMillis"),
    packageSize = packageSize,
    purchasedAtEpochMillis = nullableLong("purchasedAtEpochMillis"),
    expiresAtEpochMillis = nullableLong("expiresAtEpochMillis"),
    expiryRangeEndEpochMillis = nullableLong("expiryRangeEndEpochMillis"),
    expiryEstimateSource = nullableString("expiryEstimateSource"),
    openedAtEpochMillis = nullableLong("openedAtEpochMillis"),
    printedDateEpochMillis = nullableLong("printedDateEpochMillis"),
    printedDateType = nullableString("printedDateType"),
    receiptId = nullableString("receiptId"),
    receiptItemId = nullableString("receiptItemId"),
    barcode = nullableString("barcode"),
    addedAtEpochMillis = optLong("addedAtEpochMillis", 0L),
)

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else getInt(key)

private fun JSONObject.nullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key)

private fun JSONObject.nullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else getDouble(key)

private fun List<String>.toStringJsonArray(): JSONArray =
    JSONArray().also { array -> forEach(array::put) }

private fun <T> List<T>.toJsonArray(transform: (T) -> JSONObject): JSONArray =
    JSONArray().also { array -> forEach { array.put(transform(it)) } }

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { index ->
            optString(index, null)?.let(::add)
        }
    }
}

private fun <T> JSONArray?.toObjectList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { index ->
            optJSONObject(index)?.let(transform)?.let(::add)
        }
    }
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    value: String,
    default: T,
): T = enumValues<T>().firstOrNull { it.name == value } ?: default
