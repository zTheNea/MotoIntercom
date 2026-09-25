package com.motointercom.data.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class HotspotConfig(
    val ssid: String,
    val passphrase: String,
    val hostIp: String
)

/**
 * Creates a local-only WiFi hotspot (SoftAP) on the host device.
 *
 * This hotspot does NOT route internet traffic — it creates a pure local
 * network (192.168.x.x) between participants. This is exactly what we need
 * for offline motorcycle intercom operation.
 *
 * Requires API 26+ (Android 8.0)
 * The OS auto-generates SSID and passphrase for security.
 */
@RequiresApi(Build.VERSION_CODES.O)
class HotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "HotspotManager"
        // Common hotspot gateway IPs — used as fallback
        private val COMMON_HOTSPOT_IPS = listOf("192.168.43.1", "192.168.49.1", "10.0.0.1")
    }

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _config = MutableStateFlow<HotspotConfig?>(null)
    val config: StateFlow<HotspotConfig?> = _config.asStateFlow()

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Start the local-only hotspot.
     * [onSuccess] is called on the main thread with the network credentials.
     * [onError] is called if the hotspot cannot be started.
     */
    fun start(onSuccess: (HotspotConfig) -> Unit, onError: (String) -> Unit) {
        if (_isActive.value) {
            _config.value?.let { onSuccess(it) }
            return
        }

        // Check if device location is enabled (Mandatory on Android for LocalOnlyHotspot)
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val isLocationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager?.isLocationEnabled == true
        } else {
            @Suppress("DEPRECATION")
            (locationManager?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
             locationManager?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true)
        }

        if (!isLocationEnabled) {
            onError("⚠️ La Ubicación (GPS) está desactivada. Desliza la barra superior de tu teléfono y activa 'Ubicación' para crear el grupo.")
            return
        }

        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {

                    override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                        reservation = r

                        val ssid: String
                        val pass: String

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            ssid = r.softApConfiguration?.ssid ?: "MotoIntercom"
                            pass = r.softApConfiguration?.passphrase ?: generatePass()
                        } else {
                            @Suppress("DEPRECATION")
                            ssid = r.wifiConfiguration?.SSID?.removePrefix("\"")
                                ?.removeSuffix("\"") ?: "MotoIntercom"
                            @Suppress("DEPRECATION")
                            pass = r.wifiConfiguration?.preSharedKey ?: generatePass()
                        }

                        val ip = detectHostIp()
                        val cfg = HotspotConfig(ssid = ssid, passphrase = pass, hostIp = ip)
                        _config.value = cfg
                        _isActive.value = true
                        Log.d(TAG, "Hotspot started ✓ SSID=$ssid | IP=$ip")
                        onSuccess(cfg)
                    }

                    override fun onStopped() {
                        _isActive.value = false
                        _config.value = null
                        Log.d(TAG, "Hotspot stopped")
                    }

                    override fun onFailed(reason: Int) {
                        _isActive.value = false
                        when (reason) {
                            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> {
                                // Device already has normal tethering hotspot active! Use it directly.
                                Log.i(TAG, "Regular tethering active, using existing hotspot network")
                                val ip = detectHostIp()
                                val cfg = HotspotConfig(
                                    ssid = "Punto de Acceso del Teléfono",
                                    passphrase = "(Tu contraseña de zona WiFi)",
                                    hostIp = ip
                                )
                                _config.value = cfg
                                _isActive.value = true
                                onSuccess(cfg)
                                return
                            }
                            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> {
                                onError("Sin canal WiFi disponible. Apaga y enciende el WiFi de tu teléfono.")
                            }
                            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> {
                                onError("El sistema no permite compartir conexión. Puedes activar manualmente tu 'Zona WiFi' en Ajustes.")
                            }
                            else -> {
                                onError("Error al iniciar punto de acceso ($reason). Si persiste, activa manualmente tu 'Punto de Acceso' en Ajustes.")
                            }
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting hotspot", e)
            val msg = when {
                e is SecurityException || e.message?.contains("nearby", ignoreCase = true) == true ->
                    "⚠️ Falta el permiso 'Dispositivos Cercanos'. Ve a Ajustes > Apps > MotoIntercom > Permisos y actívalo."
                e.message?.contains("Location mode is not enabled", ignoreCase = true) == true ->
                    "⚠️ La Ubicación (GPS) está desactivada. Actívala en la barra superior de tu teléfono."
                else -> e.localizedMessage ?: "Error al iniciar punto de acceso"
            }
            onError(msg)
        }
    }

    fun stop() {
        reservation?.close()
        reservation = null
        _isActive.value = false
        _config.value = null
        Log.d(TAG, "Hotspot reservation closed")
    }

    /**
     * Try to find the IP address of the hotspot network interface.
     * Falls back to the most common hotspot IP (192.168.43.1) on failure.
     */
    private fun detectHostIp(): String {
        return NetworkUtils.getLocalIpAddress(context)
    }

    private fun generatePass(): String =
        (1..8).map { ('a'..'z').random() }.joinToString("")
}
