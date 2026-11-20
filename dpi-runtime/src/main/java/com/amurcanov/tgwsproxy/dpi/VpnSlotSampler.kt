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
    /** Builds a fresh request snapshot (slot, prepared, lockdown). */
    fun sample(): VpnStartRequest
}

/**
 * Production sampler. It is fail-closed: when observation is unavailable (no
 * ConnectivityManager, no visible networks, or a capabilities read throws) it
 * reports [VpnSlotState.UNKNOWN], never [VpnSlotState.FREE], so a start is never
 * authorized on the basis of a failed observation.
 *
 * It inspects every visible network (not just the default active network) for
 * [NetworkCapabilities.TRANSPORT_VPN], so a VPN reachable on a non-default
 * network is still detected.
 *
 * Honest limits: Android exposes no public API to name the owner of an active
 * VPN, so any visible foreign VPN is [VpnSlotState.UNKNOWN] (never
 * [VpnSlotState.THIRD_PARTY]), [VpnSlotState.OURS] is not produced (the
 * controller already rejects a start while our VPN is Running), and
 * always-on/lockdown is reported absent because it cannot be read reliably.
 */
class AndroidVpnSlotSampler(private val context: Context) : VpnSlotSampler {
    override fun sample(): VpnStartRequest {
        val prepared = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        return VpnStartRequest(
            slot = observeSlot(),
            prepared = prepared,
            lockdown = false,
        )
    }

    @Suppress("DEPRECATION")
    private fun observeSlot(): VpnSlotState {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return VpnSlotState.UNKNOWN
        return try {
            // getAllNetworks() is a one-shot snapshot (the framework recommends a
            // live NetworkCallback), which is exactly what an authorization sample
            // wants; the residual gap between this snapshot and establish() is the
            // documented check/establish window.
            val networks = manager.allNetworks ?: return VpnSlotState.UNKNOWN
            if (networks.isEmpty()) return VpnSlotState.FREE // no networks => no VPN
            val anyVpn = networks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            if (anyVpn) VpnSlotState.UNKNOWN else VpnSlotState.FREE
        } catch (e: Exception) {
            VpnSlotState.UNKNOWN // fail-closed on observation failure
        }
    }
}
