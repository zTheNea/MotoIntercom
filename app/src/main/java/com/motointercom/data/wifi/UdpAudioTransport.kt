package com.motointercom.data.wifi

import android.util.Log
import com.motointercom.data.audio.codec.AdpcmCodec
import com.motointercom.data.crypto.SessionCrypto
import com.motointercom.data.wifi.transport.ClientRegistry
import com.motointercom.data.wifi.transport.PacketCodec
import com.motointercom.data.wifi.transport.ReconnectionManager
import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.Rider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey

/**
 * UDP-based real-time audio and control transport orchestrator over local WiFi/Hotspot.
 */
class UdpAudioTransport(
    private val scope: CoroutineScope,
    private val isHost: Boolean,
    private val localRiderName: String,
    var sessionToken: ByteArray = ByteArray(4),
    var encryptionKey: SecretKey? = null
) {
    companion object {
        private const val TAG = "UdpAudioTransport"
        const val VOICE_PORT = 12346
        const val MUSIC_PORT = 12348
        const val AUDIO_PORT = VOICE_PORT // Compatibility alias
        private const val MAX_PACKET = 1500
    }

    private var voiceSocket: DatagramSocket? = null
    private var musicSocket: DatagramSocket? = null
    private var voiceReceiveJob: Job? = null
    private var musicReceiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var sweepJob: Job? = null
    private var voiceSendChannel: Channel<ByteArray>? = null
    private var voiceSendJob: Job? = null
    private var musicSendChannel: Channel<ByteArray>? = null
    private var musicSendJob: Job? = null

    private val clientRegistry = ClientRegistry()
    private val reconnectionManager = ReconnectionManager()
    private val voiceEncoder = AdpcmCodec.Encoder()
    private var hasAdoptedToken: Boolean = isHost

    var currentMusicSenderId: String? = null
        private set
    var currentMusicSenderLastSeen: Long = 0L
        private set

    private var hostAddress: InetAddress? = null
    private var cachedHostIp: String = ""
    val localId: String = PacketCodec.buildLocalId()
    private val sequence = AtomicInteger(0)
    private val musicSequence = AtomicInteger(0)

    // ── Callbacks ────────────────────────────────────────────────────────

    var onAudioReceived: ((senderId: String, pcm: ByteArray, amplitude: Float) -> Unit)? = null
    var onMusicFrameReceived: ((senderId: String, pcm: ByteArray, sampleRate: Int, sequence: Int) -> Unit)? = null
    var onMusicCtrlReceived: ((senderId: String, action: Byte, trackTitle: String) -> Unit)? = null
    var onRosterUpdated: ((riders: List<Rider>) -> Unit)? = null
    var onClientJoined: ((clientId: String, name: String) -> Unit)? = null
    var onClientLeft: ((clientId: String) -> Unit)? = null
    var onSessionClosedByHost: (() -> Unit)? = null
    var onReconnectionStateChanged: ((state: ReconnectionState) -> Unit)? = null

    // ── Lifecycle ────────────────────────────────────────────────────────

    fun startHost(): Boolean {
        return try {
            stop(notifyPeers = false)
            voiceSocket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = MAX_PACKET * 8
                bind(InetSocketAddress("0.0.0.0", VOICE_PORT))
            }
            musicSocket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = MAX_PACKET * 16
                bind(InetSocketAddress("0.0.0.0", MUSIC_PORT))
            }
            Log.d(TAG, "HOST sockets active: Voice on :$VOICE_PORT, Music on :$MUSIC_PORT | localId=$localId | name=$localRiderName")
            startVoiceReceiving()
            startMusicReceiving()
            startVoiceSender()
            startMusicSender()
            startHostSweepAndRosterJob()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting host sockets", e)
            false
        }
    }

    fun setLocalMusicActive(active: Boolean) {
        if (isHost) {
            currentMusicSenderId = if (active) localId else null
            currentMusicSenderLastSeen = if (active) System.currentTimeMillis() else 0L
        }
    }

    fun startClient(hostIp: String): Boolean {
        return try {
            stop(notifyPeers = false)
            cachedHostIp = hostIp
            voiceSocket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = MAX_PACKET * 8
                bind(InetSocketAddress(0))
            }
            musicSocket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = MAX_PACKET * 16
                try {
                    bind(InetSocketAddress(MUSIC_PORT))
                } catch (_: Exception) {
                    bind(InetSocketAddress(0))
                }
            }
            hostAddress = InetAddress.getByName(hostIp)
            Log.d(TAG, "CLIENT sockets active: Voice port=${voiceSocket?.localPort} -> host:$VOICE_PORT, Music port=${musicSocket?.localPort} -> host:$MUSIC_PORT | localId=$localId")

            startVoiceReceiving()
            startMusicReceiving()
            startVoiceSender()
            startMusicSender()

            reconnectionManager.reset()
            reconnectionManager.onReconnectionStateChanged = { state -> onReconnectionStateChanged?.invoke(state) }
            reconnectionManager.onSessionClosedByHost = { onSessionClosedByHost?.invoke() }

            // Immediately burst 3 HELLO handshakes to announce client to host without delay
            scope.launch(Dispatchers.IO) {
                repeat(3) {
                    sendHello()
                    delay(80)
                }
            }

            heartbeatJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val keepRunning = reconnectionManager.tick(
                        now = System.currentTimeMillis(),
                        onPerformBurst = { triggerReconnectBurst() },
                        onSendHeartbeat = {
                            if (!hasAdoptedToken) {
                                sendHello()
                            } else {
                                sendHeartbeat()
                            }
                        }
                    )
                    if (!keepRunning) break
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting client socket", e)
            false
        }
    }

    private suspend fun triggerReconnectBurst() {
        try {
            if (cachedHostIp.isNotBlank()) {
                try {
                    hostAddress = InetAddress.getByName(cachedHostIp)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not resolve host IP during reconnect: ${e.message}")
                }
            }
            // Keep existing socket alive and burst HELLO to re-sync with host
            repeat(3) {
                sendHello()
                delay(120)
            }
            if (hasAdoptedToken) {
                sendHeartbeat()
                sendMusicPing()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in reconnect burst", e)
        }
    }

    var isCompressionEnabled: Boolean = true

    fun sendAudio(pcm: ByteArray, amplitude: Float = 0f) {
        val payload = if (isCompressionEnabled && pcm.size == AdpcmCodec.PCM_FRAME_BYTES) {
            voiceEncoder.encode(pcm)
        } else {
            pcm
        }
        val packet = PacketCodec.buildAudioPacket(
            sequence = sequence.getAndIncrement(),
            sessionToken = sessionToken,
            localId = localId,
            pcm = payload,
            amplitude = amplitude,
            encryptionKey = encryptionKey
        )
        voiceSendChannel?.trySend(packet)
    }

    private fun startVoiceSender() {
        voiceSendJob?.cancel()
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        voiceSendChannel = channel
        voiceSendJob = scope.launch(Dispatchers.IO) {
            try {
                for (packet in channel) {
                    dispatchRaw(packet)
                }
            } catch (_: Exception) {}
        }
    }

    private fun startMusicSender() {
        musicSendJob?.cancel()
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        musicSendChannel = channel
        musicSendJob = scope.launch(Dispatchers.IO) {
            try {
                for (packet in channel) {
                    dispatchMusicRaw(packet)
                }
            } catch (_: Exception) {}
        }
    }

    fun sendMusicFrame(pcm: ByteArray, sampleRate: Int = 16000) {
        val seq = musicSequence.getAndIncrement() and 0xFFFF
        val packet = PacketCodec.buildMusicFramePacket(
            sequence = seq,
            sessionToken = sessionToken,
            localId = localId,
            pcm = pcm,
            sampleRate = sampleRate
        )
        musicSendChannel?.trySend(packet)
    }

    fun sendMusicCtrl(action: Byte, trackTitle: String) {
        scope.launch(Dispatchers.IO) {
            val packet = PacketCodec.buildMusicCtrlPacket(
                sessionToken = sessionToken,
                localId = localId,
                action = action,
                trackTitle = trackTitle
            )
            dispatchMusicRaw(packet)
        }
    }

    fun sendMusicPing() {
        if (!isHost) {
            val packet = PacketCodec.buildMusicCtrlPacket(
                sessionToken = sessionToken,
                localId = localId,
                action = PacketCodec.MUSIC_ACTION_PING,
                trackTitle = ""
            )
            musicSendChannel?.trySend(packet)
        }
    }

    fun connectedClientCount(): Int = if (isHost) clientRegistry.count() + 1 else 1

    fun getRemoteRiders(): List<Rider> = clientRegistry.getRemoteRiders()

    fun stop(notifyPeers: Boolean = true) {
        if (notifyPeers) {
            if (!isHost) {
                try { repeat(3) { sendByeSync() } } catch (_: Exception) {}
            } else {
                try { repeat(3) { broadcastByeSync() } } catch (_: Exception) {}
            }
        }
        voiceReceiveJob?.cancel()
        musicReceiveJob?.cancel()
        heartbeatJob?.cancel()
        sweepJob?.cancel()
        voiceSendJob?.cancel()
        voiceSendJob = null
        voiceSendChannel?.close()
        voiceSendChannel = null
        musicSendJob?.cancel()
        musicSendJob = null
        musicSendChannel?.close()
        musicSendChannel = null
        voiceSocket?.close()
        voiceSocket = null
        musicSocket?.close()
        musicSocket = null
        clientRegistry.clear()
        voiceEncoder.reset()
        Log.d(TAG, "UdpAudioTransport stopped (notifyPeers=$notifyPeers)")
    }

    // ── Dedicated Voice Receiving & Processing (Port 12346) ─────────────

    private fun startVoiceReceiving() {
        voiceReceiveJob?.cancel()
        voiceReceiveJob = scope.launch(Dispatchers.IO) {
            val recvBuffer = ByteArray(MAX_PACKET)
            val packet = DatagramPacket(recvBuffer, recvBuffer.size)

            while (isActive) {
                try {
                    val s = voiceSocket ?: break
                    s.receive(packet)
                    processVoiceIncoming(packet)
                } catch (e: Exception) {
                    if (isActive && voiceSocket?.isClosed == false) {
                        Log.w(TAG, "Voice receive error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun processVoiceIncoming(packet: DatagramPacket) {
        if (packet.length < 11) return

        val data = packet.data
        if (data[0] != PacketCodec.MAGIC_0 || data[1] != PacketCodec.MAGIC_1) return

        val msgType = data[2]
        val packetToken = data.copyOfRange(3, 7)
        val senderId = String(data, 7, 4, Charsets.UTF_8).trimEnd('\u0000', ' ')
        if (senderId == localId) return

        if (isHost) {
            // MSG_HELLO is an introduction from a new client who does not yet have the room's token.
            // For all other packet types, enforce strict token matching.
            if (msgType != PacketCodec.MSG_HELLO && !packetToken.contentEquals(sessionToken)) {
                Log.w(TAG, "Host rejected voice packet (type=$msgType) with invalid session token from ${packet.address.hostAddress}")
                return
            }
        } else {
            // Client side:
            // Any packet received from the host marks that host is alive and reachable!
            reconnectionManager.onPacketReceivedFromHost()

            // Allow MSG_ROSTER through before token adoption so the client can acquire room credentials.
            if (hasAdoptedToken) {
                if (!packetToken.contentEquals(sessionToken)) {
                    Log.w(TAG, "Client rejected voice packet (type=$msgType) with invalid session token from ${packet.address.hostAddress}")
                    return
                }
            } else {
                if (msgType != PacketCodec.MSG_ROSTER) {
                    Log.d(TAG, "Client awaiting initial MSG_ROSTER with token, ignoring packet type=$msgType")
                    return
                }
            }
        }

        when (msgType) {
            PacketCodec.MSG_HELLO -> {
                if (isHost && packet.length >= 13) {
                    val nameLen = ByteBuffer.wrap(data, 11, 2).short.toInt()
                    val actualLen = nameLen.coerceIn(0, packet.length - 13)
                    val riderName = if (actualLen > 0) String(data, 13, actualLen, Charsets.UTF_8) else "Rider"
                    val clientAddr = InetSocketAddress(packet.address, packet.port)

                    val (isNew, _) = clientRegistry.registerOrUpdate(senderId, riderName, clientAddr)
                    if (isNew) {
                        onClientJoined?.invoke(senderId, riderName)
                    }
                    onRosterUpdated?.invoke(getRemoteRiders())

                    // Immediately respond directly to this client with current room credentials & roster
                    val rosterPacket = PacketCodec.buildRosterPacket(
                        sessionToken = sessionToken,
                        localId = localId,
                        localRiderName = localRiderName,
                        clients = clientRegistry.all(),
                        encryptionKey = encryptionKey
                    )
                    try {
                        // Send burst of 3 to ensure reliability over WiFi UDP
                        voiceSocket?.send(DatagramPacket(rosterPacket, rosterPacket.size, clientAddr))
                        voiceSocket?.send(DatagramPacket(rosterPacket, rosterPacket.size, clientAddr))
                        voiceSocket?.send(DatagramPacket(rosterPacket, rosterPacket.size, clientAddr))
                        Log.d(TAG, "Sent direct MSG_ROSTER burst to $riderName @ $clientAddr")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error sending direct roster response to $riderName: ${e.message}")
                    }

                    broadcastRoster()
                }
            }

            PacketCodec.MSG_AUDIO -> {
                if (packet.length >= 16) {
                    val seq = ByteBuffer.wrap(data, 11, 2).short.toInt() and 0xFFFF
                    val pcmLen = ByteBuffer.wrap(data, 13, 2).short.toInt()
                    val ampByte = data[15].toInt() and 0xFF
                    val amp = ampByte / 100f

                    if (pcmLen > 0 && pcmLen <= packet.length - 16) {
                        val payload = try {
                            if (encryptionKey != null) {
                                SessionCrypto.decrypt(data, 16, pcmLen, encryptionKey!!)
                            } else {
                                val raw = ByteArray(pcmLen)
                                System.arraycopy(data, 16, raw, 0, pcmLen)
                                raw
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Audio decryption failed from $senderId: ${e.message}")
                            return
                        }

                        val pcm = if (AdpcmCodec.isAdpcmFrame(payload)) {
                            AdpcmCodec.decode(payload)
                        } else {
                            payload
                        }

                        if (isHost) {
                            clientRegistry.get(senderId)?.apply {
                                recordPacket(seq.toLong())
                                address = InetSocketAddress(packet.address, packet.port)
                                isTalking = amp > 0.08f
                                amplitude = amp
                                if (!isConnected) {
                                    isConnected = true
                                    onRosterUpdated?.invoke(getRemoteRiders())
                                }
                            }
                            onAudioReceived?.invoke(senderId, pcm, amp)
                            relayVoiceToOthers(senderId, data, packet.length)
                        } else {
                            onAudioReceived?.invoke(senderId, pcm, amp)
                        }
                    }
                }
            }

            PacketCodec.MSG_HEARTBEAT -> {
                if (isHost) {
                    clientRegistry.get(senderId)?.apply {
                        lastSeen = System.currentTimeMillis()
                        address = InetSocketAddress(packet.address, packet.port)
                        if (!isConnected) {
                            isConnected = true
                            onRosterUpdated?.invoke(getRemoteRiders())
                        }
                    }
                }
            }

            PacketCodec.MSG_BYE -> {
                if (isHost) {
                    val removed = clientRegistry.remove(senderId)
                    if (removed != null) {
                        if (currentMusicSenderId == senderId) {
                            currentMusicSenderId = null
                            currentMusicSenderLastSeen = 0L
                            val stopPacket = PacketCodec.buildMusicCtrlPacket(sessionToken, localId, PacketCodec.MUSIC_ACTION_STOP, "")
                            dispatchMusicRaw(stopPacket)
                            onMusicCtrlReceived?.invoke(localId, PacketCodec.MUSIC_ACTION_STOP, "")
                        }
                        onClientLeft?.invoke(senderId)
                        onRosterUpdated?.invoke(getRemoteRiders())
                        broadcastRoster()
                    }
                } else {
                    Log.d(TAG, "Host ended session (MSG_BYE from host $senderId)")
                    reconnectionManager.reset()
                    onReconnectionStateChanged?.invoke(ReconnectionState(isReconnecting = false))
                    onSessionClosedByHost?.invoke()
                }
            }

            PacketCodec.MSG_ROSTER -> {
                if (!isHost) {
                    val parsed = PacketCodec.parseRosterPacket(data, packet.length, localId)
                    if (parsed != null) {
                        val isFirstAdoption = !hasAdoptedToken
                        if (!hasAdoptedToken || !sessionToken.contentEquals(parsed.sessionToken)) {
                            sessionToken = parsed.sessionToken
                            hasAdoptedToken = true
                            Log.d(TAG, "Client adopted session token from host")
                            if (isFirstAdoption) {
                                sendMusicPing()
                            }
                        }
                        if (parsed.encryptionKey != null && (encryptionKey == null || !hasAdoptedToken)) {
                            encryptionKey = parsed.encryptionKey
                            Log.d(TAG, "Client adopted AES encryption key from host")
                        }
                        onRosterUpdated?.invoke(parsed.riders)
                    }
                }
            }
        }
    }

    // ── Dedicated Music Receiving & Processing (Port 12348) ────────────

    private fun startMusicReceiving() {
        musicReceiveJob?.cancel()
        musicReceiveJob = scope.launch(Dispatchers.IO) {
            val recvBuffer = ByteArray(MAX_PACKET)
            val packet = DatagramPacket(recvBuffer, recvBuffer.size)

            while (isActive) {
                try {
                    val s = musicSocket ?: break
                    s.receive(packet)
                    processMusicIncoming(packet)
                } catch (e: Exception) {
                    if (isActive && musicSocket?.isClosed == false) {
                        Log.w(TAG, "Music receive error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun processMusicIncoming(packet: DatagramPacket) {
        if (packet.length < 11) return

        val data = packet.data
        if (data[0] != PacketCodec.MAGIC_0 || data[1] != PacketCodec.MAGIC_1) return

        val msgType = data[2]
        val packetToken = data.copyOfRange(3, 7)
        val senderId = String(data, 7, 4, Charsets.UTF_8).trimEnd('\u0000', ' ')
        if (senderId == localId) return

        if (!hasAdoptedToken && !isHost) return
        if (!packetToken.contentEquals(sessionToken)) return

        when (msgType) {
            PacketCodec.MSG_MUSIC_FRAME -> {
                val parsed = PacketCodec.parseMusicFramePacket(data, packet.length)
                if (parsed != null) {
                    if (isHost) {
                        clientRegistry.get(senderId)?.musicPort = packet.port
                        val now = System.currentTimeMillis()
                        // Exclusive music relay: only relay frames from the current active DJ
                        if (currentMusicSenderId == null || currentMusicSenderId == senderId || (now - currentMusicSenderLastSeen > 5000L)) {
                            currentMusicSenderId = senderId
                            currentMusicSenderLastSeen = now
                            relayMusicToOthers(senderId, data, packet.length)
                            onMusicFrameReceived?.invoke(parsed.senderId, parsed.pcm, parsed.sampleRate, parsed.sequence)
                        }
                    } else {
                        onMusicFrameReceived?.invoke(parsed.senderId, parsed.pcm, parsed.sampleRate, parsed.sequence)
                    }
                }
            }

            PacketCodec.MSG_MUSIC_CTRL -> {
                val parsed = PacketCodec.parseMusicCtrlPacket(data, packet.length)
                if (parsed != null) {
                    if (isHost) {
                        clientRegistry.get(senderId)?.musicPort = packet.port
                        if (parsed.action == PacketCodec.MUSIC_ACTION_PING) {
                            Log.d(TAG, "Registered client $senderId music port=${packet.port} via ping")
                            return
                        }
                        val now = System.currentTimeMillis()
                        when (parsed.action) {
                            PacketCodec.MUSIC_ACTION_PLAY -> {
                                if (currentMusicSenderId != null &&
                                    currentMusicSenderId != senderId &&
                                    (now - currentMusicSenderLastSeen < 5000L)) {
                                    Log.w(TAG, "Exclusive music sharing: Rejected PLAY from $senderId because $currentMusicSenderId is active DJ")
                                    return
                                }
                                currentMusicSenderId = senderId
                                currentMusicSenderLastSeen = now
                            }
                            PacketCodec.MUSIC_ACTION_STOP -> {
                                if (currentMusicSenderId == senderId || currentMusicSenderId == null) {
                                    currentMusicSenderId = null
                                    currentMusicSenderLastSeen = 0L
                                }
                            }
                            PacketCodec.MUSIC_ACTION_PAUSE -> {
                                if (currentMusicSenderId == senderId) {
                                    currentMusicSenderLastSeen = now
                                }
                            }
                        }
                        relayMusicToOthers(senderId, data, packet.length)
                    }
                    onMusicCtrlReceived?.invoke(parsed.senderId, parsed.action, parsed.trackTitle)
                }
            }
        }
    }

    // ── Host Periodic Sweep & Roster Broadcast ───────────────────────────

    private fun startHostSweepAndRosterJob() {
        sweepJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                val changed = clientRegistry.sweep(System.currentTimeMillis()) { evictedId ->
                    if (currentMusicSenderId == evictedId) {
                        currentMusicSenderId = null
                        currentMusicSenderLastSeen = 0L
                        val stopPacket = PacketCodec.buildMusicCtrlPacket(sessionToken, localId, PacketCodec.MUSIC_ACTION_STOP, "")
                        dispatchMusicRaw(stopPacket)
                        onMusicCtrlReceived?.invoke(localId, PacketCodec.MUSIC_ACTION_STOP, "")
                    }
                    onClientLeft?.invoke(evictedId)
                }
                if (changed) {
                    onRosterUpdated?.invoke(getRemoteRiders())
                }
                broadcastRoster()
            }
        }
    }

    private fun broadcastRoster() {
        if (!isHost) return
        val rosterPacket = PacketCodec.buildRosterPacket(
            sessionToken = sessionToken,
            localId = localId,
            localRiderName = localRiderName,
            clients = clientRegistry.all(),
            encryptionKey = encryptionKey
        )
        dispatchRaw(rosterPacket)
    }

    private fun relayVoiceToOthers(excludeId: String, data: ByteArray, length: Int) {
        val sock = voiceSocket ?: return
        clientRegistry.all().forEach { client ->
            if (client.id != excludeId) {
                try {
                    sock.send(DatagramPacket(data, length, client.address))
                } catch (e: Exception) {
                    Log.w(TAG, "Voice relay to ${client.name} failed: ${e.message}")
                }
            }
        }
    }

    private fun relayMusicToOthers(excludeId: String, data: ByteArray, length: Int) {
        val sock = musicSocket ?: return
        clientRegistry.all().forEach { client ->
            if (client.id != excludeId) {
                try {
                    sock.send(DatagramPacket(data, length, client.address.address, client.musicPort))
                } catch (e: Exception) {
                    Log.w(TAG, "Music relay to ${client.name} failed: ${e.message}")
                }
            }
        }
    }

    // ── Packet Dispatch ──────────────────────────────────────────────────

    private fun sendHello() {
        val packet = PacketCodec.buildHelloPacket(sessionToken, localId, localRiderName)
        dispatchRaw(packet)
    }

    private fun sendHeartbeat() {
        val packet = PacketCodec.buildHeartbeatPacket(sessionToken, localId)
        dispatchRaw(packet)
    }

    private fun sendByeSync() {
        val raw = PacketCodec.buildByePacket(sessionToken, localId)
        val sock = voiceSocket ?: return
        hostAddress?.let { addr ->
            try {
                sock.send(DatagramPacket(raw, raw.size, addr, VOICE_PORT))
            } catch (_: Exception) {}
        }
    }

    private fun broadcastByeSync() {
        val raw = PacketCodec.buildByePacket(sessionToken, localId)
        val sock = voiceSocket ?: return
        clientRegistry.all().forEach { client ->
            try {
                sock.send(DatagramPacket(raw, raw.size, client.address))
            } catch (_: Exception) {}
        }
    }

    /** Dispatches voice and signaling packets through the voiceSocket (Port 12346) */
    private fun dispatchRaw(data: ByteArray) {
        val sock = voiceSocket ?: return
        if (isHost) {
            clientRegistry.all().forEach { client ->
                try {
                    sock.send(DatagramPacket(data, data.size, client.address))
                } catch (e: Exception) {
                    Log.w(TAG, "Voice send to ${client.name} failed: ${e.message}")
                }
            }
        } else {
            hostAddress?.let { addr ->
                try {
                    sock.send(DatagramPacket(data, data.size, addr, VOICE_PORT))
                } catch (e: Exception) {
                    Log.w(TAG, "Voice send to host failed: ${e.message}")
                }
            }
        }
    }

    /** Dispatches music packets exclusively through the musicSocket (Port 12348) */
    private fun dispatchMusicRaw(data: ByteArray) {
        val sock = musicSocket ?: return
        if (isHost) {
            clientRegistry.all().forEach { client ->
                try {
                    sock.send(DatagramPacket(data, data.size, client.address.address, client.musicPort))
                } catch (e: Exception) {
                    Log.w(TAG, "Music send to ${client.name} failed: ${e.message}")
                }
            }
        } else {
            hostAddress?.let { addr ->
                try {
                    sock.send(DatagramPacket(data, data.size, addr, MUSIC_PORT))
                } catch (e: Exception) {
                    Log.w(TAG, "Music send to host failed: ${e.message}")
                }
            }
        }
    }
}
