package com.interstellar.proxy.core

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.interstellar.proxy.InterstellarApplication
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * TCP-connect ping that stays off the app's own VPN.
 *
 * While the VPN is up the process's default network IS the tun: a plain
 * [Socket.connect] completes against the local tun stack (hev bridge /
 * sing-box accept the handshake before dialing upstream), so every node
 * "pings" ~3-4ms no matter where it lives. Under mihomo it is worse — DNS
 * through the tunnel is fake-ip, so node domains resolve to unroutable
 * 198.18.x.x before the connect even starts.
 *
 * So: resolve AND dial through the tracked physical (non-VPN) network —
 * network-bound sockets bypass the tun entirely. With no network tracked
 * (VPN off, or the tracker failed to register) this degrades to a plain
 * socket, which measures the same thing in that state.
 */
object DirectPing {

    /**
     * Elapsed ms for a TCP connect to [host]:[port]; timing includes DNS,
     * matching the pre-existing plain-socket behavior. Throws on failure —
     * callers classify (timeout / refused / unknown-host / …).
     */
    fun tcpConnect(host: String, port: Int, timeoutMs: Int): Int {
        val startedAt = System.currentTimeMillis()
        val network = PhysicalNetwork.current
        // resolve on the same network we dial on, else mihomo's fake-ip DNS
        // (which only the tunnel sees) hands back a 198.18.x.x address
        val address = network?.let { net ->
            net.getAllByName(host).firstOrNull() ?: throw UnknownHostException(host)
        }
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(
                if (address != null) InetSocketAddress(address, port) else InetSocketAddress(host, port),
                timeoutMs,
            )
        } finally {
            runCatching { socket.close() }
        }
        return (System.currentTimeMillis() - startedAt).toInt()
    }

    /**
     * Idempotent kick of the network tracker; optionally wait for the first
     * callback so an early sweep doesn't fall back to tun-routed sockets.
     */
    suspend fun warmup(waitMs: Long = 0): Network? = PhysicalNetwork.await(waitMs)
}

/**
 * Best physical (non-VPN) network. The request keeps its default
 * capabilities — NOT_VPN included — so the VPN itself never matches; several
 * physical nets may (wifi + cell), so keep the underlay-likely best by
 * transport rank, mirroring what best-matching would pick on API 31+.
 * Registered once per process and never unregistered — it is a cheap,
 * process-wide listen used by every ping sweep.
 */
private object PhysicalNetwork {
    @Volatile
    var current: Network? = null
        private set

    @Volatile
    private var registered = false

    private val available = java.util.concurrent.ConcurrentHashMap<Network, Int>()

    suspend fun await(waitMs: Long): Network? {
        register()
        if (waitMs <= 0 || current != null) return current
        val deadline = System.currentTimeMillis() + waitMs
        while (current == null && System.currentTimeMillis() < deadline) delay(50)
        return current
    }

    private fun register() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    available[network] =
                        rank(InterstellarApplication.connectivity.getNetworkCapabilities(network))
                    recompute()
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    available[network] = rank(caps)
                    recompute()
                }

                override fun onLost(network: Network) {
                    available.remove(network)
                    recompute()
                }
            }
            runCatching {
                InterstellarApplication.connectivity.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback,
                )
            }.onSuccess { registered = true }
        }
    }

    private fun recompute() {
        current = available.entries.minByOrNull { it.value }?.key
    }

    /** Underlay preference: wired > wifi > cell > anything else. */
    private fun rank(caps: NetworkCapabilities?): Int = when {
        caps == null -> 5
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        else -> 3
    }
}
