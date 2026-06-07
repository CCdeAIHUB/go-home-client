package com.ccdeaihub.gohome

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class GoHomeVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var establishedTunnel = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GoHomeTunnelRuntime.protectSocket(this)
        if (intent?.action == ACTION_PROTECT_SOCKET) {
            Log.i(TAG, "Protected UDP tunnel socket before handshake")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val homeCidr = intent?.getStringExtra(EXTRA_HOME_CIDR)
        val virtualAddress = intent?.getStringExtra(EXTRA_VIRTUAL_ADDRESS)
        val routePolicy = intent?.getStringExtra(EXTRA_ROUTE_POLICY) ?: ROUTE_POLICY_LAN
        val dnsServer = intent?.getStringExtra(EXTRA_DNS_SERVER) ?: ""
        if (homeCidr.isNullOrBlank() || virtualAddress.isNullOrBlank()) {
            Log.e(TAG, "Missing homeCidr=$homeCidr or virtualAddress=$virtualAddress")
            stopSelf()
            return START_NOT_STICKY
        }
        val (homeAddress, homePrefix) = splitCidr(homeCidr) ?: run {
            Log.e(TAG, "Invalid CIDR: $homeCidr")
            stopSelf()
            return START_NOT_STICKY
        }

        // Close old tunnel if any
        tunnel?.close()
        tunnel = null
        establishedTunnel = false

        // Build and establish the VPN interface
        val newTunnel = try {
            val builder = Builder()
                .setSession("Go Home")
                .setMtu(1380)
                .addAddress(virtualAddress, 32)
            if (routePolicy == ROUTE_POLICY_FULL) {
                builder.addRoute("0.0.0.0", 0)
                dnsServer.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .forEach {
                        builder.addDnsServer(it)
                    }
            } else {
                if (dnsServer.isNotBlank()) {
                    builder.addDnsServer(dnsServer)
                }
                builder.addRoute(homeAddress, homePrefix)
            }
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "VpnService.Builder.establish() failed", e)
            null
        }

        if (newTunnel == null) {
            Log.e(TAG, "VpnService.Builder.establish() returned null - VPN permission may not be granted")
            GoHomeTunnelRuntime.setError("VPN interface could not be established")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "VPN established: address=$virtualAddress route=$homeAddress/$homePrefix policy=$routePolicy dns=$dnsServer")
        tunnel = newTunnel
        establishedTunnel = true
        GoHomeTunnelRuntime.attachTunnel(tunnel)
        return START_STICKY
    }

    override fun onDestroy() {
        val hadEstablishedTunnel = establishedTunnel
        establishedTunnel = false
        tunnel?.close()
        tunnel = null
        if (hadEstablishedTunnel) {
            GoHomeTunnelRuntime.handleVpnServiceStopped()
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        GoHomeTunnelRuntime.stop(null)
        super.onRevoke()
    }

    companion object {
        private const val TAG = "GoHomeVpn"
        private const val ACTION_PROTECT_SOCKET = "com.ccdeaihub.gohome.PROTECT_SOCKET"
        private const val ROUTE_POLICY_FULL = "full"
        private const val ROUTE_POLICY_LAN = "lan"
        const val EXTRA_HOME_CIDR = "home_cidr"
        const val EXTRA_VIRTUAL_ADDRESS = "virtual_address"
        const val EXTRA_ROUTE_POLICY = "route_policy"
        const val EXTRA_DNS_SERVER = "dns_server"

        fun protectTunnelSocket(context: Context) {
            val intent = Intent(context, GoHomeVpnService::class.java).setAction(ACTION_PROTECT_SOCKET)
            context.startService(intent)
        }

        private fun splitCidr(cidr: String): Pair<String, Int>? {
            val parts = cidr.split("/", limit = 2)
            val prefix = parts.getOrNull(1)?.toIntOrNull()
            return if (parts.size == 2 && prefix != null) parts[0] to prefix else null
        }
    }
}
