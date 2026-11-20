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
    /** Builds a fresh request snapshot (slot, prepared, lockdown, vpnNetworks). */
    fun sample(): VpnStartRequest
}

/**
 * Production sampler. It is fail-closed: when observation is unavailable (no
 * ConnectivityManager, a null or *empty* network list — empty is indeterminate,
 * not confidently free — or a capabilities read throws) it reports
 * [VpnSlotState.UNAVAILABLE], never [VpnSlotState.FREE], and carries no network
 * handles, so a start or replacement can never be authorized on a failed sample.
 *
 * It inspects every visible network (not just the default active network) for
 * [NetworkCapabilities.TRANSPORT_VPN], and records the `Network.getNetworkHandle()`
 * of each observed VPN network so a replacement proof can be bound to the exact
 * observed VPN and a later, different VPN is rejected.
 *
 * Honest limits: Android exposes no public API to name the owner of an active
 * VPN, so any visible foreign VPN is [VpnSlotState.UNKNOWN] (never
 * [VpnSlotState.THIRD_PARTY]) but carries its network handles; [VpnSlotState.OURS]
 * is not produced (the controller already rejects a start while our VPN is
 * Running); always-on/lockdown is reported absent because it cannot be read.
 * `getAllNetworks()` is a one-shot snapshot — the residual gap between it and
 * `establish()` is the documented check/establish window.
 */
class AndroidVpnSlotSampler(private val context: Context) : VpnSlotSampler {
    override fun sample(): VpnStartRequest {
        val prepared = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        val observation = observeNetworks()
        return VpnStartRequest(
            slot = observation.first,
            prepared = prepared,
            lockdown = false,
            vpnNetworks = observation.second,
        )
    }

    @Suppress("DEPRECATION")
    private fun observeNetworks(): Pair<VpnSlotState, Set<Long>> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return VpnSlotState.UNAVAILABLE to emptySet()
        return try {
            val networks = manager.allNetworks
                ?: return VpnSlotState.UNAVAILABLE to emptySet()
            if (networks.isEmpty()) return VpnSlotState.UNAVAILABLE to emptySet() // empty is indeterminate

            val vpnHandles = networks.mapNotNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) network.networkHandle else null
            }
            if (vpnHandles.isEmpty()) {
                VpnSlotState.FREE to emptySet()
            } else {
                VpnSlotState.UNKNOWN to vpnHandles.toSet()
            }
        } catch (e: Exception) {
            VpnSlotState.UNAVAILABLE to emptySet() // fail-closed on observation failure
        }
    }
}
