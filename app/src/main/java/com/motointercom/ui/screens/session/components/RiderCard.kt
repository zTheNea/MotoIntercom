package com.motointercom.ui.screens.session.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.domain.model.LinkQuality
import com.motointercom.ui.theme.*

@Composable
fun RiderCard(
    name: String,
    isLocal: Boolean,
    isTalking: Boolean,
    isConnected: Boolean,
    linkQuality: LinkQuality = LinkQuality.OPTIMA,
    isSunMode: Boolean = false,
    sunCard: Color = BackgroundCard,
    sunBorder: Color = DividerColor,
    sunBorderWidth: Dp = 1.dp,
    sunText: Color = TextPrimary,
    modifier: Modifier = Modifier
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
        modifier = modifier
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
        // ── Avatar with Discord Glowing Ring (graphicsLayer optimized) ─────────
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            // Discord outer glowing aura when speaking (draw-phase transformation)
            if (isTalking) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            scaleX = haloPulse
                            scaleY = haloPulse
                            alpha = haloAlpha
                        }
                        .background(
                            DiscordGreenGlow,
                            CircleShape
                        )
                )
            }

            // Main circular avatar (draw-phase scale)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = avatarScale
                        scaleY = avatarScale
                    }
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

        // ── Voice Status Badge (Fixed 20dp Height for Symmetrical Row Alignment) ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isTalking) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSunMode) Color(0xFF2E7D32) else DiscordGreen)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        Icons.Default.Mic,
                        null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        "VOZ",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White
                    )
                }
            } else {
                val statusLabel = when {
                    !isConnected -> "• fuera"
                    isLocal -> "• listo"
                    else -> "• ${linkQuality.label}"
                }
                val statusColor = when {
                    !isConnected -> TextMuted
                    isLocal || linkQuality == LinkQuality.OPTIMA -> if (isSunMode) Color(0xFF1B5E20) else GreenActive.copy(alpha = 0.7f)
                    linkQuality == LinkQuality.BUENA -> if (isSunMode) Color(0xFF1565C0) else BlueInfo.copy(alpha = 0.7f)
                    linkQuality == LinkQuality.INESTABLE -> if (isSunMode) Color(0xFFE65100) else OrangeFlame.copy(alpha = 0.7f)
                    else -> if (isSunMode) Color(0xFFB71C1C) else RedDanger.copy(alpha = 0.7f)
                }
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.5.sp,
                    fontWeight = if (isSunMode) FontWeight.Bold else FontWeight.Normal,
                    color = statusColor,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun EmptyRiderSlot(
    sunCard: Color = BackgroundCard,
    sunBorder: Color = DividerColor,
    sunBorderWidth: Dp = 1.dp,
    sunTextSec: Color = TextSecondary,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(sunCard)
            .border(sunBorderWidth, sunBorder, RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
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
        }
        Text(
            "Libre",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = sunTextSec,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "• espera",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.5.sp,
                color = sunTextSec.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
