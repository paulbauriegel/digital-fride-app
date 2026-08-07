package com.example.groceriesapp.data

import android.content.Context
import com.example.groceriesapp.data.taxonomy.ExpiryEstimator
import com.example.groceriesapp.data.taxonomy.FoodConceptCatalog
import com.example.groceriesapp.data.taxonomy.GroceryCategory
import com.example.groceriesapp.data.taxonomy.ShelfLifeCatalog
import com.example.groceriesapp.data.taxonomy.StorageLocation
import com.example.groceriesapp.data.taxonomy.StorageLocationSuggester
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class GroceriesRepository(context: Context) {
    private val lock = Any()
    private val storageRoot = File(context.applicationContext.filesDir, STORAGE_DIRECTORY)
        .also(::ensureDirectory)
    private val receiptsRoot = File(storageRoot, RECEIPTS_DIRECTORY)
        .also(::ensureDirectory)
    private val inventoryRoot = File(storageRoot, INVENTORY_DIRECTORY)
        .also(::ensureDirectory)
    private val consumptionRoot = File(storageRoot, CONSUMPTION_DIRECTORY)
        .also(::ensureDirectory)
    private val receiptIndexFile = File(receiptsRoot, INDEX_FILE)
    private val inventoryIndexFile = File(inventoryRoot, INDEX_FILE)
    private val consumptionLogFile = File(consumptionRoot, CONSUMPTION_LOG_FILE)
    private val activeScanFile = File(receiptsRoot, ACTIVE_SCAN_FILE)

    private val shelfLifeCatalog = ShelfLifeCatalog.fromAssets(context)
    private val expiryEstimator = ExpiryEstimator(shelfLifeCatalog)

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
        }
    }

    /**
     * Re-matching by name first (rather than trusting a previously-stored [FoodConcept] id) lets
     * catalog corrections retroactively fix already-persisted items — e.g. a keyword fix that
     * moves "Traubensaft" from GRAPES to JUICE reclassifies existing inventory on next launch,
     * not just newly-scanned items. [FoodConceptCatalog.byId] is only a fallback for names that no
     * longer match anything (so a still-valid past classification isn't dropped to OTHER).
     */
    private fun normalizeReceiptItem(item: ReceiptItem): ReceiptItem {
        val concept = FoodConceptCatalog.match(item.name) ?: FoodConceptCatalog.byId(item.foodConceptId)
        val category = concept?.category
            ?: GroceryCategory.fromStorageValue(item.category, item.name)
        return item.copy(category = category.name, foodConceptId = concept?.id)
    }

    private fun normalizeInventoryItem(item: InventoryItem): InventoryItem {
        val concept = FoodConceptCatalog.match(item.name) ?: FoodConceptCatalog.byId(item.foodConceptId)
        val category = concept?.category
            ?: GroceryCategory.fromStorageValue(item.category, item.name)
        val normalized = item.copy(category = category.name, foodConceptId = concept?.id)
            .withSuggestedLocationUnlessUserSet(category, concept?.id)
        // Always recompute rather than only filling in a missing/stale-classification estimate:
        // nothing here is ever user-authored (there's no UI to hand-set an expiry date yet), so a
        // fresh estimate can never clobber something the user typed. This also self-heals items
        // whose stored estimate predates a shelf-life data fix, not just a classification fix —
        // e.g. a concept gaining a proper rule where it used to fall back to its category. Once
        // printed-date OCR lands, this stays safe: ExpiryEstimator checks printedDateEpochMillis
        // first regardless, so a real printed date still wins over the algorithmic guess. The
        // storage location is user-authored state, but the estimator receives it as an input, so
        // the recompute respects it rather than overwriting it.
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
                        fillLevel = group.firstNotNullOfOrNull(InventoryItem::fillLevel),
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

    private fun InventoryItem.withEstimatedExpiry(
        purchasedAtEpochMillis: Long?,
    ): InventoryItem {
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
     * is typically much shorter than unopened.
     */
    fun markInventoryItemOpened(id: String): InventoryItem? {
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            if (existing.openedAtEpochMillis != null) return existing

            val opened = existing.copy(openedAtEpochMillis = System.currentTimeMillis())
            val reestimated = opened
                .copy(expiresAtEpochMillis = null, expiryRangeEndEpochMillis = null)
                .withEstimatedExpiry(opened.purchasedAtEpochMillis ?: opened.addedAtEpochMillis)
                // Keep the original date if nothing better could be derived.
                .let { if (it.expiresAtEpochMillis == null) opened else it }

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
        val concept = FoodConceptCatalog.byId(foodConceptId)
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
     * destination's storage life differs (freezing extends it, thawing shortens it).
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
            val reestimated = moved
                .copy(expiresAtEpochMillis = null, expiryRangeEndEpochMillis = null)
                .withEstimatedExpiry(moved.purchasedAtEpochMillis ?: moved.addedAtEpochMillis)
                // Keep the original date if nothing better could be derived.
                .let { if (it.expiresAtEpochMillis == null) moved else it }

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

    /** Fill level is a display-only hint about the remaining amount; expiry is unaffected. */
    fun setInventoryItemFillLevel(id: String, fillLevel: FillLevel?): InventoryItem? {
        synchronized(lock) {
            val current = _inventory.value
            val existing = current.firstOrNull { it.id == id } ?: return null
            if (existing.fillLevel == fillLevel?.name) return existing

            val changed = existing.copy(fillLevel = fillLevel?.name)
            val updated = current.map { if (it.id == id) changed else it }
            persistInventory(updated)
            _inventory.value = updated
            return changed
        }
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

    fun addInventoryItem(item: InventoryItem): InventoryItem {
        synchronized(lock) {
            // The same product bought again the same day stacks onto the existing entry instead
            // of cluttering the list — three yogurts become one row with quantity 3.
            val anchorEpochMillis0 = item.purchasedAtEpochMillis ?: item.addedAtEpochMillis
            val stackTarget = _inventory.value.firstOrNull { existing ->
                mergeKeyOf(existing.name, existing.unit) == mergeKeyOf(item.name, item.unit) &&
                    isSameCalendarDay(
                        existing.purchasedAtEpochMillis ?: existing.addedAtEpochMillis,
                        anchorEpochMillis0,
                    )
            }
            if (stackTarget != null) {
                val stacked = stackTarget.copy(quantity = stackTarget.quantity + item.quantity)
                val updated = _inventory.value.map { if (it.id == stacked.id) stacked else it }
                persistInventory(updated)
                _inventory.value = updated
                return stacked
            }

            val concept = FoodConceptCatalog.byId(item.foodConceptId)
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

            if (reason != null) {
                val event = ConsumptionEvent(
                    itemId = existing.id,
                    name = existing.name,
                    category = existing.category,
                    foodConceptId = existing.foodConceptId,
                    quantity = existing.quantity,
                    unit = existing.unit,
                    priceCents = existing.priceCents,
                    fillLevel = existing.fillLevel,
                    reason = reason,
                    addedAtEpochMillis = existing.addedAtEpochMillis,
                    expiresAtEpochMillis = existing.expiresAtEpochMillis,
                    removedAtEpochMillis = System.currentTimeMillis(),
                )
                val events = _consumptionEvents.value + event
                persistConsumptionEvents(events)
                _consumptionEvents.value = events
            }

            val updated = current.filterNot { it.id == id }
            persistInventory(updated)
            _inventory.value = updated
            return true
        }
    }

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
            val addedItems = receipt.items
                .filter {
                    it.id in selectionsById && it.id !in existingSourceIds &&
                        mergeKeyOf(it.name, it.unit) !in existingImportKeys
                }
                // The same product rung up as several receipt lines becomes one inventory entry
                // with a summed quantity ("3 × Joghurt" → one row, quantity 3).
                .groupBy { mergeKeyOf(it.name, it.unit) }
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
            return addedItems
        }
    }

    private fun loadReceipts(): List<DigitalReceipt> =
        loadArray(receiptIndexFile, RECEIPTS_KEY) { it.toDigitalReceipt() }
            .sortedByDescending(DigitalReceipt::sortEpochMillis)

    private fun loadInventory(): List<InventoryItem> =
        loadArray(inventoryIndexFile, INVENTORY_KEY) { it.toInventoryItem() }
            .sortedByDescending(InventoryItem::addedAtEpochMillis)

    private fun loadConsumptionEvents(): List<ConsumptionEvent> {
        if (!consumptionLogFile.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(consumptionLogFile.readText(StandardCharsets.UTF_8))
                .optJSONArray(CONSUMPTION_EVENTS_KEY)
                ?: return@runCatching emptyList()
            buildList {
                repeat(array.length()) { index ->
                    array.optJSONObject(index)?.toConsumptionEventOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun <T> loadArray(
        file: File,
        key: String,
        transform: (JSONObject) -> T,
    ): List<T> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText(StandardCharsets.UTF_8)).optJSONArray(key)
                ?: return@runCatching emptyList()
            buildList {
                repeat(array.length()) { index ->
                    val value = array.optJSONObject(index) ?: return@repeat
                    runCatching { transform(value) }.getOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persistReceipts(receipts: List<DigitalReceipt>) {
        val root = JSONObject()
            .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            .put(RECEIPTS_KEY, receipts.toJsonArray(DigitalReceipt::toJson))
        atomicWrite(receiptIndexFile, root.toString())
    }

    private fun persistInventory(inventory: List<InventoryItem>) {
        val root = JSONObject()
            .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            .put(INVENTORY_KEY, inventory.toJsonArray(InventoryItem::toJson))
        atomicWrite(inventoryIndexFile, root.toString())
    }

    // Whole-file rewrite like every other persist path. At roughly ten removals a day this stays
    // around a megabyte per year; revisit with an append-only format if the log ever grows hot.
    private fun persistConsumptionEvents(events: List<ConsumptionEvent>) {
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
        private const val STORAGE_DIRECTORY = "groceries"
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
        private const val SCHEMA_VERSION = 4
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

private fun ensureDirectory(directory: File) {
    val path = directory.toPath()
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Expected a directory at ${directory.absolutePath}."
        }
        return
    }
    Files.createDirectories(path)
}

private fun atomicWrite(target: File, contents: String) {
    ensureDirectory(checkNotNull(target.parentFile))
    val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write(contents)
            writer.flush()
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}

@Throws(IOException::class)
private fun deleteDirectoryWithoutFollowingLinks(directory: File) {
    val path = directory.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(
                file: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                error: IOException?,
            ): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun DigitalReceipt.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .putNullable("merchant", merchant)
    .putNullable("dateEpochMillis", dateEpochMillis)
    .putNullable("totalCents", totalCents)
    .put("currency", currency)
    .putNullable("previewPath", previewPath)
    .put("tilePaths", tilePaths.toStringJsonArray())
    .putNullable("sourcePreviewPath", sourcePreviewPath)
    .put("sourceTilePaths", sourceTilePaths.toStringJsonArray())
    .put("sourceRegions", sourceRegions.toJsonArray(ReceiptTextRegion::toJson))
    .put("imageEdit", imageEdit.toJson())
    .put("originalFramePaths", originalFramePaths.toStringJsonArray())
    .put("rawText", rawText)
    .put("regions", regions.toJsonArray(ReceiptTextRegion::toJson))
    .put("items", items.toJsonArray(ReceiptItem::toJson))
    .put("status", status.name)
    .put("createdAtEpochMillis", createdAtEpochMillis)
    .put("source", source.name)
    .putNullable("bioCompanyReceiptNumber", bioCompanyReceiptNumber)
    .putNullable("bioCompanyCheckout", bioCompanyCheckout)
    .putNullable("reweReceiptId", reweReceiptId)

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
    .putNullable("foodConceptId", foodConceptId)
    .put("quantity", quantity)
    .putNullable("unit", unit)
    .putNullable("priceCents", priceCents)
    .putNullable("storageLocation", storageLocation)
    .putNullable("locationSource", locationSource)
    .putNullable("locationChangedAtEpochMillis", locationChangedAtEpochMillis)
    .putNullable("fillLevel", fillLevel)
    .putNullable("purchasedAtEpochMillis", purchasedAtEpochMillis)
    .putNullable("expiresAtEpochMillis", expiresAtEpochMillis)
    .putNullable("expiryRangeEndEpochMillis", expiryRangeEndEpochMillis)
    .putNullable("expiryEstimateSource", expiryEstimateSource)
    .putNullable("openedAtEpochMillis", openedAtEpochMillis)
    .putNullable("printedDateEpochMillis", printedDateEpochMillis)
    .putNullable("printedDateType", printedDateType)
    .putNullable("receiptId", receiptId)
    .putNullable("receiptItemId", receiptItemId)
    .put("addedAtEpochMillis", addedAtEpochMillis)

private fun JSONObject.toDigitalReceipt(): DigitalReceipt = DigitalReceipt(
    id = optString("id", ""),
    merchant = nullableString("merchant"),
    dateEpochMillis = nullableLong("dateEpochMillis"),
    totalCents = nullableInt("totalCents"),
    currency = optString("currency", "EUR"),
    previewPath = nullableString("previewPath"),
    tilePaths = optJSONArray("tilePaths").toStringList(),
    sourcePreviewPath = nullableString("sourcePreviewPath"),
    sourceTilePaths = optJSONArray("sourceTilePaths").toStringList(),
    sourceRegions =
        optJSONArray("sourceRegions").toObjectList(JSONObject::toReceiptTextRegion),
    imageEdit = optJSONObject("imageEdit")?.toReceiptImageEdit() ?: ReceiptImageEdit(),
    originalFramePaths = optJSONArray("originalFramePaths").toStringList(),
    rawText = optString("rawText", ""),
    regions = optJSONArray("regions").toObjectList(JSONObject::toReceiptTextRegion),
    items = optJSONArray("items").toObjectList(JSONObject::toReceiptItem),
    status = enumValueOrDefault(optString("status"), ReceiptStatus.PROCESSING),
    createdAtEpochMillis = optLong("createdAtEpochMillis", 0L),
    source = enumValueOrDefault(optString("source", "CAPTURED"), ReceiptSource.CAPTURED),
    bioCompanyReceiptNumber = nullableInt("bioCompanyReceiptNumber"),
    bioCompanyCheckout = nullableInt("bioCompanyCheckout"),
    reweReceiptId = nullableString("reweReceiptId"),
)

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
        rotationDegrees = migratedRotation.toFloat().coerceIn(-90f, 90f),
        cropLeft = left,
        cropTop = top,
        cropRight = right,
        cropBottom = bottom,
    )
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

internal fun JSONObject.toInventoryItem(): InventoryItem = InventoryItem(
    id = getString("id"),
    name = getString("name"),
    category = nullableString("category"),
    foodConceptId = nullableString("foodConceptId"),
    quantity = optDouble("quantity", 1.0),
    unit = nullableString("unit"),
    priceCents = nullableInt("priceCents"),
    storageLocation = nullableString("storageLocation"),
    locationSource = nullableString("locationSource"),
    locationChangedAtEpochMillis = nullableLong("locationChangedAtEpochMillis"),
    fillLevel = nullableString("fillLevel"),
    purchasedAtEpochMillis = nullableLong("purchasedAtEpochMillis"),
    expiresAtEpochMillis = nullableLong("expiresAtEpochMillis"),
    expiryRangeEndEpochMillis = nullableLong("expiryRangeEndEpochMillis"),
    expiryEstimateSource = nullableString("expiryEstimateSource"),
    openedAtEpochMillis = nullableLong("openedAtEpochMillis"),
    printedDateEpochMillis = nullableLong("printedDateEpochMillis"),
    printedDateType = nullableString("printedDateType"),
    receiptId = nullableString("receiptId"),
    receiptItemId = nullableString("receiptItemId"),
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
