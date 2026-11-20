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

internal data class DpiCommand(val text: String, val pinned: Boolean = false, val name: String? = null)

internal data class DpiDomainList(
    val id: String,
    val name: String,
    val domains: List<String>,
    val isActive: Boolean = true,
    val isBuiltIn: Boolean = false,
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

    /** Raw settings map for the editor; values are String/Number/Boolean. */
    fun currentSettings(): Map<String, Any?> {
        val settings = JSONObject(exportJson()).optJSONObject("settings") ?: return emptyMap()
        return settings.keys().asSequence().associateWith { settings.get(it) }
    }

    /**
     * Merges validated strategy/settings values into the stored document and
     * re-validates the whole document before committing. A value of `null`
     * removes the key. Unknown keys and invalid values are rejected without
     * changing the stored document.
     */
    fun updateSettings(fields: Map<String, Any?>) {
        val current = JSONObject(exportJson())
        val settings = current.optJSONObject("settings") ?: JSONObject().also { current.put("settings", it) }
        for ((key, value) in fields) {
            require(key in SETTINGS_KEYS) { "Unknown setting: $key" }
            if (value == null) {
                settings.remove(key)
            } else {
                require(value is String || value is Number || value is Boolean ||
                    (value is JSONArray && value.isStringArray())) { "Invalid value for $key" }
                settings.put(key, value)
            }
        }
        connectionOptions(current)
        check(preferences.edit().putString("backup", current.toString()).commit()) { "Could not save ByeByeDPI document" }
    }

    /** Strategy history (parity C-03..C-05), stored in the document's `history` section. */
    fun history(): List<DpiCommand> {
        val document = JSONObject(exportJson())
        val history = document.optJSONArray("history") ?: return emptyList()
        return (0 until history.length()).map { index ->
            val record = history.getJSONObject(index)
            DpiCommand(record.getString("text"), record.optBoolean("pinned"), record.optString("name").ifBlank { null })
        }
    }

    fun pinnedHistory(): List<DpiCommand> = history().filter { it.pinned }

    fun addCommand(command: String) {
        if (command.isBlank()) return
        mutateHistory { current ->
            if (current.any { it.text == command }) current
            else {
                val next = (listOf(DpiCommand(command)) + current).toMutableList()
                while (next.size > MAX_HISTORY) {
                    val lastUnpinned = next.indexOfLast { !it.pinned }
                    if (lastUnpinned >= 0) next.removeAt(lastUnpinned) else next.removeAt(next.size - 1)
                }
                next
            }
        }
    }

    fun pinCommand(command: String) = mutateHistory { current -> current.map { if (it.text == command) it.copy(pinned = true) else it } }

    fun unpinCommand(command: String) = mutateHistory { current -> current.map { if (it.text == command) it.copy(pinned = false) else it } }

    fun deleteCommand(command: String) = mutateHistory { current -> current.filterNot { it.text == command } }

    fun renameCommand(command: String, name: String) = mutateHistory { current -> current.map { if (it.text == command) it.copy(name = name) else it } }

    fun clearUnpinnedHistory() = mutateHistory { current -> current.filter { it.pinned } }

    /** Applies a strategy command: enables command mode, sets the args, records history. */
    fun applyStrategy(command: String) {
        if (command.isBlank()) return
        updateSettings(mapOf("byedpi_enable_cmd_settings" to true, "byedpi_cmd_args" to command))
        addCommand(command)
    }

    /** Resets to a default document (parity X-03), dropping all settings, history and apps. */
    fun resetSettings() {
        check(preferences.edit().putString("backup", document().put("settings", JSONObject()).toString()).commit()) {
            "Could not reset ByeByeDPI settings"
        }
    }

    /** Domain lists (parity T-06), stored in the document's `domainLists` section. */
    fun domainLists(): List<DpiDomainList> {
        val lists = JSONObject(exportJson()).optJSONArray("domainLists") ?: return emptyList()
        return (0 until lists.length()).map { index ->
            val record = lists.getJSONObject(index)
            DpiDomainList(
                record.getString("id"),
                record.getString("name"),
                (0 until record.getJSONArray("domains").length()).map { record.getJSONArray("domains").getString(it) },
                record.optBoolean("isActive", true),
                record.optBoolean("isBuiltIn", false),
            )
        }
    }

    fun addDomainList(name: String, domains: List<String>) {
        val id = name.lowercase().replace(" ", "_")
        mutateDomainLists { current ->
            if (current.any { it.id == id }) current
            else current + DpiDomainList(id, name, domains.filter { it.isNotBlank() }, isActive = true)
        }
    }

    fun updateDomainList(id: String, name: String, domains: List<String>) =
        mutateDomainLists { current -> current.map { if (it.id == id) it.copy(name = name, domains = domains.filter { it.isNotBlank() }) else it } }

    fun deleteDomainList(id: String) =
        mutateDomainLists { current -> current.filterNot { it.id == id } }

    /** Sets the VPN application filter (parity S-07/S-08): type + selected package names. */
    fun setAppList(type: String, apps: List<String>) {
        require(type in setOf("disable", "blacklist", "whitelist")) { "Invalid application filter" }
        val document = JSONObject(exportJson())
        val settings = document.optJSONObject("settings") ?: JSONObject().also { document.put("settings", it) }
        settings.put("applist_type", type)
        val array = org.json.JSONArray()
        apps.distinct().filter { it.isNotBlank() }.forEach { array.put(it) }
        document.put("apps", array)
        connectionOptions(document)
        check(preferences.edit().putString("backup", document.toString()).commit()) { "Could not save ByeByeDPI app selection" }
    }

    private fun mutateDomainLists(transform: (List<DpiDomainList>) -> List<DpiDomainList>) {
        val document = JSONObject(exportJson())
        val next = transform(domainLists())
        val array = org.json.JSONArray()
        next.forEach { list ->
            val domains = org.json.JSONArray()
            list.domains.forEach { domains.put(it) }
            array.put(org.json.JSONObject()
                .put("id", list.id)
                .put("name", list.name)
                .put("domains", domains)
                .put("isActive", list.isActive)
                .put("isBuiltIn", list.isBuiltIn))
        }
        document.put("domainLists", array)
        check(preferences.edit().putString("backup", document.toString()).commit()) { "Could not save ByeByeDPI domain lists" }
    }

    private fun mutateHistory(transform: (List<DpiCommand>) -> List<DpiCommand>) {
        val document = JSONObject(exportJson())
        val next = transform(historyFrom(document))
        val array = org.json.JSONArray()
        next.forEach { command ->
            array.put(org.json.JSONObject()
                .put("text", command.text)
                .put("pinned", command.pinned)
                .put("name", command.name ?: org.json.JSONObject.NULL))
        }
        document.put("history", array)
        check(preferences.edit().putString("backup", document.toString()).commit()) { "Could not save ByeByeDPI history" }
    }

    private fun historyFrom(document: JSONObject): List<DpiCommand> {
        val history = document.optJSONArray("history") ?: return emptyList()
        return (0 until history.length()).map { index ->
            val record = history.getJSONObject(index)
            DpiCommand(record.getString("text"), record.optBoolean("pinned"), record.optString("name").ifBlank { null })
        }
    }

    private fun connectionOptions(snapshot: JSONObject): DpiConnectionOptions {
        val settings = snapshot.optJSONObject("settings") ?: JSONObject()
        fun text(key: String, default: String): String = settings.section(key)?.let {
            require(it is String) { "Expected text in $key" }
            it
        } ?: default
        val commandMode = (settings.section("byedpi_enable_cmd_settings") as? Boolean) ?: true

        val host: String
        val port: Int
        val args: List<String>
        if (commandMode) {
            val (tokens, endpoint) = commandTokens(settings, snapshot)
            val (cmdIp, cmdPort) = endpoint
            host = (cmdIp ?: text("byedpi_proxy_ip", "127.0.0.1")).removeSurrounding("[", "]")
            port = (cmdPort ?: (settings.section("byedpi_proxy_port") ?: "1080").toString())
                .toIntOrNull()?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("Invalid SOCKS5 port")
            args = tokens
        } else {
            host = text("byedpi_proxy_ip", "127.0.0.1").removeSurrounding("[", "]")
            port = (settings.section("byedpi_proxy_port") ?: "1080").toString().toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("Invalid SOCKS5 port")
            args = strategyArgs(settings)
        }
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

    /**
     * Command-line mode (upstream `ByeDpiProxyCmdPreferences`). Mirrors upstream
     * `parseCmdToArguments`: expand `{list:name}` placeholders from the domain
     * lists, shell-split, drop leading non-flag tokens, filter help/version, then
     * let the caller splice `--ip`/`--port` for the endpoint. Returns the tokens
     * minus the ip/port tokens, plus the ip/port extracted from the command.
     */
    private fun commandTokens(settings: JSONObject, snapshot: JSONObject): Pair<List<String>, Pair<String?, String?>> {
        val raw = settings.section("byedpi_cmd_args") as? String ?: DEFAULT_CMD_ARGS
        val expanded = Regex("""\{list:([^}]+)\}""").replace(raw) { match ->
            val name = match.groupValues[1].trim()
            snapshot.optJSONArray("domainLists")?.let { lists ->
                for (index in 0 until lists.length()) {
                    val list = lists.getJSONObject(index)
                    if (list.optString("name").equals(name, ignoreCase = true)) {
                        return@replace list.optJSONArray("domains")?.let { domains ->
                            (0 until domains.length()).joinToString(" ") { domains.getString(it) }
                        } ?: ""
                    }
                }
            }
            ""
        }
        val tokens = shellSplit(expanded)
            .dropWhile { !it.startsWith("-") }
            .filter { it !in setOf("--help", "--version", "-h", "-v") }
        val (ip, port) = tokens.extractIpAndPort()
        return tokens.minusIpPort() to (ip to port)
    }

    private fun shellSplit(string: CharSequence): List<String> {
        val tokens = mutableListOf<String>()
        var quoteChar = ' '
        var escaping = false
        var quoting = false
        var lastCloseQuoteIndex = Int.MIN_VALUE
        var current = StringBuilder()
        for (i in string.indices) {
            val c = string[i]
            if (escaping) {
                current.append(c)
                escaping = false
            } else if (c == '\\' && quoting) {
                if (i + 1 < string.length && string[i + 1] == quoteChar) escaping = true else current.append(c)
            } else if (quoting && c == quoteChar) {
                quoting = false
                lastCloseQuoteIndex = i
            } else if (!quoting && (c == '\'' || c == '"')) {
                quoting = true
                quoteChar = c
            } else if (!quoting && Character.isWhitespace(c)) {
                if (current.isNotEmpty() || lastCloseQuoteIndex == i - 1) {
                    tokens.add(current.toString())
                    current = StringBuilder()
                }
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty() || lastCloseQuoteIndex == string.length - 1) tokens.add(current.toString())
        return tokens
    }

    private val NO_VALUE_FLAGS = setOf(
        "--daemon", "--no-domain", "--no-ipv6", "--no-udp", "--help", "--version",
        "--transparent", "--tfo", "--md5sig", "--wait-send", "--drop-sack",
    )

    private fun List<String>.extractIpAndPort(): Pair<String?, String?> {
        var host: String? = null
        var port: String? = null
        var index = 0
        while (index < size) {
            val arg = this[index++]
            if (arg == "--") break
            if (arg.startsWith("--")) {
                val key = arg.substringBefore('=')
                if (NO_VALUE_FLAGS.any { it.startsWith(key) }) continue
                val value = if ('=' in arg) arg.substringAfter('=') else getOrNull(index++)
                when (key) {
                    "--ip" -> host = value
                    "--po", "--por", "--port" -> port = value
                }
            } else if (arg.startsWith("-")) {
                for (position in 1 until arg.length) {
                    val key = arg[position]
                    if (key in "DNXUhvEFSZY") continue
                    val value = arg.substring(position + 1).ifEmpty { getOrNull(index++) }
                    when (key) {
                        'i' -> host = value
                        'p' -> port = value
                    }
                    break
                }
            }
        }
        return host to port
    }

    /** Drops the `-i`/`-p`/`--ip`/`--port` tokens so the endpoint is never duplicated. */
    private fun List<String>.minusIpPort(): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < size) {
            val arg = this[index]
            if (arg == "--") { result.add(arg); index++; continue }
            var consumedValue = false
            if (arg.startsWith("--")) {
                val key = arg.substringBefore('=')
                if (key == "--ip" || key == "--po" || key == "--por" || key == "--port") {
                    if ('=' !in arg) index++
                    consumedValue = true
                }
            } else if (arg.startsWith("-") && !arg.startsWith("--")) {
                for (position in 1 until arg.length) {
                    val key = arg[position]
                    if (key in "DNXUhvEFSZY") continue
                    if (key == 'i' || key == 'p') {
                        if (arg.substring(position + 1).isEmpty()) index++
                        consumedValue = true
                    }
                    break
                }
            }
            if (!consumedValue) result.add(arg)
            index++
        }
        return result
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
        const val DEFAULT_CMD_ARGS = "-o1 -a1 -r-5+se"
        const val MAX_HISTORY = 40
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
