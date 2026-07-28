package com.example.groceriesapp.vision.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class OcrRegion(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float?,
    val sourceTileIndex: Int,
)

data class ReceiptOcrResult(
    val rawText: String,
    val regions: List<OcrRegion>,
)

class ReceiptOcrException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ReceiptOcrEngine(
    private val overlapRows: Int = DEFAULT_OCR_OVERLAP_ROWS,
) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        require(overlapRows >= 0) { "OCR overlap must not be negative" }
    }

    suspend fun recognizeTiles(tilePaths: List<String>): ReceiptOcrResult {
        require(tilePaths.isNotEmpty()) { "At least one receipt tile is required for OCR" }
        val layouts = readTileLayouts(tilePaths)
        val windows = OcrWindowPlanner.plan(
            tileHeights = layouts.map(OcrTileLayout::height),
            overlapRows = overlapRows,
        )
        val detectedRegions = mutableListOf<OcrRegion>()

        windows.forEach { window ->
            currentCoroutineContext().ensureActive()
            detectedRegions += recognizeWindow(window, layouts)
        }

        val regions = OcrRegionDeduplicator.deduplicate(detectedRegions)
        return ReceiptOcrResult(
            rawText = regions.joinToString("\n", transform = OcrRegion::text),
            regions = regions,
        )
    }

    private suspend fun readTileLayouts(tilePaths: List<String>): List<OcrTileLayout> =
        withContext(Dispatchers.IO) {
            var globalTop = 0L
            var expectedWidth: Int? = null
            tilePaths.mapIndexed { index, path ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    throw ReceiptOcrException("Could not read receipt tile ${index + 1}")
                }
                if (expectedWidth == null) {
                    expectedWidth = options.outWidth
                } else if (options.outWidth != expectedWidth) {
                    throw ReceiptOcrException(
                        "Receipt tile ${index + 1} has an inconsistent width",
                    )
                }
                if (globalTop + options.outHeight > Int.MAX_VALUE) {
                    throw ReceiptOcrException("The stitched receipt is too tall to OCR safely")
                }
                OcrTileLayout(
                    index = index,
                    path = path,
                    width = options.outWidth,
                    height = options.outHeight,
                    globalTop = globalTop.toInt(),
                ).also {
                    globalTop += options.outHeight
                }
            }
        }

    private suspend fun recognizeWindow(
        window: OcrWindowSpec,
        layouts: List<OcrTileLayout>,
    ): List<OcrRegion> = withContext(Dispatchers.IO) {
        val bitmap = decodeWindow(window, layouts)
        try {
            currentCoroutineContext().ensureActive()
            val task = recognizer.process(InputImage.fromBitmap(bitmap, 0))
            val result = task.awaitTerminalResult()
            currentCoroutineContext().ensureActive()
            mapLines(
                result = result,
                window = window,
                layouts = layouts,
                windowWidth = bitmap.width,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeWindow(
        window: OcrWindowSpec,
        layouts: List<OcrTileLayout>,
    ): Bitmap {
        val primaryLayout = layouts[window.primaryTileIndex]
        val primary = decodeRequired(primaryLayout)
        if (window.appendedRows == 0) return primary

        val nextLayout = layouts[window.primaryTileIndex + 1]
        val next = try {
            decodeRequired(nextLayout)
        } catch (error: Throwable) {
            primary.recycle()
            throw error
        }
        try {
            val combined = createBitmap(
                primary.width,
                window.height,
                Bitmap.Config.ARGB_8888,
            )
            try {
                val canvas = Canvas(combined)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(primary, 0f, 0f, paint)
                canvas.drawBitmap(
                    next,
                    Rect(0, 0, next.width, window.appendedRows),
                    Rect(
                        0,
                        primary.height,
                        primary.width,
                        primary.height + window.appendedRows,
                    ),
                    paint,
                )
                return combined
            } catch (error: Throwable) {
                combined.recycle()
                throw error
            }
        } finally {
            primary.recycle()
            next.recycle()
        }
    }

    private fun decodeRequired(layout: OcrTileLayout): Bitmap {
        val bitmap = BitmapFactory.decodeFile(layout.path)
            ?: throw ReceiptOcrException("Could not decode receipt tile ${layout.index + 1}")
        if (bitmap.width != layout.width || bitmap.height != layout.height) {
            bitmap.recycle()
            throw ReceiptOcrException(
                "Receipt tile ${layout.index + 1} changed while OCR was running",
            )
        }
        return bitmap
    }

    private fun mapLines(
        result: Text,
        window: OcrWindowSpec,
        layouts: List<OcrTileLayout>,
        windowWidth: Int,
    ): List<OcrRegion> {
        val receiptHeight = layouts.last().globalBottom
        return buildList {
            result.textBlocks.forEach { block ->
                block.lines.forEach lineLoop@{ line ->
                    val box = line.boundingBox ?: return@lineLoop
                    val text = line.text.trim()
                    if (text.isEmpty()) return@lineLoop
                    val left = box.left.coerceIn(0, windowWidth).toFloat()
                    val right = box.right.coerceIn(0, windowWidth).toFloat()
                    val top = (box.top + window.globalTop)
                        .coerceIn(0, receiptHeight)
                        .toFloat()
                    val bottom = (box.bottom + window.globalTop)
                        .coerceIn(0, receiptHeight)
                        .toFloat()
                    if (right <= left || bottom <= top) return@lineLoop
                    add(
                        OcrRegion(
                            text = text,
                            left = left,
                            top = top,
                            right = right,
                            bottom = bottom,
                            confidence = line.confidence,
                            sourceTileIndex = layouts.tileIndexAt((top + bottom) / 2f),
                        ),
                    )
                }
            }
        }
    }

    override fun close() {
        recognizer.close()
    }

    private companion object {
        const val DEFAULT_OCR_OVERLAP_ROWS = 200
    }
}

internal data class OcrTileLayout(
    val index: Int,
    val path: String,
    val width: Int,
    val height: Int,
    val globalTop: Int,
) {
    val globalBottom: Int
        get() = Math.addExact(globalTop, height)
}

internal data class OcrWindowSpec(
    val primaryTileIndex: Int,
    val globalTop: Int,
    val primaryHeight: Int,
    val appendedRows: Int,
) {
    val height: Int
        get() = Math.addExact(primaryHeight, appendedRows)
}

internal object OcrWindowPlanner {
    fun plan(
        tileHeights: List<Int>,
        overlapRows: Int,
    ): List<OcrWindowSpec> {
        require(tileHeights.isNotEmpty())
        require(tileHeights.all { it > 0 })
        require(overlapRows >= 0)

        var globalTop = 0L
        return tileHeights.mapIndexed { index, height ->
            if (globalTop + height > Int.MAX_VALUE) {
                throw IllegalArgumentException("Receipt height exceeds supported coordinates")
            }
            val appendedRows = tileHeights.getOrNull(index + 1)
                ?.let { min(overlapRows, it) }
                ?: 0
            OcrWindowSpec(
                primaryTileIndex = index,
                globalTop = globalTop.toInt(),
                primaryHeight = height,
                appendedRows = appendedRows,
            ).also {
                globalTop += height
            }
        }
    }
}

internal object OcrRegionDeduplicator {
    fun deduplicate(regions: List<OcrRegion>): List<OcrRegion> {
        val sorted = regions.sortedWith(compareBy(OcrRegion::top, OcrRegion::left))
        val kept = mutableListOf<OcrRegion>()
        val indicesByText = mutableMapOf<String, MutableList<Int>>()

        sorted.forEach { candidate ->
            val key = candidate.text.normalizedOcrText()
            val bucket = indicesByText.getOrPut(key) { mutableListOf() }
            val duplicateIndex = bucket.firstOrNull { index ->
                kept[index].occupiesSameLineAs(candidate)
            }
            if (duplicateIndex == null) {
                bucket += kept.size
                kept += candidate
            } else if (candidate.confidenceScore() > kept[duplicateIndex].confidenceScore()) {
                kept[duplicateIndex] = candidate
            }
        }

        return kept.sortedWith(compareBy(OcrRegion::top, OcrRegion::left))
    }

    private fun OcrRegion.occupiesSameLineAs(other: OcrRegion): Boolean {
        val verticalOverlap = overlapFraction(top, bottom, other.top, other.bottom)
        val horizontalOverlap = overlapFraction(left, right, other.left, other.right)
        val centerDistance = abs((top + bottom) - (other.top + other.bottom)) / 2f
        val smallerHeight = min(bottom - top, other.bottom - other.top).coerceAtLeast(1f)
        return horizontalOverlap >= 0.45f &&
            (verticalOverlap >= 0.45f || centerDistance <= max(4f, smallerHeight * 0.55f))
    }

    private fun overlapFraction(
        firstStart: Float,
        firstEnd: Float,
        secondStart: Float,
        secondEnd: Float,
    ): Float {
        val intersection = max(0f, min(firstEnd, secondEnd) - max(firstStart, secondStart))
        val smallerLength = min(firstEnd - firstStart, secondEnd - secondStart).coerceAtLeast(1f)
        return intersection / smallerLength
    }

    private fun OcrRegion.confidenceScore(): Float = confidence ?: -1f

    private fun String.normalizedOcrText(): String = lowercase().filter(Char::isLetterOrDigit)
}

private fun List<OcrTileLayout>.tileIndexAt(globalY: Float): Int {
    val match = indexOfFirst { globalY >= it.globalTop && globalY < it.globalBottom }
    return if (match >= 0) match else lastIndex
}

private val DIRECT_TASK_EXECUTOR = Executor { command -> command.run() }

/**
 * A generic Google [Task] cannot be cancelled by cancelling its awaiting coroutine. Waiting in a
 * non-cancellable context keeps the caller-owned ML input alive until the task reaches a terminal
 * state; the cancelled caller resumes only after it is safe to release that input.
 */
internal suspend fun <T> Task<T>.awaitTerminalResult(): T = withContext(NonCancellable) {
    suspendCoroutine { continuation ->
        addOnCompleteListener(DIRECT_TASK_EXECUTOR) { completed ->
            when {
                completed.isCanceled -> continuation.resumeWithException(
                    CancellationException("ML Kit task was cancelled"),
                )
                completed.isSuccessful -> continuation.resume(completed.result)
                else -> continuation.resumeWithException(
                    completed.exception ?: IllegalStateException("ML Kit task failed"),
                )
            }
        }
    }
}
