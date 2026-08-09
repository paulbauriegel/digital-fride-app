package com.example.groceriesapp.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStorageTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun atomicWriteReplacesContentAndLeavesNoTemporaryFile() {
        val directory = temporaryFolder.newFolder("store")
        val target = File(directory, "index.json")
        atomicWrite(target, "first")
        atomicWrite(target, "second")

        assertEquals("second", target.readText())
        assertEquals(
            "no .tmp residue expected",
            listOf("index.json"),
            directory.listFiles().orEmpty().map(File::getName).sorted(),
        )
    }

    @Test
    fun atomicWriteCreatesMissingParentDirectories() {
        val target = File(temporaryFolder.root, "a/b/c/index.json")
        atomicWrite(target, "content")
        assertEquals("content", target.readText())
    }

    @Test
    fun deleteRemovesNestedTrees() {
        val root = temporaryFolder.newFolder("household")
        File(root, "receipts/r1/tiles").mkdirs()
        File(root, "receipts/r1/tiles/tile_000.jpg").writeBytes(byteArrayOf(1))
        File(root, "receipts/index.json").writeText("{}")

        deleteDirectoryWithoutFollowingLinks(root)

        assertFalse(root.exists())
    }

    @Test
    fun deleteOnAMissingPathIsANoOp() {
        deleteDirectoryWithoutFollowingLinks(File(temporaryFolder.root, "absent"))
    }

    @Test
    fun moveRenamesADirectoryWithItsContents() {
        val source = temporaryFolder.newFolder("source")
        File(source, "nested").mkdirs()
        File(source, "nested/file.txt").writeText("payload")
        val target = File(temporaryFolder.root, "target")

        moveAtomicallyOrPlain(source, target)

        assertFalse(source.exists())
        assertTrue(target.isDirectory)
        assertEquals("payload", File(target, "nested/file.txt").readText())
    }
}
