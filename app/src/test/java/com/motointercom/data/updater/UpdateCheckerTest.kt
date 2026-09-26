package com.motointercom.data.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `isVersionNewer returns true when remote is higher minor or patch`() {
        assertTrue(UpdateChecker.isVersionNewer("1.0.0", "1.0.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.0.0", "1.1.0"))
        assertTrue(UpdateChecker.isVersionNewer("1.1.0", "1.1.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.0.0", "2.0.0"))
        assertTrue(UpdateChecker.isVersionNewer("v1.0.0", "v1.0.1"))
        assertTrue(UpdateChecker.isVersionNewer("1.0.0", "v1.0.1"))
    }

    @Test
    fun `isVersionNewer returns false when versions are equal`() {
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isVersionNewer("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", "v1.0.0"))
        assertFalse(UpdateChecker.isVersionNewer("v1.2.3", "v1.2.3"))
    }

    @Test
    fun `isVersionNewer returns false when local is higher than remote`() {
        assertFalse(UpdateChecker.isVersionNewer("1.1.0", "1.0.0"))
        assertFalse(UpdateChecker.isVersionNewer("2.0.0", "1.9.9"))
        assertFalse(UpdateChecker.isVersionNewer("1.0.2", "1.0.1"))
    }

    @Test
    fun `isVersionNewer handles blank or malformed input gracefully`() {
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", ""))
        assertFalse(UpdateChecker.isVersionNewer("1.0.0", "   "))
    }
}
