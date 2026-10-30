package com.motointercom.ui.screens.session.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motointercom.ui.theme.AmberGlow
import com.motointercom.ui.theme.TextPrimary

@Composable
fun ReconnectionBanner(
    attempt: Int,
    maxAttempts: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
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
