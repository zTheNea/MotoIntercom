package com.motointercom.data.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures internal device audio (Spotify, YouTube, browser, games)
 * using Android 10+ [AudioPlaybackCaptureConfiguration] and [MediaProjection].
 * Converts/resamples captured audio to 16 kHz 16-bit PCM mono (320 samples / 20ms frames)
 * and delivers them to [onFrameAvailable].
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapture(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "SystemAudioCapture"
        const val TARGET_SAMPLE_RATE = 16000
        const val TARGET_FRAME_SAMPLES = 320 // 20ms @ 16kHz mono
    }

    private var audioRecord: AudioRecord? = null
    private var mediaProjection: MediaProjection? = null
    private var captureJob: Job? = null
    @Volatile
    private var isCapturing = false

    @SuppressLint("MissingPermission")
    fun startCapture(
        projection: MediaProjection,
        onFrameAvailable: (ShortArray) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isCapturing) stopCapture()
        this.mediaProjection = projection

        // Android 14+ (API 34+) requires callback registration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    stopCapture()
                }
            }, null)
        }

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        var record: AudioRecord? = null
        var captureRate = TARGET_SAMPLE_RATE
        var isStereo = false

        // Attempt 1: 16000 Hz Mono directly
        try {
            val format16k = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(TARGET_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val rec = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(format16k)
                .setBufferSizeInBytes(minBuf.coerceAtLeast(TARGET_FRAME_SAMPLES * 4 * 2))
                .build()
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                record = rec
                captureRate = TARGET_SAMPLE_RATE
                isStereo = false
                Log.d(TAG, "Initialized AudioRecord with direct 16kHz mono")
            } else {
                rec.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "16kHz mono capture not supported, trying 48kHz fallback: ${e.message}")
        }

        // Attempt 2: Fallback to 48000 Hz Stereo (native to Android audio flinger)
        if (record == null) {
            try {
                captureRate = 48000
                isStereo = true
                val format48k = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minBuf = AudioRecord.getMinBufferSize(
                    48000,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val rec = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format48k)
                    .setBufferSizeInBytes(minBuf.coerceAtLeast(48000 / 50 * 2 * 2 * 2))
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    record = rec
                    Log.d(TAG, "Initialized AudioRecord with 48kHz stereo fallback")
                } else {
                    rec.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "48kHz stereo fallback failed: ${e.message}")
            }
        }

        // Attempt 3: Fallback to 44100 Hz Stereo
        if (record == null) {
            try {
                captureRate = 44100
                isStereo = true
                val format44k = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minBuf = AudioRecord.getMinBufferSize(
                    44100,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val rec = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format44k)
                    .setBufferSizeInBytes(minBuf.coerceAtLeast(44100 / 50 * 2 * 2 * 2))
                    .build()
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    record = rec
                    Log.d(TAG, "Initialized AudioRecord with 44.1kHz stereo fallback")
                } else {
                    rec.release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "44.1kHz stereo fallback failed: ${e.message}", e)
            }
        }

        if (record == null) {
            onError("No se pudo inicializar la captura de audio interno en este dispositivo.")
            return
        }

        audioRecord = record
        isCapturing = true

        try {
            record.startRecording()
            Log.d(TAG, "AudioPlaybackCapture recording started (rate=$captureRate, stereo=$isStereo)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord: ${e.message}", e)
            onError("Error al iniciar grabación: ${e.message}")
            stopCapture()
            return
        }

        val activeRecord = record
        captureJob = scope.launch(Dispatchers.IO) {
            val capturedRate = captureRate
            val capturedStereo = isStereo

            if (capturedRate == TARGET_SAMPLE_RATE && !capturedStereo) {
                val buffer = ShortArray(TARGET_FRAME_SAMPLES)
                while (isActive && isCapturing) {
                    var readTotal = 0
                    while (readTotal < TARGET_FRAME_SAMPLES && isActive && isCapturing) {
                        val read = activeRecord.read(buffer, readTotal, TARGET_FRAME_SAMPLES - readTotal)
                        if (read > 0) {
                            readTotal += read
                        } else if (read < 0) {
                            Log.e(TAG, "AudioRecord read error: $read")
                            break
                        }
                    }
                    if (readTotal == TARGET_FRAME_SAMPLES) {
                        onFrameAvailable(buffer.copyOf())
                    }
                }
            } else {
                val samplesPer20ms = capturedRate / 50
                val channels = if (capturedStereo) 2 else 1
                val rawBuffer = ShortArray(samplesPer20ms * channels)
                val monoInput = ShortArray(samplesPer20ms)
                val outFrame = ShortArray(TARGET_FRAME_SAMPLES)

                while (isActive && isCapturing) {
                    var readTotal = 0
                    val targetRaw = rawBuffer.size
                    while (readTotal < targetRaw && isActive && isCapturing) {
                        val read = activeRecord.read(rawBuffer, readTotal, targetRaw - readTotal)
                        if (read > 0) {
                            readTotal += read
                        } else if (read < 0) {
                            break
                        }
                    }

                    if (readTotal == targetRaw) {
                        // Downmix to mono if captured in stereo
                        if (capturedStereo) {
                            for (i in 0 until samplesPer20ms) {
                                val left = rawBuffer[i * 2].toInt()
                                val right = rawBuffer[i * 2 + 1].toInt()
                                monoInput[i] = ((left + right) / 2).coerceIn(-32768, 32767).toShort()
                            }
                        } else {
                            System.arraycopy(rawBuffer, 0, monoInput, 0, samplesPer20ms)
                        }

                        // Linear resampling to 16kHz 320 samples
                        val step = samplesPer20ms.toDouble() / TARGET_FRAME_SAMPLES.toDouble()
                        for (i in 0 until TARGET_FRAME_SAMPLES) {
                            val srcPos = i * step
                            val srcIdx = srcPos.toInt()
                            val frac = srcPos - srcIdx
                            val s1 = monoInput[srcIdx.coerceAtMost(samplesPer20ms - 1)].toDouble()
                            val s2 = monoInput[(srcIdx + 1).coerceAtMost(samplesPer20ms - 1)].toDouble()
                            val interpolated = (s1 + frac * (s2 - s1)).toInt()
                            outFrame[i] = interpolated.coerceIn(-32768, 32767).toShort()
                        }

                        onFrameAvailable(outFrame.copyOf())
                    }
                }
            }
        }
    }

    fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null
        Log.d(TAG, "SystemAudioCapture stopped")
    }
}
