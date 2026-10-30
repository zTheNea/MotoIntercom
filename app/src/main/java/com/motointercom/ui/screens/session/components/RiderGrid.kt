package com.motointercom.ui.screens.session.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motointercom.domain.model.Rider
import com.motointercom.ui.theme.BackgroundCard
import com.motointercom.ui.theme.DividerColor
import com.motointercom.ui.theme.TextPrimary
import com.motointercom.ui.theme.TextSecondary

@Composable
fun RiderGrid(
    localName: String,
    riders: List<Rider>,
    isTalking: Boolean,
    isSunMode: Boolean = false,
    sunCard: Color = BackgroundCard,
    sunBorder: Color = DividerColor,
    sunBorderWidth: Dp = 1.dp,
    sunText: Color = TextPrimary,
    sunTextSec: Color = TextSecondary,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
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
                        linkQuality = rider.linkQuality,
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
