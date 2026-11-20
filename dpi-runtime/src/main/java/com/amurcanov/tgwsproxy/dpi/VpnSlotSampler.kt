package com.amurcanov.tgwsproxy.dpi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService

/**
 * Samples the current VPN slot into a [VpnStartRequest]. This is the seam the
 * authorization layer depends on, so it can be unit-tested with a scripted
 * sampler while [AndroidVpnSlotSampler] provides the real (best-effort) OS view.
 */
fun interface VpnSlotSampler {
    /**
     * Builds a fresh request snapshot. [replaceAuthorized] is a per-attempt
     * explicit confirmation supplied by the caller (it cannot be sampled from
     * the OS); slot, [VpnStartRequest.prepared] and lockdown are sampled.
     */
    fun sample(replaceAuthorized: Boolean): VpnStartRequest
}

/**
 * Production sampler. It is deliberately conservative: Android exposes no public
 * API to name the owner of an active VPN, so any active VPN that is not ours is
 * reported as [VpnSlotState.UNKNOWN] (never [VpnSlotState.THIRD_PARTY]), and
 * always-on/lockdown cannot be read reliably so it is reported as absent and the
 * UNKNOWN path provides the conservative warning instead.
 */
class AndroidVpnSlotSampler(private val context: Context) : VpnSlotSampler {
    override fun sample(replaceAuthorized: Boolean): VpnStartRequest {
        val prepared = VpnService.prepare(context) == null
        val slot = if (vpnTransportActive()) VpnSlotState.UNKNOWN else VpnSlotState.FREE
        return VpnStartRequest(
            slot = slot,
            prepared = prepared,
            replaceAuthorized = replaceAuthorized,
            lockdown = false,
        )
    }

    private fun vpnTransportActive(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            val network = manager.activeNetwork ?: return false
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }.getOrDefault(false)
    }
}
