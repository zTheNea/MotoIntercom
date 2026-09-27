package com.motointercom.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.motointercom.domain.model.Session
import com.motointercom.domain.model.SessionRole
import com.motointercom.domain.model.SessionState
import com.motointercom.ui.screens.HomeScreen
import com.motointercom.ui.theme.MotoIntercomTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_rendersInitialTabsAndSwitchesTabs() {
        val session = Session(
            id = "test-session",
            localRiderName = "Rider Test",
            role = SessionRole.HOST,
            state = SessionState.IDLE
        )

        composeTestRule.setContent {
            MotoIntercomTheme {
                HomeScreen(
                    session = session,
                    errorMessage = null,
                    discoveredSessions = emptyList(),
                    updateInfo = null,
                    initialRiderName = "Rider Test",
                    onCreateSession = {},
                    onJoinSession = { _, _ -> },
                    onClearError = {},
                    onNavigateToSession = {}
                )
            }
        }

        // Verify tabs are displayed
        composeTestRule.onNodeWithText("CREAR GRUPO").assertIsDisplayed()
        composeTestRule.onNodeWithText("UNIRSE").assertIsDisplayed()

        // Switch to UNIRSE tab
        composeTestRule.onNodeWithText("UNIRSE").performClick()

        // Switch back to CREAR GRUPO
        composeTestRule.onNodeWithText("CREAR GRUPO").performClick()
        composeTestRule.onNodeWithText("CREAR GRUPO").assertIsDisplayed()
    }
}
