package com.motointercom.data.wifi.transport

import com.motointercom.domain.model.LinkQuality
import java.net.InetSocketAddress
import kotlin.math.abs

/**
 * Represents a remote intercom participant known to the HOST.
 * Mutable state tracker for active connections in [ClientRegistry].
 */
class RemoteClient(
    val id: String,
    val name: String,
    var address: InetSocketAddress,
    var lastSeen: Long = System.currentTimeMillis(),
    var isTalking: Boolean = false,
    var amplitude: Float = 0f,
    var isConnected: Boolean = true,
    var linkQuality: LinkQuality = LinkQuality.OPTIMA,
    var musicPort: Int = 12348,
    var lastSeqNum: Long = -1L,
    var receivedPackets: Int = 0,
    var expectedPackets: Int = 0,
    var jitterMs: Float = 0f
) {
    private var lastArrivalTime: Long = 0L

    fun recordPacket(seqNum: Long, now: Long = System.currentTimeMillis()) {
        lastSeen = now
        isConnected = true
        if (lastSeqNum >= 0 && seqNum > lastSeqNum) {
            val gap = (seqNum - lastSeqNum - 1).coerceAtLeast(0)
            expectedPackets += (gap + 1).toInt()
        } else if (lastSeqNum < 0) {
            expectedPackets += 1
        }
        lastSeqNum = seqNum
        receivedPackets += 1

        if (lastArrivalTime > 0) {
            val interArrival = now - lastArrivalTime
            val deviation = abs(interArrival - 20L).toFloat()
            jitterMs += (deviation - jitterMs) / 16f
        }
        lastArrivalTime = now

        // Calculate link quality
        val lossRate = if (expectedPackets > 10) {
            val lost = (expectedPackets - receivedPackets).coerceAtLeast(0)
            lost.toFloat() / expectedPackets
        } else 0f

        linkQuality = when {
            lossRate < 0.03f && jitterMs < 45f -> LinkQuality.OPTIMA
            lossRate < 0.12f && jitterMs < 100f -> LinkQuality.BUENA
            lossRate < 0.25f -> LinkQuality.INESTABLE
            else -> LinkQuality.CRITICA
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RemoteClient) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "RemoteClient(id='$id', name='$name', address=$address, isConnected=$isConnected, quality=$linkQuality)"
}
