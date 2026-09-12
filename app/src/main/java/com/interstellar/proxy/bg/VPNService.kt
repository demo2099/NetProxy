package com.interstellar.proxy.bg

import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.interstellar.proxy.R
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.TunOptions
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ktx.toIpPrefix
import com.interstellar.proxy.ktx.toList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class VPNService :
    VpnService(),
    PlatformInterfaceWrapper {
    companion object {
        private const val TAG = "VPNService"
    }

    private val service = BoxService(this, this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = service.onStartCommand()

    override fun onBind(intent: Intent): IBinder {
        val binder = super.onBind(intent)
        if (binder != null) {
            return binder
        }
        return service.onBind()
    }

    override fun onDestroy() {
        service.onDestroy()
    }

    override fun onRevoke() {
        runBlocking {
            withContext(Dispatchers.Main) {
                service.onRevoke()
            }
        }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    var systemProxyAvailable = false
    var systemProxyEnabled = false

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")

        val builder =
            Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (Settings.allowBypass) {
            builder.allowBypass()
        }

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServerAddress = options.dnsServerAddress
                while (dnsServerAddress.hasNext()) {
                    builder.addDnsServer(dnsServerAddress.next())
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        builder.addRoute(inet4RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }

                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        builder.addRoute(inet6RouteAddress.next().toIpPrefix())
                    }
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }

                val inet4RouteExcludeAddress = options.inet4RouteExcludeAddress
                while (inet4RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet4RouteExcludeAddress.next().toIpPrefix())
                }

                val inet6RouteExcludeAddress = options.inet6RouteExcludeAddress
                while (inet6RouteExcludeAddress.hasNext()) {
                    builder.excludeRoute(inet6RouteExcludeAddress.next().toIpPrefix())
                }
            } else {
                val inet4RouteAddress = options.inet4RouteRange
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) {
                        val address = inet4RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }

                val inet6RouteAddress = options.inet6RouteRange
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) {
                        val address = inet6RouteAddress.next()
                        builder.addRoute(address.address(), address.prefix())
                    }
                }
            }

            val includePackage = options.includePackage
            if (includePackage.hasNext()) {
                while (includePackage.hasNext()) {
                    try {
                        val nextPackage = includePackage.next()
                        builder.addAllowedApplication(nextPackage)
                        Log.d(TAG, "addAllowedApplication: $nextPackage")
                    } catch (e: NameNotFoundException) {
                        Log.e(TAG, "addAllowedApplication failed", e)
                    }
                }
            }

            val excludePackage = options.excludePackage
            if (excludePackage.hasNext()) {
                while (excludePackage.hasNext()) {
                    try {
                        val nextPackage = excludePackage.next()
                        builder.addDisallowedApplication(nextPackage)
                        Log.d(TAG, "addDisallowedApplication: $nextPackage")
                    } catch (e: NameNotFoundException) {
                        Log.e(TAG, "addDisallowedApplication failed", e)
                    }
                }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            systemProxyAvailable = true
            systemProxyEnabled = Settings.systemProxyEnabled
            if (systemProxyEnabled) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy(
                        options.httpProxyServer,
                        options.httpProxyServerPort,
                        options.httpProxyBypassDomain.toList(),
                    ),
                )
            }
        } else {
            systemProxyAvailable = false
            systemProxyEnabled = false
        }

        val pfd =
            builder.establish() ?: error("android: the application is not prepared or is revoked")
        service.fileDescriptor = pfd
        return pfd.fd
    }

    override fun sendNotification(notification: Notification) = service.sendNotification(notification)

    override fun cancelNotification(identifier: String, typeID: Int) = service.cancelNotification(identifier, typeID)

    // ---- sidecar-core tun (mihomo / Xray) ----

    /**
     * Establish the VPN for a sidecar core: the app (not the core) owns the
     * builder. Node server IPs are excluded from the routes so the core's
     * own outbound sockets bypass the tun (no protect() across processes).
     * The returned fd has CLOEXEC cleared so the exec'd core inherits it.
     */
    fun establishSidecarTun(spec: com.interstellar.proxy.core.SidecarTunSpec): Int? {
        if (prepare(this) != null) return null

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(spec.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        if (spec.allowBypass) {
            builder.allowBypass()
        }
        builder.addAddress("172.19.0.1", 30)
        builder.addAddress("fdfe:dcba:9876::1", 126)
        builder.addDnsServer("172.19.0.1")

        val excluded = resolveExclusions(spec.exclusions)
        val hasModernExclusions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (hasModernExclusions) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            for (prefix in excluded) {
                runCatching { builder.excludeRoute(prefix) }
            }
        } else {
            // pre-13: split the IPv4/IPv6 space around the exclusions instead
            for (prefix in complementRoutes(excluded)) {
                runCatching { builder.addRoute(prefix) }
            }
        }

        if (spec.perAppEnabled) {
            val selfPackage = packageName
            try {
                if (spec.perAppInclude) {
                    (spec.perAppPackages + selfPackage).forEach { builder.addAllowedApplication(it) }
                } else {
                    (spec.perAppPackages - selfPackage).forEach { builder.addDisallowedApplication(it) }
                }
            } catch (e: NameNotFoundException) {
                Log.e(TAG, "per-app vpn config failed", e)
            }
        }

        val pfd = builder.establish() ?: return null
        service.fileDescriptor = pfd
        // child must inherit the fd across exec
        runCatching { android.system.Os.fcntlInt(pfd.fileDescriptor, android.system.OsConstants.F_SETFD, 0) }
        return pfd.fd
    }

    /** hosts/IPs → IpPrefixes (domains resolved via the system resolver). */
    private fun resolveExclusions(hosts: List<String>): List<android.net.IpPrefix> {
        val prefixes = mutableListOf<android.net.IpPrefix>()
        for (host in hosts.distinct()) {
            val ip = host.substringBefore('/')
            if (ip.isEmpty()) continue
            val literal = runCatching {
                val addr = java.net.InetAddress.getByName(ip)
                if (addr.isAnyLocalAddress || addr.isLoopbackAddress) null else addr
            }.getOrNull()
            if (literal != null) {
                prefixes.add(android.net.IpPrefix(literal, if (literal.address.size == 4) 32 else 128))
            }
        }
        return prefixes
    }

    /** IPv4 full space minus exclusions as addRoute-able prefixes (API < 33). */
    private fun complementRoutes(excluded: List<android.net.IpPrefix>): List<android.net.IpPrefix> {
        var ranges = listOf(0L..(1L shl 32) - 1)
        for (prefix in excluded) {
            val r = toRange(prefix) ?: continue
            ranges = ranges.flatMap { existing ->
                when {
                    r.last < existing.first || r.first > existing.last -> listOf(existing)
                    r.first <= existing.first && r.last >= existing.last -> emptyList()
                    else -> buildList {
                        if (r.first > existing.first) add(existing.first until r.first)
                        if (r.last < existing.last) add((r.last + 1)..existing.last)
                    }
                }
            }
        }
        return ranges.flatMap { rangeToCidrs(it.first, it.last) }
    }

    private fun toRange(p: android.net.IpPrefix): LongRange? {
        val bytes = p.address.address
        if (bytes.size != 4) return null // v6 complement skipped on legacy devices
        var start = 0L
        bytes.forEach { start = (start shl 8) or (it.toLong() and 0xff) }
        val size = 1L shl (32 - p.prefixLength)
        return start until (start + size).coerceAtMost(1L shl 32)
    }

    private fun rangeToCidrs(start: Long, endInclusive: Long): List<android.net.IpPrefix> {
        var current = start
        val out = mutableListOf<android.net.IpPrefix>()
        while (current <= endInclusive && out.size < 1024) {
            var prefixLen = 32
            while (prefixLen > 0) {
                val size = 1L shl (32 - prefixLen)
                if (current % size == 0L && current + size - 1 <= endInclusive) break
                prefixLen--
            }
            val size = 1L shl (32 - prefixLen)
            val addr = java.net.InetAddress.getByAddress(
                byteArrayOf(
                    (current ushr 24).toByte(),
                    (current ushr 16).toByte(),
                    (current ushr 8).toByte(),
                    current.toByte(),
                ),
            )
            out.add(android.net.IpPrefix(addr, prefixLen))
            current += size
        }
        return out
    }
}
