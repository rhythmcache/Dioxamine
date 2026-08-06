package io.github.rhythmcache.dioxamine.adb

import io.github.rhythmcache.adb.AdbStream

/**
 * Extension function for AdbStream to read exactly the specified number of bytes.
 * Throws EOF or returns false if connection is closed prematurely.
 */
suspend fun AdbStream.readExactly(target: ByteArray, offset: Int = 0, length: Int = target.size - offset): Boolean {
    var readTotal = 0
    while (readTotal < length) {
        val n = this.read(target, offset + readTotal, length - readTotal)
        if (n <= 0) return false
        readTotal += n
    }
    return true
}
