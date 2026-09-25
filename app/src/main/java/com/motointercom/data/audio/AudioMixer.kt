package com.motointercom.data.audio

import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time PCM audio mixer.
 *
 * Combines up to N simultaneous 16-bit mono audio streams into one output stream.
 * Uses sample-wise summation with symmetric clipping to prevent distortion.
 *
 * Thread-safe: streams can be added/removed while mixing is in progress.
 */
class AudioMixer {

    // Active audio streams keyed by participant ID
    private val streams = ConcurrentHashMap<String, ShortArray>()
    // Reusable short buffers per participant to avoid per-frame allocations
    private val streamShortPool = ConcurrentHashMap<String, ShortArray>()

    // Reusable mixing scratchpad buffers
    private var mixedShorts = ShortArray(AudioCapture.SAMPLES_PER_FRAME)
    private var mixedBytes = ByteArray(AudioCapture.BYTES_PER_FRAME)

    /**
     * Submit a PCM frame from a participant.
     * [id] — unique participant identifier (matches Rider.id)
     * [pcmBytes] — little-endian 16-bit PCM bytes
     */
    fun submitFrame(id: String, pcmBytes: ByteArray) {
        val sampleCount = pcmBytes.size / 2
        var targetShorts = streamShortPool[id]
        if (targetShorts == null || targetShorts.size < sampleCount) {
            targetShorts = ShortArray(sampleCount)
            streamShortPool[id] = targetShorts
        }

        for (i in 0 until sampleCount) {
            targetShorts[i] = ((pcmBytes[i * 2].toInt() and 0xFF) or
                    (pcmBytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        streams[id] = targetShorts
    }

    /**
     * Mix all submitted streams, optionally excluding [excludeId].
     * Used by the HOST to send back mixed audio (excluding the original sender).
     *
     * @return mixed PCM as ByteArray (little-endian 16-bit), or empty if no streams.
     */
    fun mix(excludeId: String? = null): ByteArray {
        val activeEntries = mutableListOf<ShortArray>()
        val it = streams.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.key != excludeId) {
                activeEntries.add(entry.value)
                it.remove()
            }
        }

        if (activeEntries.isEmpty()) return ByteArray(0)

        // Optimization: single talker (most common scenario)
        if (activeEntries.size == 1) {
            val single = activeEntries[0]
            val neededBytes = single.size * 2
            if (mixedBytes.size < neededBytes) {
                mixedBytes = ByteArray(neededBytes)
            }
            for (i in single.indices) {
                val s = single[i].toInt()
                mixedBytes[i * 2] = (s and 0xFF).toByte()
                mixedBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            return mixedBytes.copyOf(neededBytes)
        }

        // Multiple talkers: sum with saturation clipping
        val outputLen = activeEntries.maxOf { it.size }
        if (mixedShorts.size < outputLen) {
            mixedShorts = ShortArray(outputLen)
        }
        mixedShorts.fill(0, 0, outputLen)

        for (stream in activeEntries) {
            for (i in stream.indices) {
                val mixed = mixedShorts[i].toLong() + stream[i].toLong()
                mixedShorts[i] = mixed.coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
            }
        }

        val neededBytes = outputLen * 2
        if (mixedBytes.size < neededBytes) {
            mixedBytes = ByteArray(neededBytes)
        }
        for (i in 0 until outputLen) {
            val s = mixedShorts[i].toInt()
            mixedBytes[i * 2] = (s and 0xFF).toByte()
            mixedBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }

        return mixedBytes.copyOf(neededBytes)
    }

    fun removeStream(id: String) {
        streams.remove(id)
        streamShortPool.remove(id)
    }

    fun clear() {
        streams.clear()
        streamShortPool.clear()
    }

    fun activeStreamCount() = streams.size
}

