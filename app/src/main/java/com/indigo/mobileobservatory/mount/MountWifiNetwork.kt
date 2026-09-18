package com.indigo.mobileobservatory.mount

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket

/** Bind UDP traffic to the phone's Wi-Fi so mount-AP packets are not routed via cellular. */
internal object MountWifiNetwork {
    fun bindSocket(context: Context, socket: DatagramSocket) {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val wifi = preferredWifiNetwork(connectivity) ?: return
        runCatching { wifi.bindSocket(socket) }
    }

    fun preferredWifiNetwork(connectivity: ConnectivityManager): Network? {
        @Suppress("DEPRECATION")
        val wifi = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            network to capabilities
        }
        return wifi.firstOrNull { (_, capabilities) ->
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }?.first ?: wifi.firstOrNull()?.first
    }
}
