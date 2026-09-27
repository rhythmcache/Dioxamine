package io.github.rhythmcache.dioxamine.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class TempFileBridgeTest {

    private lateinit var testDir: File
    private lateinit var testScope: CoroutineScope
    private lateinit var dialogGate: PluginDialogGate
    private lateinit var bridge: TempFileBridge

    private val resolvedMap = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val rejectedMap = mutableMapOf<String, CompletableDeferred<String>>()

    private fun getResolveDeferred(callbackId: String): CompletableDeferred<JsonElement> =
        synchronized(resolvedMap) {
            resolvedMap.getOrPut(callbackId) { CompletableDeferred() }
        }

    private fun getRejectDeferred(callbackId: String): CompletableDeferred<String> =
        synchronized(rejectedMap) {
            rejectedMap.getOrPut(callbackId) { CompletableDeferred() }
        }

    @Before
    fun setUp() {
        testDir = Files.createTempDirectory("temp_file_bridge_test").toFile()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        dialogGate = PluginDialogGate()

        bridge = TempFileBridge(
            baseDir = testDir,
            pluginId = "test-plugin",
            pluginName = "Test Plugin",
            dialogGate = dialogGate,
            scope = testScope,
            onResolve = { id, result ->
                getResolveDeferred(id).complete(result)
            },
            onReject = { id, error ->
                getRejectDeferred(id).complete(error)
            },
        )
    }

    @After
    fun tearDown() {
        testScope.cancel()
        testDir.deleteRecursively()
    }

    private suspend fun requestToken(approved: Boolean = true): String {
        val answerJob = testScope.launch {
            val req = dialogGate.pendingRequest.filterNotNull().first()
            req.onResult(if (approved) 1 else 0)
        }

        val cbId = "req_${System.nanoTime()}"
        bridge.requestTempFile(cbId)

        return if (approved) {
            val res = withTimeout(5000) { getResolveDeferred(cbId).await() }
            answerJob.join()
            res.jsonObject["token"]?.jsonPrimitive?.content ?: error("Token missing")
        } else {
            val err = withTimeout(5000) { getRejectDeferred(cbId).await() }
            answerJob.join()
            throw IllegalStateException(err)
        }
    }

    @Test
    fun testLazyFileCreation() = runBlocking(Dispatchers.Default) {
        val token = requestToken(approved = true)
        assertNotNull(token)
        assertTrue(bridge.hasToken(token))

        // File must not exist on disk yet
        val file = bridge.getFileForToken(token)
        assertNotNull(file)
        assertFalse(file!!.exists())
    }

    @Test
    fun testReadBeforeWriteReturnsEmptyNotError() = runBlocking(Dispatchers.Default) {
        val token = requestToken(approved = true)

        val cbId = "read_${System.nanoTime()}"
        bridge.readTempFileChunk(token, 0L, 100, cbId)

        val res = withTimeout(5000) { getResolveDeferred(cbId).await() }
        assertEquals("", res.jsonPrimitive.content)
    }

    @Test
    fun testWriteReadAndSize() = runBlocking(Dispatchers.Default) {
        val token = requestToken(approved = true)

        // Initial size before write
        val sizeCb1 = "size_1_${System.nanoTime()}"
        bridge.getTempFileSize(token, sizeCb1)
        val sizeRes1 = withTimeout(5000) { getResolveDeferred(sizeCb1).await() }
        assertEquals("0", sizeRes1.jsonPrimitive.content)

        // Write first chunk
        val chunk1 = Base64.getEncoder().encodeToString("Hello ".toByteArray(Charsets.UTF_8))
        val writeCb1 = "write_1_${System.nanoTime()}"
        bridge.writeTempFileChunk(token, chunk1, writeCb1)
        withTimeout(5000) { getResolveDeferred(writeCb1).await() }

        // Write second chunk (append-only)
        val chunk2 = Base64.getEncoder().encodeToString("World!".toByteArray(Charsets.UTF_8))
        val writeCb2 = "write_2_${System.nanoTime()}"
        bridge.writeTempFileChunk(token, chunk2, writeCb2)
        withTimeout(5000) { getResolveDeferred(writeCb2).await() }

        // Verify file exists on disk
        val file = bridge.getFileForToken(token)
        assertNotNull(file)
        assertTrue(file!!.exists())
        assertEquals(12L, file.length())

        // Verify size
        val sizeCb2 = "size_2_${System.nanoTime()}"
        bridge.getTempFileSize(token, sizeCb2)
        val sizeRes2 = withTimeout(5000) { getResolveDeferred(sizeCb2).await() }
        assertEquals("12", sizeRes2.jsonPrimitive.content)

        // Read chunk 1
        val readCb1 = "read_1_${System.nanoTime()}"
        bridge.readTempFileChunk(token, 0L, 5, readCb1)
        val readRes1 = withTimeout(5000) { getResolveDeferred(readCb1).await() }
        val decoded1 = String(Base64.getDecoder().decode(readRes1.jsonPrimitive.content), Charsets.UTF_8)
        assertEquals("Hello", decoded1)

        // Read chunk 2
        val readCb2 = "read_2_${System.nanoTime()}"
        bridge.readTempFileChunk(token, 6L, 6, readCb2)
        val readRes2 = withTimeout(5000) { getResolveDeferred(readCb2).await() }
        val decoded2 = String(Base64.getDecoder().decode(readRes2.jsonPrimitive.content), Charsets.UTF_8)
        assertEquals("World!", decoded2)

        // Read past EOF
        val readCb3 = "read_3_${System.nanoTime()}"
        bridge.readTempFileChunk(token, 100L, 10, readCb3)
        val readRes3 = withTimeout(5000) { getResolveDeferred(readCb3).await() }
        assertEquals("", readRes3.jsonPrimitive.content)
    }

    @Test
    fun testUnknownTokenRejectionOnEveryMethod() = runBlocking(Dispatchers.Default) {
        val badToken = "unknown-token-12345"

        // writeTempFileChunk
        val writeCb = "bad_write_${System.nanoTime()}"
        bridge.writeTempFileChunk(badToken, "dGVzdA==", writeCb)
        val writeErr = withTimeout(5000) { getRejectDeferred(writeCb).await() }
        assertTrue(writeErr.contains("Unknown or invalid"))
        assertFalse(writeErr.contains(testDir.absolutePath))

        // readTempFileChunk
        val readCb = "bad_read_${System.nanoTime()}"
        bridge.readTempFileChunk(badToken, 0L, 10, readCb)
        val readErr = withTimeout(5000) { getRejectDeferred(readCb).await() }
        assertTrue(readErr.contains("Unknown or invalid"))
        assertFalse(readErr.contains(testDir.absolutePath))

        // getTempFileSize
        val sizeCb = "bad_size_${System.nanoTime()}"
        bridge.getTempFileSize(badToken, sizeCb)
        val sizeErr = withTimeout(5000) { getRejectDeferred(sizeCb).await() }
        assertTrue(sizeErr.contains("Unknown or invalid"))
        assertFalse(sizeErr.contains(testDir.absolutePath))

        // deleteTempFile
        val delCb = "bad_del_${System.nanoTime()}"
        bridge.deleteTempFile(badToken, delCb)
        val delErr = withTimeout(5000) { getRejectDeferred(delCb).await() }
        assertTrue(delErr.contains("Unknown or invalid"))
        assertFalse(delErr.contains(testDir.absolutePath))
    }

    @Test
    fun testDeleteThenReuseFails() = runBlocking(Dispatchers.Default) {
        val token = requestToken(approved = true)
        val chunk = Base64.getEncoder().encodeToString("data".toByteArray(Charsets.UTF_8))

        // Write data
        val writeCb1 = "write_del_${System.nanoTime()}"
        bridge.writeTempFileChunk(token, chunk, writeCb1)
        withTimeout(5000) { getResolveDeferred(writeCb1).await() }

        val file = bridge.getFileForToken(token)
        assertNotNull(file)
        assertTrue(file!!.exists())

        // Delete file
        val delCb = "del_${System.nanoTime()}"
        bridge.deleteTempFile(token, delCb)
        withTimeout(5000) { getResolveDeferred(delCb).await() }

        // Verify file is deleted on disk
        assertFalse(file.exists())
        assertFalse(bridge.hasToken(token))

        // Subsequent write must fail
        val writeCb2 = "write_after_del_${System.nanoTime()}"
        bridge.writeTempFileChunk(token, chunk, writeCb2)
        val writeErr = withTimeout(5000) { getRejectDeferred(writeCb2).await() }
        assertTrue(writeErr.contains("Unknown or invalid"))

        // Subsequent read must fail
        val readCb = "read_after_del_${System.nanoTime()}"
        bridge.readTempFileChunk(token, 0L, 4, readCb)
        val readErr = withTimeout(5000) { getRejectDeferred(readCb).await() }
        assertTrue(readErr.contains("Unknown or invalid"))
    }

    @Test
    fun testSessionCleanup() = runBlocking(Dispatchers.Default) {
        val token1 = requestToken(approved = true)
        val token2 = requestToken(approved = true)

        val chunk = Base64.getEncoder().encodeToString("session data".toByteArray(Charsets.UTF_8))
        val writeCb1 = "w1_${System.nanoTime()}"
        val writeCb2 = "w2_${System.nanoTime()}"

        bridge.writeTempFileChunk(token1, chunk, writeCb1)
        bridge.writeTempFileChunk(token2, chunk, writeCb2)
        withTimeout(5000) { getResolveDeferred(writeCb1).await() }
        withTimeout(5000) { getResolveDeferred(writeCb2).await() }

        val file1 = bridge.getFileForToken(token1)
        val file2 = bridge.getFileForToken(token2)
        assertTrue(file1!!.exists())
        assertTrue(file2!!.exists())
        assertEquals(2, bridge.activeTokenCount())

        // Teardown session
        bridge.deleteAllForSession()

        assertEquals(0, bridge.activeTokenCount())
        assertFalse(file1.exists())
        assertFalse(file2.exists())

        // Old tokens reject
        val readCb = "read_post_session_${System.nanoTime()}"
        bridge.readTempFileChunk(token1, 0L, 5, readCb)
        val readErr = withTimeout(5000) { getRejectDeferred(readCb).await() }
        assertTrue(readErr.contains("Unknown or invalid"))
    }

    @Test
    fun testStaleFileCleanupWithEmptyInMemoryMap() = runBlocking(Dispatchers.Default) {
        // In-memory map is empty
        assertEquals(0, bridge.activeTokenCount())

        // Create stale files on disk simulating previous crash
        val stale1 = File(testDir, "stale_1.tmp").apply { writeText("stale1") }
        val stale2 = File(testDir, "stale_2.tmp").apply { writeText("stale2") }
        assertTrue(stale1.exists())
        assertTrue(stale2.exists())

        bridge.cleanupStaleFiles()

        assertFalse(stale1.exists())
        assertFalse(stale2.exists())
        assertEquals(0, bridge.activeTokenCount())
    }

    @Test
    fun testConsentDenialRejects() = runBlocking(Dispatchers.Default) {
        try {
            requestToken(approved = false)
            org.junit.Assert.fail("Expected requestTempFile to reject on user denial")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("User denied") == true)
        }
        assertEquals(0, bridge.activeTokenCount())
    }

    @Test
    fun testConcurrentWritesSerializedWithoutInterleaving() = runBlocking(Dispatchers.Default) {
        val token = requestToken(approved = true)
        val numChunks = 10
        val chunkPrefix = "serialized_chunk_"

        val jobs = (0 until numChunks).map { i ->
            val content = "$chunkPrefix$i\n"
            val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
            val cbId = "concurrent_write_${i}_${System.nanoTime()}"
            bridge.writeTempFileChunk(token, b64, cbId)
            cbId
        }

        // Await all writes
        jobs.forEach { cbId ->
            withTimeout(5000) { getResolveDeferred(cbId).await() }
        }

        // Read total file
        val file = bridge.getFileForToken(token)
        assertNotNull(file)
        assertTrue(file!!.exists())

        val readCb = "read_all_${System.nanoTime()}"
        bridge.readTempFileChunk(token, 0L, file.length().toInt(), readCb)
        val readRes = withTimeout(5000) { getResolveDeferred(readCb).await() }
        val readText = String(Base64.getDecoder().decode(readRes.jsonPrimitive.content), Charsets.UTF_8)

        // Verify each chunk is completely intact
        for (i in 0 until numChunks) {
            assertTrue("Expected to contain chunk $i", readText.contains("$chunkPrefix$i\n"))
        }
    }

    @Test
    fun testMalformedBase64Rejection() = runBlocking(Dispatchers.Default) {
        val token = requestToken(approved = true)
        val badBase64 = "%%%not-valid-base64-at-all%%%"

        val cbId = "bad_b64_${System.nanoTime()}"
        bridge.writeTempFileChunk(token, badBase64, cbId)
        val err = withTimeout(5000) { getRejectDeferred(cbId).await() }
        assertTrue(err.contains("Invalid base64 payload"))
    }

    @Test
    fun testReadChunkClampedToMaxChunkSize() = runBlocking(Dispatchers.Default) {
        val token = requestToken(approved = true)
        val content = "sample stream content"
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))

        val writeCb = "write_clamp_${System.nanoTime()}"
        bridge.writeTempFileChunk(token, b64, writeCb)
        withTimeout(5000) { getResolveDeferred(writeCb).await() }

        // Request Int.MAX_VALUE length
        val readCb = "read_clamp_${System.nanoTime()}"
        bridge.readTempFileChunk(token, 0L, Int.MAX_VALUE, readCb)
        val readRes = withTimeout(5000) { getResolveDeferred(readCb).await() }
        val decoded = String(Base64.getDecoder().decode(readRes.jsonPrimitive.content), Charsets.UTF_8)
        assertEquals(content, decoded)
    }
}
