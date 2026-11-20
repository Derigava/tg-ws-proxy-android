package com.amurcanov.tgwsproxy.dpi

/**
 * What the OS VPN slot currently looks like. Android exposes no public API to
 * enumerate the owning app of an active VPN, so the adapter maps its best
 * evidence to this model: [FREE] when no VPN is active, [OURS] when we hold
 * the slot, [THIRD_PARTY] when another VPN is reliably identified, and
 * [UNKNOWN] when a VPN is active but its owner cannot be verified.
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
 * A single, immutable snapshot the VPN policy decides on.
 *
 * Two orthogonal facts drive the decision, and they must not be conflated:
 *  * [prepared] — the ambient OS grant (`VpnService.prepare()` returned null).
 *    This is a cached, user-level permission to use the VPN at all; it says
 *    nothing about whether the user agreed to replace *this* active VPN.
 *  * [replaceAuthorized] — a per-attempt, explicit authorization the user gave
 *    right now to replace the specific third-party VPN occupying the slot.
 */
data class VpnStartRequest(
    val slot: VpnSlotState,
    val prepared: Boolean,
    val replaceAuthorized: Boolean,
    val lockdown: Boolean = false,
)

/** Outcome of [VpnStartPolicy.decide]. */
sealed interface VpnStartDecision {
    /** Establish the VPN now. */
    data object Start : VpnStartDecision

    /**
     * Present the user a choice: switch to SOCKS5, replace the current VPN
     * with explicit consent, or cancel. [ownerUncertain] is true when the
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
     * The conservative decision table:
     *
     * | slot        | prepared | replaceAuthorized | lockdown | decision                        |
     * |-------------|----------|-------------------|----------|---------------------------------|
     * | FREE        | true     | -                 | false    | Start                           |
     * | FREE        | false    | -                 | false    | Refuse(CONSENT_NOT_GRANTED)     |
     * | OURS        | true     | -                 | false    | Start                           |
     * | OURS        | false    | -                 | false    | Refuse(CONSENT_NOT_GRANTED)     |
     * | THIRD_PARTY | -        | true              | false    | Start (explicit replacement)    |
     * | THIRD_PARTY | -        | false             | false    | Offer(ownerUncertain=false)     |
     * | UNKNOWN     | -        | -                 | false    | Offer(ownerUncertain=true)      |
     * | any         | -        | -                 | true     | Refuse(LOCKDOWN)                |
     *
     * A cached OS grant ([prepared]) never authorizes replacing a third-party
     * VPN — only an explicit, per-attempt [replaceAuthorized] does. UNKNOWN is
     * never an automatic start: the owner cannot be verified, so the caller
     * must present a conservative warning and require a fresh confirmation.
     */
    fun decide(request: VpnStartRequest): VpnStartDecision {
        if (request.lockdown) return VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN)

        return when (request.slot) {
            VpnSlotState.UNKNOWN -> VpnStartDecision.Offer(ownerUncertain = true)
            VpnSlotState.THIRD_PARTY ->
                if (request.replaceAuthorized) VpnStartDecision.Start
                else VpnStartDecision.Offer(ownerUncertain = false)
            VpnSlotState.FREE, VpnSlotState.OURS ->
                if (request.prepared) VpnStartDecision.Start
                else VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED)
        }
    }
}
