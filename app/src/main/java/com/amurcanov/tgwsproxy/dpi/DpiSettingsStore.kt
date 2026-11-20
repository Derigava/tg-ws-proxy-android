package com.amurcanov.tgwsproxy.dpi

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class DpiConnectionOptions(
    val host: String,
    val port: Int,
    val routing: DpiVpnRouting,
    val args: List<String> = emptyList(),
)

/** Stores a validated v.1.8.2 exchange document, independently of TG settings. */
internal class DpiSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("dpi_document", Context.MODE_PRIVATE)

    fun exportJson(): String = preferences.getString("backup", null)
        ?: document().put("settings", JSONObject()).toString()

    fun importJson(json: String) {
        require(json.length <= MAX_CHARS) { "ByeByeDPI document exceeds the size limit" }
        val validated = try {
            val source = JSONObject(json)
            require(source.get("app") == UPSTREAM_APP)
            val version = source.get("version") as? String ?: error("Missing backup version")
            require(version.isNotBlank())
            document().put("version", version).apply {
                source.section("settings")?.let {
                    val values = it as? JSONObject ?: invalid()
                    val filtered = JSONObject()
                    for (key in values.keys()) {
                        if (key !in SETTINGS_KEYS) continue
                        val value = values.get(key)
                        require(value is String || value is Number || value is Boolean ||
                            (value is JSONArray && value.isStringArray()))
                        filtered.put(key, value)
                    }
                    put("settings", filtered)
                }
                source.section("apps")?.let {
                    require(it is JSONArray && it.isStringArray())
                    put("apps", it)
                }
                source.section("history")?.let {
                    put("history", records(it, setOf("text"), setOf("pinned"), setOf("name")))
                }
                source.section("domainLists")?.let {
                    put("domainLists", records(it, setOf("id", "name"), DOMAIN_FLAGS, stringArray = "domains"))
                }
                require(length() > 2) { "No settings sections" }
            }
        } catch (exception: JSONException) {
            throw IllegalArgumentException("Invalid ByeByeDPI document", exception)
        }
        connectionOptions(validated)
        check(preferences.edit().putString("backup", validated.toString()).commit()) { "Could not save ByeByeDPI document" }
    }

    fun connectionOptions(): DpiConnectionOptions = connectionOptions(JSONObject(exportJson()))

    private fun connectionOptions(snapshot: JSONObject): DpiConnectionOptions {
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        fun text(key: String, default: String): String = settings.section(key)?.let {
            require(it is String) { "Expected text in $key" }
            it
        } ?: default
        val rawPort = settings.section("byedpi_proxy_port") ?: "1080"
        val port = rawPort.toString().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Invalid SOCKS5 port")
        val host = text("byedpi_proxy_ip", "127.0.0.1").removeSurrounding("[", "]")
        val loopback = when (host) {
            "127.0.0.1", "0.0.0.0" -> "127.0.0.1"
            "::1", "::" -> "::1"
            else -> throw IllegalArgumentException("SOCKS5 endpoint must be local")
        }
        val ipv6 = settings.section("ipv6_enable")?.let {
            require(it is Boolean) { "Invalid IPv6 setting" }
            it
        } ?: false
        val apps = snapshot.optJSONArray("apps")?.let { records ->
            (0 until records.length()).map { records.getString(it) }.distinct()
        }.orEmpty()
        val appList = when (text("applist_type", "disable")) {
            "disable" -> VpnAppListMode.ALL_APPS
            "blacklist" -> VpnAppListMode.BLACKLIST
            "whitelist" -> VpnAppListMode.WHITELIST
            else -> throw IllegalArgumentException("Invalid application filter")
        }
        val dns = text("dns_ip", "").trim()
        require(dns.isEmpty() || isNumericDnsAddress(dns)) { "DNS address must be numeric" }
        val args = strategyArgs(settings)
        return DpiConnectionOptions(loopback, port, DpiVpnRouting(dns = dns, ipv6 = ipv6, appListMode = appList, apps = apps), args)
    }

    /**
     * Maps the v.1.8.2 UI settings to `ciadpi` strategy arguments, mirroring
     * upstream `ByeDpiProxyUIPreferences.uiargs` exactly (the endpoint `--ip`/
     * `--port` are handled by [DpiConfig.toArgv], not here).
     */
    private fun strategyArgs(settings: JSONObject): List<String> {
        fun text(key: String, default: String): String = settings.section(key)?.let {
            require(it is String) { "Expected text in $key" }
            it
        } ?: default
        fun int(key: String, default: Int): Int = settings.section(key)?.let {
            val value = it.toString().toIntOrNull() ?: throw IllegalArgumentException("Invalid number in $key")
            value
        } ?: default
        fun bool(key: String, default: Boolean): Boolean = settings.section(key)?.let {
            require(it is Boolean) { "Expected boolean in $key" }
            it
        } ?: default

        val args = mutableListOf<String>()
        int("byedpi_max_connections", 512).takeIf { it != 0 }?.let { args.add("-c$it") }
        int("byedpi_buffer_size", 16384).takeIf { it != 0 }?.let { args.add("-b$it") }

        val protocols = buildList {
            if (bool("byedpi_desync_https", true)) add("t")
            if (bool("byedpi_desync_http", true)) add("h")
        }
        val hostsMode = text("byedpi_hosts_mode", "disable")
        val hosts = when (hostsMode) {
            "disable" -> null
            "blacklist" -> text("byedpi_hosts_blacklist", "")
            "whitelist" -> text("byedpi_hosts_whitelist", "")
            else -> throw IllegalArgumentException("Invalid hosts mode")
        }
        if (!hosts.isNullOrBlank()) {
            val hostStr = ":${hosts.replace("\n", " ")}"
            when (hostsMode) {
                "blacklist" -> {
                    args.add("-H$hostStr")
                    args.add("-An")
                    if (protocols.isNotEmpty()) args.add("-K${protocols.joinToString(",")}")
                }
                "whitelist" -> {
                    if (protocols.isNotEmpty()) args.add("-K${protocols.joinToString(",")}")
                    args.add("-H$hostStr")
                }
            }
        } else {
            if (protocols.isNotEmpty()) args.add("-K${protocols.joinToString(",")}")
        }

        int("byedpi_default_ttl", 0).takeIf { it != 0 }?.let { args.add("-g$it") }
        if (bool("byedpi_no_domain", false)) args.add("-N")

        val splitPosition = int("byedpi_split_position", 1)
        val desyncMethod = text("byedpi_desync_method", "oob")
        if (splitPosition != 0) {
            val posArg = splitPosition.toString() + if (bool("byedpi_split_at_host", false)) "+h" else ""
            val option = when (desyncMethod) {
                "split" -> "-s"
                "disorder" -> "-d"
                "oob" -> "-o"
                "disoob" -> "-q"
                "fake" -> "-f"
                "none" -> ""
                else -> throw IllegalArgumentException("Invalid desync method")
            }
            if (option.isNotEmpty()) args.add("$option$posArg")
        } else if (desyncMethod !in setOf("none", "split", "disorder", "oob", "disoob", "fake")) {
            throw IllegalArgumentException("Invalid desync method")
        }

        if (desyncMethod == "fake") {
            int("byedpi_fake_ttl", 8).takeIf { it != 0 }?.let { args.add("-t$it") }
            text("byedpi_fake_sni", "www.iana.org").takeIf { it.isNotEmpty() }?.let { args.add("-n$it") }
            int("byedpi_fake_offset", 0).takeIf { it != 0 }?.let { args.add("-O$it") }
        }
        if (desyncMethod == "oob" || desyncMethod == "disoob") {
            val oob = text("byedpi_oob_data", "a")
            require(oob.isNotEmpty()) { "OOB data must not be empty" }
            args.add("-e${oob[0].code.toByte()}")
        }

        val modHttpFlags = buildList {
            if (bool("byedpi_host_mixed_case", false)) add("h")
            if (bool("byedpi_domain_mixed_case", false)) add("d")
            if (bool("byedpi_host_remove_spaces", false)) add("r")
        }
        if (modHttpFlags.isNotEmpty()) args.add("-M${modHttpFlags.joinToString(",")}")

        if (bool("byedpi_tlsrec_enabled", false) && int("byedpi_tlsrec_position", 0) != 0) {
            val tlsRecArg = int("byedpi_tlsrec_position", 0).toString() + if (bool("byedpi_tlsrec_at_sni", false)) "+s" else ""
            args.add("-r$tlsRecArg")
        }

        if (bool("byedpi_tcp_fast_open", false)) args.add("-F")
        if (bool("byedpi_drop_sack", false)) args.add("-Y")

        args.add("-An")
        if (bool("byedpi_desync_udp", true)) {
            args.add("-Ku")
            int("byedpi_udp_fake_count", 1).takeIf { it != 0 }?.let { args.add("-a$it") }
            args.add("-An")
        }
        return args
    }

    private fun isNumericDnsAddress(value: String): Boolean {
        if (':' in value) {
            // Restrict the parser to numeric IPv6 syntax; never send a hostname to DNS.
            if (!value.matches(Regex("[0-9a-fA-F:.]{2,45}"))) return false
            return try {
                java.net.InetAddress.getByName(value)
                true
            } catch (_: java.net.UnknownHostException) {
                false
            }
        }
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.length in 1..3 && octet.all { it in '0'..'9' } && octet.toInt() <= 255
        }
    }

    private fun JSONObject.section(name: String): Any? = if (isNull(name)) null else get(name)

    private fun records(
        section: Any,
        requiredStrings: Set<String>,
        booleans: Set<String>,
        optionalStrings: Set<String> = emptySet(),
        stringArray: String? = null,
    ): JSONArray {
        val input = section as? JSONArray ?: invalid()
        return JSONArray().apply {
            for (index in 0 until input.length()) {
                val record = input.getJSONObject(index)
                require(requiredStrings.all { record.get(it) is String })
                require(booleans.all { !record.has(it) || record.get(it) is Boolean })
                require(optionalStrings.all { record.isNull(it) || record.get(it) is String })
                if (stringArray != null) require(record.getJSONArray(stringArray).isStringArray())
                val allowed = requiredStrings + booleans + optionalStrings + listOfNotNull(stringArray)
                val clean = JSONObject()
                for (key in record.keys()) if (key in allowed) clean.put(key, record.get(key))
                put(clean)
            }
        }
    }

    private fun JSONArray.isStringArray() = (0 until length()).all { get(it) is String }

    private fun document() = JSONObject().put("app", UPSTREAM_APP).put("version", "1.8.2")

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid ByeByeDPI section")

    private companion object {
        const val UPSTREAM_APP = "io.github.romanvht.byedpi"
        const val MAX_CHARS = 2 * 1024 * 1024
        val DOMAIN_FLAGS = setOf("isActive", "isBuiltIn", "isModified", "isDeleted")
        val SETTINGS_KEYS = setOf(
            "language", "app_theme", "dns_ip", "private_dns", "ipv6_enable", "applist_type", "autostart", "auto_connect",
            "byedpi_mode", "byedpi_proxy_ip", "byedpi_proxy_port", "byedpi_enable_cmd_settings", "byedpi_cmd_args",
            "byedpi_max_connections", "byedpi_buffer_size", "byedpi_no_domain", "byedpi_tcp_fast_open", "byedpi_default_ttl",
            "byedpi_hosts_mode", "byedpi_hosts_blacklist", "byedpi_hosts_whitelist", "byedpi_desync_method",
            "byedpi_split_position", "byedpi_split_at_host", "byedpi_drop_sack", "byedpi_fake_ttl", "byedpi_fake_offset",
            "byedpi_fake_sni", "byedpi_oob_data", "byedpi_desync_http", "byedpi_desync_https", "byedpi_desync_udp",
            "byedpi_host_mixed_case", "byedpi_domain_mixed_case", "byedpi_host_remove_spaces", "byedpi_tlsrec_enabled",
            "byedpi_tlsrec_position", "byedpi_tlsrec_at_sni", "byedpi_udp_fake_count", "byedpi_proxytest_delay",
            "byedpi_proxytest_requests", "byedpi_proxytest_limit", "byedpi_proxytest_timeout", "byedpi_proxytest_sni",
            "byedpi_proxytest_usercommands", "byedpi_proxytest_commands",
        )
    }
}
