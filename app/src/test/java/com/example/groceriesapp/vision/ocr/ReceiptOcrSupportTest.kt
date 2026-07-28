package com.example.groceriesapp.vision.ocr

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptOcrSupportTest {
    @Test
    fun plansWindowsAcrossEveryStorageTileBoundary() {
        val windows = OcrWindowPlanner.plan(
            tileHeights = listOf(2_048, 2_048, 512),
            overlapRows = 200,
        )

        assertEquals(
            listOf(
                OcrWindowSpec(
                    primaryTileIndex = 0,
                    globalTop = 0,
                    primaryHeight = 2_048,
                    appendedRows = 200,
                ),
                OcrWindowSpec(
                    primaryTileIndex = 1,
                    globalTop = 2_048,
                    primaryHeight = 2_048,
                    appendedRows = 200,
                ),
                OcrWindowSpec(
                    primaryTileIndex = 2,
                    globalTop = 4_096,
                    primaryHeight = 512,
                    appendedRows = 0,
                ),
            ),
            windows,
        )
    }

    @Test
    fun clampsOverlapToAShortFollowingTile() {
        val windows = OcrWindowPlanner.plan(
            tileHeights = listOf(2_048, 120),
            overlapRows = 200,
        )

        assertEquals(120, windows.first().appendedRows)
        assertEquals(2_168, windows.first().height)
        assertEquals(0, windows.last().appendedRows)
    }

    @Test
    fun deduplicatesBoundaryLinesByGlobalPositionAndKeepsBestConfidence() {
        val firstWindowLine = region(
            text = "MILCH 1,49",
            top = 2_026f,
            bottom = 2_062f,
            confidence = 0.72f,
            sourceTileIndex = 0,
        )
        val overlappingWindowLine = region(
            text = "Milch 1.49",
            top = 2_029f,
            bottom = 2_064f,
            confidence = 0.93f,
            sourceTileIndex = 1,
        )
        val repeatedLaterLine = region(
            text = "MILCH 1,49",
            top = 4_200f,
            bottom = 4_236f,
            confidence = 0.81f,
            sourceTileIndex = 2,
        )

        val deduplicated = OcrRegionDeduplicator.deduplicate(
            listOf(repeatedLaterLine, firstWindowLine, overlappingWindowLine),
        )

        assertEquals(2, deduplicated.size)
        assertEquals(2_029f, deduplicated.first().top, 0f)
        assertEquals(0.93f, checkNotNull(deduplicated.first().confidence), 0f)
        assertEquals(4_200f, deduplicated.last().top, 0f)
    }

    @Test
    fun doesNotMergeAdjacentDifferentRowsWithSameText() {
        val first = region(text = "PFAND 0,25", top = 100f, bottom = 130f)
        val second = region(text = "PFAND 0,25", top = 142f, bottom = 172f)

        val deduplicated = OcrRegionDeduplicator.deduplicate(listOf(first, second))

        assertEquals(2, deduplicated.size)
    }

    @Test
    fun taskAwaitDefersCallerCleanupUntilTaskTerminates() = runBlocking {
        val source = TaskCompletionSource<Int>()
        var released = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                source.task.awaitTerminalResult()
            } finally {
                released = true
            }
        }

        job.cancel()
        yield()
        assertFalse("Input must remain alive while the task is running", released)

        source.setResult(42)
        job.join()

        assertTrue(released)
        assertTrue(job.isCancelled)
    }

    private fun region(
        text: String,
        top: Float,
        bottom: Float,
        confidence: Float = 0.8f,
        sourceTileIndex: Int = 0,
    ): OcrRegion = OcrRegion(
        text = text,
        left = 80f,
        top = top,
        right = 920f,
        bottom = bottom,
        confidence = confidence,
        sourceTileIndex = sourceTileIndex,
    )
}
