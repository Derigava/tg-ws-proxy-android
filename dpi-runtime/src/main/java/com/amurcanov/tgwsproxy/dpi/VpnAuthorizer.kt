package com.amurcanov.tgwsproxy.dpi

/**
 * Production authorization entrypoint. It runs the pure [VpnStartPolicy.decide]
 * over a fresh slot sample and, when that decision is [VpnStartDecision.Start],
 * re-samples and re-decides immediately before the caller establishes — so a
 * slot that changed (a third-party/unknown VPN appeared, or a *different* VPN
 * with different network handles appeared), a prepare grant that was revoked,
 * an observation that became unavailable, or a lockdown that became active
 * between decision and establish is refused conservatively.
 *
 * Replacing a foreign VPN is deliberately two-step: the caller first renders
 * the [VpnStartDecision.Offer], then — after the user confirms and (if needed)
 * the OS prepare consent is obtained — calls [confirmReplacement] to mint an
 * immutable [VpnReplacement] bound to the exact observed VPN network handles,
 * and re-runs [authorize] with that proof. A cached prepare grant is never
 * treated as replacement authorization, and a proof can never be minted from an
 * unavailable observation.
 */
class VpnAuthorizer(private val sampler: VpnSlotSampler) {

    fun authorize(replacement: VpnReplacement?): VpnStartDecision {
        val first = sampler.sample()
        val firstDecision = VpnStartPolicy.decide(first, replacement)
        if (firstDecision !is VpnStartDecision.Start) return firstDecision

        val second = sampler.sample()
        val secondDecision = VpnStartPolicy.decide(second, replacement)
        if (secondDecision is VpnStartDecision.Start) return VpnStartDecision.Start
        return VpnStartDecision.Refuse(recheckReason(first, second))
    }

    /**
     * Mints a deliberate, single-attempt replacement proof bound to the exact
     * VPN network handles observed right now. It fails if nothing to replace is
     * observed or if the observation is unavailable/identity-less, so a proof is
     * only ever produced from a confident, handle-bearing VPN observation.
     */
    fun confirmReplacement(): VpnReplacement {
        val sample = sampler.sample()
        require(sample.slot == VpnSlotState.THIRD_PARTY || sample.slot == VpnSlotState.UNKNOWN) {
            "No foreign VPN observed to replace (slot=${sample.slot})"
        }
        require(sample.vpnNetworks.isNotEmpty()) {
            "Observed VPN carries no network identity"
        }
        return VpnReplacement(confirmedSlot = sample.slot, confirmedNetworks = sample.vpnNetworks)
    }

    /** Maps a recheck refusal to the most specific conservative reason. */
    private fun recheckReason(first: VpnStartRequest, second: VpnStartRequest): VpnRefuseReason = when {
        second.lockdown -> VpnRefuseReason.LOCKDOWN
        second.slot == VpnSlotState.UNAVAILABLE -> VpnRefuseReason.OBSERVATION_UNAVAILABLE
        first.slot != second.slot -> VpnRefuseReason.SLOT_CHANGED
        first.vpnNetworks != second.vpnNetworks -> VpnRefuseReason.SLOT_CHANGED
        first.prepared && !second.prepared -> VpnRefuseReason.CONSENT_LOST
        else -> VpnRefuseReason.CONSENT_NOT_GRANTED
    }
}
