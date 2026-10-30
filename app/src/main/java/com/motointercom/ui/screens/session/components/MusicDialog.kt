package com.motointercom.ui.screens.session.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicDialog(
    musicTrack: String?,
    musicSharerName: String? = null,
    isMusicPlaying: Boolean,
    isMusicHost: Boolean,
    musicVolume: Float,
    isMultitasking: Boolean,
    isSystemAudioActive: Boolean = false,
    isCompression: Boolean = true,
    onPlayDemo: () -> Unit,
    onPickAudioFile: () -> Unit,
    onStartSystemAudio: () -> Unit = {},
    onStopSystemAudio: () -> Unit = {},
    onTogglePlayPause: () -> Unit,
    onStopMusic: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMultitasking: () -> Unit,
    onToggleCompression: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        dragHandle = { BottomSheetDefaults.DragHandle(color = DividerColor) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier
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

            Spacer(Modifier.height(12.dp))

            // Audio Compression Toggle (ADPCM 4:1)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BackgroundElevated)
                    .border(1.dp, if (isCompression) GreenActive.copy(alpha = 0.4f) else DividerColor, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Compresión de largo alcance WiFi",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = TextPrimary
                    )
                    Text(
                        "Comprime la voz un 75% (ADPCM 4:1) reduciendo el paquete a 164B para evitar cortes a máxima distancia entre motos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.width(12.dp))

                Switch(
                    checked = isCompression,
                    onCheckedChange = { onToggleCompression() },
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
