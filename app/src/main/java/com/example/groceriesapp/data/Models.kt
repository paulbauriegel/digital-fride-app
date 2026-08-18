package com.example.groceriesapp.data

import java.util.UUID

enum class ReceiptSource {
    CAPTURED,
    BIO_COMPANY,
    REWE,
}

enum class ReceiptStatus {
    PROCESSING,
    READY,
    FAILED,
}

/**
 * An OCR region positioned in the coordinate system of the complete digital receipt.
 */
data class ReceiptTextRegion(
    val text: String,
    val globalLeft: Float,
    val globalTop: Float,
    val globalRight: Float,
    val globalBottom: Float,
    val confidence: Float? = null,
    val sourceTileIndex: Int = 0,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(text.isNotBlank()) { "Receipt text must not be blank." }
        require(
            globalLeft.isFinite() &&
                globalTop.isFinite() &&
                globalRight.isFinite() &&
                globalBottom.isFinite(),
        ) {
            "Receipt text coordinates must be finite."
        }
        require(globalRight >= globalLeft) { "globalRight must not be less than globalLeft." }
        require(globalBottom >= globalTop) { "globalBottom must not be less than globalTop." }
        require(confidence == null || confidence.isFinite()) { "Confidence must be finite." }
        require(sourceTileIndex >= 0) { "sourceTileIndex must not be negative." }
        require(id.isNotBlank()) { "Receipt text region ID must not be blank." }
    }
}

data class ReceiptItem(
    val name: String,
    val quantity: Double = 1.0,
    val unit: String? = null,
    val priceCents: Int? = null,
    val category: String? = null,
    val foodConceptId: String? = null,
    val textRegionId: String? = null,
    val needsReview: Boolean = false,
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(name.isNotBlank()) { "Receipt item name must not be blank." }
        require(quantity.isFinite() && quantity > 0.0) { "Quantity must be finite and positive." }
        require(id.isNotBlank()) { "Receipt item ID must not be blank." }
    }
}

data class ReceiptImageEdit(
    val rotationDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
) {
    init {
        // Quarter turns plus fine straightening span the full circle, canonically (-180, 180].
        require(rotationDegrees.isFinite() && rotationDegrees in -180f..180f) {
            "Rotation must be between -180 and 180 degrees."
        }
        require(
            listOf(cropLeft, cropTop, cropRight, cropBottom).all {
                it.isFinite() && it in 0f..1f
            },
        ) {
            "Crop fractions must be finite and between 0 and 1."
        }
        require(cropRight - cropLeft >= 0.05f) { "Crop width must be at least 5%." }
        require(cropBottom - cropTop >= 0.05f) { "Crop height must be at least 5%." }
    }
}

/** What became of the goods a receipt line was imported as, as far as the app can tell. */
enum class ImportDisposition {
    IN_INVENTORY,
    USED_UP,
    THROWN_AWAY,
    COOKED,

    /** Imported at some point; the app cannot say by whom, or where it ended up. */
    UNKNOWN,
    ;

    /**
     * Whether the goods are physically gone. Releasing a record makes its line importable again, so
     * only records whose goods might still be sitting somewhere are ever eligible.
     */
    val goodsAreGone: Boolean
        get() = this == USED_UP || this == THROWN_AWAY || this == COOKED
}

/**
 * One receipt line's import record. Its mere presence blocks re-import everywhere — in every
 * household, and forever once the goods are gone. [householdId] and [disposition] are provenance
 * for the user to read, not policy: nothing consults them to decide whether a line may be imported.
 *
 * Per receipt *line*, not per unit. "3 × Joghurt" is three lines but one inventory row, so all
 * three take that row's disposition — eat two and bin one and all three read THROWN_AWAY. Coarse
 * on purpose; splitting records per unit would buy a better label and nothing else.
 */
data class ReceiptItemImport(
    val receiptItemId: String,
    /** Null when unattributable (legacy backfill) or when the owning household was deleted. */
    val householdId: String? = null,
    val importedAtEpochMillis: Long? = null,
    val disposition: ImportDisposition = ImportDisposition.UNKNOWN,
    val dispositionChangedAtEpochMillis: Long? = null,
)

data class DigitalReceipt(
    val id: String = UUID.randomUUID().toString(),
    val merchant: String? = null,
    val dateEpochMillis: Long? = null,
    val totalCents: Int? = null,
    val currency: String = "EUR",
    val previewPath: String? = null,
    val tilePaths: List<String> = emptyList(),
    val sourcePreviewPath: String? = null,
    val sourceTilePaths: List<String> = emptyList(),
    val sourceRegions: List<ReceiptTextRegion> = emptyList(),
    val imageEdit: ReceiptImageEdit = ReceiptImageEdit(),
    val originalFramePaths: List<String> = emptyList(),
    val rawText: String = "",
    val regions: List<ReceiptTextRegion> = emptyList(),
    val items: List<ReceiptItem> = emptyList(),
    val status: ReceiptStatus = ReceiptStatus.PROCESSING,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val source: ReceiptSource = ReceiptSource.CAPTURED,
    val bioCompanyReceiptNumber: Int? = null,
    val bioCompanyCheckout: Int? = null,
    val reweReceiptId: String? = null,
    /**
     * Import provenance, one record per [ReceiptItem] that has been added to some household's
     * inventory. Persisted on the receipt — which is shared device-wide, unlike inventory — so the
     * imported/not-imported split holds across every household and survives the inventory row
     * later being consumed or removed.
     *
     * Deliberately unvalidated in [init]: a duplicate or malformed entry here must never throw,
     * because the loader drops a whole receipt whose parse fails.
     */
    val imports: List<ReceiptItemImport> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Receipt ID must not be blank." }
        require(currency.isNotBlank()) { "Currency must not be blank." }
    }

    /**
     * The ids alone — what the import filters and the imported/not-imported UI split ask for.
     * Computed rather than stored, so it can never disagree with [imports].
     */
    val importedItemIds: Set<String>
        get() = imports.mapTo(LinkedHashSet(imports.size), ReceiptItemImport::receiptItemId)

    fun importOf(receiptItemId: String): ReceiptItemImport? =
        imports.firstOrNull { it.receiptItemId == receiptItemId }
}

/** How an [InventoryItem.storageLocation] came to be set. */
enum class LocationSource {
    /** Assigned by [com.example.groceriesapp.data.taxonomy.StorageLocationSuggester]; may be re-suggested. */
    SUGGESTED,

    /** Chosen by the user; never overwritten automatically. */
    USER,
}

/**
 * How an [InventoryItem.category] came to be set. The same shape as [LocationSource], and for the
 * same reason: the launch normalize pass re-derives the category from the name-matched food
 * concept every time, so without a marker a hand-picked category reverts within one app start.
 */
enum class CategorySource {
    /** Derived from the item's food concept; tracks it, so catalogue fixes propagate. */
    DERIVED,

    /** Chosen by the user for this row; never re-derived. */
    USER,
}

/** One receipt item the user chose to import, with the storage location it should land in. */
data class ReceiptItemSelection(
    val receiptItemId: String,
    val storageLocation: com.example.groceriesapp.data.taxonomy.StorageLocation,
    val locationSource: LocationSource,
)

data class InventoryItem(
    val name: String,
    val category: String? = null,
    /** [CategorySource] name; DERIVED categories track the concept, USER ones are never re-derived. */
    val categorySource: String? = null,
    val foodConceptId: String? = null,
    val quantity: Double = 1.0,
    val unit: String? = null,
    val priceCents: Int? = null,
    /** [com.example.groceriesapp.data.taxonomy.StorageLocation] name, or null before migration. */
    val storageLocation: String? = null,
    /** [LocationSource] name; SUGGESTED locations may be re-suggested on launch, USER never is. */
    val locationSource: String? = null,
    /** When the item last moved between locations; re-anchors the expiry estimate. */
    val locationChangedAtEpochMillis: Long? = null,
    /**
     * What a full one holds, in the same [unit] as [quantity] — the "of 1 L" the amount is read
     * against, the top of the edit screen's scale, and the base for its percentage presets.
     * [quantity] is what is *left* of it, so `quantity / packageSize` is how full the item is.
     *
     * Null means the amount isn't tracked: a bare receipt line whose unit nothing recognised, or a
     * row the user has never touched. Untracked is a real state, not a missing value — filling it
     * in as "100% full" would put a fill indicator on every row in the list.
     */
    val packageSize: Double? = null,
    val purchasedAtEpochMillis: Long? = null,
    /** Earliest edge of the estimated window, or the exact date when read off packaging. */
    val expiresAtEpochMillis: Long? = null,
    val expiryRangeEndEpochMillis: Long? = null,
    /** [com.example.groceriesapp.data.taxonomy.ExpirySource] name, or null when unknown. */
    val expiryEstimateSource: String? = null,
    val openedAtEpochMillis: Long? = null,
    /** Reserved for packaging-date OCR, which no capture flow populates yet. */
    val printedDateEpochMillis: Long? = null,
    val printedDateType: String? = null,
    val receiptId: String? = null,
    val receiptItemId: String? = null,
    /**
     * EAN/UPC read by the barcode scanner; null for manual and receipt-imported rows.
     *
     * Deliberately unvalidated: loadArray drops rows whose parse throws, so rejecting an odd code
     * here would delete a real inventory row over a field nothing depends on.
     */
    val barcode: String? = null,
    val addedAtEpochMillis: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(name.isNotBlank()) { "Inventory item name must not be blank." }
        require(quantity.isFinite() && quantity > 0.0) { "Quantity must be finite and positive." }
        require(id.isNotBlank()) { "Inventory item ID must not be blank." }
        // Deliberately permissive about packageSize vs quantity: a refilled canister legitimately
        // holds more than the pack it came from, and loadArray drops rows whose parse throws, so
        // an over-strict rule here would delete real inventory rather than reject one field.
        require(packageSize == null || (packageSize.isFinite() && packageSize > 0.0)) {
            "Package size must be finite and positive when set."
        }
    }
}

/**
 * True when the quantity reads as a count of pieces, so stepping it by ±1 makes sense
 * (three yogurts → two). Weight/volume amounts ("500 g", "1.5 l") are not steppable, and an
 * unknown unit is not assumed to mean pieces — a bare receipt line could be a 500 g pack.
 */
val InventoryItem.isCountable: Boolean
    get() = quantity % 1.0 == 0.0 &&
        unit?.trim()?.lowercase() in COUNTABLE_UNITS

internal val COUNTABLE_UNITS = setOf("pc", "pcs", "st", "st.", "stk", "stk.", "stück", "x")

/**
 * How full the item is, 0f..1f, or null when the amount isn't tracked. Clamped rather than
 * validated: a top-up can push [InventoryItem.quantity] past the pack it was bought in, and a
 * gauge reading 110% is worse than one reading full.
 */
val InventoryItem.fillFraction: Float?
    get() {
        val full = packageSize?.takeIf { it > 0.0 } ?: return null
        return (quantity / full).toFloat().coerceIn(0f, 1f)
    }

data class ScanSession(
    val id: String,
    val directoryPath: String,
    val createdAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Scan session ID must not be blank." }
        require(directoryPath.isNotBlank()) { "Scan session directory must not be blank." }
    }
}

data class ScanDraft(
    val session: ScanSession,
    val acceptedFramePaths: List<String>,
)
