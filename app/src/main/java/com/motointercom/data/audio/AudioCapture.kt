package com.motointercom.data.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Captures raw PCM audio from the device microphone.
 *
 * Uses VOICE_COMMUNICATION source for optimal phone call quality.
 * Applies hardware-accelerated AEC, Noise Suppressor, and AGC when available.
 * Reports real-time voice amplitude for UI visualization.
 */
class AudioCapture(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16000        // 16kHz Wideband HD Voice quality
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val SAMPLES_PER_FRAME = 320    // 20ms frame at 16kHz
        const val BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2  // 16-bit = 2 bytes/sample (640B)

        // VOX silence threshold (0–32767 scale)
        const val VOX_THRESHOLD = 950
        const val VOX_HOLD_FRAMES = 12 // Keep transmitting ~240ms after silence to avoid clipping words
    }

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var captureJob: Job? = null
    private val windFilter = WindNoiseFilter(SAMPLE_RATE, 120f)

    private var lastAmplitudeReportTime = 0L
    private var lastReportedAmplitude = 0f

    /** Called with each captured PCM frame (BYTES_PER_FRAME bytes) */
    var onAudioCaptured: ((ByteArray) -> Unit)? = null

    /** Called with RMS amplitude 0.0–1.0 for UI meters */
    var onAmplitude: ((Float) -> Unit)? = null

    var isCapturing = false
        private set

    // VOX (Voice Activated Transmission) state
    var voxEnabled = false
    private var voxHoldCounter = 0

    fun start() {
        if (isCapturing) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = maxOf(minBuf, BYTES_PER_FRAME * 4)

        try {
            windFilter.reset()
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )

            if (audioRecord!!.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize at ${SAMPLE_RATE}Hz")
                return
            }

            val sessionId = audioRecord!!.audioSessionId

            // Hardware-accelerated DSP effects
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "✓ AEC enabled (echo cancellation)")
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "✓ Noise Suppressor enabled")
                }
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "✓ AGC enabled")
                }
            }

            audioRecord!!.startRecording()
            isCapturing = true
            Log.d(TAG, "Audio capture started (HD Voice) — SR=${SAMPLE_RATE}Hz, frame=${SAMPLES_PER_FRAME}samples/${BYTES_PER_FRAME}B")

            captureJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(SAMPLES_PER_FRAME)
                val byteBuffer = ByteArray(BYTES_PER_FRAME)

                while (isActive && isCapturing) {
                    val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (samplesRead <= 0) continue

                    // 1. Apply Motorcycle Wind & Engine Rumble Filter (High-Pass 120Hz)
                    windFilter.process(buffer, samplesRead)

                    // 2. Calculate RMS amplitude for UI meter
                    val rms = calculateRms(buffer, samplesRead)

                    // Throttle UI amplitude updates to ~20 FPS unless talking state changes
                    val now = System.currentTimeMillis()
                    val isSignificantChange = abs(rms - lastReportedAmplitude) > 0.06f
                    val isVoiceStateCrossing = (rms > 0.04f) != (lastReportedAmplitude > 0.04f)
                    if (now - lastAmplitudeReportTime >= 50L || isSignificantChange || isVoiceStateCrossing) {
                        lastAmplitudeReportTime = now
                        lastReportedAmplitude = rms
                        onAmplitude?.invoke(rms)
                    }

                    // 3. VOX gate — only transmit if filtered voice detected
                    val shouldTransmit = if (voxEnabled) {
                        val hasVoice = (rms * 32767) > VOX_THRESHOLD
                        if (hasVoice) {
                            voxHoldCounter = VOX_HOLD_FRAMES
                            true
                        } else {
                            if (voxHoldCounter > 0) {
                                voxHoldCounter--
                                true
                            } else false
                        }
                    } else true

                    if (shouldTransmit) {
                        // Convert ShortArray to ByteArray (little-endian)
                        for (i in 0 until samplesRead) {
                            val s = buffer[i].toInt()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        onAudioCaptured?.invoke(byteBuffer.copyOf(samplesRead * 2))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio capture", e)
        }
    }

    fun pause() {
        try {
            audioRecord?.stop()
            Log.d(TAG, "Audio capture paused")
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing AudioRecord", e)
        }
    }

    fun resume() {
        try {
            if (isCapturing && audioRecord?.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                audioRecord?.startRecording()
                Log.d(TAG, "Audio capture resumed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming AudioRecord", e)
        }
    }

    fun stop() {
        isCapturing = false
        captureJob?.cancel()
        windFilter.reset()
        releaseEffects()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null
        Log.d(TAG, "Audio capture stopped")
    }

    private fun releaseEffects() {
        aec?.release(); aec = null
        ns?.release(); ns = null
        agc?.release(); agc = null
    }

    private fun calculateRms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        return (sqrt(sum / length) / 32767.0).toFloat().coerceIn(0f, 1f)
    }
}
