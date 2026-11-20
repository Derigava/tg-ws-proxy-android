package com.amurcanov.tgwsproxy.dpi

/**
 * Engine configuration for one start. `host`/`port` are the loopback SOCKS5
 * listen endpoint; `args` are the additional `ciadpi` command-line parameters
 * (desync strategies and so on), already shell-split by the settings layer.
 */
data class DpiConfig(
    val mode: DpiMode,
    val host: String = "127.0.0.1",
    val port: Int = 1080,
    val args: List<String> = emptyList(),
    val vpnRouting: DpiVpnRouting? = null,
) {
    /** True only for loopback (or a wildcard we coerce to loopback) addresses. */
    val isLoopback: Boolean
        get() = when (host.removeSurrounding("[", "]")) {
            "127.0.0.1", "::1", "0.0.0.0", "::" -> true
            else -> false
        }

    /** Loopback address to actually bind: wildcards are narrowed to the loopback interface. */
    val normalizedHost: String
        get() = when (host.removeSurrounding("[", "]")) {
            "0.0.0.0" -> "127.0.0.1"
            "::" -> "::1"
            else -> host
        }

    /** Full native argv, mirroring the upstream `ciadpi` invocation. */
    fun toArgv(): Array<String> =
        (listOf("ciadpi", "--ip", normalizedHost, "--port", port.toString()) + args).toTypedArray()
}
