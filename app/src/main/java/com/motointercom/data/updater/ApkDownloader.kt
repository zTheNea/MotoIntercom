package com.motointercom.data.updater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object ApkDownloader {

    fun downloadApk(context: Context, apkUrl: String, versionName: String) {
        try {
            val fileName = "MotoIntercom-v$versionName.apk"
            val uri = Uri.parse(apkUrl)

            val request = DownloadManager.Request(uri).apply {
                setTitle("MotoIntercom v$versionName")
                setDescription("Descargando actualizacion APK...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(
                context,
                "Descargando $fileName. Revisa la barra de notificaciones.",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            // En caso de fallo con DownloadManager, abrir directamente en navegador
            openBrowserDownload(context, apkUrl)
        }
    }

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
