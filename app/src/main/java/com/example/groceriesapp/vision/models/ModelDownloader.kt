package com.example.groceriesapp.vision.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class InsufficientStorageException(val neededBytes: Long) :
    IOException("Not enough free storage: ${neededBytes / 1_000_000} MB needed")

class ModelChecksumException(file: String) : IOException("Downloaded $file does not match its checksum")

/**
 * Downloads one model file into place: streams into `<name>.part` next to the target (resuming a
 * previous partial download with an HTTP Range request), checks size and sha256, then moves it over
 * the target atomically. A file that is already in place and matches is left alone, so models that
 * were copied onto the device by other means are adopted instead of downloaded again.
 */
class ModelDownloader(private val client: OkHttpClient = defaultClient) {

    /** [onProgress] receives bytes done and total for this file. */
    suspend fun download(spec: ModelFileSpec, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            if (matches(target, spec)) {
                onProgress(spec.bytes, spec.bytes)
                return@withContext
            }
            val dir = requireNotNull(target.parentFile)
            dir.mkdirs()
            val part = File(dir, target.name + PART_SUFFIX)
            if (part.length() > spec.bytes) part.delete()
            for (attempt in 0..1) {
                val needed = spec.bytes - part.length() + SPACE_MARGIN_BYTES
                if (dir.usableSpace < needed) throw InsufficientStorageException(needed)
                val resumedFrom = part.length()
                val digest = MessageDigest.getInstance("SHA-256")
                if (resumedFrom > 0) part.inputStream().use { copy(it, null, digest) {} }
                fetch(spec, part, resumedFrom, digest, onProgress)
                if (part.length() == spec.bytes && digest.digest().toHex() == spec.sha256) {
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    return@withContext
                }
                // A resumed download may have joined two different files; retry once from scratch.
                part.delete()
                if (resumedFrom == 0L) break
            }
            throw ModelChecksumException(spec.destName)
        }

    private suspend fun fetch(spec: ModelFileSpec, part: File, offset: Long, digest: MessageDigest, onProgress: (Long, Long) -> Unit) {
        val request = Request.Builder().url(spec.url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        client.newCall(request).execute().use { response ->
            val append = when (response.code) {
                206 -> true
                200 -> false // no range support: start over
                416 -> { // range beyond the end: the partial file is stale
                    part.delete()
                    throw IOException("Stale partial download of ${spec.destName}, retrying")
                }
                else -> throw IOException("HTTP ${response.code} for ${spec.destName}")
            }
            if (!append) digest.reset()
            var done = if (append) offset else 0L
            onProgress(done, spec.bytes)
            val body = response.body ?: throw IOException("Empty response for ${spec.destName}")
            body.byteStream().use { input ->
                FileOutputStream(part, append).buffered(BUFFER).use { out ->
                    var lastReport = done
                    copy(input, out, digest) { n ->
                        done += n
                        if (done - lastReport >= REPORT_EVERY_BYTES) {
                            lastReport = done
                            onProgress(done, spec.bytes)
                        }
                    }
                }
            }
            onProgress(done, spec.bytes)
        }
    }

    private suspend inline fun copy(input: java.io.InputStream, out: java.io.OutputStream?, digest: MessageDigest, onBytes: (Int) -> Unit) {
        val buf = ByteArray(BUFFER)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out?.write(buf, 0, n)
            digest.update(buf, 0, n)
            onBytes(n)
        }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        private const val BUFFER = 1 shl 20
        private const val REPORT_EVERY_BYTES = 4L shl 20
        private const val SPACE_MARGIN_BYTES = 64L shl 20

        private val defaultClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true) // the Hub redirects /resolve/ to its CDN
                .build()
        }

        /** Size first (cheap), then sha256. */
        fun matches(file: File, spec: ModelFileSpec): Boolean =
            file.isFile && file.length() == spec.bytes && sha256(file) == spec.sha256

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
