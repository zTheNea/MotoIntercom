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
import com.motointercom.domain.model.LinkQuality
import com.motointercom.domain.model.ReconnectionState
import com.motointercom.domain.model.Rider
import com.motointercom.domain.model.Session
import com.motointercom.domain.model.SessionRole
import com.motointercom.ui.screens.session.components.*
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
    isCompression: Boolean = true,
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
    onToggleCompression: () -> Unit = {},
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
                isCompression = isCompression,
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
                onToggleCompression = onToggleCompression,
                onDismiss = { showMusicSheet = false }
            )
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

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

