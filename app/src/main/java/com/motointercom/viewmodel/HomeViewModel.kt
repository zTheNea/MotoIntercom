package com.motointercom.viewmodel

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.motointercom.data.wifi.DiscoveredSession
import com.motointercom.data.wifi.HotspotManager
import com.motointercom.data.wifi.NetworkUtils
import com.motointercom.data.wifi.SessionDiscovery
import com.motointercom.domain.model.Session
import com.motointercom.domain.model.SessionRole
import com.motointercom.domain.model.SessionState
import com.motointercom.data.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.content.Context
import com.motointercom.data.updater.ApkDownloader
import com.motointercom.data.updater.UpdateChecker
import com.motointercom.data.updater.UpdateDownloadProgress
import com.motointercom.data.updater.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    preferencesRepository: PreferencesRepository? = null
) : AndroidViewModel(application) {

    private val prefs = preferencesRepository ?: PreferencesRepository(application)
    val savedRiderName: String get() = prefs.riderName

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _localIp = MutableStateFlow(NetworkUtils.getLocalIpAddress(application))
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _suggestedHostIp = MutableStateFlow("")
    val suggestedHostIp: StateFlow<String> = _suggestedHostIp.asStateFlow()

    private val discovery = SessionDiscovery(application, viewModelScope)
    val discoveredSessions: StateFlow<List<DiscoveredSession>> = discovery.discoveredSessions

    private var hotspotManager: HotspotManager? = null

    private val updateChecker = UpdateChecker(application)

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _updateStatusMessage = MutableStateFlow<String?>(null)
    val updateStatusMessage: StateFlow<String?> = _updateStatusMessage.asStateFlow()

    private val _downloadProgress = MutableStateFlow(UpdateDownloadProgress())
    val downloadProgress: StateFlow<UpdateDownloadProgress> = _downloadProgress.asStateFlow()
    private var downloadJob: Job? = null

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hotspotManager = HotspotManager(application)
        }
        // Start listening for available sessions over WiFi
        discovery.startListening()
        refreshLocalIp()
        checkForUpdates(isManual = false)
    }

    fun refreshLocalIp() {
        val app = getApplication<Application>()
        _localIp.value = NetworkUtils.getLocalIpAddress(app)
        NetworkUtils.getGatewayIpAddress(app)?.let { gw ->
            _suggestedHostIp.value = gw
        }
    }

    fun createSession(riderName: String) {
        if (_session.value.state != SessionState.IDLE) return

        if (riderName.isNotBlank()) {
            prefs.riderName = riderName.trim()
        }

        _session.update { it.copy(state = SessionState.CREATING, localRiderName = riderName) }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            _errorMessage.value = "Se requiere Android 8.0 o superior para crear sesiones"
            _session.update { it.copy(state = SessionState.IDLE) }
            return
        }

        hotspotManager?.start(
            onSuccess = { config ->
                discovery.stopListening()
                _session.update {
                    it.copy(
                        state = SessionState.ACTIVE,
                        role = SessionRole.HOST,
                        ssid = config.ssid,
                        passphrase = config.passphrase,
                        hostIp = config.hostIp
                    )
                }
            },
            onError = { error ->
                _errorMessage.value = error
                _session.update { it.copy(state = SessionState.IDLE) }
            }
        )
    }

    fun joinSession(riderName: String, hostIp: String) {
        if (hostIp.isBlank()) {
            _errorMessage.value = "Ingresa la IP del anfitrión"
            return
        }
        if (riderName.isNotBlank()) {
            prefs.riderName = riderName.trim()
        }
        discovery.stopListening()
        _session.update {
            it.copy(
                state = SessionState.ACTIVE,
                role = SessionRole.CLIENT,
                localRiderName = riderName,
                hostIp = hostIp
            )
        }
    }

    fun endSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hotspotManager?.stop()
        }
        _session.update { Session() }
        refreshLocalIp()
        discovery.startListening()
    }

    fun clearError() { _errorMessage.value = null }

    fun setErrorMessage(msg: String) { _errorMessage.value = msg }

    fun checkForUpdates(isManual: Boolean = false) {
        viewModelScope.launch {
            if (isManual) {
                _isCheckingUpdate.value = true
                _updateStatusMessage.value = null
            }
            val result = updateChecker.checkForUpdates()
            result.onSuccess { info ->
                if (info.isUpdateAvailable) {
                    _updateInfo.value = info
                } else if (isManual) {
                    _updateStatusMessage.value = "Tienes la version mas reciente instalada (v${info.currentVersion})."
                }
            }.onFailure {
                if (isManual) {
                    _updateStatusMessage.value = "No fue posible verificar actualizaciones en este momento."
                }
            }
            if (isManual) {
                _isCheckingUpdate.value = false
            }
        }
    }

    fun dismissUpdate() {
        cancelApkDownload()
        _updateInfo.value = null
    }

    fun startApkDownload(apkUrl: String, versionName: String) {
        downloadJob?.cancel()
        _downloadProgress.value = UpdateDownloadProgress(status = UpdateDownloadProgress.Status.DOWNLOADING)
        downloadJob = viewModelScope.launch {
            try {
                val file = ApkDownloader.downloadApkStreaming(
                    context = getApplication(),
                    apkUrl = apkUrl,
                    versionName = versionName
                ) { downloaded, total ->
                    val ratio = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                    _downloadProgress.value = UpdateDownloadProgress(
                        status = UpdateDownloadProgress.Status.DOWNLOADING,
                        progress = ratio,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                }
                val canInstall = ApkDownloader.canRequestPackageInstalls(getApplication())
                _downloadProgress.value = UpdateDownloadProgress(
                    status = UpdateDownloadProgress.Status.READY_TO_INSTALL,
                    progress = 1f,
                    downloadedBytes = file.length(),
                    totalBytes = file.length(),
                    apkPath = file.absolutePath,
                    needsInstallPermission = !canInstall
                )
                if (canInstall) {
                    installDownloadedApk()
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _downloadProgress.value = UpdateDownloadProgress(status = UpdateDownloadProgress.Status.IDLE)
                } else {
                    _downloadProgress.value = UpdateDownloadProgress(
                        status = UpdateDownloadProgress.Status.ERROR,
                        error = e.message ?: "Error desconocido durante la descarga."
                    )
                }
            }
        }
    }

    fun cancelApkDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadProgress.value = UpdateDownloadProgress(status = UpdateDownloadProgress.Status.IDLE)
    }

    fun installDownloadedApk(context: Context = getApplication()) {
        val path = _downloadProgress.value.apkPath ?: return
        val file = File(path)
        if (!file.exists()) {
            _downloadProgress.value = UpdateDownloadProgress(
                status = UpdateDownloadProgress.Status.ERROR,
                error = "El archivo APK descargado no esta disponible."
            )
            return
        }

        if (!ApkDownloader.canRequestPackageInstalls(context)) {
            _downloadProgress.value = _downloadProgress.value.copy(needsInstallPermission = true)
            ApkDownloader.openUnknownSourcesSettings(context)
        } else {
            _downloadProgress.value = _downloadProgress.value.copy(needsInstallPermission = false)
            val result = ApkDownloader.installApk(context, file)
            result.onFailure { err ->
                _downloadProgress.value = _downloadProgress.value.copy(
                    status = UpdateDownloadProgress.Status.ERROR,
                    error = "Fallo al iniciar el instalador: ${err.message}"
                )
            }
        }
    }

    fun checkInstallPermissionOnResume(context: Context = getApplication()) {
        if (_downloadProgress.value.status == UpdateDownloadProgress.Status.READY_TO_INSTALL) {
            val canInstall = ApkDownloader.canRequestPackageInstalls(context)
            _downloadProgress.value = _downloadProgress.value.copy(needsInstallPermission = !canInstall)
            if (canInstall) {
                installDownloadedApk(context)
            }
        }
    }

    fun clearUpdateStatusMessage() {
        _updateStatusMessage.value = null
    }

    override fun onCleared() {
        discovery.stopListening()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hotspotManager?.stop()
        }
        super.onCleared()
    }
}
