package com.motointercom.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.motointercom.domain.model.LinkQuality
import com.motointercom.domain.model.Rider
import com.motointercom.domain.model.Session
import com.motointercom.domain.model.SessionRole
import com.motointercom.domain.model.SessionState
import com.motointercom.ui.screens.SessionScreen
import com.motointercom.ui.theme.MotoIntercomTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

@RunWith(AndroidJUnit4::class)
class SessionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sessionScreen_rendersControlsAndRiders() {
        val session = Session(
            id = "test-session",
            localRiderName = "Piloto 1",
            role = SessionRole.HOST,
            state = SessionState.ACTIVE,
            hostIp = "192.168.49.1"
        )

        val riders = listOf(
            Rider(
                id = "rider-1",
                name = "Piloto 1",
                address = InetAddress.getByName("192.168.49.1"),
                port = 50005,
                isTalking = false,
                isConnected = true,
                linkQuality = LinkQuality.OPTIMA
            ),
            Rider(
                id = "rider-2",
                name = "Piloto 2",
                address = InetAddress.getByName("192.168.49.2"),
                port = 50005,
                isTalking = false,
                isConnected = true,
                linkQuality = LinkQuality.BUENA
            )
        )

        var muteToggled = false
        var voxToggled = false
        var speakerToggled = false

        composeTestRule.setContent {
            MotoIntercomTheme {
                SessionScreen(
                    session = session,
                    localIp = "192.168.49.1",
                    riders = riders,
                    isMuted = false,
                    isPttActive = false,
                    isVox = false,
                    isSpeaker = false,
                    connectedCount = 2,
                    onMuteToggle = { muteToggled = true },
                    onPttDown = {},
                    onPttUp = {},
                    onVoxToggle = { voxToggled = true },
                    onSpeakerToggle = { speakerToggled = true },
                    onEndSession = {}
                )
            }
        }

        // Verify ControlBar labels
        composeTestRule.onNodeWithText("Mic").assertIsDisplayed()
        composeTestRule.onNodeWithText("VOX").assertIsDisplayed()
        composeTestRule.onNodeWithText("Casco").assertIsDisplayed()
        composeTestRule.onNodeWithText("Música").assertIsDisplayed()

        // Test clicking buttons
        composeTestRule.onNodeWithText("Mic").performClick()
        assert(muteToggled)

        composeTestRule.onNodeWithText("VOX").performClick()
        assert(voxToggled)

        composeTestRule.onNodeWithText("Casco").performClick()
        assert(speakerToggled)

        // Verify Rider QoS label is displayed
        composeTestRule.onNodeWithText("• Buena").assertIsDisplayed()
    }
}
