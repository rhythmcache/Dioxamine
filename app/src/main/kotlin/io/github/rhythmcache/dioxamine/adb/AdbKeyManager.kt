// ---------------------------------------------------------------------------
// AdbKeyManager.kt
// Native key generation / regeneration / custom-key loading for adb-kt.
// Mirrors FileKeyProvider's own on-disk format exactly, so files it writes
// are 100% compatible with what FileKeyProvider expects to read back.
// ---------------------------------------------------------------------------

package io.github.rhythmcache.dioxamine.adb

import io.github.rhythmcache.adb.FileKeyProvider
import io.github.rhythmcache.adb.AdbAuth
import io.github.rhythmcache.adb.pairing.PairingIdentity
import io.github.rhythmcache.adb.pairing.buildPairingIdentity
import io.github.rhythmcache.dioxamine.BuildConfig
import io.github.rhythmcache.dioxamine.core.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

sealed class KeyLoadResult {
    data class Success(val fingerprint: String) : KeyLoadResult()
    data class Failure(val message: String) : KeyLoadResult()
}

/**
 * Owns the on-disk adb key pair (private key file + matching .pub file) and
 * the derived TLS PairingIdentity certificate files (identity_cert.pem & identity_key.pem).
 */
class AdbKeyManager(private val keyDir: File) {

    companion object {
        private const val TAG = "AdbKeyManager"
    }

    val keyFile: File get() = File(keyDir, "adbkey")
    val pubKeyFile: File get() = File(keyDir, "adbkey.pub")
    val certFile: File get() = File(keyDir, "identity_cert.pem")
    val keyPemFile: File get() = File(keyDir, "identity_key.pem")

    /** Creates a FileKeyProvider configured with a clean app identity comment (e.g. Pixel_7@Dioxamine). */
    fun createKeyProvider(): FileKeyProvider {
        val model = android.os.Build.MODEL.ifBlank { "Device" }.replace(" ", "_")
        return FileKeyProvider(keyFile = keyFile, pubKeyFile = pubKeyFile, identityComment = "$model@${BuildConfig.APP_NAME}")
    }

    @Volatile
    private var cachedIdentity: PairingIdentity? = null
    private val identityMutex = Mutex()

    /** Generates a fresh 2048-bit RSA key pair and overwrites both files and TLS pairing identity. */
    suspend fun regenerateKey(): KeyLoadResult {
        return try {
            val kp = AdbAuth.generateKey()
            writeKeyPair(kp)
            KeyLoadResult.Success(fingerprint(kp.public as RSAPublicKey))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Key generation failed: ${e.message}", e)
            KeyLoadResult.Failure("Key generation failed: ${e.message}")
        }
    }

    /**
     * Accepts arbitrary bytes the user picked. Parses it, writes it as the
     * new private key file, plus a freshly derived .pub file and TLS pairing identity.
     */
    suspend fun loadCustomKey(rawInput: ByteArray): KeyLoadResult {
        return try {
            val privateKey = parseAnyPrivateKey(rawInput)
                ?: return KeyLoadResult.Failure("Unrecognized key format. Expected PEM or DER RSA private key.")

            val pub = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
            val kf = KeyFactory.getInstance("RSA")
            val publicKey = kf.generatePublic(pub) as RSAPublicKey

            writeKeyPair(KeyPair(publicKey, privateKey))
            KeyLoadResult.Success(fingerprint(publicKey))
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load custom key: ${e.message}", e)
            KeyLoadResult.Failure("Failed to load key: ${e.message}")
        }
    }

    /** Retrieves or loads the stored TLS PairingIdentity from disk (generating & caching it if missing). */
    suspend fun getPairingIdentity(): PairingIdentity? = identityMutex.withLock {
        cachedIdentity?.let { return@withLock it }
        if (!hasKey()) return@withLock null

        return@withLock try {
            if (certFile.exists() && keyPemFile.exists() && certFile.length() > 0 && keyPemFile.length() > 0) {
                val kp = readKeyPairFromDisk() ?: return@withLock null
                val certPem = certFile.readText()
                val privKeyPem = keyPemFile.readText()
                val identity = PairingIdentity(kp, certPem, privKeyPem)
                cachedIdentity = identity
                identity
            } else {
                generateAndSaveIdentity()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read cached TLS identity from disk, regenerating: ${e.message}")
            runCatching { generateAndSaveIdentity() }.getOrNull()
        }
    }

    /** Deletes all ADB key files and stored TLS pairing identity files from disk. */
    fun deleteKey() {
        cachedIdentity = null
        keyFile.delete()
        pubKeyFile.delete()
        certFile.delete()
        keyPemFile.delete()
    }

    /** Returns a human-readable fingerprint of the currently stored key, or null if none exists / unreadable. */
    fun currentFingerprint(): String? {
        if (!keyFile.exists() || keyFile.length() == 0L) return null
        return try {
            val kp = readKeyPairFromDisk() ?: return null
            fingerprint(kp.public as RSAPublicKey)
        } catch (_: Exception) {
            null
        }
    }

    fun hasKey(): Boolean =
        keyFile.exists() && keyFile.length() > 0 &&
        pubKeyFile.exists() && pubKeyFile.length() > 0

    // -----------------------------------------------------------------
    // Internal: writing
    // -----------------------------------------------------------------

    private suspend fun writeKeyPair(kp: KeyPair) {
        cachedIdentity = null
        keyDir.mkdirs()
        keyFile.writeBytes(kp.private.encoded)
        val model = android.os.Build.MODEL.ifBlank { "Device" }.replace(" ", "_")
        val pubBytes = AdbAuth.encodePublicKeyAdb(kp.public as RSAPublicKey, identityComment = "$model@${BuildConfig.APP_NAME}")
        pubKeyFile.writeBytes(pubBytes)
        generateAndSaveIdentity()
    }

    private suspend fun generateAndSaveIdentity(): PairingIdentity {
        val identity = buildPairingIdentity(createKeyProvider())
        certFile.writeText(identity.certPem)
        keyPemFile.writeText(identity.privateKeyPem)
        cachedIdentity = identity
        return identity
    }

    // -----------------------------------------------------------------
    // Internal: reading back what's currently on disk (for fingerprint display)
    // -----------------------------------------------------------------

    private fun readKeyPairFromDisk(): KeyPair? {
        val bytes = keyFile.readBytes()
        val priv = parseAnyPrivateKey(bytes) ?: return null
        val kf = KeyFactory.getInstance("RSA")
        val pub = kf.generatePublic(RSAPublicKeySpec(priv.modulus, priv.publicExponent)) as RSAPublicKey
        return KeyPair(pub, priv)
    }

    // -----------------------------------------------------------------
    // Internal: parsing (PEM PKCS#1/PKCS#8, raw DER PKCS#1/PKCS#8)
    // Same detection strategy as FileKeyProvider so results stay consistent.
    // -----------------------------------------------------------------

    private fun parseAnyPrivateKey(rawBytes: ByteArray): RSAPrivateCrtKey? {
        val kf = KeyFactory.getInstance("RSA")
        return try {
            val text = try { String(rawBytes, Charsets.US_ASCII) } catch (_: Exception) { "" }

            if (text.contains("-----BEGIN")) {
                val cleanBase64 = text.lines()
                    .filter { !it.startsWith("-----") }
                    .joinToString("")
                    .replace("\\s".toRegex(), "")
                val der = Base64.getDecoder().decode(cleanBase64)

                if (text.contains("RSA PRIVATE KEY") || isPkcs1Der(der)) {
                    parsePkcs1PrivateKey(der, kf)
                } else {
                    kf.generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
                }
            } else if (isPkcs1Der(rawBytes)) {
                parsePkcs1PrivateKey(rawBytes, kf)
            } else {
                kf.generatePrivate(PKCS8EncodedKeySpec(rawBytes)) as RSAPrivateCrtKey
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isPkcs1Der(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 0x30.toByte()) return false
        val buf = java.nio.ByteBuffer.wrap(bytes)
        buf.get()
        readDerLength(buf)
        if (buf.remaining() < 3) return false
        if (buf.get() != 0x02.toByte()) return false
        val versionLen = readDerLength(buf)
        if (buf.remaining() < versionLen + 1) return false
        buf.position(buf.position() + versionLen)
        return buf.hasRemaining() && buf.get() == 0x02.toByte()
    }

    private fun parsePkcs1PrivateKey(der: ByteArray, kf: KeyFactory): RSAPrivateCrtKey {
        val buffer = java.nio.ByteBuffer.wrap(der)
        require(buffer.get() == 0x30.toByte()) { "Invalid DER sequence" }
        readDerLength(buffer)
        readDerInteger(buffer) // version
        val modulus = readDerInteger(buffer)
        val publicExponent = readDerInteger(buffer)
        val privateExponent = readDerInteger(buffer)
        val prime1 = readDerInteger(buffer)
        val prime2 = readDerInteger(buffer)
        val exponent1 = readDerInteger(buffer)
        val exponent2 = readDerInteger(buffer)
        val coefficient = readDerInteger(buffer)
        val spec = RSAPrivateCrtKeySpec(
            modulus, publicExponent, privateExponent, prime1, prime2, exponent1, exponent2, coefficient
        )
        return kf.generatePrivate(spec) as RSAPrivateCrtKey
    }

    private fun readDerLength(buf: java.nio.ByteBuffer): Int {
        var len = buf.get().toInt() and 0xFF
        if ((len and 0x80) != 0) {
            val count = len and 0x7F
            require(count != 0) { "Indefinite-length DER encoding is not valid here" }
            len = 0
            for (i in 0 until count) {
                len = (len shl 8) or (buf.get().toInt() and 0xFF)
            }
        }
        return len
    }

    private fun readDerInteger(buf: java.nio.ByteBuffer): BigInteger {
        require(buf.get() == 0x02.toByte()) { "Expected DER Integer tag 0x02" }
        val len = readDerLength(buf)
        val bytes = ByteArray(len)
        buf.get(bytes)
        return BigInteger(bytes)
    }

    // -----------------------------------------------------------------
    // Fingerprint: MD5 of the ADB mincrypt public-key blob (the same
    // binary structure adbd sends to the device), colon-separated hex.
    // Uses encodePublicKeyBlob() directly - the raw crypto identity of
    // the key - rather than encodePublicKeyAdb(), whose output also
    // carries a "user@host" comment that varies per machine and would
    // make the fingerprint non-reproducible if hashed.
    // -----------------------------------------------------------------

    private fun fingerprint(publicKey: RSAPublicKey): String {
        val blob = AdbAuth.encodePublicKeyBlob(publicKey)
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(blob)
        return hash.joinToString(":") { "%02x".format(it) }
    }
}