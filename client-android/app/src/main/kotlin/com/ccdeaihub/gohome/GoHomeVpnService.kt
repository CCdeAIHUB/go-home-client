package com.ccdeaihub.gohome

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class GoHomeVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GoHomeTunnelRuntime.protectSocket(this)
        if (intent?.action == ACTION_PROTECT_SOCKET) {
            Log.i(TAG, "Protected UDP tunnel socket before handshake")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val homeCidr = intent?.getStringExtra(EXTRA_HOME_CIDR)
        val virtualAddress = intent?.getStringExtra(EXTRA_VIRTUAL_ADDRESS)
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

        // Build and establish the VPN interface
        val newTunnel = try {
            Builder()
                .setSession("Go Home")
                .setMtu(1380)
                .addAddress(virtualAddress, 32)
                .addRoute(homeAddress, homePrefix)
                .establish()
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

        Log.i(TAG, "VPN established: address=$virtualAddress route=$homeAddress/$homePrefix")
        tunnel = newTunnel
        GoHomeTunnelRuntime.attachTunnel(tunnel)
        return START_STICKY
    }

    override fun onDestroy() {
        tunnel?.close()
        tunnel = null
        super.onDestroy()
    }

    override fun onRevoke() {
        GoHomeTunnelRuntime.stop(null)
        super.onRevoke()
    }

    companion object {
        private const val TAG = "GoHomeVpn"
        private const val ACTION_PROTECT_SOCKET = "com.ccdeaihub.gohome.PROTECT_SOCKET"
        const val EXTRA_HOME_CIDR = "home_cidr"
        const val EXTRA_VIRTUAL_ADDRESS = "virtual_address"

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
