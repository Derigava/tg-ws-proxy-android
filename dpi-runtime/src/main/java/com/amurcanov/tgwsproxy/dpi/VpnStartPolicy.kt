package com.amurcanov.tgwsproxy.dpi

/**
 * What the OS VPN slot currently looks like. Android exposes no public API to
 * enumerate the owning app of an active VPN, so the adapter maps its best
 * evidence to this model: [FREE] when no VPN is active, [OURS] when we hold
 * the slot, [THIRD_PARTY] when another VPN is reliably identified, and
 * [UNKNOWN] when a VPN is active but its owner cannot be verified.
 */
enum class VpnSlotState { FREE, OURS, THIRD_PARTY, UNKNOWN }

/** User consent to take over the system VPN slot. */
enum class VpnConsent {
    /** No consent has been given yet. */
    NONE,

    /** The user explicitly consented (and that consent is still valid). */
    GRANTED,

    /** Consent was granted but has since been revoked or expired. */
    LOST,
}

/** Conservative refusal reasons. */
enum class VpnRefuseReason {
    /** The system VPN permission has not been obtained yet. */
    CONSENT_NOT_GRANTED,

    /** A previously granted consent was lost. */
    CONSENT_LOST,

    /** Always-on VPN or lockdown is active; we must not capture the slot. */
    LOCKDOWN,
}

/** A single, immutable snapshot the VPN policy decides on. */
data class VpnStartRequest(
    val slot: VpnSlotState,
    val consent: VpnConsent,
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
     * | slot         | consent | lockdown | decision                      |
     * |--------------|---------|----------|-------------------------------|
     * | FREE         | GRANTED | false    | Start                         |
     * | FREE         | NONE    | false    | Refuse(CONSENT_NOT_GRANTED)   |
     * | FREE         | LOST    | false    | Refuse(CONSENT_LOST)          |
     * | OURS         | GRANTED | false    | Start                         |
     * | THIRD_PARTY  | GRANTED | false    | Start (explicit consent given)|
     * | THIRD_PARTY  | NONE    | false    | Offer(ownerUncertain=false)   |
     * | THIRD_PARTY  | LOST    | false    | Refuse(CONSENT_LOST)          |
     * | UNKNOWN      | GRANTED | false    | Offer(ownerUncertain=true)    |
     * | UNKNOWN      | NONE    | false    | Offer(ownerUncertain=true)    |
     * | any          | LOST    | false    | Refuse(CONSENT_LOST)          |
     * | any          | any     | true     | Refuse(LOCKDOWN)              |
     *
     * Loss of consent is a hard block regardless of slot: a consent that was
     * granted then revoked must never authorize a start. UNKNOWN is never an
     * automatic start even with GRANTED consent: the owner cannot be verified,
     * so the caller must present a conservative warning (Offer) and require a
     * fresh explicit confirmation.
     */
    fun decide(request: VpnStartRequest): VpnStartDecision {
        if (request.lockdown) return VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN)
        if (request.consent == VpnConsent.LOST) {
            return VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST)
        }

        return when (request.slot) {
            VpnSlotState.UNKNOWN -> VpnStartDecision.Offer(ownerUncertain = true)
            VpnSlotState.THIRD_PARTY -> when (request.consent) {
                VpnConsent.GRANTED -> VpnStartDecision.Start
                VpnConsent.NONE -> VpnStartDecision.Offer(ownerUncertain = false)
                VpnConsent.LOST -> VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST)
            }
            VpnSlotState.FREE, VpnSlotState.OURS -> when (request.consent) {
                VpnConsent.GRANTED -> VpnStartDecision.Start
                VpnConsent.NONE -> VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED)
                VpnConsent.LOST -> VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST)
            }
        }
    }
}
