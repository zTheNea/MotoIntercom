package com.motointercom.viewmodel

import android.app.Application
import android.content.Context
import com.motointercom.domain.model.SessionRole
import com.motointercom.domain.model.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

import com.motointercom.data.preferences.PreferencesRepository

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: HomeViewModel
    private lateinit var fakePrefs: FakePreferencesRepository

    class FakePreferencesRepository(context: Context) : PreferencesRepository(context) {
        override var riderName: String = ""
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
        viewModel = HomeViewModel(fakeApp, fakePrefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial session state is IDLE`() {
        assertEquals(SessionState.IDLE, viewModel.session.value.state)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `joinSession with empty IP sets error message`() {
        viewModel.joinSession("Carlos", "")
        assertEquals("Ingresa la IP del anfitrión", viewModel.errorMessage.value)
        assertEquals(SessionState.IDLE, viewModel.session.value.state)
    }

    @Test
    fun `joinSession with valid IP activates client session`() {
        viewModel.joinSession("Carlos", "192.168.43.1")

        val current = viewModel.session.value
        assertEquals(SessionState.ACTIVE, current.state)
        assertEquals(SessionRole.CLIENT, current.role)
        assertEquals("Carlos", current.localRiderName)
        assertEquals("192.168.43.1", current.hostIp)
    }

    @Test
    fun `endSession resets session back to idle`() {
        viewModel.joinSession("Carlos", "192.168.43.1")
        assertEquals(SessionState.ACTIVE, viewModel.session.value.state)

        viewModel.endSession()
        assertEquals(SessionState.IDLE, viewModel.session.value.state)
    }

    @Test
    fun `clearError clears existing error message`() {
        viewModel.setErrorMessage("Error de prueba")
        assertEquals("Error de prueba", viewModel.errorMessage.value)

        viewModel.clearError()
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `createSession when already active does not transition`() {
        viewModel.joinSession("Carlos", "192.168.43.1")
        assertEquals(SessionState.ACTIVE, viewModel.session.value.state)

        // Should ignore createSession since state != IDLE
        viewModel.createSession("Carlos")
        assertEquals(SessionState.ACTIVE, viewModel.session.value.state)
    }

    @Test
    fun `joinSession persists non-blank rider name to preferences`() {
        viewModel.joinSession("Motorista77", "192.168.43.1")
        assertEquals("Motorista77", fakePrefs.riderName)
        assertEquals("Motorista77", viewModel.savedRiderName)
    }
}
