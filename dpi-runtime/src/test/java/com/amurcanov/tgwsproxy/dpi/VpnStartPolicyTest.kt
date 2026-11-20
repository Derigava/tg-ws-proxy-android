package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-table test for [VpnStartPolicy]. The table pins the conservative
 * contract the VPN entry point relies on: only a free (or already-ours) slot
 * with explicit consent may start; a third-party VPN is never silently
 * replaced; an unknown owner is always treated conservatively (a warning,
 * never an automatic takeover); and refusal, lost consent, or lockdown can
 * never yield an authorized start.
 */
class VpnStartPolicyTest {

    @Test
    fun freeSlotWithConsentStarts() {
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.FREE, consent = VpnConsent.GRANTED)
        )
        assertEquals(VpnStartDecision.Start, decision)
    }

    @Test
    fun freeSlotWithoutConsentRefuses() {
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.FREE, consent = VpnConsent.NONE)
        )
        assertEquals(VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED), decision)
    }

    @Test
    fun freeSlotWithLostConsentRefuses() {
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.FREE, consent = VpnConsent.LOST)
        )
        assertEquals(VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST), decision)
    }

    @Test
    fun ownSlotWithConsentStarts() {
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.OURS, consent = VpnConsent.GRANTED)
        )
        assertEquals(VpnStartDecision.Start, decision)
    }

    @Test
    fun thirdPartyWithoutConsentOffersAlternatives() {
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.THIRD_PARTY, consent = VpnConsent.NONE)
        )
        assertEquals(VpnStartDecision.Offer(ownerUncertain = false), decision)
    }

    @Test
    fun thirdPartyWithConsentStarts() {
        // Explicit consent to replace the third-party VPN was given: take over.
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.THIRD_PARTY, consent = VpnConsent.GRANTED)
        )
        assertEquals(VpnStartDecision.Start, decision)
    }

    @Test
    fun thirdPartyWithLostConsentRefuses() {
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.THIRD_PARTY, consent = VpnConsent.LOST)
        )
        assertEquals(VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST), decision)
    }

    @Test
    fun unknownSlotWithoutConsentWarnsConservatively() {
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.UNKNOWN, consent = VpnConsent.NONE)
        )
        assertEquals(VpnStartDecision.Offer(ownerUncertain = true), decision)
    }

    @Test
    fun unknownSlotWithConsentStillWarns() {
        // Even with consent, an owner we cannot verify must not auto-start:
        // show the conservative warning (offer) rather than silently grabbing.
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.UNKNOWN, consent = VpnConsent.GRANTED)
        )
        assertEquals(VpnStartDecision.Offer(ownerUncertain = true), decision)
    }

    @Test
    fun unknownSlotWithLostConsentRefuses() {
        val decision = VpnStartPolicy.decide(
            VpnStartRequest(slot = VpnSlotState.UNKNOWN, consent = VpnConsent.LOST)
        )
        assertEquals(VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST), decision)
    }

    @Test
    fun lockdownRefusesRegardlessOfSlotOrConsent() {
        // Lockdown wins over everything: no unauthorized start under always-on/lockdown.
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(
                VpnStartRequest(slot = VpnSlotState.FREE, consent = VpnConsent.GRANTED, lockdown = true)
            ),
        )
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(
                VpnStartRequest(slot = VpnSlotState.THIRD_PARTY, consent = VpnConsent.GRANTED, lockdown = true)
            ),
        )
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(
                VpnStartRequest(slot = VpnSlotState.UNKNOWN, consent = VpnConsent.LOST, lockdown = true)
            ),
        )
    }

    @Test
    fun decisionIsPureAndRecheckable() {
        // Rechecking the exact same request must reproduce the same decision
        // (the caller re-samples the slot right before establishing and calls
        // decide() again; a pure function makes that recheck deterministic).
        val request = VpnStartRequest(slot = VpnSlotState.UNKNOWN, consent = VpnConsent.NONE)
        assertEquals(VpnStartPolicy.decide(request), VpnStartPolicy.decide(request))
        assertTrue(VpnStartPolicy.decide(request) is VpnStartDecision.Offer)
    }
}
