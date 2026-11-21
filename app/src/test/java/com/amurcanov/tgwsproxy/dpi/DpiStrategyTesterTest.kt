package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiStrategyTesterTest {
    private val candidates = listOf(
        DpiStrategyCandidate("current", listOf("-o1")),
        DpiStrategyCandidate("partial", listOf("-d1")),
        DpiStrategyCandidate("complete", listOf("-s1")),
    )

    @Test
    fun ranksCompletedStrategiesByReachableTargetsAndKeepsTheFirstOnTies() {
        assertEquals(
            listOf("complete", "current", "partial"),
            rankDpiStrategies(listOf(
                DpiStrategyResult(candidates[0], setOf(DpiProbeTarget.WEB)),
                DpiStrategyResult(candidates[1], setOf(DpiProbeTarget.IMAGES)),
                DpiStrategyResult(candidates[2], DpiProbeTarget.entries.toSet()),
            )).map { it.candidate.command },
        )
    }

    @Test
    fun reachableApiBreaksTiesInFavorOfNativeAppConnectivity() {
        val web = DpiStrategyResult(candidates[0], setOf(DpiProbeTarget.WEB, DpiProbeTarget.IMAGES))
        val api = DpiStrategyResult(candidates[1], setOf(DpiProbeTarget.API, DpiProbeTarget.IMAGES))
        assertEquals(listOf(api, web), rankDpiStrategies(listOf(web, api)))
    }

    @Test
    fun expectedApiStatusProvesReachabilityButAccessDenialDoesNot() {
        assertTrue(DpiProbeTarget.API.accepts(404))
        assertTrue(DpiProbeTarget.API.accepts(405))
        assertFalse(DpiProbeTarget.IMAGES.accepts(404))
        assertFalse(DpiProbeTarget.WEB.accepts(404))
        assertFalse(DpiProbeTarget.API.accepts(403))
        assertFalse(DpiProbeTarget.WEB.accepts(451))
    }

    @Test
    fun stopsEveryCandidateIncludingAFailedStartup() = runBlocking {
        val events = mutableListOf<String>()
        val engine = object : DpiEngine {
            override var onExit: (() -> Unit)? = null
            override fun start(config: DpiConfig): DpiEngineResult {
                events.add("start ${config.args.single()}")
                return if (config.args == listOf("-d1")) DpiEngineResult.Failed("startup failed")
                else DpiEngineResult.Running
            }
            override fun stop() { events.add("stop") }
        }
        val tester = DpiStrategyTester(engine, probe = { _, _ -> true })
        val delivered = mutableListOf<DpiStrategyResult>()
        tester.test(candidates, port = 1081) { delivered.add(it) }
        assertEquals(listOf("start -o1", "stop", "start -d1", "stop", "start -s1", "stop"), events)
        assertEquals(listOf(3, 0, 3), delivered.map { it.reachable })
        assertTrue(delivered[1].startFailed)
    }

    @Test
    fun cancellationFinishesCleanupWithoutStartingAnotherCandidate() = runBlocking {
        var starts = 0
        var stops = 0
        val engine = object : DpiEngine {
            override var onExit: (() -> Unit)? = null
            override fun start(config: DpiConfig): DpiEngineResult {
                starts++
                return DpiEngineResult.Running
            }
            override fun stop() { stops++ }
        }
        val tester = DpiStrategyTester(engine, probe = { _, _ -> throw kotlinx.coroutines.CancellationException() })
        val cancelled = runCatching { tester.test(candidates, port = 1081) {} }.exceptionOrNull()
        assertTrue(cancelled is kotlinx.coroutines.CancellationException)
        assertEquals(1, starts)
        assertEquals(1, stops)
    }
}
