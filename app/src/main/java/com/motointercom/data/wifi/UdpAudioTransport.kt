package com.motointercom.data.wifi

import android.util.Log
import com.motointercom.data.crypto.SessionCrypto
import com.motointercom.data.wifi.transport.ClientRegistry
import com.motointercom.data.wifi.transport.PacketCodec
import com.motointercom.data.wifi.transport.ReconnectionManager
import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.Rider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
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
        const val AUDIO_PORT = 12346
        private const val MAX_PACKET = 1500
    }

    private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var sweepJob: Job? = null

    private val clientRegistry = ClientRegistry()
    private val reconnectionManager = ReconnectionManager()
    private var hasAdoptedToken: Boolean = isHost

    var currentMusicSenderId: String? = null
        private set
    var currentMusicSenderLastSeen: Long = 0L
        private set

    private var hostAddress: InetAddress? = null
    private var cachedHostIp: String = ""
    val localId: String = PacketCodec.buildLocalId()
    private var sequence: Int = 0
    private var musicSequence: Int = 0

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

    fun startHost() {
        try {
            stop(notifyPeers = false)
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = MAX_PACKET * 8
                bind(InetSocketAddress("0.0.0.0", AUDIO_PORT))
            }
            Log.d(TAG, "HOST UDP socket active on :$AUDIO_PORT | localId=$localId | name=$localRiderName")
            startReceiving()
            startHostSweepAndRosterJob()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting host socket", e)
        }
    }

    fun setLocalMusicActive(active: Boolean) {
        if (isHost) {
            currentMusicSenderId = if (active) localId else null
            currentMusicSenderLastSeen = if (active) System.currentTimeMillis() else 0L
        }
    }

    fun startClient(hostIp: String) {
        try {
            stop(notifyPeers = false)
            cachedHostIp = hostIp
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = MAX_PACKET * 8
                bind(InetSocketAddress(0))
            }
            hostAddress = InetAddress.getByName(hostIp)
            Log.d(TAG, "CLIENT UDP socket active on port ${socket?.localPort} → host=$hostIp:$AUDIO_PORT | localId=$localId | name=$localRiderName")

            startReceiving()

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
        } catch (e: Exception) {
            Log.e(TAG, "Error starting client socket", e)
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in reconnect burst", e)
        }
    }

    fun sendAudio(pcm: ByteArray, amplitude: Float = 0f) {
        scope.launch(Dispatchers.IO) {
            val packet = PacketCodec.buildAudioPacket(
                sequence = sequence++,
                sessionToken = sessionToken,
                localId = localId,
                pcm = pcm,
                amplitude = amplitude,
                encryptionKey = encryptionKey
            )
            dispatchRaw(packet)
        }
    }

    fun sendMusicFrame(pcm: ByteArray, sampleRate: Int = 16000) {
        val seq = synchronized(this) {
            val s = musicSequence
            musicSequence = (musicSequence + 1) and 0xFFFF
            s
        }
        scope.launch(Dispatchers.IO) {
            val packet = PacketCodec.buildMusicFramePacket(
                sequence = seq,
                sessionToken = sessionToken,
                localId = localId,
                pcm = pcm,
                sampleRate = sampleRate
            )
            dispatchRaw(packet)
        }
    }

    fun sendMusicCtrl(action: Byte, trackTitle: String) {
        scope.launch(Dispatchers.IO) {
            val packet = PacketCodec.buildMusicCtrlPacket(
                sessionToken = sessionToken,
                localId = localId,
                action = action,
                trackTitle = trackTitle
            )
            dispatchRaw(packet)
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
        receiveJob?.cancel()
        heartbeatJob?.cancel()
        sweepJob?.cancel()
        socket?.close()
        socket = null
        clientRegistry.clear()
        Log.d(TAG, "UdpAudioTransport stopped (notifyPeers=$notifyPeers)")
    }

    // ── Receiving & Processing ───────────────────────────────────────────

    private fun startReceiving() {
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) {
            val recvBuffer = ByteArray(MAX_PACKET)
            val packet = DatagramPacket(recvBuffer, recvBuffer.size)

            while (isActive) {
                try {
                    val s = socket ?: break
                    s.receive(packet)
                    processIncoming(packet)
                } catch (e: Exception) {
                    if (isActive && socket?.isClosed == false) {
                        Log.w(TAG, "Receive error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun processIncoming(packet: DatagramPacket) {
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
                Log.w(TAG, "Host rejected packet (type=$msgType) with invalid session token from ${packet.address.hostAddress}")
                return
            }
        } else {
            // Client side:
            // Any packet received from the host marks that host is alive and reachable!
            reconnectionManager.onPacketReceivedFromHost()

            // Allow MSG_ROSTER through before token adoption so the client can acquire room credentials.
            if (hasAdoptedToken) {
                if (!packetToken.contentEquals(sessionToken)) {
                    Log.w(TAG, "Client rejected packet (type=$msgType) with invalid session token from ${packet.address.hostAddress}")
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
                        socket?.send(DatagramPacket(rosterPacket, rosterPacket.size, clientAddr))
                        socket?.send(DatagramPacket(rosterPacket, rosterPacket.size, clientAddr))
                        socket?.send(DatagramPacket(rosterPacket, rosterPacket.size, clientAddr))
                        Log.d(TAG, "Sent direct MSG_ROSTER burst to $riderName @ $clientAddr")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error sending direct roster response to $riderName: ${e.message}")
                    }

                    broadcastRoster()
                }
            }

            PacketCodec.MSG_AUDIO -> {
                if (packet.length >= 16) {
                    val pcmLen = ByteBuffer.wrap(data, 13, 2).short.toInt()
                    val ampByte = data[15].toInt() and 0xFF
                    val amp = ampByte / 100f

                    if (pcmLen > 0 && pcmLen <= packet.length - 16) {
                        val pcm = try {
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

                        if (isHost) {
                            clientRegistry.get(senderId)?.apply {
                                lastSeen = System.currentTimeMillis()
                                address = InetSocketAddress(packet.address, packet.port)
                                isTalking = amp > 0.08f
                                amplitude = amp
                                if (!isConnected) {
                                    isConnected = true
                                    onRosterUpdated?.invoke(getRemoteRiders())
                                }
                            }
                            onAudioReceived?.invoke(senderId, pcm, amp)
                            relayToOthers(senderId, data, packet.length)
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
                            dispatchRaw(stopPacket)
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
                        if (!hasAdoptedToken || !sessionToken.contentEquals(parsed.sessionToken)) {
                            sessionToken = parsed.sessionToken
                            hasAdoptedToken = true
                            Log.d(TAG, "Client adopted session token from host")
                        }
                        if (parsed.encryptionKey != null && (encryptionKey == null || !hasAdoptedToken)) {
                            encryptionKey = parsed.encryptionKey
                            Log.d(TAG, "Client adopted AES encryption key from host")
                        }
                        onRosterUpdated?.invoke(parsed.riders)
                    }
                }
            }

            PacketCodec.MSG_MUSIC_FRAME -> {
                val parsed = PacketCodec.parseMusicFramePacket(data, packet.length)
                if (parsed != null) {
                    if (isHost) {
                        val now = System.currentTimeMillis()
                        // Exclusive music relay: only relay frames from the current active DJ
                        if (currentMusicSenderId == null || currentMusicSenderId == senderId || (now - currentMusicSenderLastSeen > 5000L)) {
                            currentMusicSenderId = senderId
                            currentMusicSenderLastSeen = now
                            relayToOthers(senderId, data, packet.length)
                            onMusicFrameReceived?.invoke(parsed.senderId, parsed.pcm, parsed.sampleRate, parsed.sequence)
                        } else {
                            // Ignored: another rider is already sharing music exclusively
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
                        relayToOthers(senderId, data, packet.length)
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
                        dispatchRaw(stopPacket)
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

    private fun relayToOthers(excludeId: String, data: ByteArray, length: Int) {
        val sock = socket ?: return
        clientRegistry.all().forEach { client ->
            if (client.id != excludeId) {
                try {
                    sock.send(DatagramPacket(data, length, client.address))
                } catch (e: Exception) {
                    Log.w(TAG, "Relay to ${client.name} failed: ${e.message}")
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
        val sock = socket ?: return
        hostAddress?.let { addr ->
            try {
                sock.send(DatagramPacket(raw, raw.size, addr, AUDIO_PORT))
            } catch (_: Exception) {}
        }
    }

    private fun broadcastByeSync() {
        val raw = PacketCodec.buildByePacket(sessionToken, localId)
        val sock = socket ?: return
        clientRegistry.all().forEach { client ->
            try {
                sock.send(DatagramPacket(raw, raw.size, client.address))
            } catch (_: Exception) {}
        }
    }

    private fun dispatchRaw(data: ByteArray) {
        val sock = socket ?: return
        if (isHost) {
            clientRegistry.all().forEach { client ->
                try {
                    sock.send(DatagramPacket(data, data.size, client.address))
                } catch (e: Exception) {
                    Log.w(TAG, "Send to ${client.name} failed: ${e.message}")
                }
            }
        } else {
            hostAddress?.let { addr ->
                try {
                    sock.send(DatagramPacket(data, data.size, addr, AUDIO_PORT))
                } catch (e: Exception) {
                    Log.w(TAG, "Send to host failed: ${e.message}")
                }
            }
        }
    }
}
