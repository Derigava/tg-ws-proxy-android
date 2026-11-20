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
) {
    /** Full native argv, mirroring the upstream `ciadpi` invocation. */
    fun toArgv(): Array<String> =
        (listOf("ciadpi", "--ip", host, "--port", port.toString()) + args).toTypedArray()
}
