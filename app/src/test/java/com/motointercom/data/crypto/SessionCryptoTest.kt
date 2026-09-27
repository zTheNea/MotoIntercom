package com.motointercom.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Arrays

class SessionCryptoTest {

    @Test
    fun `generateSessionKey returns valid AES-128 key`() {
        val key = SessionCrypto.generateSessionKey()
        assertNotNull(key)
        assertEquals("AES", key.algorithm)
        assertEquals(16, key.encoded.size) // 128 bits = 16 bytes
    }

    @Test
    fun `encrypt and decrypt roundtrip preserves original plaintext`() {
        val key = SessionCrypto.generateSessionKey()
        val originalData = "Hello, MotoIntercom Audio Stream!".toByteArray(Charsets.UTF_8)

        val encrypted = SessionCrypto.encrypt(originalData, key)
        val decrypted = SessionCrypto.decrypt(encrypted, key)

        assertArrayEquals(originalData, decrypted)
    }

    @Test
    fun `encrypt with different calls produces different ciphertexts due to random IV`() {
        val key = SessionCrypto.generateSessionKey()
        val originalData = ByteArray(320) { it.toByte() }

        val encrypted1 = SessionCrypto.encrypt(originalData, key)
        val encrypted2 = SessionCrypto.encrypt(originalData, key)

        // The first 16 bytes are IVs, which should differ
        assertFalse("Two encryptions should have different IVs", Arrays.equals(encrypted1, encrypted2))
    }

    @Test
    fun `decrypt with wrong key does not return original plaintext`() {
        val key1 = SessionCrypto.generateSessionKey()
        val key2 = SessionCrypto.generateSessionKey()
        val originalData = "Super secret rider voice message".toByteArray(Charsets.UTF_8)

        val encrypted = SessionCrypto.encrypt(originalData, key1)
        val decryptedWithWrongKey = SessionCrypto.decrypt(encrypted, key2)

        assertFalse("Decrypted with wrong key must not match original", Arrays.equals(originalData, decryptedWithWrongKey))
    }

    @Test
    fun `keyToString and stringToKey roundtrip restores exact key`() {
        val originalKey = SessionCrypto.generateSessionKey()

        val stringRepresentation = SessionCrypto.keyToString(originalKey)
        val restoredKey = SessionCrypto.stringToKey(stringRepresentation)

        assertArrayEquals(originalKey.encoded, restoredKey.encoded)
        assertEquals(originalKey.algorithm, restoredKey.algorithm)
    }

    @Test
    fun `decrypt payload shorter than IV returns original data without crashing`() {
        val key = SessionCrypto.generateSessionKey()
        val shortData = ByteArray(10) { it.toByte() }

        val result = SessionCrypto.decrypt(shortData, key)
        assertArrayEquals(shortData, result)
    }

    @Test
    fun `computeMac and verifyMac successfully validate unmodified data`() {
        val key = SessionCrypto.generateSessionKey()
        val data = "Audio frame payload containing PCM samples".toByteArray(Charsets.UTF_8)

        val mac = SessionCrypto.computeMac(data, key = key)
        assertEquals(SessionCrypto.MAC_LENGTH, mac.size)

        val isValid = SessionCrypto.verifyMac(data, expectedMac = mac, key = key)
        org.junit.Assert.assertTrue(isValid)
    }

    @Test
    fun `verifyMac detects bit-flipping and altered payloads`() {
        val key = SessionCrypto.generateSessionKey()
        val data = "Audio frame payload containing PCM samples".toByteArray(Charsets.UTF_8)

        val mac = SessionCrypto.computeMac(data, key = key)

        // Alter 1 byte
        val altered = data.clone()
        altered[5] = (altered[5].toInt() xor 0xFF).toByte()

        val isValid = SessionCrypto.verifyMac(altered, expectedMac = mac, key = key)
        assertFalse("Altered payload must fail MAC verification", isValid)
    }

    @Test
    fun `verifyMac with wrong key fails verification`() {
        val key1 = SessionCrypto.generateSessionKey()
        val key2 = SessionCrypto.generateSessionKey()
        val data = "Audio frame payload".toByteArray(Charsets.UTF_8)

        val mac = SessionCrypto.computeMac(data, key = key1)
        val isValid = SessionCrypto.verifyMac(data, expectedMac = mac, key = key2)

        assertFalse("MAC verification with different key must fail", isValid)
    }
}
