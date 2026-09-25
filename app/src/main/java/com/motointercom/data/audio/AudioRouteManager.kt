package com.motointercom.data.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Comprehensive Audio Routing Manager for MotoIntercom.
 *
 * Supports:
 *  - Wired Headsets & Headphones (3.5mm jack)
 *  - USB-C Headsets & DAC audio adapters
 *  - Bluetooth Headsets & Helmet Intercoms (Cardo, Sena, Scala Rider, AirPods, etc.)
 *  - Automatic fallback to phone Loudspeaker when no headphones are connected
 *  - Manual user toggle between Loudspeaker and Headset
 */
class AudioRouteManager(private val context: Context) {

    companion object {
        private const val TAG = "AudioRouteManager"
        private const val TYPE_BLE_HEADSET_INT = 26
        private const val TYPE_BLE_SPEAKER_INT = 27
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var btHeadset: BluetoothHeadset? = null

    private var isRouting = false
    private var forceSpeaker = false

    private val _hasHeadset = MutableStateFlow(false)
    val hasHeadset: StateFlow<Boolean> = _hasHeadset.asStateFlow()

    private val _isSpeakerActive = MutableStateFlow(false)
    val isSpeakerActive: StateFlow<Boolean> = _isSpeakerActive.asStateFlow()

    private val _currentRouteName = MutableStateFlow("Altavoz")
    val currentRouteName: StateFlow<String> = _currentRouteName.asStateFlow()

    private val _isMultitasking = MutableStateFlow(true)
    val isMultitasking: StateFlow<Boolean> = _isMultitasking.asStateFlow()

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    // ── Audio Device Callback (API 23+) ───────────────────────────────────

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices connected: ${addedDevices?.map { "${it.productName} (type=${it.type})" }}")
            if (isRouting) updateAudioRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            Log.d(TAG, "Audio devices disconnected: ${removedDevices?.map { "${it.productName} (type=${it.type})" }}")
            if (isRouting) updateAudioRoute()
        }
    }

    // ── Bluetooth Headset Profile Listener ────────────────────────────────

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HEADSET) {
                btHeadset = proxy as BluetoothHeadset
                Log.d(TAG, "Bluetooth HEADSET profile proxy connected")
                if (isRouting) updateAudioRoute()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                btHeadset = null
                Log.d(TAG, "Bluetooth HEADSET profile proxy disconnected")
                if (isRouting) updateAudioRoute()
            }
        }
    }

    // ── Broadcast Receiver for Dynamic Hardware Changes ───────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", -1)
                    val name = intent.getStringExtra("name") ?: "Auriculares"
                    Log.d(TAG, "ACTION_HEADSET_PLUG: state=$state ($name)")
                    if (isRouting) updateAudioRoute()
                }

                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    Log.d(TAG, "BT ACTION_CONNECTION_STATE_CHANGED: state=$state")
                    if (isRouting) updateAudioRoute()
                }

                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    Log.d(TAG, "ACTION_SCO_AUDIO_STATE_UPDATED: state=$state")
                }

                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                    Log.d(TAG, "BT adapter state: $state")
                    if (isRouting) updateAudioRoute()
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun initialize() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)

        try {
            btAdapter?.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Bluetooth profile proxy: ${e.message}")
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        Log.d(TAG, "AudioRouteManager initialized")
    }

    fun startRouting() {
        isRouting = true
        forceSpeaker = false
        updateAudioRoute()
    }

    fun stopRouting() {
        isRouting = false
        forceSpeaker = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                stopScoLegacy()
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio route: ${e.message}")
        }
        Log.d(TAG, "Audio routing stopped, reverted to MODE_NORMAL")
    }

    fun setSpeaker(enabled: Boolean) {
        forceSpeaker = enabled
        if (isRouting) {
            updateAudioRoute()
        }
    }

    fun setMultitasking(enabled: Boolean) {
        _isMultitasking.value = enabled
        if (isRouting) {
            updateAudioRoute()
        }
    }

    fun requestSpeechFocus() {
        if (!_isMultitasking.value) return
        if (hasFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest == null) {
                    focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setWillPauseWhenDucked(false)
                        .build()
                }
                val res = audioManager.requestAudioFocus(focusRequest!!)
                hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request speech audio focus: ${e.message}")
        }
    }

    fun abandonSpeechFocus() {
        if (!hasFocus) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon speech audio focus: ${e.message}")
        } finally {
            hasFocus = false
        }
    }

    fun release() {
        stopRouting()
        abandonSpeechFocus()
        try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        try { btAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, btHeadset) } catch (_: Exception) {}
        Log.d(TAG, "AudioRouteManager released")
    }

    // ── Audio Route Computation ───────────────────────────────────────────

    @Synchronized
    fun updateAudioRoute() {
        try {
            // MODE_IN_COMMUNICATION is critical for Bluetooth helmet intercoms (Cardo, Sena, FreedConn)
            // to activate Wideband Speech (mSBC 16kHz) and route the helmet's integrated microphone.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // 1. Check all connected audio output devices
            val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            val btDevice = allOutputs.firstOrNull {
                it.type in listOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_HEARING_AID,
                    TYPE_BLE_HEADSET_INT,
                    TYPE_BLE_SPEAKER_INT
                )
            }

            val wiredDevice = allOutputs.firstOrNull {
                it.type in listOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE
                )
            }

            val hasHeadset = (btDevice != null || wiredDevice != null || hasConnectedBluetoothHeadset())
            _hasHeadset.value = hasHeadset

            // Determine if speaker should be used:
            // - If user manually toggled speaker ON: true
            // - If no headset is connected at all: true (fallback so audio plays on loud speaker instead of quiet phone earpiece)
            // - If headset is connected and user didn't force speaker: false (audio routes to headset)
            val useSpeaker = forceSpeaker || !hasHeadset

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                routeModern(useSpeaker)
            } else {
                routeLegacy(useSpeaker, btDevice, wiredDevice)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateAudioRoute", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun routeModern(useSpeaker: Boolean) {
        val commDevices = audioManager.availableCommunicationDevices

        if (useSpeaker) {
            val speaker = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) {
                val ok = audioManager.setCommunicationDevice(speaker)
                Log.d(TAG, "✓ Route Modern: Speaker set -> $ok")
            }
            _isSpeakerActive.value = true
            _currentRouteName.value = "Altavoz"
        } else {
            // Priority 1: Bluetooth Headset (BLE or SCO)
            // Priority 2: Wired or USB Headset
            // Priority 3: Built-in Speaker fallback
            val target = commDevices.firstOrNull {
                it.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET_INT)
            } ?: commDevices.firstOrNull {
                it.type in listOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_DEVICE
                )
            } ?: commDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

            if (target != null) {
                val ok = audioManager.setCommunicationDevice(target)
                val isSpeaker = target.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                _isSpeakerActive.value = isSpeaker
                _currentRouteName.value = when {
                    isSpeaker -> "Altavoz"
                    target.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET_INT) -> "Casco BT / Auriculares"
                    else -> "Auriculares con cable"
                }
                Log.d(TAG, "✓ Route Modern: ${target.productName} (type=${target.type}, success=$ok)")
            } else {
                audioManager.clearCommunicationDevice()
                _isSpeakerActive.value = false
                _currentRouteName.value = "Auriculares"
            }
        }
    }

    private fun routeLegacy(useSpeaker: Boolean, btDevice: AudioDeviceInfo?, wiredDevice: AudioDeviceInfo?) {
        if (useSpeaker) {
            stopScoLegacy()
            audioManager.isSpeakerphoneOn = true
            _isSpeakerActive.value = true
            _currentRouteName.value = "Altavoz"
            Log.d(TAG, "✓ Route Legacy: Speakerphone = true")
        } else {
            if (btDevice != null || hasConnectedBluetoothHeadset()) {
                audioManager.isSpeakerphoneOn = false
                startScoLegacy()
                _isSpeakerActive.value = false
                _currentRouteName.value = "Casco BT / Auriculares"
                Log.d(TAG, "✓ Route Legacy: Bluetooth SCO started")
            } else if (wiredDevice != null) {
                stopScoLegacy()
                // In Android, isSpeakerphoneOn = false automatically routes communication audio to wired headset
                audioManager.isSpeakerphoneOn = false
                _isSpeakerActive.value = false
                _currentRouteName.value = "Auriculares con cable"
                Log.d(TAG, "✓ Route Legacy: Wired Headset active (isSpeakerphoneOn = false)")
            } else {
                // Fallback to speaker if no headset found
                stopScoLegacy()
                audioManager.isSpeakerphoneOn = true
                _isSpeakerActive.value = true
                _currentRouteName.value = "Altavoz"
                Log.d(TAG, "✓ Route Legacy: Fallback to speakerphone")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startScoLegacy() {
        try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } catch (e: Exception) {
            Log.w(TAG, "startBluetoothSco failed: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun stopScoLegacy() {
        try {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        } catch (_: Exception) {}
    }

    private fun hasConnectedBluetoothHeadset(): Boolean {
        return try {
            val list = btHeadset?.connectedDevices
            list != null && list.isNotEmpty()
        } catch (_: SecurityException) {
            false
        }
    }
}
