package com.ccdeaihub.gohome

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

class GoHomeVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val homeCidr = intent?.getStringExtra(EXTRA_HOME_CIDR)
        val virtualAddress = intent?.getStringExtra(EXTRA_VIRTUAL_ADDRESS)
        if (homeCidr.isNullOrBlank() || virtualAddress.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val (homeAddress, homePrefix) = splitCidr(homeCidr) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        GoHomeTunnelRuntime.protectSocket(this)
        tunnel?.close()
        tunnel = Builder()
            .setSession("Go Home")
            .setMtu(1380)
            .addAddress(virtualAddress, 32)
            .addRoute(homeAddress, homePrefix)
            .establish()
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
        const val EXTRA_HOME_CIDR = "home_cidr"
        const val EXTRA_VIRTUAL_ADDRESS = "virtual_address"

        private fun splitCidr(cidr: String): Pair<String, Int>? {
            val parts = cidr.split("/", limit = 2)
            val prefix = parts.getOrNull(1)?.toIntOrNull()
            return if (parts.size == 2 && prefix != null) parts[0] to prefix else null
        }
    }
}
