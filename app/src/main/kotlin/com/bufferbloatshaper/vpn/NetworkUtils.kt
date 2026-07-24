package com.bufferbloatshaper.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.net.DatagramSocket
import java.net.Socket

/**
 * Utility for network operations and socket protection.
 * Ensures deterministic physical network selection and strict failure handling.
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Get the current active physical (non-VPN) network deterministically.
     * Uses ConnectivityManager.activeNetwork first, falling back to non-VPN networks.
     */
    fun getPhysicalDefaultNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val caps = cm.getNetworkCapabilities(activeNet)
                if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return activeNet
                }
            }
        }

        // Fallback: iterate all networks and pick the first non-VPN active network
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        for (net in networks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return net
            }
        }
        return null
    }

    /**
     * Protect a DatagramSocket from the VPN tunnel and bind it to the physical network.
     * Returns true if protection succeeded, false if protection failed (must abort).
     */
    fun protectAndBind(vpnService: VpnService, socket: DatagramSocket): Boolean {
        val protected = vpnService.protect(socket)
        if (!protected) {
            Log.e(TAG, "CRITICAL: vpnService.protect() failed for DatagramSocket!")
            return false
        }

        val physicalNet = getPhysicalDefaultNetwork(vpnService)
        if (physicalNet != null) {
            try {
                physicalNet.bindSocket(socket)
                Log.d(TAG, "Successfully bound DatagramSocket to physical network $physicalNet")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind DatagramSocket to physical network $physicalNet: ${e.message}")
            }
        }
        return true
    }

    /**
     * Protect a Socket from the VPN tunnel and bind it to the physical network.
     * Returns true if protection succeeded, false if protection failed (must abort).
     */
    fun protectAndBind(vpnService: VpnService, socket: Socket): Boolean {
        val protected = vpnService.protect(socket)
        if (!protected) {
            Log.e(TAG, "CRITICAL: vpnService.protect() failed for Socket!")
            return false
        }

        val physicalNet = getPhysicalDefaultNetwork(vpnService)
        if (physicalNet != null) {
            try {
                physicalNet.bindSocket(socket)
                Log.d(TAG, "Successfully bound Socket to physical network $physicalNet")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to bind Socket to physical network $physicalNet: ${e.message}")
            }
        }
        return true
    }
}
