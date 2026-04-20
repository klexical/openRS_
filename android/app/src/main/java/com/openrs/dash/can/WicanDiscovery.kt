package com.openrs.dash.can

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay

/**
 * Discovers WiCAN adapters on the local network via mDNS (DNS-SD).
 *
 * WiCAN devices register as `_http._tcp` with hostnames like `wican_xxxxxxxxxxxx`.
 * This scanner finds them and resolves their IP addresses so the app can
 * auto-populate the host field instead of requiring manual IP entry.
 */
object WicanDiscovery {

    data class DiscoveredDevice(
        val name: String,
        val host: String,
        val port: Int
    )

    private const val SERVICE_TYPE = "_http._tcp."
    private const val SCAN_DURATION_MS = 4_000L

    /**
     * Scan for WiCAN devices on the local network.
     * Runs discovery for [SCAN_DURATION_MS], then returns all resolved devices.
     */
    suspend fun scan(ctx: Context): List<DiscoveredDevice> {
        val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: return emptyList()

        val results = mutableListOf<DiscoveredDevice>()
        val lock = Any()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                if (!name.startsWith("wican", ignoreCase = true)) return
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val addr = info.host?.hostAddress ?: return
                            synchronized(lock) {
                                if (results.none { it.host == addr }) {
                                    results.add(DiscoveredDevice(
                                        name = info.serviceName ?: "WiCAN",
                                        host = addr,
                                        port = info.port
                                    ))
                                }
                            }
                        }
                    })
                } catch (_: Exception) {}
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            return emptyList()
        }

        delay(SCAN_DURATION_MS)

        try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}

        return synchronized(lock) { results.toList() }
    }
}
