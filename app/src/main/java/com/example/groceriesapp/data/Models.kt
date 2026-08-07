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
        require(rotationDegrees.isFinite() && rotationDegrees in -90f..90f) {
            "Rotation must be between -90 and 90 degrees."
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
) {
    init {
        require(id.isNotBlank()) { "Receipt ID must not be blank." }
        require(currency.isNotBlank()) { "Currency must not be blank." }
    }
}

data class InventoryItem(
    val name: String,
    val category: String? = null,
    val foodConceptId: String? = null,
    val quantity: Double = 1.0,
    val unit: String? = null,
    val priceCents: Int? = null,
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
    val addedAtEpochMillis: Long = System.currentTimeMillis(),
    val id: String = UUID.randomUUID().toString(),
) {
    init {
        require(name.isNotBlank()) { "Inventory item name must not be blank." }
        require(quantity.isFinite() && quantity > 0.0) { "Quantity must be finite and positive." }
        require(id.isNotBlank()) { "Inventory item ID must not be blank." }
    }
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
