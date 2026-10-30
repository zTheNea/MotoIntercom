package com.motointercom.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.motointercom.data.updater.ApkDownloader
import com.motointercom.data.updater.UpdateDownloadProgress
import com.motointercom.data.updater.UpdateInfo
import com.motointercom.ui.theme.*

@Composable
fun UpdateAvailableDialog(
    updateInfo: UpdateInfo,
    downloadProgress: UpdateDownloadProgress,
    onStartDownload: (url: String, version: String) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallApk: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = {
            if (downloadProgress.status != UpdateDownloadProgress.Status.DOWNLOADING) {
                onDismiss()
            }
        },
        title = {
            val titleText = when (downloadProgress.status) {
                UpdateDownloadProgress.Status.DOWNLOADING -> "DESCARGANDO ACTUALIZACION"
                UpdateDownloadProgress.Status.READY_TO_INSTALL -> "ACTUALIZACION LISTA"
                UpdateDownloadProgress.Status.ERROR -> "ERROR EN LA ACTUALIZACION"
                UpdateDownloadProgress.Status.IDLE -> "ACTUALIZACION DISPONIBLE"
            }
            Text(
                titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = when (downloadProgress.status) {
                    UpdateDownloadProgress.Status.READY_TO_INSTALL -> GreenActive
                    UpdateDownloadProgress.Status.ERROR -> RedDanger
                    else -> OrangeFlame
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (downloadProgress.status) {
                    UpdateDownloadProgress.Status.DOWNLOADING -> {
                        Text(
                            "MotoIntercom v${updateInfo.latestVersion}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = OrangeFlame,
                            trackColor = BackgroundCard
                        )
                        Spacer(Modifier.height(4.dp))
                        val percent = (downloadProgress.progress * 100).toInt()
                        val downloadedMb = downloadProgress.downloadedBytes / (1024f * 1024f)
                        val totalMb = if (downloadProgress.totalBytes > 0) {
                            downloadProgress.totalBytes / (1024f * 1024f)
                        } else {
                            updateInfo.apkSize / (1024f * 1024f)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "$percent%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = AmberGlow
                            )
                            Text(
                                "${String.format(java.util.Locale.US, "%.1f", downloadedMb)} / ${String.format(java.util.Locale.US, "%.1f", totalMb)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        Text(
                            "Descargando paquete de actualizacion directamente en la aplicacion...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    UpdateDownloadProgress.Status.READY_TO_INSTALL -> {
                        Text(
                            "MotoIntercom v${updateInfo.latestVersion}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            "La descarga ha finalizado correctamente.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GreenActive
                        )
                        if (downloadProgress.needsInstallPermission) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Para continuar con la instalacion, permite a MotoIntercom instalar aplicaciones desconocidas en los ajustes del sistema.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else {
                            Text(
                                "El instalador del sistema iniciara automaticamente. Si la ventana no aparece, pulsa en Instalar Ahora.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }

                    UpdateDownloadProgress.Status.ERROR -> {
                        Text(
                            "No se pudo completar la descarga de la actualizacion.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RedDanger
                        )
                        downloadProgress.error?.let { err ->
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Puedes reintentar la descarga interna o abrir el enlace directamente en tu navegador.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }

                    UpdateDownloadProgress.Status.IDLE -> {
                        Text(
                            "Nueva version: v${updateInfo.latestVersion}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            "Version instalada: v${updateInfo.currentVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        if (updateInfo.apkSize > 0) {
                            val sizeMb = updateInfo.apkSize / (1024f * 1024f)
                            Text(
                                "Tamaño del APK: ${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        if (updateInfo.releaseNotes.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Novedades:",
                                style = MaterialTheme.typography.labelMedium,
                                color = AmberGlow,
                                fontWeight = FontWeight.Bold
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BackgroundCard)
                                    .border(1.dp, DividerColor, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    updateInfo.releaseNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (downloadProgress.status) {
                UpdateDownloadProgress.Status.IDLE -> {
                    Button(
                        onClick = {
                            val url = updateInfo.apkUrl ?: updateInfo.releaseUrl
                            onStartDownload(url, updateInfo.latestVersion)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeFlame),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("DESCARGAR E INSTALAR", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }

                UpdateDownloadProgress.Status.DOWNLOADING -> {
                    // Sin boton de confirmacion durante la descarga activa
                }

                UpdateDownloadProgress.Status.READY_TO_INSTALL -> {
                    Button(
                        onClick = onInstallApk,
                        colors = ButtonDefaults.buttonColors(containerColor = GreenActive),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val btnText = if (downloadProgress.needsInstallPermission) {
                            "AUTORIZAR E INSTALAR"
                        } else {
                            "INSTALAR AHORA"
                        }
                        Text(btnText, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }

                UpdateDownloadProgress.Status.ERROR -> {
                    Button(
                        onClick = {
                            val url = updateInfo.apkUrl ?: updateInfo.releaseUrl
                            onStartDownload(url, updateInfo.latestVersion)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = OrangeFlame),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("REINTENTAR", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
            }
        },
        dismissButton = {
            when (downloadProgress.status) {
                UpdateDownloadProgress.Status.DOWNLOADING -> {
                    TextButton(onClick = onCancelDownload) {
                        Text("CANCELAR", color = RedDanger)
                    }
                }

                UpdateDownloadProgress.Status.ERROR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                val url = updateInfo.apkUrl ?: updateInfo.releaseUrl
                                ApkDownloader.openBrowserDownload(context, url)
                            }
                        ) {
                            Text("NAVEGADOR", color = TextSecondary)
                        }
                        TextButton(onClick = onDismiss) {
                            Text("CERRAR", color = TextMuted)
                        }
                    }
                }

                UpdateDownloadProgress.Status.READY_TO_INSTALL -> {
                    TextButton(onClick = onDismiss) {
                        Text("CERRAR", color = TextSecondary)
                    }
                }

                UpdateDownloadProgress.Status.IDLE -> {
                    TextButton(onClick = onDismiss) {
                        Text("MAS TARDE", color = TextSecondary)
                    }
                }
            }
        },
        containerColor = BackgroundElevated,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    )
}
