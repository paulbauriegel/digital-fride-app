package com.example.groceriesapp.vision.models

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ModelDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val requests = mutableListOf<RecordedRequest>()
    private var supportRanges = true

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val range = request.getHeader("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                return if (range != null && supportRanges) {
                    MockResponse().setResponseCode(206).setBody(Buffer().write(content, range, content.size - range))
                } else {
                    MockResponse().setResponseCode(200).setBody(Buffer().write(content))
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun spec(sha256: String = sha) =
        ModelFileSpec(server.url("/model.bin").toString(), sha256, content.size.toLong(), "models/test", "model.bin")

    private val downloader = ModelDownloader(OkHttpClient())

    @Test
    fun `downloads, verifies and moves the file into place`() = runBlocking {
        val target = File(tmp.root, "models/test/model.bin")
        downloader.download(spec(), target)
        assertArrayEquals(content, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
        assertNull(requests.single().getHeader("Range"))
    }

    @Test
    fun `resumes a partial download with a range request`() = runBlocking {
        val target = File(tmp.root, "model.bin")
        File(target.path + ".part").writeBytes(content.copyOf(120_000))
        downloader.download(spec(), target)
        assertArrayEquals(content, target.readBytes())
        assertEquals("bytes=120000-", requests.single().getHeader("Range"))
    }

    @Test
    fun `starts over when the server ignores the range`() = runBlocking {
        supportRanges = false
        val target = File(tmp.root, "model.bin")
        File(target.path + ".part").writeBytes(content.copyOf(50_000))
        downloader.download(spec(), target)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `a stale partial file from another version is discarded and refetched`() = runBlocking {
        val target = File(tmp.root, "model.bin")
        File(target.path + ".part").writeBytes(ByteArray(100_000) { 7 })
        downloader.download(spec(), target)
        assertArrayEquals(content, target.readBytes())
        assertEquals(2, requests.size) // resumed attempt failed the checksum, second one from scratch
    }

    @Test
    fun `checksum mismatch leaves no file behind`() {
        val target = File(tmp.root, "model.bin")
        val result = runCatching { runBlocking { downloader.download(spec(sha256 = "0".repeat(64)), target) } }
        assertTrue(result.exceptionOrNull() is ModelChecksumException)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun `an existing matching file is adopted without a request`() = runBlocking {
        val target = File(tmp.root, "model.bin")
        target.writeBytes(content)
        downloader.download(spec(), target)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a wrong existing file is replaced`() = runBlocking {
        val target = File(tmp.root, "model.bin")
        target.writeBytes(ByteArray(content.size))
        downloader.download(spec(), target)
        assertArrayEquals(content, target.readBytes())
    }
}
