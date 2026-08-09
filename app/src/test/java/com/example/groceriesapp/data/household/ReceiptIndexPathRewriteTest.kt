package com.example.groceriesapp.data.household

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptIndexPathRewriteTest {

    private val groceriesRoot = "/data/user/0/com.example.groceriesapp/files/groceries"

    private fun index(receipt: String, schemaVersion: Int = 4) =
        """{"schemaVersion":$schemaVersion,"receipts":[$receipt]}"""

    private fun rewrite(json: String) = rewriteReceiptIndexPathsJson(json, groceriesRoot)

    @Test
    fun legacyAbsolutePathsBecomeGroceriesRelative() {
        val legacy = "/data/user/0/com.example.groceriesapp/files/groceries/receipts/r1"
        val json = index(
            """
            {"id":"r1",
             "previewPath":"$legacy/receipt_preview.jpg",
             "sourcePreviewPath":"$legacy/receipt_preview.jpg",
             "tilePaths":["$legacy/tiles/tile_000.jpg","$legacy/tiles/tile_001.jpg"],
             "sourceTilePaths":["$legacy/tiles/tile_000.jpg"],
             "originalFramePaths":["$legacy/original/frame_0001.jpg"]}
            """.trimIndent(),
        )
        val receipt = JSONObject(rewrite(json)!!).getJSONArray("receipts").getJSONObject(0)

        assertEquals("receipts/r1/receipt_preview.jpg", receipt.getString("previewPath"))
        assertEquals("receipts/r1/receipt_preview.jpg", receipt.getString("sourcePreviewPath"))
        assertEquals(
            "receipts/r1/tiles/tile_001.jpg",
            receipt.getJSONArray("tilePaths").getString(1),
        )
        assertEquals(
            "receipts/r1/tiles/tile_000.jpg",
            receipt.getJSONArray("sourceTilePaths").getString(0),
        )
        assertEquals(
            "receipts/r1/original/frame_0001.jpg",
            receipt.getJSONArray("originalFramePaths").getString(0),
        )
    }

    @Test
    fun pathsAlreadyUnderTheGroceriesRootAreRelativizedToo() {
        val json = index("""{"id":"r1","previewPath":"$groceriesRoot/receipts/r1/p.jpg"}""")
        val receipt = JSONObject(rewrite(json)!!).getJSONArray("receipts").getJSONObject(0)
        assertEquals("receipts/r1/p.jpg", receipt.getString("previewPath"))
    }

    @Test
    fun editDirectoryPathsRelativizeCorrectly() {
        val legacy = "/data/user/0/com.example.groceriesapp/files/groceries/receipts/r1"
        val json = index("""{"id":"r1","previewPath":"$legacy/edits/edit_abc-1/preview.jpg"}""")
        val receipt = JSONObject(rewrite(json)!!).getJSONArray("receipts").getJSONObject(0)
        assertEquals("receipts/r1/edits/edit_abc-1/preview.jpg", receipt.getString("previewPath"))
    }

    @Test
    fun rewritingIsIdempotent() {
        val legacy = "/data/user/0/com.example.groceriesapp/files/groceries/receipts/r1"
        val json = index(
            """{"id":"r1","previewPath":"$legacy/p.jpg","tilePaths":["$legacy/tiles/t.jpg"]}""",
        )
        val once = rewrite(json)!!
        assertEquals(once, rewrite(once)!!)
    }

    @Test
    fun schemaVersionIsBumpedToTheRelativeLayout() {
        val json = index("""{"id":"r1"}""")
        assertEquals(5, JSONObject(rewrite(json)!!).getInt("schemaVersion"))
    }

    @Test
    fun unparseableJsonLeavesTheFileAlone() {
        assertNull(rewrite("{{{ not json"))
    }

    @Test
    fun missingSchemaVersionLeavesTheFileAlone() {
        assertNull(rewrite("""{"receipts":[{"id":"r1"}]}"""))
    }

    @Test
    fun futureSchemaVersionLeavesTheFileAlone() {
        assertNull(rewrite(index("""{"id":"r1"}""", schemaVersion = 99)))
    }

    @Test
    fun missingReceiptsArrayLeavesTheFileAlone() {
        assertNull(rewrite("""{"schemaVersion":4}"""))
    }

    @Test
    fun nullPreviewPathStaysNull() {
        val json = index("""{"id":"r1","previewPath":null,"sourcePreviewPath":null}""")
        val receipt = JSONObject(rewrite(json)!!).getJSONArray("receipts").getJSONObject(0)
        assertTrue(receipt.isNull("previewPath"))
        assertTrue(receipt.isNull("sourcePreviewPath"))
    }

    @Test
    fun emptyPathArraysStayEmpty() {
        val json = index("""{"id":"r1","tilePaths":[],"originalFramePaths":[]}""")
        val receipt = JSONObject(rewrite(json)!!).getJSONArray("receipts").getJSONObject(0)
        assertEquals(0, receipt.getJSONArray("tilePaths").length())
        assertEquals(0, receipt.getJSONArray("originalFramePaths").length())
    }

    @Test
    fun unrecognisedAbsolutePathIsKeptVerbatim() {
        val outside = "/storage/emulated/0/Download/somewhere.jpg"
        val json = index("""{"id":"r1","previewPath":"$outside"}""")
        val receipt = JSONObject(rewrite(json)!!).getJSONArray("receipts").getJSONObject(0)
        assertEquals(outside, receipt.getString("previewPath"))
    }

    @Test
    fun relativePathsPassThroughUntouched() {
        assertEquals("receipts/r1/p.jpg", relativizeStoredPath("receipts/r1/p.jpg", groceriesRoot))
    }
}
