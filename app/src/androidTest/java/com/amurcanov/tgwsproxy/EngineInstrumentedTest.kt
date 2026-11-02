package com.amurcanov.tgwsproxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    private fun engine() = PythonProxyEngine(InstrumentationRegistry.getInstrumentation().targetContext)
    private fun config(port: Int) = ProxyConfiguration("127.0.0.1", port, "", "1234567890abcdef1234567890abcdef", 0, false, "example.com")
    @Test fun cryptoAndCoreImports() {
        engine()
        val python = Python.getInstance()
        val smoke = JSONObject(python.getModule("android_proxy").callAttr("self_test").toString())
        assertTrue(smoke.getBoolean("ok"))
        assertEquals(BuildConfig.CORE_VERSION, smoke.getString("core_version"))
        assertEquals("42.0.8", smoke.getString("cryptography"))
        val result = python.getModule("builtins").callAttr("eval",
            "__import__('cryptography.hazmat.primitives.ciphers', fromlist=['Cipher']).Cipher(__import__('cryptography.hazmat.primitives.ciphers.algorithms', fromlist=['AES']).AES(bytes(16)), __import__('cryptography.hazmat.primitives.ciphers.modes', fromlist=['CTR']).CTR(bytes(16))).encryptor().update(bytes(16)).hex()")
        assertEquals("66e94bd4ef8a2c3b884cfa59ca342b2e", result.toString())
        python.getModule("proxy.h2_transport")
        python.getModule("proxy.fake_tls")
        python.getModule("proxy.raw_websocket")
    }
    @Test fun repeatedStartStopAndPortConflict() {
        val runtime = engine()
        try {
            ServerSocket(0).use { occupied ->
                val failed = runtime.start(config(occupied.localPort))
                assertNull(failed.connection)
                assertNotNull(failed.error)
                runtime.stop()
            }
            repeat(3) {
                val port = ServerSocket(0).use { it.localPort }
                val started = runtime.start(config(port))
                assertEquals(started.error, "running", started.state)
                assertEquals(port, started.connection?.port)
                assertEquals("dd1234567890abcdef1234567890abcdef", started.connection?.secret)
                assertNotNull(runtime.snapshot().connection)
                assertEquals("stopped", runtime.stop().state)
                ServerSocket(port).close()
            }
        } finally { runtime.stop() }
    }
}
