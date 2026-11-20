package com.amurcanov.tgwsproxy.dpi

/**
 * Production authorization entrypoint. It runs the pure [VpnStartPolicy.decide]
 * over a fresh slot sample and, when that decision is [VpnStartDecision.Start],
 * re-samples and re-decides immediately before the caller establishes — so a
 * slot that changed (a third-party/unknown VPN appeared), a prepare grant that
 * was revoked, or a lockdown that became active between decision and establish
 * is refused conservatively rather than silently capturing the VPN slot.
 *
 * This object holds no state beyond the injected [VpnSlotSampler]; the UI (task
 * 4) calls [authorize] to render the decision, and the engine re-runs it as the
 * safety recheck before starting [DpiVpnService].
 */
class VpnAuthorizer(private val sampler: VpnSlotSampler) {

    fun authorize(replaceAuthorized: Boolean): VpnStartDecision {
        val first = sampler.sample(replaceAuthorized)
        val firstDecision = VpnStartPolicy.decide(first)
        if (firstDecision !is VpnStartDecision.Start) return firstDecision

        val second = sampler.sample(replaceAuthorized)
        val secondDecision = VpnStartPolicy.decide(second)
        if (secondDecision is VpnStartDecision.Start) return VpnStartDecision.Start
        return VpnStartDecision.Refuse(recheckReason(first, second))
    }

    /** Maps a recheck refusal to the most specific conservative reason. */
    private fun recheckReason(first: VpnStartRequest, second: VpnStartRequest): VpnRefuseReason = when {
        second.lockdown -> VpnRefuseReason.LOCKDOWN
        first.slot != second.slot -> VpnRefuseReason.SLOT_CHANGED
        first.prepared && !second.prepared -> VpnRefuseReason.CONSENT_LOST
        else -> VpnRefuseReason.CONSENT_NOT_GRANTED
    }
}
