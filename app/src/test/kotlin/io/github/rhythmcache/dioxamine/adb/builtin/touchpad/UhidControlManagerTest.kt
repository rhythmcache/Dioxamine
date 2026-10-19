package io.github.rhythmcache.dioxamine.adb.builtin.touchpad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UhidControlManagerTest {

    private fun chunkAndReassemble(text: String, maxChunkSize: Int = 300): Pair<List<String>, String> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        val chunks = mutableListOf<String>()
        val chunkByteList = mutableListOf<ByteArray>()

        while (offset < bytes.size) {
            val chunkLen = UhidControlManager.safeUtf8ChunkLength(bytes, offset, maxChunkSize)
            assertTrue("Chunk length must be strictly positive", chunkLen > 0)
            assertTrue("Chunk length cannot exceed maxChunkSize: got $chunkLen > $maxChunkSize", chunkLen <= maxChunkSize)
            assertTrue("Chunk length cannot exceed remaining bytes", chunkLen <= bytes.size - offset)

            val chunkBytes = ByteArray(chunkLen)
            System.arraycopy(bytes, offset, chunkBytes, 0, chunkLen)
            chunkByteList.add(chunkBytes)
            chunks.add(String(chunkBytes, Charsets.UTF_8))

            offset += chunkLen
        }

        // Exact byte-for-byte reassembly assertion
        val totalBytes = chunkByteList.sumOf { it.size }
        assertEquals(bytes.size, totalBytes)
        val reassembledBytes = ByteArray(totalBytes)
        var byteOffset = 0
        for (cb in chunkByteList) {
            System.arraycopy(cb, 0, reassembledBytes, byteOffset, cb.size)
            byteOffset += cb.size
        }
        org.junit.Assert.assertArrayEquals(bytes, reassembledBytes)

        return chunks to chunks.joinToString("")
    }

    @Test
    fun testAsciiChunking() {
        val original = "a".repeat(1000)
        val (chunks, reassembled) = chunkAndReassemble(original, maxChunkSize = 300)
        assertEquals(original, reassembled)
        assertEquals(4, chunks.size) // 300 + 300 + 300 + 100
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 300 })
    }

    @Test
    fun testTwoByteCharactersChunking() {
        // Greek alpha & beta are 2-byte UTF-8 sequences (0xCE 0xB1, 0xCE 0xB2)
        val original = "αβγδε".repeat(100) // 500 chars = 1000 bytes
        val (chunks, reassembled) = chunkAndReassemble(original, maxChunkSize = 300)
        assertEquals(original, reassembled)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 300 })
    }

    @Test
    fun testThreeByteCjkCharactersChunking() {
        // CJK characters '你', '好', '世', '界' are 3 bytes each in UTF-8
        val original = "你好世界".repeat(100) // 400 chars = 1200 bytes
        val (chunks, reassembled) = chunkAndReassemble(original, maxChunkSize = 300)
        assertEquals(original, reassembled)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 300 })
        // Each chunk should be cleanly divisible by 3 bytes
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size % 3 == 0 })
    }

    @Test
    fun testFourByteEmojisChunking() {
        // Emojis 🚀, 🎉, ✨, 🔥 are 4-byte UTF-8 sequences
        val original = "🚀🎉✨🔥".repeat(100)
        val (chunks, reassembled) = chunkAndReassemble(original, maxChunkSize = 300)
        assertEquals(original, reassembled)
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 300 })
        // Each chunk must not split any 4-byte emoji
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size % 4 == 0 })
    }

    @Test
    fun testStraddlingBoundaryAcrossVariousChunkSizes() {
        // Test mixed text with 1, 2, 3, and 4-byte UTF-8 characters across tight chunk sizes (4 to 50)
        // With chunkSize >= 4, multibyte characters are never split across chunks and never exceed chunkSize.
        val mixed = "Hello! 你好! 🚀 World! αβγ".repeat(20)
        for (chunkSize in 4..50) {
            val (chunks, reassembled) = chunkAndReassemble(mixed, maxChunkSize = chunkSize)
            assertEquals("Failed reassembly for chunkSize=$chunkSize", mixed, reassembled)
        }
    }

    @Test
    fun testChunkSizesFourToTen() {
        // Mixed string with 1-byte ASCII, 2-byte Greek, 3-byte CJK, and 4-byte emoji
        // A: 1 byte, α: 2 bytes, 你: 3 bytes, 🚀: 4 bytes -> total 10 bytes
        val mixed = "Aα你🚀"

        for (requestedSize in 4..10) {
            val (chunks, reassembled) = chunkAndReassemble(mixed, maxChunkSize = requestedSize)
            assertEquals("Reassembly mismatch for chunkSize=$requestedSize", mixed, reassembled)

            for (chunk in chunks) {
                val chunkBytes = chunk.toByteArray(Charsets.UTF_8)
                val decoded = String(chunkBytes, Charsets.UTF_8)
                assertEquals("Chunk corrupted", chunk, decoded)
                assertTrue("Chunk size exceeded requestedSize", chunkBytes.size <= requestedSize)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testChunkSizeSmallerThanFourThrows() {
        val bytes = "Hello".toByteArray(Charsets.UTF_8)
        UhidControlManager.safeUtf8ChunkLength(bytes, 0, 3)
    }
}
