package com.wcam.app.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binds the app process to local Wi-Fi (even without internet access, while cellular data is active)
 * so sockets to 192.168.10.1 always route over the backup camera's Wi-Fi AP.
 */
class WifiNetworkHelper(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var passiveCallback: ConnectivityManager.NetworkCallback? = null
    private var explicitRequestCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var isWifiBound: Boolean = false
        private set

    var onWifiAvailabilityChanged: ((Boolean) -> Unit)? = null

    fun startPassiveBinding() {
        if (passiveCallback != null) return
        val request = buildCameraWifiRequest()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                val bound = connectivityManager.bindProcessToNetwork(network)
                isWifiBound = bound
                Log.i(TAG, "Passive Wi-Fi onAvailable -> bindProcessToNetwork=$bound ($network)")
                mainHandler.post { onWifiAvailabilityChanged?.invoke(bound) }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.i(TAG, "Passive Wi-Fi onLost ($network)")
                if (explicitRequestCallback == null) {
                    connectivityManager.bindProcessToNetwork(null)
                    isWifiBound = false
                    mainHandler.post { onWifiAvailabilityChanged?.invoke(false) }
                }
            }
        }
        passiveCallback = callback
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register passive Wi-Fi callback", t)
            passiveCallback = null
        }
    }

    fun stop() {
        passiveCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            passiveCallback = null
        }
        explicitRequestCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
            explicitRequestCallback = null
        }
        runCatching { connectivityManager.bindProcessToNetwork(null) }
        isWifiBound = false
    }

    fun isWifiEnabled(): Boolean = runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun getCurrentSsid(): String? {
        return runCatching {
            val info = wifiManager.connectionInfo ?: return null
            val raw = info.ssid ?: return null
            val cleaned = raw.trim().removePrefix("\"").removeSuffix("\"")
            if (cleaned == "<unknown ssid>" || cleaned.isBlank()) null else cleaned
        }.getOrNull()
    }

    fun openSystemWifiSettings(context: Context) {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun connectToCameraAp(
        ssid: String,
        password: String,
        onResult: (Boolean) -> Unit
    ) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) {
            mainHandler.post { onResult(false) }
            return
        }

        explicitRequestCallback?.let { prev ->
            runCatching { connectivityManager.unregisterNetworkCallback(prev) }
            explicitRequestCallback = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(cleanSsid)
            if (password.isNotEmpty()) {
                specifierBuilder.setWpa2Passphrase(password)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            val delivered = AtomicBoolean(false)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    val bound = connectivityManager.bindProcessToNetwork(network)
                    isWifiBound = bound
                    Log.i(TAG, "requestNetwork onAvailable($cleanSsid) -> bound=$bound")
                    if (delivered.compareAndSet(false, true)) {
                        mainHandler.post {
                            onWifiAvailabilityChanged?.invoke(bound)
                            onResult(bound)
                        }
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    Log.w(TAG, "requestNetwork onUnavailable($cleanSsid)")
                    isWifiBound = false
                    if (delivered.compareAndSet(false, true)) {
                        mainHandler.post {
                            onWifiAvailabilityChanged?.invoke(false)
                            onResult(false)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.w(TAG, "requestNetwork onLost($cleanSsid)")
                    connectivityManager.bindProcessToNetwork(null)
                    isWifiBound = false
                    mainHandler.post { onWifiAvailabilityChanged?.invoke(false) }
                }
            }
            explicitRequestCallback = callback
            try {
                connectivityManager.requestNetwork(request, callback, 30_000)
            } catch (t: Throwable) {
                Log.e(TAG, "requestNetwork failed", t)
                explicitRequestCallback = null
                if (delivered.compareAndSet(false, true)) {
                    mainHandler.post { onResult(false) }
                }
            }
        } else {
            Thread {
                val ok = connectLegacyWifi(cleanSsid, password)
                mainHandler.post { onResult(ok) }
            }.start()
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacyWifi(ssid: String, password: String): Boolean {
        return try {
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (password.isNotEmpty()) {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                } else {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }
            val netId = wifiManager.addNetwork(conf)
            if (netId == -1) return false
            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy Wi-Fi connect failed", t)
            false
        }
    }

    private fun buildCameraWifiRequest(): NetworkRequest {
        return NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    }

    companion object {
        private const val TAG = "WifiNetworkHelper"
    }
}
