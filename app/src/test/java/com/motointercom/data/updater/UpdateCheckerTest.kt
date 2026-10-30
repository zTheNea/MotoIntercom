package com.motointercom.data.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun testIsVersionNewerReturnsTrueWhenRemoteIsHigherMinorOrPatch() {
        assertTrue(UpdateChecker.isVersionNewer("1.0.0", "1.0.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.0.0", "1.1.0"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.0", "1.1.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.1", "1.1.2"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.2", "1.2.0"))
        assertTrue(UpdateChecker.isVersionNewer("1.2.0", "1.2.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.0.0", "2.0.0"))
        assertTrue(UpdateChecker.isVersionNewer("v1.0.0", "v1.0.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.0.0", "v1.0.1"))
    }

    @Test
    fun testIsVersionNewerReturnsFalseWhenVersionsAreEqual() {
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isVersionNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", "v1.0.0"))
        assertFalse(UpdateChecker.isVersionNewer("v1.2.3", "v1.2.3"))
    }

    @Test
    fun testIsVersionNewerReturnsFalseWhenLocalIsHigherThanRemote() {
        assertFalse(UpdateChecker.isVersionNewer("1.1.0", "1.0.0"))
        assertFalse(UpdateChecker.isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(UpdateChecker.isVersionNewer("1.0.2", "1.0.1"))
    }

    @Test
    fun testIsVersionNewerHandlesBlankOrMalformedInputGracefully() {
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", ""))
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", "   "))
    }

    @Test
    fun testUpdateDownloadProgressInitializesWithDefaultValues() {
        val progress = UpdateDownloadProgress()
        assertEquals(UpdateDownloadProgress.Status.IDLE, progress.status)
        assertEquals(0f, progress.progress, 0.001f)
        assertEquals(0L, progress.downloadedBytes)
        assertEquals(0L, progress.totalBytes)
        assertNull(progress.error)
        assertNull(progress.apkPath)
        assertFalse(progress.needsInstallPermission)
    }

    @Test
    fun testUpdateDownloadProgressCopyMaintainsStateTransitions() {
        val downloading = UpdateDownloadProgress(
            status = UpdateDownloadProgress.Status.DOWNLOADING,
            progress = 0.5f,
            downloadedBytes = 500L,
            totalBytes = 1000L
        )
        val ready = downloading.copy(
            status = UpdateDownloadProgress.Status.READY_TO_INSTALL,
            progress = 1.0f,
            downloadedBytes = 1000L,
            apkPath = "/tmp/test.apk",
            needsInstallPermission = false
        )
        assertEquals(UpdateDownloadProgress.Status.READY_TO_INSTALL, ready.status)
        assertEquals(1.0f, ready.progress, 0.001f)
        assertEquals("/tmp/test.apk", ready.apkPath)
    }
}
