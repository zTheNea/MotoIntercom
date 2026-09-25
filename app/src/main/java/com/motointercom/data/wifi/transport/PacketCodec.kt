package com.motointercom.data.wifi.transport

import android.util.Log
import com.motointercom.data.crypto.SessionCrypto
import com.motointercom.domain.model.Rider
import java.nio.ByteBuffer
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Parsed payload of a received MSG_ROSTER packet, containing the session token,
 * optional AES encryption key, and remote riders.
 */
data class ParsedRoster(
    val sessionToken: ByteArray,
    val encryptionKey: SecretKey?,
    val riders: List<Rider>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedRoster) return false
        if (!sessionToken.contentEquals(other.sessionToken)) return false
        if (encryptionKey != other.encryptionKey) return false
        if (riders != other.riders) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sessionToken.contentHashCode()
        result = 31 * result + (encryptionKey?.hashCode() ?: 0)
        result = 31 * result + riders.hashCode()
        return result
    }
}

/**
 * Parsed payload of a received MSG_MUSIC_FRAME packet.
 */
data class ParsedMusicFrame(
    val senderId: String,
    val sequence: Int,
    val sampleRate: Int,
    val pcm: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedMusicFrame) return false
        if (senderId != other.senderId) return false
        if (sequence != other.sequence) return false
        if (sampleRate != other.sampleRate) return false
        if (!pcm.contentEquals(other.pcm)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = senderId.hashCode()
        result = 31 * result + sequence
        result = 31 * result + sampleRate
        result = 31 * result + pcm.contentHashCode()
        return result
    }
}

/**
 * Parsed payload of a received MSG_MUSIC_CTRL packet.
 */
data class ParsedMusicCtrl(
    val senderId: String,
    val action: Byte,
    val trackTitle: String
)

/**
 * Protocol packet serialization and deserialization for MotoIntercom (Protocol v2).
 *
 * Header:
 * [2B Magic: 0x4D, 0x49 ("MI")]
 * [1B MsgType: 1=HELLO, 2=AUDIO, 3=HEARTBEAT, 4=BYE, 5=ROSTER, 6=MUSIC_FRAME, 7=MUSIC_CTRL]
 * [4B SessionToken]
 * [4B SenderId]
 * ... Payload
 */
object PacketCodec {
    private const val TAG = "PacketCodec"

    const val MAGIC_0: Byte = 0x4D // 'M'
    const val MAGIC_1: Byte = 0x49 // 'I'

    const val MSG_HELLO: Byte = 1
    const val MSG_AUDIO: Byte = 2
    const val MSG_HEARTBEAT: Byte = 3
    const val MSG_BYE: Byte = 4
    const val MSG_ROSTER: Byte = 5
    const val MSG_MUSIC_FRAME: Byte = 6
    const val MSG_MUSIC_CTRL: Byte = 7

    const val MUSIC_ACTION_PLAY: Byte = 1
    const val MUSIC_ACTION_PAUSE: Byte = 2
    const val MUSIC_ACTION_STOP: Byte = 3

    fun padId(id: String): ByteArray {
        val b = id.toByteArray(Charsets.UTF_8)
        return b.copyOf(4)
    }

    fun buildLocalId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { chars.random() }.joinToString("")
    }

    fun buildAudioPacket(
        sequence: Int,
        sessionToken: ByteArray,
        localId: String,
        pcm: ByteArray,
        amplitude: Float,
        encryptionKey: SecretKey?
    ): ByteArray {
        val payload = try {
            if (encryptionKey != null) SessionCrypto.encrypt(pcm, encryptionKey)
            else pcm
        } catch (e: Exception) {
            Log.w(TAG, "Audio encryption failed: ${e.message}")
            pcm
        }
        val buf = ByteBuffer.allocate(16 + payload.size)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.put(MSG_AUDIO)
        buf.put(sessionToken)
        buf.put(padId(localId))
        buf.putShort((sequence and 0xFFFF).toShort())
        buf.putShort(payload.size.toShort())
        buf.put((amplitude * 100).toInt().coerceIn(0, 100).toByte())
        buf.put(payload)
        return buf.array()
    }

    fun buildHelloPacket(sessionToken: ByteArray, localId: String, riderName: String): ByteArray {
        val nameBytes = riderName.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(13 + nameBytes.size)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.put(MSG_HELLO)
        buf.put(sessionToken)
        buf.put(padId(localId))
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        return buf.array()
    }

    fun buildHeartbeatPacket(sessionToken: ByteArray, localId: String): ByteArray {
        val buf = ByteBuffer.allocate(11)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.put(MSG_HEARTBEAT)
        buf.put(sessionToken)
        buf.put(padId(localId))
        return buf.array()
    }

    fun buildByePacket(sessionToken: ByteArray, localId: String): ByteArray {
        val buf = ByteBuffer.allocate(11)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.put(MSG_BYE)
        buf.put(sessionToken)
        buf.put(padId(localId))
        return buf.array()
    }

    fun buildRosterPacket(
        sessionToken: ByteArray,
        localId: String,
        localRiderName: String,
        clients: Collection<RemoteClient>,
        encryptionKey: SecretKey? = null
    ): ByteArray {
        val allRiders = mutableListOf<Triple<String, String, Boolean>>()
        allRiders.add(Triple(localId, localRiderName, true))
        clients.forEach {
            allRiders.add(Triple(it.id, it.name, false))
        }

        val keyBytes = encryptionKey?.encoded ?: ByteArray(0)
        val keyLen = keyBytes.size.coerceIn(0, 255)

        var totalSize = 11 + 1 + keyLen + 1
        val clientMap = clients.associateBy { it.id }
        val riderBytesList = allRiders.map { (id, name, isHost) ->
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val client = clientMap[id]
            val isTalking = client?.isTalking ?: false
            val amp = ((client?.amplitude ?: 0f) * 100).toInt().coerceIn(0, 100).toByte()
            totalSize += 4 + 1 + 1 + 1 + 2 + nameBytes.size
            Triple(Triple(id, isHost, isTalking), amp, nameBytes)
        }

        val buf = ByteBuffer.allocate(totalSize)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.put(MSG_ROSTER)
        buf.put(sessionToken)
        buf.put(padId(localId))
        buf.put(keyLen.toByte())
        if (keyLen > 0) {
            buf.put(keyBytes, 0, keyLen)
        }
        buf.put(allRiders.size.toByte())

        for (item in riderBytesList) {
            val (meta, amp, nameBytes) = item
            val (id, isHost, isTalking) = meta
            buf.put(padId(id))
            buf.put(if (isHost) 1.toByte() else 0.toByte())
            buf.put(if (isTalking) 1.toByte() else 0.toByte())
            buf.put(amp)
            buf.putShort(nameBytes.size.toShort())
            buf.put(nameBytes)
        }

        return buf.array()
    }

    fun parseRosterPacket(data: ByteArray, length: Int, localId: String): ParsedRoster? {
        val headerLen = 11
        if (length < headerLen + 2) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1 || data[2] != MSG_ROSTER) return null

        val sessionToken = data.copyOfRange(3, 7)
        var offset = headerLen
        val keyLen = data[offset++].toInt() and 0xFF
        val encKey: SecretKey? = if (keyLen > 0 && offset + keyLen <= length) {
            val keyBytes = data.copyOfRange(offset, offset + keyLen)
            offset += keyLen
            try {
                SecretKeySpec(keyBytes, "AES")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode SecretKey from roster: ${e.message}")
                null
            }
        } else {
            null
        }

        if (offset >= length) return ParsedRoster(sessionToken, encKey, emptyList())
        val count = data[offset++].toInt() and 0xFF
        val list = mutableListOf<Rider>()

        for (i in 0 until count) {
            if (offset + 7 > length) break
            val rId = String(data, offset, 4, Charsets.UTF_8).trimEnd('\u0000', ' ')
            offset += 4
            val rIsHost = data[offset++] == 1.toByte()
            val rIsTalking = data[offset++] == 1.toByte()
            val rAmp = (data[offset++].toInt() and 0xFF) / 100f
            val rNameLen = ByteBuffer.wrap(data, offset, 2).short.toInt()
            offset += 2
            val actualNameLen = rNameLen.coerceIn(0, length - offset)
            val rName = if (actualNameLen > 0) String(data, offset, actualNameLen, Charsets.UTF_8) else "Rider"
            offset += actualNameLen

            if (rId != localId) {
                list.add(
                    Rider(
                        id = rId,
                        name = rName,
                        isTalking = rIsTalking,
                        signalLevel = rAmp,
                        isConnected = true,
                        isHost = rIsHost
                    )
                )
            }
        }
        return ParsedRoster(sessionToken, encKey, list)
    }

    fun buildMusicFramePacket(
        sequence: Int,
        sessionToken: ByteArray,
        localId: String,
        pcm: ByteArray,
        sampleRate: Int = 16000
    ): ByteArray {
        val buf = ByteBuffer.allocate(17 + pcm.size)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.put(MSG_MUSIC_FRAME)
        buf.put(sessionToken)
        buf.put(padId(localId))
        buf.putShort((sequence and 0xFFFF).toShort())
        buf.putShort((sampleRate and 0xFFFF).toShort())
        buf.putShort(pcm.size.toShort())
        buf.put(pcm)
        return buf.array()
    }

    fun parseMusicFramePacket(data: ByteArray, length: Int): ParsedMusicFrame? {
        if (length < 17) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1 || data[2] != MSG_MUSIC_FRAME) return null
        val senderId = String(data, 7, 4, Charsets.UTF_8).trimEnd('\u0000', ' ')
        val seq = ByteBuffer.wrap(data, 11, 2).short.toInt() and 0xFFFF
        val sampleRate = ByteBuffer.wrap(data, 13, 2).short.toInt() and 0xFFFF
        val pcmLen = ByteBuffer.wrap(data, 15, 2).short.toInt() and 0xFFFF
        val actualPcmLen = pcmLen.coerceIn(0, length - 17)
        val pcm = ByteArray(actualPcmLen)
        System.arraycopy(data, 17, pcm, 0, actualPcmLen)
        return ParsedMusicFrame(senderId, seq, sampleRate, pcm)
    }

    fun buildMusicCtrlPacket(
        sessionToken: ByteArray,
        localId: String,
        action: Byte,
        trackTitle: String
    ): ByteArray {
        val titleBytes = trackTitle.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(14 + titleBytes.size)
        buf.put(MAGIC_0)
        buf.put(MAGIC_1)
        buf.put(MSG_MUSIC_CTRL)
        buf.put(sessionToken)
        buf.put(padId(localId))
        buf.put(action)
        buf.putShort(titleBytes.size.toShort())
        buf.put(titleBytes)
        return buf.array()
    }

    fun parseMusicCtrlPacket(data: ByteArray, length: Int): ParsedMusicCtrl? {
        if (length < 14) return null
        if (data[0] != MAGIC_0 || data[1] != MAGIC_1 || data[2] != MSG_MUSIC_CTRL) return null
        val senderId = String(data, 7, 4, Charsets.UTF_8).trimEnd('\u0000', ' ')
        val action = data[11]
        val titleLen = ByteBuffer.wrap(data, 12, 2).short.toInt() and 0xFFFF
        val actualTitleLen = titleLen.coerceIn(0, length - 14)
        val title = if (actualTitleLen > 0) String(data, 14, actualTitleLen, Charsets.UTF_8) else ""
        return ParsedMusicCtrl(senderId, action, title)
    }
}
