package com.amurcanov.tgwsproxy.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Integration-seam tests for [VpnAuthorizer]. A scripted [VpnSlotSampler]
 * supplies the slot snapshots so the decide-then-recheck sequence, the
 * deliberate-replacement path, and the conservative refusals are exercised
 * without Android. The production [AndroidVpnSlotSampler] is the real
 * (device-only, fail-closed) seam.
 */
class VpnAuthorizerTest {

    /** Returns each scripted sample in turn; the last one repeats. */
    private class ScriptedSampler(private val samples: List<VpnStartRequest>) : VpnSlotSampler {
        private var index = 0
        override fun sample(): VpnStartRequest = samples[minOf(index++, samples.size - 1)]
    }

    private fun authorizer(vararg samples: VpnStartRequest) = VpnAuthorizer(ScriptedSampler(samples.toList()))

    private fun freePrepared() = VpnStartRequest(VpnSlotState.FREE, prepared = true)
    private fun freeUnprepared() = VpnStartRequest(VpnSlotState.FREE, prepared = false)
    private fun unknownPrepared() = VpnStartRequest(VpnSlotState.UNKNOWN, prepared = true)
    private fun thirdPartyPrepared() = VpnStartRequest(VpnSlotState.THIRD_PARTY, prepared = true)

    @Test
    fun startSurvivesRecheckWhenSlotStable() {
        assertEquals(VpnStartDecision.Start, authorizer(freePrepared(), freePrepared()).authorize(null))
    }

    @Test
    fun changedSlotDuringRecheckRefusesConservatively() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            authorizer(freePrepared(), unknownPrepared()).authorize(null),
        )
    }

    @Test
    fun unavailableObservationFailsClosed() {
        // UNKNOWN is also the fail-closed slot when observation is unavailable:
        // it must never auto-start, only offer.
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            authorizer(unknownPrepared()).authorize(null),
        )
    }

    @Test
    fun thirdPartyWithoutReplacementOffers() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            authorizer(thirdPartyPrepared()).authorize(null),
        )
    }

    @Test
    fun cachedPrepareGrantCannotReplaceThirdParty() {
        // prepared=true is a cached OS grant, not a deliberate replacement.
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = false),
            authorizer(thirdPartyPrepared()).authorize(null),
        )
    }

    @Test
    fun thirdPartyWithDeliberateReplacementStarts() {
        assertEquals(
            VpnStartDecision.Start,
            authorizer(thirdPartyPrepared(), thirdPartyPrepared()).authorize(VpnReplacement(VpnSlotState.THIRD_PARTY)),
        )
    }

    @Test
    fun unknownCachedGrantCannotReplace() {
        assertEquals(
            VpnStartDecision.Offer(ownerUncertain = true),
            authorizer(unknownPrepared()).authorize(null),
        )
    }

    @Test
    fun unknownDeliberateReplacementWithPrepareStarts() {
        assertEquals(
            VpnStartDecision.Start,
            authorizer(unknownPrepared(), unknownPrepared()).authorize(VpnReplacement(VpnSlotState.UNKNOWN)),
        )
    }

    @Test
    fun replacementRejectedWhenSlotChangesDuringRecheck() {
        // The proof was bound to THIRD_PARTY, but the slot changed to UNKNOWN:
        // the freshly observed slot wins, so the replacement is rejected.
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.SLOT_CHANGED),
            authorizer(thirdPartyPrepared(), unknownPrepared()).authorize(VpnReplacement(VpnSlotState.THIRD_PARTY)),
        )
    }

    @Test
    fun revokedOrDeniedPrepareRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_NOT_GRANTED),
            authorizer(freeUnprepared()).authorize(null),
        )
    }

    @Test
    fun lockdownRefuses() {
        val locked = VpnStartRequest(VpnSlotState.FREE, prepared = true, lockdown = true)
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.LOCKDOWN),
            authorizer(locked).authorize(null),
        )
    }

    @Test
    fun consentLostDuringRecheckRefuses() {
        assertEquals(
            VpnStartDecision.Refuse(VpnRefuseReason.CONSENT_LOST),
            authorizer(freePrepared(), freeUnprepared()).authorize(null),
        )
    }

    @Test
    fun confirmReplacementBindsToObservedSlot() {
        val authorizer = authorizer(unknownPrepared())
        assertEquals(VpnReplacement(VpnSlotState.UNKNOWN), authorizer.confirmReplacement())
    }

    @Test
    fun confirmReplacementRefusesWhenNothingToReplace() {
        assertThrows(IllegalArgumentException::class.java) { authorizer(freePrepared()).confirmReplacement() }
    }
}
