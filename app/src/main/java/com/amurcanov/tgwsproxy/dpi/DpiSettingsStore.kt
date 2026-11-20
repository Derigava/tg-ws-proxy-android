package com.amurcanov.tgwsproxy.dpi

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal data class DpiConnectionOptions(val host: String, val port: Int, val routing: DpiVpnRouting)

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
        return DpiConnectionOptions(loopback, port, DpiVpnRouting(ipv6 = ipv6, appListMode = appList, apps = apps))
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
