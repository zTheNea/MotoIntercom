package com.motointercom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.net.Uri
import com.motointercom.MainActivity
import com.motointercom.data.audio.AudioCapture
import com.motointercom.data.audio.AudioMixer
import com.motointercom.data.audio.AudioPlayer
import com.motointercom.data.audio.AudioRouteManager
import com.motointercom.data.audio.GroupMusicPlayer
import com.motointercom.data.audio.MusicStreamer
import com.motointercom.data.audio.SystemAudioCapture
import com.motointercom.data.crypto.SessionCrypto
import com.motointercom.data.wifi.NetworkUtils
import com.motointercom.data.wifi.SessionDiscovery
import com.motointercom.data.wifi.UdpAudioTransport
import com.motointercom.data.wifi.transport.PacketCodec
import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.Rider
import com.motointercom.domain.model.SessionRole
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts the intercom audio engine.
 *
 * Keeps running while the app is in background (important while riding).
 * Manages: AudioCapture → UdpTransport → AudioMixer → AudioPlayer → AudioRouteManager
 */
class IntercomService : Service() {

    companion object {
        private const val TAG = "IntercomService"
        private const val CHANNEL_ID = "motointercom_ch"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.motointercom.action.STOP"
        const val EXTRA_ROLE = "extra_role"
        const val EXTRA_HOST_IP = "extra_host_ip"
        const val EXTRA_RIDER_NAME = "extra_rider_name"
    }

    // ── Core components ──────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var capture: AudioCapture
    private lateinit var player: AudioPlayer
    private lateinit var mixer: AudioMixer
    private lateinit var audioRouteManager: AudioRouteManager
    private lateinit var musicPlayer: GroupMusicPlayer
    private lateinit var musicStreamer: MusicStreamer
    private var systemAudioCapture: SystemAudioCapture? = null
    private var transport: UdpAudioTransport? = null
    private var voiceDuckJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ── Observables ──────────────────────────────────────────────────────

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isPttActive = MutableStateFlow(false)
    val isPttActive: StateFlow<Boolean> = _isPttActive.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _connectedClients = MutableStateFlow(1)
    val connectedClients: StateFlow<Int> = _connectedClients.asStateFlow()

    private val _riders = MutableStateFlow<List<Rider>>(emptyList())
    val riders: StateFlow<List<Rider>> = _riders.asStateFlow()

    private val _sessionTerminated = MutableStateFlow(false)
    val sessionTerminated: StateFlow<Boolean> = _sessionTerminated.asStateFlow()

    private val _reconnectionState = MutableStateFlow(ReconnectionState())
    val reconnectionState: StateFlow<ReconnectionState> = _reconnectionState.asStateFlow()

    private val _musicTrack = MutableStateFlow<String?>(null)
    val musicTrack: StateFlow<String?> = _musicTrack.asStateFlow()

    private val _isMusicPlaying = MutableStateFlow(false)
    val isMusicPlaying: StateFlow<Boolean> = _isMusicPlaying.asStateFlow()

    private val _isMusicHost = MutableStateFlow(false)
    val isMusicHost: StateFlow<Boolean> = _isMusicHost.asStateFlow()

    private val _musicSharerName = MutableStateFlow<String?>(null)
    val musicSharerName: StateFlow<String?> = _musicSharerName.asStateFlow()

    private val _isSystemAudioActive = MutableStateFlow(false)
    val isSystemAudioActive: StateFlow<Boolean> = _isSystemAudioActive.asStateFlow()

    private val _musicVolume = MutableStateFlow(0.85f)
    val musicVolume: StateFlow<Float> = _musicVolume.asStateFlow()

    val isSpeakerActive: StateFlow<Boolean> get() = audioRouteManager.isSpeakerActive
    val hasHeadset: StateFlow<Boolean> get() = audioRouteManager.hasHeadset
    val currentRouteName: StateFlow<String> get() = audioRouteManager.currentRouteName
    val isMultitaskingActive: StateFlow<Boolean> get() = audioRouteManager.isMultitasking

    // ── Binder ───────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService(): IntercomService = this@IntercomService
    }
    private val binder = LocalBinder()

    private var discovery: SessionDiscovery? = null

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        capture = AudioCapture(serviceScope)
        player = AudioPlayer()
        mixer = AudioMixer()
        audioRouteManager = AudioRouteManager(this)
        audioRouteManager.initialize()
        musicPlayer = GroupMusicPlayer()
        musicStreamer = MusicStreamer(serviceScope)
        createNotificationChannel()
        Log.d(TAG, "IntercomService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received, concluding intercom session")
            stopIntercom()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val initialTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), initialTypes)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        val role = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getSerializableExtra(EXTRA_ROLE, SessionRole::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getSerializableExtra(EXTRA_ROLE) as? SessionRole
        } ?: SessionRole.CLIENT
        val hostIp = intent?.getStringExtra(EXTRA_HOST_IP) ?: ""
        val riderName = intent?.getStringExtra(EXTRA_RIDER_NAME) ?: "Anfitrión"

        startIntercom(role, hostIp, riderName)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: Application closed/swiped from recents. Concluding session.")
        stopIntercom()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopIntercom()
        audioRouteManager.release()
        serviceScope.cancel()
        super.onDestroy()
        Log.d(TAG, "IntercomService destroyed")
    }

    // ── Intercom Engine ──────────────────────────────────────────────────

    private fun startIntercom(role: SessionRole, hostIp: String, riderName: String) {
        Log.d(TAG, "Starting intercom engine — role=$role, hostIp=$hostIp, rider=$riderName")
        if (transport != null) {
            stopIntercom()
        }
        _sessionTerminated.value = false
        _reconnectionState.value = ReconnectionState()

        acquireLocks()

        val isHost = role == SessionRole.HOST
        // Host generates the master session token and AES-128 key.
        // Client connects with initial empty credentials and adopts the host's credentials via MSG_ROSTER.
        val token = if (isHost) ByteArray(4).also { SecureRandom().nextBytes(it) } else ByteArray(4)
        val encKey: SecretKey? = if (isHost) SessionCrypto.generateSessionKey() else null

        transport = UdpAudioTransport(
            scope = serviceScope,
            isHost = isHost,
            localRiderName = riderName,
            sessionToken = token,
            encryptionKey = encKey
        ).apply {
            onAudioReceived = { senderId, pcm, amp ->
                if (amp > 0.03f) notifyVoiceActivity()
                mixer.submitFrame(senderId, pcm)
                val mixed = mixer.mix()
                if (mixed.isNotEmpty()) {
                    player.play(mixed)
                }
                updateRiderTalking(senderId, amp)
            }
            onMusicFrameReceived = { senderId, pcm, sampleRate ->
                musicPlayer.playFrame(pcm)
            }
            onMusicCtrlReceived = { senderId, action, trackTitle ->
                when (action) {
                    PacketCodec.MUSIC_ACTION_PLAY -> {
                        _musicTrack.value = trackTitle
                        _isMusicPlaying.value = true
                        val isLocal = (senderId == transport?.localId)
                        _isMusicHost.value = isLocal
                        _musicSharerName.value = if (isLocal) {
                            "Tú"
                        } else {
                            _riders.value.find { it.id == senderId }?.name ?: "Otro integrante"
                        }
                    }
                    PacketCodec.MUSIC_ACTION_PAUSE -> {
                        _isMusicPlaying.value = false
                    }
                    PacketCodec.MUSIC_ACTION_STOP -> {
                        _isMusicPlaying.value = false
                        _musicTrack.value = null
                        _musicSharerName.value = null
                        _isMusicHost.value = false
                        musicPlayer.clear()
                    }
                }
            }
            onRosterUpdated = { updatedRiders ->
                _riders.value = updatedRiders
                _connectedClients.value = updatedRiders.size + 1
                Log.d(TAG, "Roster updated: ${updatedRiders.map { it.name }} | total=${_connectedClients.value}")
            }
            onClientJoined = { clientId, name ->
                Log.d(TAG, "Client joined: $name ($clientId)")
            }
            onClientLeft = { clientId ->
                Log.d(TAG, "Client left: $clientId")
            }
            onReconnectionStateChanged = { state ->
                _reconnectionState.value = state
                Log.d(TAG, "Reconnection state changed: $state")
            }
            onSessionClosedByHost = {
                Log.w(TAG, "Host ended session or went unreachable. Concluding local session.")
                _sessionTerminated.value = true
                serviceScope.launch(Dispatchers.Main) {
                    stopIntercom()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        player.start()
        musicPlayer.start()

        // Wire musicStreamer -> transport & local loopback
        musicStreamer.onFrameProduced = { pcm ->
            transport?.sendMusicFrame(pcm)
            musicPlayer.playFrame(pcm)
        }
        musicStreamer.onControlChanged = { action, title ->
            transport?.sendMusicCtrl(action, title)
            if (action == PacketCodec.MUSIC_ACTION_STOP) {
                _musicTrack.value = null
                _isMusicPlaying.value = false
                _isMusicHost.value = false
                _musicSharerName.value = null
                transport?.setLocalMusicActive(false)
            } else {
                _musicTrack.value = title
                _isMusicPlaying.value = (action == PacketCodec.MUSIC_ACTION_PLAY)
                _isMusicHost.value = true
                _musicSharerName.value = "Tú"
                transport?.setLocalMusicActive(true)
            }
        }

        // Automatically route to headset if connected (BT/wired/USB), otherwise loudspeaker
        audioRouteManager.startRouting()

        if (role == SessionRole.HOST) {
            transport?.startHost()
            val effectiveIp = if (hostIp.isNotBlank()) hostIp else NetworkUtils.getLocalIpAddress(this)
            discovery = SessionDiscovery(this, serviceScope).apply {
                startBroadcasting(riderName, effectiveIp) {
                    _connectedClients.value
                }
            }
        } else {
            transport?.startClient(hostIp)
        }

        // Wire microphone → transport
        capture.onAudioCaptured = { pcm ->
            val canTransmit = when {
                _isMuted.value -> false
                capture.voxEnabled -> true
                _isPttActive.value -> true
                else -> false
            }
            if (canTransmit) {
                notifyVoiceActivity()
                transport?.sendAudio(pcm, _amplitude.value)
            }
        }
        capture.onAmplitude = { amp ->
            _amplitude.value = amp
        }
        capture.start()
    }

    private val riderResetJobs = ConcurrentHashMap<String, Job>()

    private fun updateRiderTalking(senderId: String, amp: Float) {
        val isTalking = amp > 0.04f
        val current = _riders.value
        val rider = current.find { it.id == senderId } ?: return

        if (rider.isTalking != isTalking || (isTalking && Math.abs(rider.signalLevel - amp) > 0.08f)) {
            _riders.value = current.map {
                if (it.id == senderId) it.copy(isTalking = isTalking, signalLevel = amp)
                else it
            }
        }

        riderResetJobs[senderId]?.cancel()
        if (isTalking) {
            riderResetJobs[senderId] = serviceScope.launch {
                delay(400)
                _riders.value = _riders.value.map {
                    if (it.id == senderId) it.copy(isTalking = false, signalLevel = 0f)
                    else it
                }
            }
        }
    }

    fun resetTermination() {
        _sessionTerminated.value = false
        _reconnectionState.value = ReconnectionState()
    }

    fun stopIntercom() {
        Log.d(TAG, "stopIntercom called")
        _sessionTerminated.value = true
        riderResetJobs.values.forEach { it.cancel() }
        riderResetJobs.clear()
        voiceDuckJob?.cancel()
        voiceDuckJob = null
        stopSystemAudioSharing()
        musicStreamer.stop()
        musicPlayer.stop()
        capture.stop()
        discovery?.stopBroadcasting()
        discovery = null
        transport?.stop()
        transport = null
        mixer.clear()
        player.stop()
        audioRouteManager.stopRouting()
        _riders.value = emptyList()
        _connectedClients.value = 1
        _reconnectionState.value = ReconnectionState()
        _musicTrack.value = null
        _isMusicPlaying.value = false
        releaseLocks()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground error: ${e.message}")
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MotoIntercom:IntercomWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(4 * 60 * 60 * 1000L) // 4 hours safety timeout
                    Log.d(TAG, "✓ Partial WakeLock acquired for ride")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WakeLock: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(mode, "MotoIntercom:IntercomWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                    Log.d(TAG, "✓ Low-latency WifiLock acquired (mode=$mode)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "WifiLock released")
            }
        } catch (_: Exception) {}
        wifiLock = null
    }

    private fun notifyVoiceActivity() {
        musicPlayer.setDucked(true)
        audioRouteManager.requestSpeechFocus()
        voiceDuckJob?.cancel()
        voiceDuckJob = serviceScope.launch(Dispatchers.Default) {
            delay(600)
            musicPlayer.setDucked(false)
            audioRouteManager.abandonSpeechFocus()
        }
    }

    // ── Controls ─────────────────────────────────────────────────────────

    fun playDemoMusic() {
        if (_isMusicPlaying.value && !_isMusicHost.value) {
            Log.w(TAG, "Cannot play demo: ${_musicSharerName.value} is already sharing music in the room")
            return
        }
        stopSystemAudioSharing()
        _musicSharerName.value = "Tú"
        transport?.setLocalMusicActive(true)
        musicStreamer.playDemo()
    }

    fun playUriMusic(uri: Uri, title: String) {
        if (_isMusicPlaying.value && !_isMusicHost.value) {
            Log.w(TAG, "Cannot play audio file: ${_musicSharerName.value} is already sharing music in the room")
            return
        }
        stopSystemAudioSharing()
        _musicSharerName.value = "Tú"
        transport?.setLocalMusicActive(true)
        musicStreamer.playUri(this, uri, title)
    }

    fun startSystemAudioSharing(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "AudioPlaybackCapture requires Android 10+")
            return
        }
        if (_isMusicPlaying.value && !_isMusicHost.value) {
            Log.w(TAG, "Cannot share system audio: ${_musicSharerName.value} is already sharing music in the room")
            return
        }
        if (_isMusicHost.value) {
            musicStreamer.stop()
        }

        // In Android 14+ (API 34+), foreground service must declare MEDIA_PROJECTION before getMediaProjection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val projectionTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), projectionTypes)
            } catch (e: Exception) {
                Log.w(TAG, "Could not update MEDIA_PROJECTION foreground type: ${e.message}")
            }
        }

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        val projection = projectionManager?.getMediaProjection(resultCode, data)
        if (projection == null) {
            Log.e(TAG, "Failed to acquire MediaProjection")
            return
        }

        if (systemAudioCapture == null) {
            systemAudioCapture = SystemAudioCapture(serviceScope)
        }

        val title = "Spotify / Audio de Apps"
        _musicTrack.value = title
        _isMusicPlaying.value = true
        _isMusicHost.value = true
        _musicSharerName.value = "Tú"
        _isSystemAudioActive.value = true
        transport?.setLocalMusicActive(true)

        transport?.sendMusicCtrl(PacketCodec.MUSIC_ACTION_PLAY, title)

        systemAudioCapture?.startCapture(
            projection = projection,
            onFrameAvailable = { pcmShorts ->
                // Little-endian byte conversion for 16-bit PCM
                val bytes = ByteArray(pcmShorts.size * 2)
                for (i in pcmShorts.indices) {
                    val s = pcmShorts[i].toInt()
                    bytes[i * 2] = (s and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                transport?.sendMusicFrame(bytes)
            },
            onError = { errMsg ->
                Log.e(TAG, "SystemAudioCapture error: $errMsg")
                stopSystemAudioSharing()
            }
        )
    }

    fun stopSystemAudioSharing() {
        if (_isSystemAudioActive.value) {
            systemAudioCapture?.stopCapture()
            _isSystemAudioActive.value = false
            _isMusicPlaying.value = false
            _musicTrack.value = null
            _musicSharerName.value = null
            _isMusicHost.value = false
            transport?.setLocalMusicActive(false)
            transport?.sendMusicCtrl(PacketCodec.MUSIC_ACTION_STOP, "")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val normalTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), normalTypes)
                } catch (_: Exception) {}
            }
        }
    }

    fun pauseMusic() {
        if (!_isMusicHost.value) return
        if (_isSystemAudioActive.value) {
            // For system audio, pause stops capture
            stopSystemAudioSharing()
            return
        }
        musicStreamer.pause()
    }

    fun resumeMusic() {
        if (!_isMusicHost.value) return
        if (!_isSystemAudioActive.value) {
            musicStreamer.resume()
        }
    }

    fun stopMusic() {
        if (!_isMusicHost.value) return
        stopSystemAudioSharing()
        musicStreamer.stop()
        transport?.setLocalMusicActive(false)
        _musicSharerName.value = null
        _isMusicHost.value = false
        _isMusicPlaying.value = false
        _musicTrack.value = null
    }

    fun setMusicVolume(vol: Float) {
        _musicVolume.value = vol
        musicPlayer.setMasterVolume(vol)
    }

    fun setMultitasking(enabled: Boolean) {
        audioRouteManager.setMultitasking(enabled)
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted && !capture.voxEnabled) {
            capture.pause()
        } else {
            capture.resume()
        }
    }

    fun setPttActive(active: Boolean) {
        _isPttActive.value = active
    }

    fun setVox(enabled: Boolean) {
        capture.voxEnabled = enabled
    }

    fun setSpeaker(on: Boolean) {
        audioRouteManager.setSpeaker(on)
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MotoIntercom",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Intercomunicador de moto activo"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, IntercomService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🏍️ MotoIntercom Activo")
            .setContentText("Intercomunicador funcionando en segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Finalizar Sesión",
                stopPendingIntent
            )
            .build()
    }
}
