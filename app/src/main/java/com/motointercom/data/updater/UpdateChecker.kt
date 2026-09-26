package com.motointercom.data.updater

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        private const val GITHUB_REPO = "zTheNea/MotoIntercom"
        private const val GITHUB_API_LATEST = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        private const val CONNECT_TIMEOUT_MS = 7000
        private const val READ_TIMEOUT_MS = 7000

        fun isVersionNewer(current: String, remote: String): Boolean {
            if (remote.isBlank()) return false
            val cParts = current.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
            val rParts = remote.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(cParts.size, rParts.size)
            for (i in 0 until maxLen) {
                val c = cParts.getOrElse(i) { 0 }
                val r = rParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.1.1"
        } catch (_: Exception) {
            "1.1.1"
        }
    }

    suspend fun checkForUpdates(): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(GITHUB_API_LATEST)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "MotoIntercom-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Servidor respondio con codigo HTTP $responseCode")
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.use { it.readText() }
                val json = JSONObject(response)

                val tagName = json.optString("tag_name", "").trim()
                val remoteVersion = tagName.removePrefix("v").trim()
                val currentVersion = getCurrentVersionName()
                val body = json.optString("body", "")
                val htmlUrl = json.optString("html_url", "https://github.com/$GITHUB_REPO/releases/latest")

                var apkDownloadUrl: String? = null
                var apkFileSize = 0L

                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            apkFileSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                val isNewer = isVersionNewer(currentVersion, remoteVersion)

                UpdateInfo(
                    isUpdateAvailable = isNewer,
                    latestVersion = remoteVersion,
                    currentVersion = currentVersion,
                    releaseNotes = body,
                    apkUrl = apkDownloadUrl,
                    apkSize = apkFileSize,
                    releaseUrl = htmlUrl
                )
            } finally {
                connection.disconnect()
            }
        }
    }
}
