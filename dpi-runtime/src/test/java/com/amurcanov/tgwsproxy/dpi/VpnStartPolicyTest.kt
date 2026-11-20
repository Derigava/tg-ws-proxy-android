package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-table test for [VpnStartPolicy]. The table pins the conservative
 * contract: a cached OS prepare grant ([VpnStartRequest.prepared]) authorizes
 * starting on a free/ours slot but NEVER authorizes replacing a foreign VPN —
 * that requires a deliberate per-attempt [VpnReplacement] bound to the slot,
 * AND a valid OS grant. An unknown owner is a warning unless deliberately and
 * scoped-ly replaced, and lockdown always refuses.
 */
class VpnStartPolicyTest {

    @Test
    fun freeSlotPreparedStarts() {
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.FREE, prepared = true)),
        )
    }

    @Test
    fun freeSlotNotPreparedRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.FREE, prepared = false)),
        )
    }

    @Test
    fun ownSlotPreparedStarts() {
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.OURS, prepared = true)),
        )
    }

    @Test
    fun thirdPartyWithoutReplacementOffers() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = true)),
        )
    }

    @Test
    fun thirdPartyWithReplacementAndPrepareStarts() {
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(
                VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = true),
                VpnReplacement(VpnSlotState.THIRD_PARTY),
            ),
        )
    }

    @Test
    fun thirdPartyCachedPrepareGrantCannotReplace() {
        // A cached OS grant (prepared=true) without a deliberate replacement is
        // still an Offer, never a silent takeover.
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = true)),
        )
    }

    @Test
    fun thirdPartyWithReplacementButNoPrepareOffers() {
        // Deliberate replacement still requires a valid OS grant to establish.
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            VpnStartPolicy.decide(
                VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = false),
                VpnReplacement(VpnSlotState.THIRD_PARTY),
            ),
        )
    }

    @Test
    fun unknownSlotWithoutReplacementWarnsConservatively() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true)),
        )
    }

    @Test
    fun unknownSlotCachedGrantWithoutReplacementOffers() {
        // Cached grant alone is never authorization to replace an unknown VPN.
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true)),
        )
    }

    @Test
    fun unknownSlotWithDeliberateReplacementAndPrepareStarts() {
        // The reachable replace path for UNKNOWN: deliberate proof + valid OS grant.
        assertEquals(
            VpnStartDecision.Start,
            VpnStartPolicy.decide(
                VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true),
                VpnReplacement(VpnSlotState.UNKNOWN),
            ),
        )
    }

    @Test
    fun unknownSlotWithReplacementButNoPrepareOffers() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            VpnStartPolicy.decide(
                VpnStartRequest(VpnSlotState.UNKNOWN, prepared = false),
                VpnReplacement(VpnSlotState.UNKNOWN),
            ),
        )
    }

    @Test
    fun replacementBoundToWrongSlotIsIgnored() {
        // A proof bound to a different observation cannot authorize a changed slot.
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            VpnStartPolicy.decide(
                VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true),
                VpnReplacement(VpnSlotState.THIRD_PARTY),
            ),
        )
    }

    @Test
    fun lockdownRefusesRegardlessOfSlotOrReplacement() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(VpnStartRequest(VpnSlotState.FREE, prepared = true, lockdown = true)),
        )
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            VpnStartPolicy.decide(
                VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true, lockdown = true),
                VpnReplacement(VpnSlotState.UNKNOWN),
            ),
        )
    }

    @Test
    fun decisionIsPureAndRecheckable() {
        val request = VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true)
        assertEquals(
            VpnStartPolicy.decide(request, VpnReplacement(VpnSlotState.UNKNOWN)),
            VpnStartPolicy.decide(request, VpnReplacement(VpnSlotState.UNKNOWN)),
        )
        assertTrue(VpnStartPolicy.decide(request) is VpnStartDecision.Offer)
    }
}
