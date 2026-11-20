package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Integration-seam tests for [VpnAuthorizer]. A scripted [VpnSlotSampler]
 * supplies the slot snapshots so the decide-then-recheck sequence and its
 * conservative refusals are exercised without Android. The production
 * [AndroidVpnSlotSampler] is the real (device-only) seam.
 */
class VpnAuthorizerTest {

    /** Returns each scripted sample in turn; the last one repeats. */
    private class ScriptedSampler(private val samples: List<VpnStartRequest>) : VpnSlotSampler {
        private var index = 0
        override fun sample(replaceAuthorized: Boolean): VpnStartRequest =
            samples[minOf(index++, samples.size - 1)]
    }

    private fun authorizer(vararg samples: VpnStartRequest) = VpnAuthorizer(ScriptedSampler(samples.toList()))

    @Test
    fun startSurvivesRecheckWhenSlotStable() {
        val free = VpnStartRequest(VpnSlotState.FREE, prepared = true, replaceAuthorized = false)
        assertEquals(VpnStartDecision.Start, authorizer(free, free).authorize(false))
    }

    @Test
    fun changedSlotDuringRecheckRefusesConservatively() {
        // A foreign VPN appeared between decision and establish: refuse.
        val free = VpnStartRequest(VpnSlotState.FREE, prepared = true, replaceAuthorized = false)
        val appeared = VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true, replaceAuthorized = false)
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            authorizer(free, appeared).authorize(false),
        )
    }

    @Test
    fun thirdPartyWithoutReplacementOffers() {
        val thirdParty = VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = true, replaceAuthorized = false)
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            authorizer(thirdParty).authorize(false),
        )
    }

    @Test
    fun cachedPrepareGrantCannotReplaceThirdParty() {
        // prepared=true is a cached OS grant, not an explicit replacement: offer.
        val thirdParty = VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = true, replaceAuthorized = false)
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            authorizer(thirdParty).authorize(false),
        )
    }

    @Test
    fun thirdPartyWithExplicitReplacementStarts() {
        val thirdParty = VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = false, replaceAuthorized = true)
        assertEquals(VpnStartDecision.Start, authorizer(thirdParty, thirdParty).authorize(true))
    }

    @Test
    fun revokedOrDeniedPrepareRefuses() {
        val denied = VpnStartRequest(VpnSlotState.FREE, prepared = false, replaceAuthorized = false)
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED),
            authorizer(denied).authorize(false),
        )
    }

    @Test
    fun lockdownRefuses() {
        val locked = VpnStartRequest(VpnSlotState.FREE, prepared = true, replaceAuthorized = false, lockdown = true)
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            authorizer(locked).authorize(false),
        )
    }

    @Test
    fun consentLostDuringRecheckRefuses() {
        // prepare grant was present at decision time but revoked before establish.
        val prepared = VpnStartRequest(VpnSlotState.FREE, prepared = true, replaceAuthorized = false)
        val lost = VpnStartRequest(VpnSlotState.FREE, prepared = false, replaceAuthorized = false)
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST),
            authorizer(prepared, lost).authorize(false),
        )
    }
}
