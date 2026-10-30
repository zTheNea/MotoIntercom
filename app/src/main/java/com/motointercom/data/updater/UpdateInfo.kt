package com.motointercom.data.updater

data class UpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val releaseNotes: String,
    val apkUrl: String?,
    val apkSize: Long,
    val releaseUrl: String
)

data class UpdateDownloadProgress(
    val status: Status = Status.IDLE,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
    val apkPath: String? = null,
    val needsInstallPermission: Boolean = false
) {
    enum class Status {
        IDLE,
        DOWNLOADING,
        READY_TO_INSTALL,
        ERROR
    }
}

