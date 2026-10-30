package com.motointercom.ui.screens.session.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.motointercom.ui.theme.*

@Composable
fun ControlBar(
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
    sunBorderWidth: Dp = 1.dp,
    isSunMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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
fun ControlButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    isSunMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
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
