package com.amurcanov.tgwsproxy.dpi

/**
 * Production authorization entrypoint. It runs the pure [VpnStartPolicy.decide]
 * over a fresh slot sample and, when that decision is [VpnStartDecision.Start],
 * re-samples and re-decides immediately before the caller establishes — so a
 * slot that changed (a third-party/unknown VPN appeared), a prepare grant that
 * was revoked, or a lockdown that became active between decision and establish
 * is refused conservatively rather than silently capturing the VPN slot.
 *
 * Replacing a foreign VPN is deliberately two-step: the caller first renders
 * the [VpnStartDecision.Offer], then — after the user confirms and (if needed)
 * the OS prepare consent is obtained — calls [confirmReplacement] to mint an
 * immutable [VpnReplacement] bound to the freshly observed slot, and re-runs
 * [authorize] with that proof. A cached prepare grant is never treated as
 * replacement authorization.
 *
 * This object holds no state beyond the injected [VpnSlotSampler]; the UI (task
 * 4) calls [authorize] to render the decision, and the engine re-runs it as the
 * safety recheck before starting [DpiVpnService].
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
     * Mints a deliberate, single-attempt replacement proof bound to the slot
     * observed right now. The caller must only call this after rendering an
     * [VpnStartDecision.Offer] and receiving the user's explicit confirmation;
     * a [VpnSlotState.FREE]/[VpnSlotState.OURS] observation has nothing to
     * replace and is refused here.
     */
    fun confirmReplacement(): VpnReplacement {
        val slot = sampler.sample().slot
        require(slot == VpnSlotState.THIRD_PARTY || slot == VpnSlotState.UNKNOWN) {
            "No foreign VPN observed to replace (slot=$slot)"
        }
        return VpnReplacement(confirmedSlot = slot)
    }

    /** Maps a recheck refusal to the most specific conservative reason. */
    private fun recheckReason(first: VpnStartRequest, second: VpnStartRequest): VpnRefuseReason = when {
        second.lockdown -> VpnRefuseReason.LOCKDOWN
        first.slot != second.slot -> VpnRefuseReason.SLOT_CHANGED
        first.prepared && !second.prepared -> VpnRefuseReason.CONSENT_LOST
        else -> VpnRefuseReason.CONSENT_NOT_GRANTED
    }
}
