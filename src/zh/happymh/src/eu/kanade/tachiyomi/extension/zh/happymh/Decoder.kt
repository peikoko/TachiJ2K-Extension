package eu.kanade.tachiyomi.extension.zh.happymh

import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class Decoder {

    // scandec.wasm -> decrypt()
    fun decodeScans(encryptedScans: String): String {
        val buf = encryptedScans.toByteArray()
        val digest = sha256(SECRET.toByteArray() + buf.copyOfRange(0, 8) + DOMAIN.toByteArray())
        val off1 = (digest[0].toInt() and 0xFF) % 24 + 8
        val off2 = (digest[1].toInt() and 0xFF) % 24 + 8
        val off3 = (digest[2].toInt() and 0xFF) % 24 + 8

        val key = encryptedScans.substring(off1 + 8, off1 + 72).decodeHex()
        val nonce = encryptedScans.substring(off1 + 72 + off2, off1 + 72 + off2 + 32).decodeHex()
        val ciphertext = runCatching {
            Base64.decode(encryptedScans.substring(off1 + 72 + off2 + 32 + off3), Base64.DEFAULT)
        }.getOrNull() ?: error("Failed to decode scan data")

        val state = ByteArray(52)
        key.copyInto(state, 0)
        nonce.copyInto(state, 32)

        val plain = ByteArray(ciphertext.size)
        for (i in ciphertext.indices step 32) {
            writeIntBigEndian(state, 48, i / 32)
            val keystream = sha256(state)
            val blockSize = minOf(32, ciphertext.size - i)
            for (j in 0 until blockSize) {
                plain[i + j] = (ciphertext[i + j].toInt() xor keystream[j].toInt()).toByte()
            }
        }

        if (!plain.startsWith("SC01".toByteArray())) error("Decrypting scans failed")
        return String(zlibDecompress(plain.copyOfRange(4, plain.size)), Charsets.UTF_8)
    }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    private fun zlibDecompress(data: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(data), Inflater(true)).use { it.readBytes() }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "Invalid hex string" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun writeIntBigEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    companion object {
        private const val SECRET = "PRO_SCAN_SECRET_20260712_watching_you_DEBUG"
        private const val DOMAIN = "happymh.com"

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            if (size < prefix.size) return false
            for (i in prefix.indices) if (this[i] != prefix[i]) return false
            return true
        }
    }
}
