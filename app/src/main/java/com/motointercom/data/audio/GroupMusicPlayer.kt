package com.motointercom.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Dedicated high-fidelity audio player for shared group music.
 *
 * Runs an independent AudioTrack with USAGE_MEDIA so that Android AudioFlinger
 * mixes music and voice naturally without interfering with voice intercom buffers.
 *
 * Features smooth audio ducking (fading volume to 20% when voice intercom is active).
 */
class GroupMusicPlayer {

    companion object {
        private const val TAG = "GroupMusicPlayer"
        const val MUSIC_SAMPLE_RATE = 16000 // 16kHz wideband audio
        const val SAMPLES_PER_FRAME = 320   // 20ms frame at 16kHz
        const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2 // 16-bit = 2 bytes/sample

        private const val QUEUE_CAPACITY = 32
        private const val JITTER_BUFFER_FRAMES = 6 // ~120ms buffer
        private const val DUCK_RATIO = 0.20f       // Volume reduced to 20% when someone talks
        private const val FADE_STEP = 0.04f        // Smooth volume interpolation per frame
    }

    private var audioTrack: AudioTrack? = null
    var isPlaying = false
        private set

    private val musicQueue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var playbackThread: Thread? = null

    @Volatile
    var isDucked = false
        private set

    @Volatile
    var masterVolume = 0.85f
        private set

    private var currentFadeVolume = 0.85f

    fun start() {
        if (isPlaying) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                MUSIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, BYTES_PER_FRAME * JITTER_BUFFER_FRAMES * 2)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MUSIC_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            isPlaying = true
            currentFadeVolume = masterVolume

            playbackThread = Thread({
                Log.d(TAG, "GroupMusic playback thread started")
                while (isPlaying) {
                    try {
                        val chunk = musicQueue.poll(40, TimeUnit.MILLISECONDS)
                        if (chunk != null) {
                            val processed = applySmoothVolume(chunk)
                            audioTrack?.write(processed, 0, processed.size)
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in music playback loop", e)
                    }
                }
                Log.d(TAG, "GroupMusic playback thread stopped")
            }, "GroupMusicPlayback").apply {
                priority = Thread.NORM_PRIORITY + 1
                isDaemon = true
                start()
            }

            Log.d(TAG, "GroupMusicPlayer initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize GroupMusicPlayer", e)
        }
    }

    fun playFrame(pcmData: ByteArray) {
        if (!isPlaying || pcmData.isEmpty()) return
        if (!musicQueue.offer(pcmData)) {
            // Buffer overflow under high jitter: drop oldest frame
            musicQueue.poll()
            musicQueue.offer(pcmData)
        }
    }

    fun setDucked(ducked: Boolean) {
        this.isDucked = ducked
    }

    fun setMasterVolume(volume: Float) {
        this.masterVolume = volume.coerceIn(0f, 1f)
    }

    fun clear() {
        musicQueue.clear()
    }

    fun stop() {
        isPlaying = false
        playbackThread?.interrupt()
        playbackThread?.join(400)
        playbackThread = null
        musicQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GroupMusicPlayer", e)
        }
        audioTrack = null
        Log.d(TAG, "GroupMusicPlayer stopped")
    }

    /**
     * Smoothly scales PCM 16-bit little-endian samples according to ducking state.
     */
    private fun applySmoothVolume(chunk: ByteArray): ByteArray {
        val targetVolume = if (isDucked) masterVolume * DUCK_RATIO else masterVolume

        if (currentFadeVolume < targetVolume) {
            currentFadeVolume = (currentFadeVolume + FADE_STEP).coerceAtMost(targetVolume)
        } else if (currentFadeVolume > targetVolume) {
            currentFadeVolume = (currentFadeVolume - FADE_STEP).coerceAtLeast(targetVolume)
        }

        val out = ByteArray(chunk.size)
        val vol = currentFadeVolume

        var i = 0
        while (i < chunk.size - 1) {
            val sample = ((chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)).toShort()
            val scaled = (sample * vol).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            out[i] = (scaled.toInt() and 0xFF).toByte()
            out[i + 1] = ((scaled.toInt() shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }
}
