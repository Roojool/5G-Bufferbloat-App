package com.bufferbloatshaper.calibration

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Monitors network state changes to trigger active recalibration (§5).
 *
 * Registers callbacks with TelephonyManager and ConnectivityManager to detect:
 *   - RAT changes (5G ↔ LTE ↔ 3G)
 *   - Cell handovers
 *   - Network type switches (WiFi ↔ cellular)
 *   - Signal strength changes
 *
 * These events invalidate a future network-specific calibration profile. They
 * do not automatically start a traffic probe: only independently measured
 * physical-network samples may change a shaping rate.
 */
class NetworkStateMonitor(private val context: Context) {

    /** Callback for network state changes. */
    var onNetworkStateChanged: ((reason: String, networkType: String) -> Unit)? = null

    private var connectivityManager: ConnectivityManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Last known network type for change detection. */
    @Volatile
    var currentNetworkType: String = "Unknown"
        private set

    /** Whether monitoring is active. */
    @Volatile
    var isMonitoring: Boolean = false
        private set

    /**
     * Start monitoring network state changes.
     */
    fun start() {
        if (isMonitoring) return
        isMonitoring = true

        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                as? TelephonyManager

        // Register connectivity callback for WiFi/cellular switches and capability changes
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val newType = detectNetworkType()
                if (newType != currentNetworkType) {
                    val oldType = currentNetworkType
                    currentNetworkType = newType
                    Log.d(TAG, "Network available: $oldType → $newType")
                    onNetworkStateChanged?.invoke(
                        "Network type changed: $oldType → $newType",
                        newType
                    )
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val newType = detectNetworkType(capabilities)
                if (newType != currentNetworkType) {
                    val oldType = currentNetworkType
                    currentNetworkType = newType
                    Log.d(TAG, "Capabilities changed: $oldType → $newType")
                    onNetworkStateChanged?.invoke(
                        "Network capabilities changed: $oldType → $newType",
                        newType
                    )
                }
            }

            override fun onLost(network: Network) {
                val oldType = currentNetworkType
                currentNetworkType = "Disconnected"
                Log.d(TAG, "Network lost: $oldType → Disconnected")
                onNetworkStateChanged?.invoke("Network lost", "Disconnected")
            }
        }

        networkCallback = callback

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }

        // Detect initial network type
        currentNetworkType = detectNetworkType()
        Log.d(TAG, "Network monitoring started. Current type: $currentNetworkType")
    }

    /**
     * Stop monitoring network state.
     */
    fun stop() {
        isMonitoring = false
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback", e)
            }
        }
        networkCallback = null
        Log.d(TAG, "Network monitoring stopped")
    }

    /**
     * Detect the current network type from ConnectivityManager.
     */
    fun detectNetworkType(capabilities: NetworkCapabilities? = null): String {
        val caps = capabilities ?: run {
            val activeNetwork = connectivityManager?.activeNetwork ?: return "Unknown"
            connectivityManager?.getNetworkCapabilities(activeNetwork) ?: return "Unknown"
        }

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> detectCellularType()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Unknown"
        }
    }

    /**
     * Detect the specific cellular RAT (Radio Access Technology).
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // Guarded immediately below; no telephony read without grant.
    private fun detectCellularType(): String {
        // RAT detail is optional. Do not attempt a protected telephony read
        // unless a future, explicit user flow has granted the permission.
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "Cellular"
        }
        return try {
            when (telephonyManager?.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA -> "HSPA+"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "CDMA/EVDO"
                else -> "Cellular"
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for data network type", e)
            "Cellular"
        }
    }

    companion object {
        private const val TAG = "NetworkStateMonitor"
    }
}
