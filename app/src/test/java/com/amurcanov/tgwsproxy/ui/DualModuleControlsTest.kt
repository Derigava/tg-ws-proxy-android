package com.amurcanov.tgwsproxy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * RED/GREEN target for Task 4. Proves the two module controls are independent
 * buttons with their own accessible name and state, that toggling one never
 * commands the other, that a Starting module never advertises "running", that
 * narrow width + large font keep both labels and actions reachable, and that a
 * TV D-pad can move focus between them and activate the right one.
 *
 * The controls are exercised with a pure [DualModuleControls] composable and
 * fake callbacks — no Android service or native engine is involved, so the test
 * runs on the JVM under Robolectric and pins the shared control contract that
 * [ConnectionTab] renders in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class DualModuleControlsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var leftClicks = 0
    private var rightClicks = 0

    private fun statusText(state: CursorVisualState, onText: String = "[OK] CONNECTED") = when (state) {
        CursorVisualState.On -> onText
        CursorVisualState.Connecting -> "> CONNECTING"
        CursorVisualState.Off -> "● OFFLINE"
    }

    private fun setControls(
        leftState: CursorVisualState = CursorVisualState.Off,
        rightState: CursorVisualState = CursorVisualState.Off,
    ) {
        leftClicks = 0
        rightClicks = 0
        composeRule.setContent {
            // Robolectric starts in touch mode. Model a TV's non-touch input
            // while exercising the real Compose focus traversal and key activation.
            val keyboardMode = object : InputModeManager {
                override val inputMode = InputMode.Keyboard
                override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
            }
            CompositionLocalProvider(LocalInputModeManager provides keyboardMode) {
                TgWsProxyTheme {
                    DualModuleControls(
                        tg = ModuleControls(
                            moduleLabel = "TG-WS",
                            visualState = leftState,
                            actionDescription = "Start TG-WS proxy",
                            statusText = statusText(leftState),
                            addressText = "127.0.0.1:1443",
                            onToggle = { leftClicks++ },
                        ),
                        dpi = ModuleControls(
                            moduleLabel = "ByeByeDPI",
                            visualState = rightState,
                            actionDescription = "Start ByeByeDPI VPN",
                            statusText = statusText(rightState),
                            addressText = "VPN",
                            onToggle = { rightClicks++ },
                        ),
                        applyInTelegramEnabled = true,
                        onApplyInTelegram = {},
                    )
                }
            }
        }
    }

    private fun assertButton(name: String, stateDescription: String) {
        val node = composeRule.onNodeWithContentDescription(name).assertHasClickAction()
        val config = node.fetchSemanticsNode().config
        assertEquals("button role", Role.Button, config[SemanticsProperties.Role])
        assertEquals("state description", stateDescription, config[SemanticsProperties.StateDescription])
    }

    @Test
    fun strategyEditorReadsStringTypedNumbersFromImport() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = com.amurcanov.tgwsproxy.dpi.DpiSettingsStore(context)
        // v1.8.2 exchange documents store numeric settings as JSON strings.
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080","byedpi_enable_cmd_settings":false,"byedpi_split_position":"7"}}""")
        composeRule.setContent {
            TgWsProxyTheme { com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyEditor() }
        }
        composeRule.onNodeWithText("7").assertExists()
    }

    @Test
    fun strategyEditorPersistsDesyncMethodChoice() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = com.amurcanov.tgwsproxy.dpi.DpiSettingsStore(context)
        store.importJson("""{"app":"io.github.romanvht.byedpi","version":"1.8.2","settings":{"byedpi_proxy_port":"1080"}}""")
        composeRule.setContent {
            TgWsProxyTheme { com.amurcanov.tgwsproxy.ui.dpi.DpiStrategyEditor() }
        }
        composeRule.onNodeWithText("split").performClick()
        composeRule.waitForIdle()
        assertEquals("split", store.currentSettings()["byedpi_desync_method"])
    }

    @Test
    fun changingLogSourceHidesTelegramEntries() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val logs = com.amurcanov.tgwsproxy.LogManager.logs
        val previous = logs.value
        logs.value = listOf(com.amurcanov.tgwsproxy.LogEntry("test", "Telegram-only event", 1, false, 4, true))
        try {
            composeRule.setContent {
                TgWsProxyTheme { LogsTab(com.amurcanov.tgwsproxy.SettingsStore(context)) }
            }
            composeRule.onNodeWithText("Telegram-only event").assertExists()
            composeRule.onNodeWithText("ByeByeDPI").performClick()
            composeRule.onNodeWithText("Telegram-only event").assertDoesNotExist()
        } finally {
            logs.value = previous
        }
    }

    @Test
    fun settingsExposeSeparateModulePages() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.setContent {
            TgWsProxyTheme { SettingsTab(com.amurcanov.tgwsproxy.SettingsStore(context)) }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("ByeByeDPI")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("TG-WS").assertExists()
        composeRule.onNodeWithText("ByeByeDPI").performClick()
        composeRule.onNodeWithText("SOCKS5").assertExists()
        composeRule.onNodeWithText("Import ByeByeDPI backup").assertHasClickAction()
        composeRule.onNodeWithText("Export ByeByeDPI backup").assertHasClickAction()
    }

    @Test
    fun commonHeaderNamesProductAndCreditsAuthor() {
        composeRule.setContent { TgWsProxyTheme { BrandHeader("Test") } }
        composeRule.onNodeWithText("TG-WS Proxy").assertExists()
        composeRule.onNodeWithText("by derigava_").assertExists()
    }

    @Test
    fun twoIndependentButtonsExposeRoleNameAndState() {
        setControls()
        assertButton("Start TG-WS proxy", "● OFFLINE")
        assertButton("Start ByeByeDPI VPN", "● OFFLINE")
    }

    @Test
    fun togglingRightDoesNotInvokeLeft() {
        setControls()
        composeRule.onNodeWithContentDescription("Start ByeByeDPI VPN").performClick()
        composeRule.waitForIdle()
        assertEquals("left (TG-WS) command must not fire", 0, leftClicks)
        assertEquals("right (ByeByeDPI) command must fire once", 1, rightClicks)
    }

    @Test
    fun startingRightShowsConnectingNotRunning() {
        setControls(rightState = CursorVisualState.Connecting)
        val state = composeRule.onNodeWithContentDescription("Start ByeByeDPI VPN")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("> CONNECTING", state)
        assertTrue("Starting must not advertise running", state != "RUNNING" && state != "[OK] CONNECTED")
    }

    @Test
    fun narrowWidthAndLargeFontKeepLabelsAndActionsAccessible() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                TgWsProxyTheme {
                    Box(Modifier.width(320.dp)) {
                        DualModuleControls(
                            tg = ModuleControls(
                                moduleLabel = "TG-WS",
                                visualState = CursorVisualState.Off,
                                actionDescription = "Start TG-WS proxy",
                                statusText = "● OFFLINE",
                                addressText = "127.0.0.1:1443",
                                onToggle = { leftClicks++ },
                            ),
                            dpi = ModuleControls(
                                moduleLabel = "ByeByeDPI",
                                visualState = CursorVisualState.Off,
                                actionDescription = "Start ByeByeDPI VPN",
                                statusText = "● OFFLINE",
                                addressText = "VPN",
                                onToggle = { rightClicks++ },
                            ),
                            applyInTelegramEnabled = true,
                            onApplyInTelegram = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("Start TG-WS proxy").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Start ByeByeDPI VPN").assertHasClickAction()
        composeRule.onNodeWithText("TG-WS").assertExists()
        composeRule.onNodeWithText("ByeByeDPI").assertExists()
    }

    @Test
    fun tvDpadMovesFocusAndActivates() {
        setControls()
        val left = composeRule.onNodeWithContentDescription("Start TG-WS proxy")
        val right = composeRule.onNodeWithContentDescription("Start ByeByeDPI VPN")
        left.performSemanticsAction(SemanticsActions.RequestFocus).assertIsFocused()
        left.performKeyInput { pressKey(Key.DirectionRight) }
        right.assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
        assertEquals("D-pad select must not fire the left command", 0, leftClicks)
        assertEquals("D-pad select must fire the right command", 1, rightClicks)
    }
}
