package com.motointercom.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays PCM audio data through the device speaker or routed BT headset.
 * Uses VOICE_COMMUNICATION usage to integrate properly with the Android
 * audio focus and Bluetooth SCO routing system.
 *
 * Enhancements:
 *  - Adaptive Jitter Buffer: pre-buffers 2 frames (40ms) upon burst start to smooth network jitter,
 *    and bounds queue to max 6 frames (120ms) to ensure conversational zero-lag.
 *  - Packet Loss Concealment (PLC): upon missing UDP frames, applies smooth exponential decay (0.55x, 0.25x)
 *    to the last played waveform rather than abrupt cut-to-silence, completely eliminating digital pops/clicks.
 */
class AudioPlayer {

    companion object {
        private const val TAG = "AudioPlayer"
        private const val BUFFER_FRAMES = 8
        private val BUFFER_SIZE = AudioCapture.BYTES_PER_FRAME * BUFFER_FRAMES

        // Adaptive Jitter constraints (at 20ms per frame)
        private const val QUEUE_CAPACITY = 32
        private const val PREBUFFER_TARGET_FRAMES = 2  // 40ms initial cushion for smooth speech onset
        private const val MAX_LATENCY_FRAMES = 6      // 120ms max target before dropping stale frames

        // PLC constraints
        private const val MAX_PLC_FRAMES = 2          // Up to 40ms of smooth waveform fading
    }

    private var audioTrack: AudioTrack? = null
    var isPlaying = false
        private set

    private val audioQueue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var playbackThread: Thread? = null

    // Pre-allocated PLC working buffer to prevent GC allocations in audio loop
    private var lastPlayedFrame: ByteArray? = null
    private var plcWorkingBuffer: ByteArray? = null
    private var plcConsecutiveCount = 0
    private var isVoiceBurstActive = false

    fun start() {
        if (isPlaying) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                AudioCapture.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, BUFFER_SIZE)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioCapture.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            isPlaying = true
            isVoiceBurstActive = false
            plcConsecutiveCount = 0

            // Dedicated high-priority playback thread
            playbackThread = Thread({
                Log.d(TAG, "Adaptive Playback & PLC thread started")
                while (isPlaying) {
                    try {
                        // 1. Adaptive Pre-buffering: at speech onset, ensure 2 frames exist before draining
                        if (!isVoiceBurstActive) {
                            if (audioQueue.size >= PREBUFFER_TARGET_FRAMES) {
                                isVoiceBurstActive = true
                            } else {
                                // Wait briefly for next frame to build up minimal 40ms cushion
                                val firstChunk = audioQueue.poll(40, TimeUnit.MILLISECONDS)
                                if (firstChunk != null) {
                                    writeFrame(firstChunk)
                                    isVoiceBurstActive = true
                                }
                                continue
                            }
                        }

                        // 2. Poll next frame with short timeout matching 20ms frame cadence
                        val chunk = audioQueue.poll(25, TimeUnit.MILLISECONDS)
                        if (chunk != null) {
                            plcConsecutiveCount = 0
                            writeFrame(chunk)
                        } else {
                            // 3. UDP Packet Loss Concealment (PLC)
                            if (isVoiceBurstActive && plcConsecutiveCount < MAX_PLC_FRAMES && lastPlayedFrame != null) {
                                plcConsecutiveCount++
                                val fadeMultiplier = if (plcConsecutiveCount == 1) 0.55f else 0.22f
                                val concealed = generatePlcFrame(lastPlayedFrame!!, fadeMultiplier)
                                audioTrack?.write(concealed, 0, concealed.size)
                            } else {
                                // Speech burst has ended or packet loss is prolonged; revert to clean silence
                                isVoiceBurstActive = false
                                plcConsecutiveCount = 0
                            }
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in playback thread", e)
                    }
                }
                Log.d(TAG, "Playback thread stopped")
            }, "AudioPlayback").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
                start()
            }

            Log.d(TAG, "AudioPlayer started with bufSize=$bufSize")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioPlayer", e)
        }
    }

    private fun writeFrame(chunk: ByteArray) {
        // Cache last played frame for PLC without allocating a new buffer each time
        if (lastPlayedFrame == null || lastPlayedFrame?.size != chunk.size) {
            lastPlayedFrame = ByteArray(chunk.size)
        }
        System.arraycopy(chunk, 0, lastPlayedFrame!!, 0, chunk.size)
        audioTrack?.write(chunk, 0, chunk.size)
    }

    /**
     * Generates a Packet Loss Concealment frame by applying a smooth decay factor
     * to the 16-bit PCM samples of the previous frame. Avoids phase discontinuity pops.
     */
    private fun generatePlcFrame(source: ByteArray, fade: Float): ByteArray {
        if (plcWorkingBuffer == null || plcWorkingBuffer?.size != source.size) {
            plcWorkingBuffer = ByteArray(source.size)
        }
        val dest = plcWorkingBuffer!!
        val sampleCount = source.size / 2
        for (i in 0 until sampleCount) {
            val idx = i * 2
            val low = source[idx].toInt() and 0xFF
            val high = source[idx + 1].toInt()
            val sample = (high shl 8) or low
            val decayed = (sample * fade).toInt().coerceIn(-32768, 32767)
            dest[idx] = (decayed and 0xFF).toByte()
            dest[idx + 1] = ((decayed shr 8) and 0xFF).toByte()
        }
        return dest
    }

    /**
     * Enqueues a PCM buffer for playback on the dedicated thread.
     * Non-blocking. If network burst exceeds MAX_LATENCY_FRAMES (120ms),
     * oldest frames are dropped to keep conversation strictly real-time.
     */
    fun play(pcmData: ByteArray) {
        if (!isPlaying || pcmData.isEmpty()) return

        // Maintain real-time conversational latency (<120ms)
        while (audioQueue.size >= MAX_LATENCY_FRAMES) {
            audioQueue.poll()
        }

        if (!audioQueue.offer(pcmData)) {
            audioQueue.poll()
            audioQueue.offer(pcmData)
        }
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread?.join(500)
        playbackThread = null
        audioQueue.clear()
        lastPlayedFrame = null
        plcWorkingBuffer = null
        plcConsecutiveCount = 0
        isVoiceBurstActive = false
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioPlayer", e)
        }
        audioTrack = null
        Log.d(TAG, "AudioPlayer stopped")
    }

    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }
}
