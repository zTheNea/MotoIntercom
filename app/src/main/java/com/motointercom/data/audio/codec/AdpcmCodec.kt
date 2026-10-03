package com.motointercom.data.audio.codec

/**
 * Ultra-low latency, zero-allocation IMA-ADPCM 4:1 Audio Codec.
 *
 * Compresses 16-bit linear PCM (16 kHz mono) into 4-bit ADPCM nibbles with an autonomous
 * per-packet header, achieving a 74.4% reduction in packet payload (640 bytes -> 164 bytes).
 *
 * Key Properties:
 *  - Algorithmic Latency: 0.0 ms (sample-by-sample state machine, zero lookahead buffering).
 *  - Zero-GC Hot Path: Reusable work buffers avoid garbage collector pressure.
 *  - Autonomous Packet Resilience: Each 164-byte frame contains its own initial predictor anchor
 *    and step index in the 4-byte header. Lost UDP packets do not corrupt subsequent packet decoding.
 *  - Backward Compatibility: Provides helper methods to identify uncompressed vs ADPCM packets.
 */
object AdpcmCodec {

    const val SAMPLES_PER_FRAME = 320 // 20 ms at 16 kHz
    const val PCM_FRAME_BYTES = SAMPLES_PER_FRAME * 2 // 640 bytes
    const val HEADER_BYTES = 4
    const val ADPCM_DATA_BYTES = SAMPLES_PER_FRAME / 2 // 160 bytes
    const val ADPCM_FRAME_BYTES = HEADER_BYTES + ADPCM_DATA_BYTES // 164 bytes

    const val CODEC_IDENTIFIER: Byte = 0x01 // ADPCM V1

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val STEP_SIZE_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    /**
     * Checks if a payload conforms to the ADPCM frame specification.
     */
    fun isAdpcmFrame(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Boolean {
        if (length != ADPCM_FRAME_BYTES) return false
        if (offset + HEADER_BYTES > data.size) return false
        return data[offset + 3] == CODEC_IDENTIFIER
    }

    /**
     * Encodes 640 bytes of 16-bit PCM (320 samples) into a 164-byte ADPCM packet.
     * Thread-safe; returns a new ByteArray.
     */
    fun encode(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): ByteArray {
        val out = ByteArray(ADPCM_FRAME_BYTES)
        encode(pcm, offset, length, out, 0)
        return out
    }

    /**
     * In-place zero-allocation encode.
     * Writes exactly [ADPCM_FRAME_BYTES] into [out] at [outOffset].
     */
    fun encode(
        pcm: ByteArray,
        pcmOffset: Int,
        pcmLength: Int,
        out: ByteArray,
        outOffset: Int
    ): Int {
        val sampleCount = (pcmLength / 2).coerceAtMost(SAMPLES_PER_FRAME)
        if (sampleCount == 0) return 0

        // Read initial sample for predictor anchor
        val firstSample = ((pcm[pcmOffset].toInt() and 0xFF) or (pcm[pcmOffset + 1].toInt() shl 8)).toShort().toInt()
        var predictor = firstSample
        var index = 0

        // 4-byte Header:
        // [0..1] Initial predictor sample (little-endian short)
        // [2] Initial step index
        // [3] Codec identifier flag (0x01)
        out[outOffset] = (firstSample and 0xFF).toByte()
        out[outOffset + 1] = ((firstSample shr 8) and 0xFF).toByte()
        out[outOffset + 2] = (index and 0xFF).toByte()
        out[outOffset + 3] = CODEC_IDENTIFIER

        var pcmIdx = pcmOffset
        var outIdx = outOffset + HEADER_BYTES

        var i = 0
        while (i < sampleCount) {
            // Encode sample A
            val lowA = pcm[pcmIdx].toInt() and 0xFF
            val highA = pcm[pcmIdx + 1].toInt()
            val sampleA = (highA shl 8) or lowA
            pcmIdx += 2

            val deltaA = encodeSample(sampleA, predictor, index)
            predictor = updatePredictor(predictor, deltaA, index)
            index = updateIndex(index, deltaA)

            // Encode sample B (or pad with 0 if odd number of samples)
            val deltaB: Int
            if (i + 1 < sampleCount) {
                val lowB = pcm[pcmIdx].toInt() and 0xFF
                val highB = pcm[pcmIdx + 1].toInt()
                val sampleB = (highB shl 8) or lowB
                pcmIdx += 2

                deltaB = encodeSample(sampleB, predictor, index)
                predictor = updatePredictor(predictor, deltaB, index)
                index = updateIndex(index, deltaB)
            } else {
                deltaB = 0
            }

            // Pack two 4-bit nibbles into one byte: low nibble = sample A, high nibble = sample B
            out[outIdx++] = ((deltaA and 0x0F) or ((deltaB and 0x0F) shl 4)).toByte()
            i += 2
        }

        return ADPCM_FRAME_BYTES
    }

    /**
     * Decodes a 164-byte ADPCM packet into 640 bytes of 16-bit PCM (320 samples).
     * Thread-safe; returns a new ByteArray.
     */
    fun decode(adpcm: ByteArray, offset: Int = 0, length: Int = adpcm.size - offset): ByteArray {
        val out = ByteArray(PCM_FRAME_BYTES)
        decode(adpcm, offset, length, out, 0)
        return out
    }

    /**
     * In-place zero-allocation decode.
     * Writes exactly [PCM_FRAME_BYTES] into [out] at [outOffset].
     */
    fun decode(
        adpcm: ByteArray,
        adpcmOffset: Int,
        adpcmLength: Int,
        out: ByteArray,
        outOffset: Int
    ): Int {
        if (adpcmLength < HEADER_BYTES) return 0

        // Parse Header
        val lowPred = adpcm[adpcmOffset].toInt() and 0xFF
        val highPred = adpcm[adpcmOffset + 1].toInt()
        var predictor = ((highPred shl 8) or lowPred).toShort().toInt()
        var index = (adpcm[adpcmOffset + 2].toInt() and 0xFF).coerceIn(0, 88)

        var inIdx = adpcmOffset + HEADER_BYTES
        var outIdx = outOffset
        val endInIdx = (adpcmOffset + adpcmLength).coerceAtMost(adpcm.size)

        while (inIdx < endInIdx && outIdx < outOffset + PCM_FRAME_BYTES) {
            val byteVal = adpcm[inIdx++].toInt() and 0xFF
            val deltaA = byteVal and 0x0F
            val deltaB = (byteVal shr 4) and 0x0F

            // Sample A
            predictor = updatePredictor(predictor, deltaA, index)
            index = updateIndex(index, deltaA)
            out[outIdx++] = (predictor and 0xFF).toByte()
            out[outIdx++] = ((predictor shr 8) and 0xFF).toByte()

            if (outIdx >= outOffset + PCM_FRAME_BYTES) break

            // Sample B
            predictor = updatePredictor(predictor, deltaB, index)
            index = updateIndex(index, deltaB)
            out[outIdx++] = (predictor and 0xFF).toByte()
            out[outIdx++] = ((predictor shr 8) and 0xFF).toByte()
        }

        return outIdx - outOffset
    }

    private fun encodeSample(sample: Int, predictor: Int, index: Int): Int {
        val step = STEP_SIZE_TABLE[index]
        var diff = sample - predictor
        var delta = 0

        if (diff < 0) {
            delta = 8
            diff = -diff
        }

        if (diff >= step) {
            delta = delta or 4
            diff -= step
        }
        val stepHalf = step shr 1
        if (diff >= stepHalf) {
            delta = delta or 2
            diff -= stepHalf
        }
        val stepQuarter = step shr 2
        if (diff >= stepQuarter) {
            delta = delta or 1
        }

        return delta
    }

    private fun updatePredictor(predictor: Int, delta: Int, index: Int): Int {
        val step = STEP_SIZE_TABLE[index]
        var vpdiff = step shr 3

        if ((delta and 4) != 0) vpdiff += step
        if ((delta and 2) != 0) vpdiff += (step shr 1)
        if ((delta and 1) != 0) vpdiff += (step shr 2)

        val newPredictor = if ((delta and 8) != 0) {
            predictor - vpdiff
        } else {
            predictor + vpdiff
        }

        return newPredictor.coerceIn(-32768, 32767)
    }

    private fun updateIndex(index: Int, delta: Int): Int {
        val newIndex = index + INDEX_TABLE[delta and 0x0F]
        return newIndex.coerceIn(0, 88)
    }
}
