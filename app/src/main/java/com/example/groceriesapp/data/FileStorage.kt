package com.example.groceriesapp.data

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
import java.util.UUID

/**
 * Shared file primitives for the app-private storage tree. These live outside
 * [GroceriesRepository] because household management needs the same write and delete guarantees:
 * writes that either land whole or not at all, and deletes that never follow a symlink out of
 * app-private storage.
 */

internal fun ensureDirectory(directory: File) {
    val path = directory.toPath()
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        check(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Expected a directory at ${directory.absolutePath}."
        }
        return
    }
    Files.createDirectories(path)
}

internal fun atomicWrite(target: File, contents: String) {
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

/**
 * Renames [source] onto [target] without ever falling back to copy-then-delete: a partially copied
 * tree followed by a delete is the shape that loses data when the process dies mid-way. Both paths
 * here leave the source intact on failure.
 */
@Throws(IOException::class)
internal fun moveAtomicallyOrPlain(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    }
}

@Throws(IOException::class)
internal fun deleteDirectoryWithoutFollowingLinks(directory: File) {
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
