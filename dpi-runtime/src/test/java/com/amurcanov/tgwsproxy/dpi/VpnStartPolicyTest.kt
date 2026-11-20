package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-table test for [VpnStartPolicy]. The table pins the conservative
 * contract: a cached OS prepare grant ([VpnStartRequest.prepared]) authorizes
 * starting on a free/ours slot but NEVER authorizes replacing a third-party
 * VPN — that requires a separate per-attempt [VpnStartRequest.replaceAuthorized].
 * An unknown owner is always a warning, and lockdown always refuses.
 */
class VpnStartPolicyTest {

    @Test
    fun freeSlotPreparedStarts() {
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.FREE, prepared = true, replaceAuthorized = false)),
        )
    }

    @Test
    fun freeSlotNotPreparedRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.FREE, prepared = false, replaceAuthorized = false)),
        )
    }

    @Test
    fun ownSlotPreparedStarts() {
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.OURS, prepared = true, replaceAuthorized = false)),
        )
    }

    @Test
    fun thirdPartyWithoutReplacementOffers() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = false, replaceAuthorized = false)),
        )
    }

    @Test
    fun thirdPartyWithExplicitReplacementStarts() {
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = false, replaceAuthorized = true)),
        )
    }

    @Test
    fun thirdPartyCachedPrepareGrantCannotReplace() {
        // The OS permission is cached (prepared = true) but the user has NOT
        // explicitly authorized replacing this third-party VPN. That must still
        // offer a choice, never silently take over the slot.
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = true, replaceAuthorized = false)),
        )
    }

    @Test
    fun unknownSlotOffersConservativelyRegardlessOfGrants() {
        // An owner we cannot verify is a warning even with a cached grant and
        // even with an explicit replacement authorization.
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true, replaceAuthorized = false)),
        )
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true, replaceAuthorized = true)),
        )
    }

    @Test
    fun lockdownRefusesRegardlessOfSlotOrGrants() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.FREE, prepared = true, replaceAuthorized = false, lockdown = true)),
        )
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = false, replaceAuthorized = true, lockdown = true)),
        )
    }

    @Test
    fun decisionIsPureAndRecheckable() {
        val request = VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true, replaceAuthorized = false)
        assertEquals(VpnStartPolicy.decide(request), VpnStartPolicy.decide(request))
        assertTrue(VpnStartPolicy.decide(request) is VpnStartDecision.Offer)
    }
}
