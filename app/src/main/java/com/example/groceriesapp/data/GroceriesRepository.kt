package com.example.groceriesapp.data

import android.content.Context
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
    private val receiptIndexFile = File(receiptsRoot, INDEX_FILE)
    private val inventoryIndexFile = File(inventoryRoot, INDEX_FILE)
    private val activeScanFile = File(receiptsRoot, ACTIVE_SCAN_FILE)

    private val _receipts = MutableStateFlow(loadReceipts())
    val receipts: StateFlow<List<DigitalReceipt>> = _receipts.asStateFlow()

    private val _inventory = MutableStateFlow(loadInventory())
    val inventory: StateFlow<List<InventoryItem>> = _inventory.asStateFlow()

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
            val storedItem = item.copy()
            val updated = _inventory.value
                .filterNot { it.id == storedItem.id }
                .plus(storedItem)
                .sortedByDescending(InventoryItem::addedAtEpochMillis)
            persistInventory(updated)
            _inventory.value = updated
            return storedItem
        }
    }

    fun removeInventoryItem(id: String): Boolean {
        synchronized(lock) {
            val current = _inventory.value
            if (current.none { it.id == id }) return false

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

    private fun parseIsoDateToEpochMillis(dateIso: String): Long? {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.parse(dateIso)?.time
        }.getOrDefault(runCatching {
            val sdf2 = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf2.parse(dateIso)?.time
        }.getOrNull())
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
        itemIds: Collection<String>,
    ): List<InventoryItem> {
        if (itemIds.isEmpty()) return emptyList()
        synchronized(lock) {
            val receipt = _receipts.value.firstOrNull { it.id == receiptId } ?: return emptyList()
            val requestedIds = itemIds.toSet()
            val existingSourceIds = _inventory.value
                .asSequence()
                .filter { it.receiptId == receiptId }
                .mapNotNull(InventoryItem::receiptItemId)
                .toSet()
            val addedAt = System.currentTimeMillis()
            val addedItems = receipt.items
                .asSequence()
                .filter { it.id in requestedIds && it.id !in existingSourceIds }
                .map { receiptItem ->
                    InventoryItem(
                        name = receiptItem.name,
                        category = receiptItem.category,
                        quantity = receiptItem.quantity,
                        unit = receiptItem.unit,
                        priceCents = receiptItem.priceCents,
                        purchasedAtEpochMillis = receipt.dateEpochMillis,
                        receiptId = receipt.id,
                        receiptItemId = receiptItem.id,
                        addedAtEpochMillis = addedAt,
                    )
                }
                .toList()

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
        private const val ORIGINAL_DIRECTORY = "original"
        private const val TILES_DIRECTORY = "tiles"
        private const val INDEX_FILE = "index.json"
        private const val ACTIVE_SCAN_FILE = "active_scan.json"
        private const val SCAN_DRAFT_FILE = "scan_draft.json"
        private const val SCHEMA_VERSION_KEY = "schemaVersion"
        private const val RECEIPTS_KEY = "receipts"
        private const val INVENTORY_KEY = "inventory"
        private const val SCHEMA_VERSION = 2
        private val RECEIPT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val FRAME_FILE_PATTERN = Regex("frame_\\d{4,}\\.jpg")
    }
}

private val DigitalReceipt.sortEpochMillis: Long
    get() = dateEpochMillis ?: createdAtEpochMillis

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
    .putNullable("textRegionId", textRegionId)
    .put("needsReview", needsReview)

private fun ReceiptImageEdit.toJson(): JSONObject = JSONObject()
    .put("rotationDegrees", rotationDegrees.toDouble())
    .put("cropLeft", cropLeft.toDouble())
    .put("cropTop", cropTop.toDouble())
    .put("cropRight", cropRight.toDouble())
    .put("cropBottom", cropBottom.toDouble())

private fun InventoryItem.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("name", name)
    .putNullable("category", category)
    .put("quantity", quantity)
    .putNullable("unit", unit)
    .putNullable("priceCents", priceCents)
    .putNullable("purchasedAtEpochMillis", purchasedAtEpochMillis)
    .putNullable("expiresAtEpochMillis", expiresAtEpochMillis)
    .putNullable("receiptId", receiptId)
    .putNullable("receiptItemId", receiptItemId)
    .put("addedAtEpochMillis", addedAtEpochMillis)

private fun JSONObject.toDigitalReceipt(): DigitalReceipt = DigitalReceipt(
    id = getString("id"),
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
    textRegionId = nullableString("textRegionId"),
    needsReview = optBoolean("needsReview", false),
)

private fun JSONObject.toInventoryItem(): InventoryItem = InventoryItem(
    id = getString("id"),
    name = getString("name"),
    category = nullableString("category"),
    quantity = optDouble("quantity", 1.0),
    unit = nullableString("unit"),
    priceCents = nullableInt("priceCents"),
    purchasedAtEpochMillis = nullableLong("purchasedAtEpochMillis"),
    expiresAtEpochMillis = nullableLong("expiresAtEpochMillis"),
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
