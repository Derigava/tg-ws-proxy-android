package com.amurcanov.tgwsproxy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amurcanov.tgwsproxy.dpi.DpiSettingsStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DpiSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = DpiSettingsStore(context)
    // Fixture matches the AppSettings/Command/DomainList DTOs at v.1.8.2.
    private val fixture = """{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080","ipv6_enable":true},"apps":["org.example.client"],"history":[{"text":"-s1","pinned":true}],"domainLists":[{"id":"test","name":"Example","domains":["example.org"]}]}"""

    @Test fun pinnedFormatRoundTripsAllSectionsWithoutTgKeys() {
        store.importJson(fixture.replace("\"ipv6_enable\":true", "\"ipv6_enable\":true,\"secret_key\":\"not-for-export\""))
        val output = JSONObject(store.exportJson())
        val input = JSONObject(fixture)
        for (section in listOf("settings", "apps", "history", "domainLists")) {
            assertEquals(input.get(section).toString(), output.get(section).toString())
        }
        assertFalse(output.toString().contains("not-for-export"))
    }

    @Test fun importsEndpointAndPerAppRoutingWithoutActivatingVpn() {
        store.importJson(fixture.replace("\"1080\"", "\"23456\"").replace("\"ipv6_enable\":true", "\"ipv6_enable\":false,\"applist_type\":\"whitelist\""))
        val options = store.connectionOptions()
        assertEquals(23456, options.port)
        assertEquals("127.0.0.1", options.host)
        assertFalse(options.routing.ipv6)
        assertEquals(com.amurcanov.tgwsproxy.dpi.VpnAppListMode.WHITELIST, options.routing.appListMode)
        assertEquals(listOf("org.example.client"), options.routing.apps)
    }

    @Test fun dnsAcceptsOnlyNumericAddressesOrSystemDefault() {
        store.importJson(fixture.replace("\"ipv6_enable\":true", "\"ipv6_enable\":true,\"dns_ip\":\"2001:db8::53\""))
        assertEquals("2001:db8::53", store.connectionOptions().routing.dns)
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.importJson(before.replace("2001:db8::53", "dns.example.org"))
        }
        assertEquals(before, store.exportJson())
        store.importJson(before.replace("2001:db8::53", ""))
        assertEquals("", store.connectionOptions().routing.dns)
    }

    @Test fun invalidImportedEndpointDoesNotOverwriteBackup() {
        store.importJson(fixture)
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.importJson(fixture.replace("\"1080\"", "\"99999\""))
        }
        assertEquals(before, store.exportJson())
    }

    @Test fun emptyStoreCanExportAnImportableBackup() {
        context.getSharedPreferences("dpi_document", Context.MODE_PRIVATE).edit().clear().commit()
        val initial = store.exportJson()
        store.importJson(initial)
        assertEquals(initial, store.exportJson())
    }

    @Test fun malformedLaterSectionDoesNotReplacePreviousSnapshot() {
        store.importJson(fixture)
        val before = store.exportJson()
        assertThrows(IllegalArgumentException::class.java) {
            store.importJson(fixture.replace("\"example.org\"", "false"))
        }
        assertEquals(before, DpiSettingsStore(context).exportJson())
    }
}
