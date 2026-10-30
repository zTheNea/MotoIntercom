package com.motointercom.ui.screens.home.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.data.wifi.DiscoveredSession
import com.motointercom.ui.theme.*

@Composable
fun JoinSessionContent(
    discoveredSessions: List<DiscoveredSession>,
    onJoinSession: (hostIp: String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BackgroundCard)
            .border(1.dp, DividerColor, RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.WifiFind, null, tint = AmberGlow, modifier = Modifier.size(40.dp))
        Text(
            "Unirse a un Grupo",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )

        // ── Auto-Discovered Sessions ─────────────────────────────────────
        if (discoveredSessions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dotAlpha by rememberInfiniteTransition(label = "pulse_radar").animateFloat(
                    initialValue = 0.4f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "radar_alpha"
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(GreenActive.copy(alpha = dotAlpha), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "SESIONES ACTIVAS DETECTADAS (${discoveredSessions.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = GreenActive,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            discoveredSessions.forEach { discovered ->
                DiscoveredSessionCard(
                    discovered = discovered,
                    onJoin = {
                        onJoinSession(discovered.hostIp)
                    }
                )
            }
        } else {
            // Radar pulse search banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BackgroundElevated)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = AmberGlow,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Escaneando sesiones activas en tu red WiFi...",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun DiscoveredSessionCard(
    discovered: DiscoveredSession,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BackgroundElevated)
            .border(1.dp, GreenActive.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TwoWheeler, null, tint = GreenActive, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        discovered.hostName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "IP: ${discovered.hostIp}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AmberGlow,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GreenDim)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    "${discovered.ridersCount}/${discovered.maxRiders} Motos",
                    style = MaterialTheme.typography.labelSmall,
                    color = GreenActive,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Button(
            onClick = onJoin,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(listOf(GreenActive, Color(0xFF00B0FF))),
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Login, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "UNIRSE CON 1 TOQUE",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
