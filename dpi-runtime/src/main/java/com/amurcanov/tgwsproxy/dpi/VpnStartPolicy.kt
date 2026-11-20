package com.amurcanov.tgwsproxy.dpi

/**
 * What the OS VPN slot currently looks like. Android exposes no public API to
 * enumerate the owning app of an active VPN, so the adapter maps its best
 * evidence to this model: [FREE] when no VPN is active, [OURS] when we hold
 * the slot, [THIRD_PARTY] when another VPN is reliably identified, and
 * [UNKNOWN] when a VPN is active but its owner cannot be verified (this is the
 * production result for any visible foreign VPN, and also the fail-closed state
 * when observation itself is unavailable).
 */
enum class VpnSlotState { FREE, OURS, THIRD_PARTY, UNKNOWN }

/** Conservative refusal reasons. */
enum class VpnRefuseReason {
    /** The system VPN permission has not been obtained (prepare() still returns an intent). */
    CONSENT_NOT_GRANTED,

    /** Authorization was present at decision time but was lost before establish. */
    CONSENT_LOST,

    /** Always-on VPN or lockdown is active; we must not capture the slot. */
    LOCKDOWN,

    /** The VPN slot changed between decision and establish (active/unknown VPN appeared). */
    SLOT_CHANGED,
}

/**
 * Immutable, single-attempt proof that the user deliberately confirmed replacing
 * the VPN observed at confirmation time. It is bound to [confirmedSlot] (the
 * slot state observed when the confirmation was given), so the final service
 * check can reject a start whose freshly re-sampled slot no longer matches.
 *
 * This is deliberately NOT a cached OS grant and NOT an unscoped Boolean: a
 * cached `VpnService.prepare() == null` is ambient permission to use the VPN at
 * all, never authorization to replace a specific active VPN.
 */
data class VpnReplacement(val confirmedSlot: VpnSlotState)

/**
 * A single, immutable snapshot the VPN policy decides on.
 *
 * [prepared] is the ambient OS grant (`VpnService.prepare()` returned null);
 * [lockdown] is always-on/lockdown (best-effort, often unavailable). The
 * deliberate replacement authorization is passed separately as [VpnReplacement]
 * to [VpnStartPolicy.decide], because it must never be conflated with [prepared].
 */
data class VpnStartRequest(
    val slot: VpnSlotState,
    val prepared: Boolean,
    val lockdown: Boolean = false,
)

/** Outcome of [VpnStartPolicy.decide]. */
sealed interface VpnStartDecision {
    /** Establish the VPN now. */
    data object Start : VpnStartDecision

    /**
     * Present the user a choice: switch to SOCKS5, replace the current VPN
     * with deliberate confirmation, or cancel. [ownerUncertain] is true when the
     * active VPN's owner could not be verified, so the UI must warn rather
     * than assume it is a third-party VPN.
     */
    data class Offer(val ownerUncertain: Boolean) : VpnStartDecision

    /** Do not start; [reason] says why. */
    data class Refuse(val reason: VpnRefuseReason) : VpnStartDecision
}

/**
 * Pure decision over whether a VPN start is authorized. It never performs I/O
 * and never mutates state, so the caller can re-sample the slot immediately
 * before establishing and call [decide] again — a changed answer means the
 * start is aborted before touching the system VPN slot.
 */
object VpnStartPolicy {
    /**
     * The conservative decision table (lockdown always wins):
     *
     * | slot        | prepared | replacement(confirmedSlot) | decision                        |
     * |-------------|----------|---------------------------|---------------------------------|
     * | FREE/OURS   | true     | (ignored)                 | Start                           |
     * | FREE/OURS   | false    | (ignored)                 | Refuse(CONSENT_NOT_GRANTED)     |
     * | THIRD_PARTY | true     | THIRD_PARTY               | Start (deliberate replacement)  |
     * | THIRD_PARTY | *        | null / other              | Offer(ownerUncertain=false)     |
     * | UNKNOWN     | true     | UNKNOWN                   | Start (deliberate replacement)  |
     * | UNKNOWN     | *        | null / other              | Offer(ownerUncertain=true)      |
     *
     * Replacing a foreign VPN (THIRD_PARTY or UNKNOWN) requires BOTH a valid OS
     * grant ([prepared]) AND a deliberate per-attempt proof bound to that slot.
     * A cached OS grant alone never authorizes replacement, and a proof bound to
     * a different slot is ignored (the freshly observed slot wins).
     */
    fun decide(request: VpnStartRequest, replacement: VpnReplacement? = null): VpnStartDecision {
        if (request.lockdown) return VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN)

        return when (request.slot) {
            VpnSlotState.UNKNOWN ->
                if (request.prepared && replacement?.confirmedSlot == VpnSlotState.UNKNOWN) VpnStartDecision.Start
                else VpnStartDecision.Offer(ownerUncertain = true)

            VpnSlotState.THIRD_PARTY ->
                if (request.prepared && replacement?.confirmedSlot == VpnSlotState.THIRD_PARTY) VpnStartDecision.Start
                else VpnStartDecision.Offer(ownerUncertain = false)

            VpnSlotState.FREE, VpnSlotState.OURS ->
                if (request.prepared) VpnStartDecision.Start
                else VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED)
        }
    }
}
