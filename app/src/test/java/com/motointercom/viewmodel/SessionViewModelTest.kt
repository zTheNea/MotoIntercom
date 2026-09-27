package com.motointercom.viewmodel

import android.app.Application
import android.content.Context
import com.motointercom.data.preferences.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: SessionViewModel
    private lateinit var fakePrefs: FakePreferencesRepository

    class FakePreferencesRepository(context: Context) : PreferencesRepository(context) {
        override var riderName: String = "TestRider"
        override var isVoxEnabled: Boolean = false
        override var isSpeakerOn: Boolean = false
        override var musicVolume: Float = 0.85f
        override var isMultitasking: Boolean = true
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val fakeApp = object : Application() {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? = null
        }

        fakePrefs = FakePreferencesRepository(fakeApp)
        viewModel = SessionViewModel(fakeApp, fakePrefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial uiState matches default values and preferences`() {
        val state = viewModel.uiState.value
        assertFalse(state.isMuted)
        assertFalse(state.isPttActive)
        assertFalse(state.isVox)
        assertFalse(state.isSpeaker)
        assertEquals(0.85f, state.musicVolume, 0.001f)
        assertTrue(state.isMultitasking)
        assertEquals(1, state.connectedCount)
        assertTrue(state.riders.isEmpty())
        assertFalse(state.sessionTerminated)
    }

    @Test
    fun `setMuted updates uiState and isMuted StateFlow`() {
        viewModel.setMuted(true)
        assertTrue(viewModel.uiState.value.isMuted)
        assertTrue(viewModel.isMuted.value)

        viewModel.setMuted(false)
        assertFalse(viewModel.uiState.value.isMuted)
        assertFalse(viewModel.isMuted.value)
    }

    @Test
    fun `setPttActive updates uiState and isPttActive StateFlow`() {
        viewModel.setPttActive(true)
        assertTrue(viewModel.uiState.value.isPttActive)
        assertTrue(viewModel.isPttActive.value)

        viewModel.setPttActive(false)
        assertFalse(viewModel.uiState.value.isPttActive)
        assertFalse(viewModel.isPttActive.value)
    }

    @Test
    fun `setVox updates uiState and persists preference`() {
        viewModel.setVox(true)
        assertTrue(viewModel.uiState.value.isVox)
        assertTrue(viewModel.isVox.value)
        assertTrue(fakePrefs.isVoxEnabled)

        viewModel.setVox(false)
        assertFalse(viewModel.uiState.value.isVox)
        assertFalse(fakePrefs.isVoxEnabled)
    }

    @Test
    fun `setSpeaker updates uiState and persists preference`() {
        viewModel.setSpeaker(true)
        assertTrue(viewModel.uiState.value.isSpeaker)
        assertTrue(viewModel.isSpeaker.value)
        assertTrue(fakePrefs.isSpeakerOn)

        viewModel.setSpeaker(false)
        assertFalse(viewModel.uiState.value.isSpeaker)
        assertFalse(fakePrefs.isSpeakerOn)
    }

    @Test
    fun `setMusicVolume updates uiState and persists preference`() {
        viewModel.setMusicVolume(0.5f)
        assertEquals(0.5f, viewModel.uiState.value.musicVolume, 0.001f)
        assertEquals(0.5f, viewModel.musicVolume.value, 0.001f)
        assertEquals(0.5f, fakePrefs.musicVolume, 0.001f)
    }

    @Test
    fun `toggleMultitasking flips value in uiState and persists preference`() {
        assertTrue(viewModel.uiState.value.isMultitasking)

        viewModel.toggleMultitasking()
        assertFalse(viewModel.uiState.value.isMultitasking)
        assertFalse(viewModel.isMultitasking.value)
        assertFalse(fakePrefs.isMultitasking)

        viewModel.toggleMultitasking()
        assertTrue(viewModel.uiState.value.isMultitasking)
        assertTrue(fakePrefs.isMultitasking)
    }

    @Test
    fun `stopAndUnbind resets state`() {
        viewModel.stopAndUnbind()
        val state = viewModel.uiState.value
        assertEquals(1, state.connectedCount)
        assertTrue(state.riders.isEmpty())
        assertFalse(state.sessionTerminated)
        assertFalse(state.reconnectionState.isReconnecting)
    }
}
