package io.github.rhythmcache.dioxamine.adb.builtin.packagemanager

import io.github.rhythmcache.adb.AdbStream
import io.github.rhythmcache.dioxamine.core.AppLogger
import okio.Buffer

data class AppPackageItem(
    val packageName: String,
    val label: String,
    val sourceDir: String,
    val splitDirs: List<String>,
    val dataDir: String,
    val uid: Int,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val hasSplits: Boolean,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val installer: String,
    val iconBytes: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppPackageItem

        if (packageName != other.packageName) return false
        if (label != other.label) return false
        if (sourceDir != other.sourceDir) return false
        if (splitDirs != other.splitDirs) return false
        if (dataDir != other.dataDir) return false
        if (uid != other.uid) return false
        if (isSystem != other.isSystem) return false
        if (isEnabled != other.isEnabled) return false
        if (hasSplits != other.hasSplits) return false
        if (versionName != other.versionName) return false
        if (versionCode != other.versionCode) return false
        if (minSdk != other.minSdk) return false
        if (targetSdk != other.targetSdk) return false
        if (firstInstallTime != other.firstInstallTime) return false
        if (lastUpdateTime != other.lastUpdateTime) return false
        if (installer != other.installer) return false
        if (!iconBytes.contentEquals(other.iconBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + sourceDir.hashCode()
        result = 31 * result + splitDirs.hashCode()
        result = 31 * result + dataDir.hashCode()
        result = 31 * result + uid
        result = 31 * result + isSystem.hashCode()
        result = 31 * result + isEnabled.hashCode()
        result = 31 * result + hasSplits.hashCode()
        result = 31 * result + versionName.hashCode()
        result = 31 * result + versionCode.hashCode()
        result = 31 * result + minSdk
        result = 31 * result + targetSdk
        result = 31 * result + firstInstallTime.hashCode()
        result = 31 * result + lastUpdateTime.hashCode()
        result = 31 * result + installer.hashCode()
        result = 31 * result + iconBytes.contentHashCode()
        return result
    }
}

class RawStdoutStream(private val stream: AdbStream) {
    private val buffer = Buffer()
    private var isEof = false

    suspend fun findMagic(): Boolean {
        var window = 0
        while (true) {
            if (!ensureBytes(1)) return false
            val b = buffer.readByte().toInt() and 0xFF
            window = (window shl 8) or b
            if (window == 0x504B4744) { // ASCII "PKGD"
                return true
            }
        }
    }

    suspend fun readByte(): Byte {
        if (!ensureBytes(1)) throw java.io.EOFException("Unexpected EOF reading byte")
        return buffer.readByte()
    }

    suspend fun readUnsignedShort(): Int {
        if (!ensureBytes(2)) throw java.io.EOFException("Unexpected EOF reading short")
        val b0 = buffer.readByte().toInt() and 0xFF
        val b1 = buffer.readByte().toInt() and 0xFF
        return (b0 shl 8) or b1
    }

    suspend fun readInt(): Int {
        if (!ensureBytes(4)) throw java.io.EOFException("Unexpected EOF reading int")
        val b0 = buffer.readByte().toInt() and 0xFF
        val b1 = buffer.readByte().toInt() and 0xFF
        val b2 = buffer.readByte().toInt() and 0xFF
        val b3 = buffer.readByte().toInt() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    suspend fun readLong(): Long {
        if (!ensureBytes(8)) throw java.io.EOFException("Unexpected EOF reading long")
        var res = 0L
        for (i in 0 until 8) {
            val b = buffer.readByte().toLong() and 0xFF
            res = (res shl 8) or b
        }
        return res
    }

    suspend fun readUTF(): String {
        val len = readUnsignedShort()
        if (len == 0) return ""
        if (!ensureBytes(len.toLong())) throw java.io.EOFException("Unexpected EOF reading UTF of len $len")
        val bytes = buffer.readByteArray(len.toLong())
        return String(bytes, Charsets.UTF_8)
    }

    suspend fun readExactly(target: ByteArray): Boolean {
        if (!ensureBytes(target.size.toLong())) return false
        buffer.readFully(target)
        return true
    }

    suspend fun readNextAppPackageItem(): AppPackageItem? {
        return try {
            val marker = readByte().toInt()
            if (marker != 1) return null

            val packageName = readUTF()
            val label = readUTF()
            val sourceDir = readUTF()
            val splitCount = readInt()
            val splitDirs = List(splitCount) { readUTF() }
            val dataDir = readUTF()
            val uid = readInt()
            val flags = readByte().toInt()
            val versionName = readUTF()
            val versionCode = readLong()
            val minSdk = readInt()
            val targetSdk = readInt()
            val firstInstallTime = readLong()
            val lastUpdateTime = readLong()
            val installer = readUTF()
            val iconLength = readInt()
            val iconBytes = if (iconLength > 0) {
                ByteArray(iconLength).also { if (!readExactly(it)) return null }
            } else {
                ByteArray(0)
            }

            AppPackageItem(
                packageName = packageName,
                label = label,
                sourceDir = sourceDir,
                splitDirs = splitDirs,
                dataDir = dataDir,
                uid = uid,
                isSystem = (flags and 1) != 0,
                isEnabled = (flags and 2) != 0,
                hasSplits = (flags and 4) != 0,
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = targetSdk,
                firstInstallTime = firstInstallTime,
                lastUpdateTime = lastUpdateTime,
                installer = installer,
                iconBytes = iconBytes
            )
        } catch (e: Exception) {
            AppLogger.e("PKGDUMP_DIAGNOSTIC", ">>> [PKGDUMP_DIAGNOSTIC] PARSE_FAILURE: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    private suspend fun ensureBytes(requiredBytes: Long): Boolean {
        while (buffer.size < requiredBytes && !isEof) {
            val chunk = stream.recv()
            if (chunk == null) {
                isEof = true
                break
            }
            buffer.write(chunk)
        }
        return buffer.size >= requiredBytes
    }
}
