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
