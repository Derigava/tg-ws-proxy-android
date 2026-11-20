package com.amurcanov.tgwsproxy.dpi

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Contract test for [DpiController]. The three cases below pin the behaviour
 * the follow-up VPN/UI layers rely on: a failed bind must never look like a
 * running engine, stop must be idempotent, and a failing DPI engine must not
 * disturb a neighbouring service (the Telegram proxy).
 */
class DpiControllerTest {

    /** Test double that performs a real loopback bind to mirror native readiness. */
    private class BindProbeEngine : DpiEngine {
        override fun start(config: DpiConfig): DpiEngineResult = try {
            ServerSocket().use { it.bind(InetSocketAddress(config.host, config.port)) }
            DpiEngineResult.Running
        } catch (e: java.io.IOException) {
            DpiEngineResult.Failed("bind failed: ${e.message}")
        }

        override fun stop() = Unit
    }

    /** Test double that reports a ready engine and counts stop requests. */
    private class CountingEngine : DpiEngine {
        @Volatile
        var stopCount = 0
            private set

        override fun start(config: DpiConfig): DpiEngineResult = DpiEngineResult.Running

        override fun stop() {
            stopCount++
        }
    }

    @Test
    fun startWithOccupiedPortReportsFailedNotRunning() {
        val occupied = ServerSocket(0)
        try {
            val controller = DpiController(BindProbeEngine())
            controller.start(
                DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = occupied.localPort)
            )
            val state = runBlocking {
                controller.state.first { it is DpiState.Failed || it is DpiState.Running }
            }
            assertTrue("expected Failed, got $state", state is DpiState.Failed)
            assertTrue((state as DpiState.Failed).reason.isNotBlank())
        } finally {
            occupied.close()
        }
    }

    @Test
    fun doubleStopEndsOffAndStopsEngineOnce() {
        val engine = CountingEngine()
        val controller = DpiController(engine)
        controller.start(DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = 1080))
        runBlocking { controller.state.first { it is DpiState.Running } }
        assertEquals(DpiState.Running(DpiMode.SOCKS5), controller.state.value)

        controller.stop()
        runBlocking { controller.state.first { it is DpiState.Off } }
        assertEquals(DpiState.Off, controller.state.value)

        controller.stop()
        assertEquals(DpiState.Off, controller.state.value)
        assertEquals("engine must be stopped once for two stop commands", 1, engine.stopCount)
    }

    @Test
    fun siblingServiceStaysReachableWhenDpiFailsToBind() {
        val sibling = ServerSocket(0)
        val occupied = ServerSocket(0)
        try {
            val controller = DpiController(BindProbeEngine())
            controller.start(
                DpiConfig(mode = DpiMode.SOCKS5, host = "127.0.0.1", port = occupied.localPort)
            )
            runBlocking {
                controller.state.first { it is DpiState.Failed || it is DpiState.Running }
            }
            assertTrue(controller.state.value is DpiState.Failed)

            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", sibling.localPort), 1000)
                assertTrue(socket.isConnected)
            }
        } finally {
            sibling.close()
            occupied.close()
        }
    }
}
