package com.amurcanov.tgwsproxy.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductHeaderTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun infoFooterShowsVersionLicensesAndByline() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent { TgWsProxyTheme { InfoTab(com.amurcanov.tgwsproxy.SettingsStore(context)) } }
        composeRule.onNodeWithText("Licenses:", substring = true).assertExists()
        composeRule.onNodeWithText("Version ${com.amurcanov.tgwsproxy.BuildConfig.VERSION_NAME}").assertExists()
        composeRule.onAllNodesWithText("by derigava_█").assertCountEquals(2)
    }

    @Test fun headerShowsProductBylineAndSectionWithoutTechPrefix() {
        composeRule.setContent { TgWsProxyTheme { BrandHeader(section = "Info") } }
        composeRule.onNodeWithText("TG-WS Proxy").assertExists()
        composeRule.onNodeWithText("by derigava_█").assertExists()
        composeRule.onNodeWithText("INFO").assertExists()
        composeRule.onNodeWithText("TECH / INFO").assertDoesNotExist()
    }
}
