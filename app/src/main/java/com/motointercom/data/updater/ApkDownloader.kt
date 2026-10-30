package com.motointercom.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Gestor de descarga e instalacion de actualizaciones APK dentro de la aplicacion.
 *
 * Caracteristicas principales:
 * - Descarga directa en memoria intermedia con streaming en segundo plano y reporte de progreso.
 * - Soporte para redirecciones HTTP 3xx (GitHub Releases -> AWS S3 / CDN).
 * - Verificacion de permisos de instalacion de paquetes desconocidos (Android 8.0+).
 * - Activacion del instalador nativo del sistema mediante FileProvider seguro.
 */
object ApkDownloader {

    private const val TAG = "ApkDownloader"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 30000
    private const val BUFFER_SIZE = 16384

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "No fue posible abrir ajustes de fuentes desconocidas", e)
            val fallbackIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        }
    }

    /**
     * Descarga el archivo APK en el almacenamiento interno de cache con reporte continuo de progreso.
     * Soporta cancelacion limpia a traves del contexto de la corrutina.
     */
    suspend fun downloadApkStreaming(
        context: Context,
        apkUrl: String,
        versionName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "apk_updates")
        if (!updateDir.exists()) {
            updateDir.mkdirs()
        }

        // Limpieza de APKs anteriores en cache
        updateDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".apk", ignoreCase = true)) {
                file.delete()
            }
        }

        val targetFile = File(updateDir, "MotoIntercom-v$versionName.apk")
        if (targetFile.exists()) {
            targetFile.delete()
        }

        var currentUrl = apkUrl
        var redirects = 0
        var connection: HttpURLConnection? = null

        try {
            while (redirects < 6) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MotoIntercom-Android")
                    setRequestProperty(
                        "Accept",
                        "application/octet-stream, application/vnd.android.package-archive, */*"
                    )
                }

                val responseCode = connection.responseCode
                if (responseCode in 301..308) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) {
                        throw IOException("Redireccion HTTP $responseCode sin cabecera Location.")
                    }
                    currentUrl = location
                    redirects++
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                    connection.disconnect()
                    throw IOException("Error del servidor al descargar APK (HTTP $responseCode).")
                }
            }

            val conn = connection ?: throw IOException("No se pudo establecer conexion con el servidor.")
            val totalBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                conn.contentLengthLong
            } else {
                conn.contentLength.toLong()
            }

            var downloadedBytes = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            conn.inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    var bytesRead: Int
                    var lastReportTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (!coroutineContext.isActive) {
                            targetFile.delete()
                            throw IOException("Descarga cancelada por el usuario.")
                        }

                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 80L || downloadedBytes == totalBytes) {
                            lastReportTime = now
                            onProgress(downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            if (!targetFile.exists() || targetFile.length() == 0L) {
                throw IOException("El archivo descargado esta incompleto o corrupto.")
            }

            onProgress(downloadedBytes, totalBytes)
            targetFile
        } catch (e: Exception) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Inicia el flujo de instalacion nativo del sistema utilizando FileProvider.
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return runCatching {
            if (!apkFile.exists() || apkFile.length() == 0L) {
                throw IllegalStateException("El archivo APK no existe en el almacenamiento.")
            }

            val authority = "${context.packageName}.provider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
        }
    }

    /**
     * Fallback para abrir el enlace en el navegador en caso de contingencia.
     */
    fun openBrowserDownload(context: Context, apkUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                context,
                "No fue posible abrir el enlace de descarga.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
