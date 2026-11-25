package com.example.groceriesapp.feature.receipts

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptSyncerTest {
    private fun source(
        label: String,
        connected: Boolean = true,
        sync: suspend () -> SourceSyncOutcome,
    ) = ReceiptSyncSource(label, { connected }, sync)

    @Test
    fun `asks for a store account when none is connected`() = runTest {
        var ran = false
        val syncer = ReceiptSyncer(
            listOf(source("REWE", connected = false) { ran = true; SourceSyncOutcome() }),
        )

        syncer.syncAll()

        val state = syncer.state.value as ReceiptSyncState.Finished
        assertTrue(state.needsSetup)
        assertEquals(false, ran)
    }

    @Test
    fun `adds up new receipts across connected sources only`() = runTest {
        val syncer = ReceiptSyncer(
            listOf(
                source("REWE") { SourceSyncOutcome(imported = 2) },
                source("EDEKA") { SourceSyncOutcome(imported = 1) },
                source("BIO COMPANY", connected = false) { SourceSyncOutcome(imported = 9) },
            ),
        )

        syncer.syncAll()

        assertEquals(
            ReceiptSyncState.Finished("Imported 3 new receipts"),
            syncer.state.value,
        )
    }

    @Test
    fun `one failing source does not stop the others`() = runTest {
        val syncer = ReceiptSyncer(
            listOf(
                source("REWE") { error("Sign in again") },
                source("EDEKA") { SourceSyncOutcome(imported = 1, failed = 2) },
            ),
        )

        syncer.syncAll()

        assertEquals(
            ReceiptSyncState.Finished(
                "Imported 1 new receipt · REWE: Sign in again · EDEKA: 2 failed",
            ),
            syncer.state.value,
        )
    }

    @Test
    fun `reports up to date and returns to idle once acknowledged`() = runTest {
        val syncer = ReceiptSyncer(listOf(source("REWE") { SourceSyncOutcome() }))

        syncer.syncAll()
        assertEquals(ReceiptSyncState.Finished("Receipts are up to date"), syncer.state.value)

        syncer.acknowledge()
        assertEquals(ReceiptSyncState.Idle, syncer.state.value)
    }

    @Test
    fun `a failed listing alone leaves no misleading headline`() {
        assertEquals(
            "REWE: Couldn't load receipts",
            ReceiptSyncer.summarize(
                listOf("REWE" to SourceSyncOutcome(error = "Couldn't load receipts")),
            ),
        )
    }
}
