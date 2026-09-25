package com.motointercom.data.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lightweight AES-CTR encryption for real-time audio packets.
 *
 * Design choices:
 *  - AES-CTR (no padding, constant output size, parallelizable)
 *  - 128-bit key (fast on ARM, sufficient for session-scoped audio)
 *  - Random 16-byte IV prepended to each ciphertext for stateless decryption
 *  - Overhead per packet: 16 bytes (IV) — acceptable at 8kHz/20ms frames (~320B payload)
 *
 * Thread-safe: all methods are stateless and can be called concurrently.
 */
object SessionCrypto {

    private const val ALGORITHM = "AES/CTR/NoPadding"
    private const val KEY_SIZE_BITS = 128

    // ThreadLocal Cipher to avoid expensive JCA Security Provider lookup per packet (50 times/sec per rider)
    private val cipherHolder = ThreadLocal.withInitial {
        Cipher.getInstance(ALGORITHM)
    }

    // Reusable cryptographically strong random generator (thread-safe)
    private val secureRandom = SecureRandom()

    /**
     * Generate a new random AES-128 key for this session.
     * Called once by the HOST when creating a session.
     */
    fun generateSessionKey(): SecretKey {
        return KeyGenerator.getInstance("AES").apply {
            init(KEY_SIZE_BITS, secureRandom)
        }.generateKey()
    }

    /**
     * Serialize a key to a Base64 string for transmission in discovery beacons.
     */
    fun keyToString(key: SecretKey): String =
        Base64.getEncoder().encodeToString(key.encoded)

    /**
     * Deserialize a Base64 string back to a SecretKey.
     */
    fun stringToKey(encoded: String): SecretKey =
        SecretKeySpec(Base64.getDecoder().decode(encoded), "AES")

    /**
     * Encrypt [data] with [key] using AES-CTR.
     * Writes IV (16 bytes) + ciphertext directly into a single allocated array.
     * @return IV (16 bytes) + ciphertext
     */
    fun encrypt(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        key: SecretKey
    ): ByteArray {
        val cipher = cipherHolder.get() ?: Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(16).also { secureRandom.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

        val outputSize = cipher.getOutputSize(length)
        val result = ByteArray(iv.size + outputSize)
        System.arraycopy(iv, 0, result, 0, iv.size)
        cipher.doFinal(data, offset, length, result, iv.size)
        return result
    }

    /**
     * Decrypt [data] (IV + ciphertext) with [key] using AES-CTR.
     * Reads IV directly from the specified [offset] without intermediate array allocations.
     * @return original plaintext
     * @throws javax.crypto.BadPaddingException if key is wrong
     */
    fun decrypt(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
        key: SecretKey
    ): ByteArray {
        if (length < 17) {
            // Too short to contain 16-byte IV + ciphertext, pass through as copy
            val pass = ByteArray(length)
            System.arraycopy(data, offset, pass, 0, length)
            return pass
        }

        val cipher = cipherHolder.get() ?: Cipher.getInstance(ALGORITHM)
        // Direct IV parameter spec from the input buffer offset without array copy
        val ivSpec = IvParameterSpec(data, offset, 16)
        cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)

        // Decrypt directly from the slice
        return cipher.doFinal(data, offset + 16, length - 16)
    }

    /**
     * Backward-compatible convenience overload for full array encryption.
     */
    fun encrypt(data: ByteArray, key: SecretKey): ByteArray = encrypt(data, 0, data.size, key)

    /**
     * Backward-compatible convenience overload for full array decryption.
     */
    fun decrypt(data: ByteArray, key: SecretKey): ByteArray = decrypt(data, 0, data.size, key)
}
