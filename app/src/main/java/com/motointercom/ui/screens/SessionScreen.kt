package com.motointercom.ui.screens

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.motointercom.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.Rider
import com.motointercom.domain.model.Session
import com.motointercom.domain.model.SessionRole
import com.motointercom.ui.theme.*

@Composable
fun SessionScreen(
    session: Session,
    localIp: String = "",
    riders: List<Rider> = emptyList(),
    reconnectionState: ReconnectionState = ReconnectionState(),
    isMuted: Boolean,
    isPttActive: Boolean,
    isVox: Boolean,
    isSpeaker: Boolean,
    connectedCount: Int,
    musicTrack: String? = null,
    musicSharerName: String? = null,
    isMusicPlaying: Boolean = false,
    isMusicHost: Boolean = false,
    musicVolume: Float = 0.85f,
    isMultitasking: Boolean = true,
    isSystemAudioActive: Boolean = false,
    onMuteToggle: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onVoxToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onPlayDemoMusic: () -> Unit = {},
    onPlayUriMusic: (Uri, String) -> Unit = { _, _ -> },
    onStartSystemAudio: (Int, Intent) -> Unit = { _, _ -> },
    onStopSystemAudio: () -> Unit = {},
    onTogglePlayPauseMusic: () -> Unit = {},
    onStopMusic: () -> Unit = {},
    onSetMusicVolume: (Float) -> Unit = {},
    onToggleMultitasking: () -> Unit = {},
    onEndSession: () -> Unit
) {
    var showExitDialog by remember { mutableStateOf(false) }
    var showMusicSheet by remember { mutableStateOf(false) }
    var isSunMode by remember { mutableStateOf(false) }
    var isRainLocked by remember { mutableStateOf(false) }

    // Outdoor High-Contrast Sunlight Mode Theme Palette
    val sunBg = if (isSunMode) Color(0xFFF4F6F9) else BackgroundDeep
    val sunCard = if (isSunMode) Color(0xFFFFFFFF) else BackgroundCard
    val sunElevated = if (isSunMode) Color(0xFFE5E7EB) else BackgroundElevated
    val sunText = if (isSunMode) Color(0xFF000000) else TextPrimary
    val sunTextSec = if (isSunMode) Color(0xFF1F2937) else TextSecondary
    val sunBorder = if (isSunMode) Color(0xFF111827) else DividerColor
    val sunBorderWidth = if (isSunMode) 2.dp else 1.dp

    val context = LocalContext.current
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = getFileName(context, uri) ?: "Pista de Audio"
            onPlayUriMusic(uri, fileName)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            onStartSystemAudio(result.resultCode, result.data!!)
        }
    }

    BackHandler {
        if (isRainLocked) {
            // In Rain Lock mode, back button is protected from water touches
        } else {
            showExitDialog = true
        }
    }

    // Animate PTT button scale
    val pttScale by animateFloatAsState(
        targetValue = if (isPttActive) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "ptt_scale"
    )

    // Pulsing ring animation when talking
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val isTalking = isPttActive && !isMuted

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(sunBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val isScrollable = maxHeight < 580.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .then(
                    if (isScrollable) Modifier.verticalScroll(rememberScrollState())
                    else Modifier
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (isScrollable) Arrangement.spacedBy(20.dp) else Arrangement.SpaceBetween
        ) {
            // ── Top Section: Status Header & Voice Channel ───────────────
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Session Status Header
                SessionHeader(
                    role = session.role,
                    hostIp = session.hostIp,
                    localIp = localIp,
                    connectedCount = connectedCount,
                    maxRiders = session.maxRiders,
                    reconnectionState = reconnectionState,
                    isSunMode = isSunMode,
                    onToggleSunMode = { isSunMode = !isSunMode },
                    onToggleRainLock = { isRainLocked = true },
                    onEnd = { showExitDialog = true },
                    containerColor = sunCard,
                    borderColor = sunBorder,
                    borderWidth = sunBorderWidth,
                    textColor = sunText,
                    textSecColor = sunTextSec
                )

                // Reconnection Banner if connection dropped temporarily
                AnimatedVisibility(
                    visible = reconnectionState.isReconnecting,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    ReconnectionBanner(
                        attempt = reconnectionState.attempt,
                        maxAttempts = reconnectionState.maxAttempts
                    )
                }

                // Rider Cards (4-column responsive grid)
                RiderGrid(
                    localName = session.localRiderName,
                    riders = riders,
                    isTalking = isTalking,
                    isSunMode = isSunMode,
                    sunCard = sunCard,
                    sunBorder = sunBorder,
                    sunBorderWidth = sunBorderWidth,
                    sunText = sunText,
                    sunTextSec = sunTextSec
                )

                // Mini music banner when music is active
                AnimatedVisibility(
                    visible = musicTrack != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(sunElevated)
                            .border(sunBorderWidth, PurpleAccent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable { showMusicSheet = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.GraphicEq, null, tint = PurpleAccent, modifier = Modifier.size(18.dp))
                            Column {
                                Text(
                                    musicTrack ?: "",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = sunText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (isMusicHost) "TU TRANSMISIÓN" else "DJ: ${musicSharerName ?: "Rider"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PurpleAccent,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Text(
                            if (isMusicPlaying) "EN VIVO" else "PAUSADO",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMusicPlaying) (if (isSunMode) Color(0xFF1B5E20) else GreenActive) else AmberGlow,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── Center Section: PTT Button & Voice Status ────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (!isScrollable) Modifier.weight(1f) else Modifier)
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (!isVox) {
                    PttButton(
                        isTalking = isTalking,
                        scale = pttScale,
                        pulseAlpha = if (isTalking) pulseAlpha else 0f,
                        pulseScale = if (isTalking) pulseScale else 1f,
                        onDown = onPttDown,
                        onUp = onPttUp,
                        isSunMode = isSunMode
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        when {
                            reconnectionState.isReconnecting -> stringResource(R.string.reconnecting_warning)
                            isTalking -> stringResource(R.string.ptt_transmitting)
                            else -> stringResource(R.string.ptt_hold_to_talk)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSunMode) FontWeight.Black else FontWeight.Bold,
                        color = when {
                            reconnectionState.isReconnecting -> AmberGlow
                            isTalking -> if (isSunMode) Color(0xFF1B5E20) else GreenActive
                            else -> sunTextSec
                        },
                        letterSpacing = 2.sp
                    )
                } else {
                    VoxIndicator(isTalking = isTalking)
                }
            }

            // ── Bottom Section: Control Bar ──────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ControlBar(
                    isMuted = isMuted,
                    isVox = isVox,
                    isSpeaker = isSpeaker,
                    isMusicActive = isMusicPlaying || musicTrack != null,
                    onMuteToggle = onMuteToggle,
                    onVoxToggle = onVoxToggle,
                    onSpeakerToggle = onSpeakerToggle,
                    onMusicClick = { showMusicSheet = true },
                    sunCard = sunCard,
                    sunBorder = sunBorder,
                    sunBorderWidth = sunBorderWidth,
                    isSunMode = isSunMode
                )
            }
        }

        // ── Rain Lock Fullscreen Protective Shield Overlay ──────────────
        if (isRainLocked) {
            RainLockOverlay(
                isTalking = isTalking,
                isSunMode = isSunMode,
                pttScale = pttScale,
                pulseAlpha = if (isTalking) pulseAlpha else 0f,
                pulseScale = if (isTalking) pulseScale else 1f,
                onPttDown = onPttDown,
                onPttUp = onPttUp,
                onUnlock = { isRainLocked = false }
            )
        }

        // ── Exit Confirmation Dialog ────────────────────────────────────
        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                containerColor = BackgroundCard,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary,
                shape = RoundedCornerShape(20.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CallEnd,
                            null,
                            tint = RedDanger,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.end_session_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                text = {
                    Text(
                        stringResource(R.string.end_session_message),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            onEndSession()
                        }
                    ) {
                        Text(stringResource(R.string.dialog_exit), color = RedDanger, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text(stringResource(R.string.dialog_cancel), color = TextSecondary)
                    }
                }
            )
        }

        // ── Music & Audio Multitasking Bottom Sheet ─────────────────
        if (showMusicSheet) {
            MusicDialog(
                musicTrack = musicTrack,
                musicSharerName = musicSharerName,
                isMusicPlaying = isMusicPlaying,
                isMusicHost = isMusicHost,
                musicVolume = musicVolume,
                isMultitasking = isMultitasking,
                isSystemAudioActive = isSystemAudioActive,
                onPlayDemo = onPlayDemoMusic,
                onPickAudioFile = { audioPickerLauncher.launch("audio/*") },
                onStartSystemAudio = {
                    val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                    val intent = projectionManager?.createScreenCaptureIntent()
                    if (intent != null) {
                        mediaProjectionLauncher.launch(intent)
                    }
                },
                onStopSystemAudio = onStopSystemAudio,
                onTogglePlayPause = onTogglePlayPauseMusic,
                onStopMusic = onStopMusic,
                onVolumeChange = onSetMusicVolume,
                onToggleMultitasking = onToggleMultitasking,
                onDismiss = { showMusicSheet = false }
            )
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun SessionHeader(
    role: SessionRole,
    hostIp: String,
    localIp: String,
    connectedCount: Int,
    maxRiders: Int,
    reconnectionState: ReconnectionState,
    isSunMode: Boolean,
    onToggleSunMode: () -> Unit,
    onToggleRainLock: () -> Unit,
    onEnd: () -> Unit,
    containerColor: Color = BackgroundCard,
    borderColor: Color = DividerColor,
    borderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    textColor: Color = TextPrimary,
    textSecColor: Color = TextSecondary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            val isReconnecting = reconnectionState.isReconnecting
            val dotAlpha by rememberInfiniteTransition(label = "dot").animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(if (isReconnecting) 350 else 600),
                    RepeatMode.Reverse
                ),
                label = "dot_alpha"
            )
            val dotColor = if (isReconnecting) AmberGlow else if (role == SessionRole.HOST) OrangeFlame else GreenActive
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor.copy(alpha = dotAlpha), CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusText = when {
                        isReconnecting -> "RECONECTANDO..."
                        role == SessionRole.HOST -> "ANFITRIÓN"
                        else -> "CONECTADO"
                    }
                    val statusColor = when {
                        isReconnecting -> AmberGlow
                        role == SessionRole.HOST -> if (isSunMode) Color(0xFFD84315) else OrangeFlame
                        else -> if (isSunMode) Color(0xFF1B5E20) else GreenActive
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSunMode) FontWeight.Black else FontWeight.Bold,
                        color = statusColor,
                        letterSpacing = 1.sp
                    )
                }
                if (role == SessionRole.HOST) {
                    if (hostIp.isNotBlank()) {
                        Text(
                            "IP sala: $hostIp",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSunMode) Color(0xFFB78103) else AmberGlow,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSunMode) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                } else {
                    Text(
                        "Host: $hostIp • Tu IP: ${localIp.ifBlank { "..." }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textSecColor,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "$connectedCount / $maxRiders motociclistas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Action Buttons Row: Sun Mode, Rain Lock, End Session
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Outdoor Sun Mode Toggle (High Contrast)
            IconButton(
                onClick = onToggleSunMode,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (isSunMode) Color(0xFFFFD54F) else BackgroundElevated)
                    .border(1.dp, if (isSunMode) Color(0xFFFFA000) else borderColor, CircleShape)
            ) {
                Icon(
                    Icons.Default.WbSunny,
                    contentDescription = "Modo Sol",
                    tint = if (isSunMode) Color(0xFFBF360C) else TextSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Water / Rain Lock Toggle
            IconButton(
                onClick = onToggleRainLock,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(BackgroundElevated)
                    .border(1.dp, borderColor, CircleShape)
            ) {
                Icon(
                    Icons.Default.WaterDrop,
                    contentDescription = "Modo Lluvia",
                    tint = CyanAccent,
                    modifier = Modifier.size(19.dp)
                )
            }

            // End Session Button
            IconButton(
                onClick = onEnd,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(RedDim)
            ) {
                Icon(Icons.Default.CallEnd, "Terminar", tint = RedDanger, modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun ReconnectionBanner(
    attempt: Int,
    maxAttempts: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        colors = CardDefaults.cardColors(containerColor = AmberGlow.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, AmberGlow.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.5.dp,
                color = AmberGlow,
                trackColor = AmberGlow.copy(alpha = 0.2f)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Reconectando con la sala...",
                    style = MaterialTheme.typography.titleSmall,
                    color = AmberGlow,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Intento $attempt de $maxAttempts • Manteniendo la conferencia",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun RiderGrid(
    localName: String,
    riders: List<Rider>,
    isTalking: Boolean,
    isSunMode: Boolean = false,
    sunCard: Color = BackgroundCard,
    sunBorder: Color = DividerColor,
    sunBorderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    sunText: Color = TextPrimary,
    sunTextSec: Color = TextSecondary
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "CANAL DE VOZ",
            style = MaterialTheme.typography.labelMedium,
            color = if (isSunMode) sunTextSec else TextSecondary,
            fontWeight = if (isSunMode) FontWeight.Black else FontWeight.Normal,
            letterSpacing = 2.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Local rider (self)
            Box(modifier = Modifier.weight(1f)) {
                RiderCard(
                    name = localName.ifBlank { "Tú" },
                    isLocal = true,
                    isTalking = isTalking,
                    isConnected = true,
                    isSunMode = isSunMode,
                    sunCard = sunCard,
                    sunBorder = sunBorder,
                    sunBorderWidth = sunBorderWidth,
                    sunText = sunText
                )
            }
            // Remote riders (up to 3)
            val displayedRemoteRiders = riders.take(3)
            displayedRemoteRiders.forEach { rider ->
                Box(modifier = Modifier.weight(1f)) {
                    RiderCard(
                        name = rider.name,
                        isLocal = false,
                        isTalking = rider.isTalking,
                        isConnected = rider.isConnected,
                        isSunMode = isSunMode,
                        sunCard = sunCard,
                        sunBorder = sunBorder,
                        sunBorderWidth = sunBorderWidth,
                        sunText = sunText
                    )
                }
            }
            // Empty slots so total is 4 slots
            val emptySlots = (3 - displayedRemoteRiders.size).coerceAtLeast(0)
            repeat(emptySlots) {
                Box(modifier = Modifier.weight(1f)) {
                    EmptyRiderSlot(
                        sunCard = sunCard,
                        sunBorder = sunBorder,
                        sunBorderWidth = sunBorderWidth,
                        sunTextSec = sunTextSec
                    )
                }
            }
        }
    }
}

// ── Discord-Style Rider Card ────────────────────────────────────────────────

@Composable
private fun RiderCard(
    name: String,
    isLocal: Boolean,
    isTalking: Boolean,
    isConnected: Boolean,
    isSunMode: Boolean = false,
    sunCard: Color = BackgroundCard,
    sunBorder: Color = DividerColor,
    sunBorderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    sunText: Color = TextPrimary
) {
    // Discord bounce animation on voice activity
    val avatarScale by animateFloatAsState(
        targetValue = if (isTalking) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "avatar_scale"
    )

    // Glowing halo pulse for Discord green ring
    val infiniteTransition = rememberInfiniteTransition(label = "discord_halo")
    val haloPulse by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "halo_scale"
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "halo_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isTalking)
                    Brush.verticalGradient(
                        if (isSunMode) listOf(Color(0xFFE8F5E9), Color.White)
                        else listOf(Color(0xFF142B1F), BackgroundCard)
                    )
                else
                    SolidColor(sunCard)
            )
            .border(
                width = if (isTalking) (sunBorderWidth + 1.dp) else sunBorderWidth,
                color = when {
                    isTalking   -> if (isSunMode) Color(0xFF2E7D32) else DiscordGreenLight
                    isLocal     -> if (isSunMode) Color(0xFFD84315) else OrangeFlame.copy(alpha = 0.5f)
                    isConnected -> if (isSunMode) Color(0xFF1565C0) else BlueInfo.copy(alpha = 0.35f)
                    else        -> sunBorder
                },
                shape = RoundedCornerShape(14.dp)
            )
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // ── Avatar with Discord Glowing Ring ───────────────────────────
        Box(
            modifier = Modifier.size(50.dp),
            contentAlignment = Alignment.Center
        ) {
            // Discord outer glowing aura when speaking
            if (isTalking) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .scale(haloPulse)
                        .background(
                            DiscordGreenGlow.copy(alpha = haloAlpha),
                            CircleShape
                        )
                )
            }

            // Main circular avatar
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .scale(avatarScale)
                    .border(
                        width = if (isTalking) 2.5.dp else 1.5.dp,
                        color = when {
                            isTalking   -> if (isSunMode) Color(0xFF2E7D32) else DiscordGreenLight
                            isLocal     -> if (isSunMode) Color(0xFFD84315) else OrangeFlame
                            isConnected -> if (isSunMode) Color(0xFF1565C0) else BlueInfo
                            else        -> TextMuted
                        },
                        shape = CircleShape
                    )
                    .background(
                        when {
                            isTalking   -> Brush.radialGradient(listOf(Color(0xFF1D5A32), Color(0xFF0D2816)))
                            isLocal     -> Brush.radialGradient(listOf(OrangeFlame, OrangeDim))
                            isConnected -> Brush.radialGradient(listOf(BlueInfo, Color(0xFF153366)))
                            else        -> Brush.radialGradient(listOf(TextMuted, Color(0xFF20202F)))
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // ── Name ────────────────────────────────────────────────────────
        Text(
            text = if (isLocal) "$name (Tú)" else name,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = if (isTalking) (if (isSunMode) Color(0xFF1B5E20) else Color.White) else sunText,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // ── Discord Voice Status Badge ──────────────────────────────────
        if (isTalking) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSunMode) Color(0xFF2E7D32) else DiscordGreen)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Icon(
                    Icons.Default.Mic,
                    null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp)
                )
                Text(
                    "VOZ",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
        } else {
            Text(
                if (isConnected) "• listo" else "• fuera",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.5.sp,
                fontWeight = if (isSunMode) FontWeight.Bold else FontWeight.Normal,
                color = if (isConnected) (if (isSunMode) Color(0xFF1B5E20) else GreenActive.copy(alpha = 0.7f)) else TextMuted
            )
        }
    }
}

@Composable
private fun EmptyRiderSlot(
    sunCard: Color = BackgroundCard,
    sunBorder: Color = DividerColor,
    sunBorderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    sunTextSec: Color = TextSecondary
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(sunCard)
            .border(sunBorderWidth, sunBorder, RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(BackgroundElevated, CircleShape)
                .border(1.dp, sunBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PersonAdd, null, tint = sunTextSec, modifier = Modifier.size(18.dp))
        }
        Text(
            "Libre",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = sunTextSec,
            textAlign = TextAlign.Center
        )
        Text(
            "• espera",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.5.sp,
            color = sunTextSec.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PttButton(
    isTalking: Boolean,
    scale: Float,
    pulseAlpha: Float,
    pulseScale: Float,
    onDown: () -> Unit,
    onUp: () -> Unit,
    isSunMode: Boolean = false
) {
    Box(
        modifier = Modifier.size(160.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulse ring
        if (isTalking) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .scale(pulseScale)
                    .background(
                        (if (isSunMode) Color(0xFF2E7D32) else GreenActive).copy(alpha = pulseAlpha * 0.35f),
                        CircleShape
                    )
            )
        }
        // Middle ring
        Box(
            modifier = Modifier
                .size(140.dp)
                .background(
                    if (isTalking) (if (isSunMode) Color(0xFFC8E6C9) else GreenDim) else (if (isSunMode) Color(0xFFE5E7EB) else BackgroundElevated),
                    CircleShape
                )
                .border(
                    width = if (isSunMode) 3.dp else 2.dp,
                    color = if (isTalking) (if (isSunMode) Color(0xFF1B5E20) else GreenActive) else (if (isSunMode) Color(0xFF111827) else DividerColor),
                    shape = CircleShape
                )
        )
        // PTT button itself
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .background(
                    brush = if (isTalking)
                        Brush.radialGradient(
                            if (isSunMode) listOf(Color(0xFF2E7D32), Color(0xFF1B5E20))
                            else listOf(GreenActive, GreenPulse)
                        )
                    else
                        Brush.radialGradient(
                            if (isSunMode) listOf(Color(0xFFFF6D00), Color(0xFFD84315))
                            else listOf(OrangeFlame, OrangeDim)
                        ),
                    shape = CircleShape
                )
                .border(
                    width = if (isSunMode) 3.dp else 0.dp,
                    color = if (isSunMode) Color(0xFF000000) else Color.Transparent,
                    shape = CircleShape
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onDown()
                            tryAwaitRelease()
                            onUp()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (isTalking) Icons.Default.Mic else Icons.Default.MicNone,
                    contentDescription = "PTT",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
    }
}

@Composable
private fun VoxIndicator(isTalking: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(
                    if (isTalking) GreenDim else BackgroundCard,
                    CircleShape
                )
                .border(2.dp, if (isTalking) GreenActive else DividerColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isTalking) Icons.Default.GraphicEq else Icons.Default.Mic,
                null,
                tint = if (isTalking) GreenActive else TextSecondary,
                modifier = Modifier.size(48.dp)
            )
        }
        Text(
            "MODO VOX ACTIVO",
            style = MaterialTheme.typography.labelMedium,
            color = GreenActive,
            letterSpacing = 2.sp
        )
        Text(
            "Transmisión automática por voz",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun ControlBar(
    isMuted: Boolean,
    isVox: Boolean,
    isSpeaker: Boolean,
    isMusicActive: Boolean,
    onMuteToggle: () -> Unit,
    onVoxToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onMusicClick: () -> Unit,
    sunCard: Color = BackgroundCard,
    sunBorder: Color = DividerColor,
    sunBorderWidth: androidx.compose.ui.unit.Dp = 1.dp,
    isSunMode: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(sunCard)
            .border(sunBorderWidth, sunBorder, RoundedCornerShape(16.dp))
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ControlButton(
            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
            label = if (isMuted) "Mute" else "Mic",
            isActive = !isMuted,
            activeColor = if (isSunMode) Color(0xFF1B5E20) else GreenActive,
            onClick = onMuteToggle,
            isSunMode = isSunMode
        )
        ControlButton(
            icon = Icons.Default.GraphicEq,
            label = "VOX",
            isActive = isVox,
            activeColor = if (isSunMode) Color(0xFFE65100) else AmberGlow,
            onClick = onVoxToggle,
            isSunMode = isSunMode
        )
        ControlButton(
            icon = if (isSpeaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Headset,
            label = if (isSpeaker) "Altavoz" else "Casco",
            isActive = true,
            activeColor = if (isSpeaker) (if (isSunMode) Color(0xFFE65100) else AmberGlow) else (if (isSunMode) Color(0xFF1B5E20) else GreenActive),
            onClick = onSpeakerToggle,
            isSunMode = isSunMode
        )
        ControlButton(
            icon = if (isMusicActive) Icons.Default.MusicNote else Icons.Default.MusicOff,
            label = "Música",
            isActive = isMusicActive,
            activeColor = if (isSunMode) Color(0xFF6A1B9A) else PurpleAccent,
            onClick = onMusicClick,
            isSunMode = isSunMode
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    isSunMode: Boolean = false
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    if (isActive) activeColor.copy(alpha = if (isSunMode) 0.22f else 0.15f) else (if (isSunMode) Color(0xFFE5E7EB) else BackgroundElevated),
                    CircleShape
                )
                .border(
                    width = if (isSunMode) 1.5.dp else 0.dp,
                    color = if (isSunMode && isActive) activeColor else (if (isSunMode) Color(0xFF9CA3AF) else Color.Transparent),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = if (isActive) activeColor else (if (isSunMode) Color(0xFF4B5563) else TextSecondary),
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSunMode) FontWeight.Bold else FontWeight.Normal,
            color = if (isActive) activeColor else (if (isSunMode) Color(0xFF374151) else TextSecondary),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicDialog(
    musicTrack: String?,
    musicSharerName: String? = null,
    isMusicPlaying: Boolean,
    isMusicHost: Boolean,
    musicVolume: Float,
    isMultitasking: Boolean,
    isSystemAudioActive: Boolean = false,
    onPlayDemo: () -> Unit,
    onPickAudioFile: () -> Unit,
    onStartSystemAudio: () -> Unit = {},
    onStopSystemAudio: () -> Unit = {},
    onTogglePlayPause: () -> Unit,
    onStopMusic: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMultitasking: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = DividerColor) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(PurpleAccent.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = PurpleAccent, modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "MÚSICA & AUDIO",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, null, tint = TextSecondary)
                }
            }

            HorizontalDivider(color = DividerColor)

            // ── Section 1: Transmisión Compartida en el Grupo ────────────────
            Text(
                "COMPARTIR MÚSICA EN EL GRUPO (WIFI)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = AmberGlow,
                letterSpacing = 1.sp
            )

            if (musicTrack != null) {
                // Active Track Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BackgroundElevated)
                        .border(1.dp, PurpleAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.linearGradient(listOf(PurpleAccent, CyanAccent))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                musicTrack,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                if (isMusicHost) "Compartiendo con el grupo (Eres el DJ)" else "Transmitido por ${musicSharerName ?: "un integrante"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMusicPlaying) GreenActive else TextSecondary
                            )
                        }
                    }

                    if (isMusicHost) {
                        // Playback Controls (Exclusive to the current DJ)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledIconButton(
                                onClick = onTogglePlayPause,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = PurpleAccent),
                                modifier = Modifier.size(50.dp)
                            ) {
                                Icon(
                                    if (isMusicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Spacer(Modifier.width(16.dp))

                            FilledTonalIconButton(
                                onClick = onStopMusic,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = RedDim),
                                modifier = Modifier.size(50.dp)
                            ) {
                                Icon(Icons.Default.Stop, null, tint = RedDanger, modifier = Modifier.size(26.dp))
                            }
                        }
                    } else {
                        // Listener info card - exclusive single sharer message
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(PurpleAccent.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Headphones, null, tint = PurpleAccent, modifier = Modifier.size(18.dp))
                            Text(
                                "Modo Oyente: ${musicSharerName ?: "Otro integrante"} tiene el control de la música. Solo 1 integrante puede transmitir a la vez.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                        }
                    }

                    // Volume Slider (Independently adjustable by each listener)
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Tu volumen de música", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                            Text("${(musicVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                        }
                        Slider(
                            value = musicVolume,
                            onValueChange = onVolumeChange,
                            colors = SliderDefaults.colors(
                                thumbColor = PurpleAccent,
                                activeTrackColor = PurpleAccent,
                                inactiveTrackColor = BackgroundCard
                            )
                        )
                    }

                    // Ducking badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GreenDim.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeDown, null, tint = GreenActive, modifier = Modifier.size(16.dp))
                        Text(
                            "Auto-Ducking activo: el volumen baja al 20% cuando alguien habla.",
                            style = MaterialTheme.typography.labelSmall,
                            color = GreenActive
                        )
                    }

                    if (isSystemAudioActive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1DB954).copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Cast, null, tint = Color(0xFF1DB954), modifier = Modifier.size(16.dp))
                                Text(
                                    "Transmitiendo en vivo desde Spotify / apps externas.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF1DB954)
                                )
                            }
                            if (isMusicHost) {
                                TextButton(
                                    onClick = onStopSystemAudio,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("DETENER", color = RedDanger, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else {
                // Options to start music
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Button(
                            onClick = onStartSystemAudio,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Cast, null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "TRANSMITIR DESDE OTRA APP (SPOTIFY / YOUTUBE)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onPlayDemo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(listOf(PurpleAccent, CyanAccent)),
                                    RoundedCornerShape(12.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Bolt, null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "PROBAR PISTA DEMO (MOTOBEAT)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onPickAudioFile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, PurpleAccent)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.FolderOpen, null, tint = PurpleAccent)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "ELEGIR ARCHIVO DE MÚSICA (MP3 / WAV)",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = PurpleAccent
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = DividerColor)

            // ── Section 2: Audio Multitasking (Spotify / Fondo) ──────────────
            Text(
                "AUDIO MULTITASKING (SPOTIFY / DEEZER / MAPS)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = GreenActive,
                letterSpacing = 1.sp
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BackgroundElevated)
                    .border(1.dp, if (isMultitasking) GreenActive.copy(alpha = 0.4f) else DividerColor, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Música personal de fondo",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        "Permite escuchar Spotify / YouTube Music y navegación GPS en tu casco sin que el intercomunicador corte la música. Se atenúa automáticamente al hablar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.width(12.dp))

                Switch(
                    checked = isMultitasking,
                    onCheckedChange = { onToggleMultitasking() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = GreenActive,
                        uncheckedTrackColor = BackgroundCard
                    )
                )
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

private fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}

// ── Motorcycle Rain / Water Glove Lock Overlay ──────────────────────────────

@Composable
private fun RainLockOverlay(
    isTalking: Boolean,
    isSunMode: Boolean,
    pttScale: Float,
    pulseAlpha: Float,
    pulseScale: Float,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onUnlock: () -> Unit
) {
    var unlockProgress by remember { mutableFloatStateOf(0f) }
    var isHoldingUnlock by remember { mutableStateOf(false) }

    LaunchedEffect(isHoldingUnlock) {
        if (isHoldingUnlock) {
            val startTime = System.currentTimeMillis()
            val holdDuration = 1200L // 1.2s continuous intentional hold to reject raindrops
            while (isHoldingUnlock) {
                val elapsed = System.currentTimeMillis() - startTime
                unlockProgress = (elapsed.toFloat() / holdDuration).coerceIn(0f, 1f)
                if (unlockProgress >= 1f) {
                    onUnlock()
                    break
                }
                delay(16)
            }
        } else {
            unlockProgress = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0080B14))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .pointerInput(Unit) {
                // Intercept all phantom taps from rain drops across the entire screen
                detectTapGestures { }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top: Rain Shield Alert Banner ───────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B2238)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0xFF00E5FF).copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.WaterDrop,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "MODO LLUVIA ACTIVO",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            "Pantalla blindada contra gotas de lluvia. PTT habilitado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB0BEC5),
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            // ── Center: High-Priority Rain PTT Button ───────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PttButton(
                    isTalking = isTalking,
                    scale = pttScale,
                    pulseAlpha = pulseAlpha,
                    pulseScale = pulseScale,
                    onDown = onPttDown,
                    onUp = onPttUp,
                    isSunMode = isSunMode
                )

                Text(
                    if (isTalking) "TRANSMITIENDO..." else "PULSA Y HABLA (PTT)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = if (isTalking) GreenActive else Color.White,
                    letterSpacing = 2.sp
                )

                Text(
                    "Operable con guantes mojados",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )
            }

            // ── Bottom: Secure Rain Unlock Button (Hold 1.2s) ───────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E2235))
                        .border(
                            width = 2.dp,
                            color = if (isHoldingUnlock) Color(0xFF00E5FF) else Color(0xFF424B63),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    isHoldingUnlock = true
                                    tryAwaitRelease()
                                    isHoldingUnlock = false
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Fill bar progress as rider holds down the unlock button
                    if (unlockProgress > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(unlockProgress)
                                .align(Alignment.CenterStart)
                                .background(Color(0xFF00E5FF).copy(alpha = 0.35f))
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isHoldingUnlock) {
                            CircularProgressIndicator(
                                progress = { unlockProgress },
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 3.dp,
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0x3300E5FF)
                            )
                        } else {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            if (isHoldingUnlock) "DESBLOQUEANDO..." else "MANTÉN 1.5s PARA DESBLOQUEAR",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (isHoldingUnlock) Color(0xFF00E5FF) else Color.White,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Text(
                    "Evita toques fantasma causados por salpicaduras o lluvia",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF90A4AE),
                    fontSize = 10.5.sp
                )
            }
        }
    }
}
