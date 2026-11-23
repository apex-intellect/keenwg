package ru.anisimov.keenwg.ui.connections

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeError

class XkeenRuntimeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownAndUnsupportedNeverLookLikeAnOffSwitch() {
        compose.setContent {
            MaterialTheme {
                XkeenRuntimeCard(
                    XkeenRuntimeUiState(loading = false, error = XkeenRuntimeError.UNSUPPORTED), {}, {},
                )
            }
        }
        compose.onNodeWithTag("xkeen_runtime_card").assertExists()
        compose.onNodeWithTag("xkeen_runtime_switch").assertDoesNotExist()
        compose.onNodeWithTag("xkeen_runtime_retry").assertExists()
    }

    @Test fun changingKeepsVerifiedStateAndDisablesSwitch() {
        compose.setContent {
            MaterialTheme {
                XkeenRuntimeCard(XkeenRuntimeUiState(running = true, canControl = true, loading = false, changing = true), {}, {})
            }
        }
        compose.onNodeWithTag("xkeen_runtime_switch").assertIsOn().assertIsNotEnabled()
    }

    @Test fun viewerCanReadButCannotToggle() {
        compose.setContent {
            MaterialTheme {
                XkeenRuntimeCard(XkeenRuntimeUiState(running = false, canControl = false, loading = false), {}, {})
            }
        }
        compose.onNodeWithTag("xkeen_runtime_switch").assertIsOff().assertIsNotEnabled()
    }

    @Test fun stopRequiresSeparateConfirmAndCancelDoesNotStop() {
        val state = mutableStateOf(XkeenRuntimeUiState(running = true, canControl = true, loading = false))
        var stopCount = 0
        compose.setContent {
            MaterialTheme {
                XkeenRuntimeCard(state.value, { state.value = state.value.copy(confirmStop = !it) }, {})
                if (state.value.confirmStop) XkeenStopConfirmation(
                    onConfirm = { stopCount++; state.value = state.value.copy(confirmStop = false, changing = true) },
                    onCancel = { state.value = state.value.copy(confirmStop = false) },
                )
            }
        }
        compose.onNodeWithTag("xkeen_runtime_switch").performClick()
        compose.onNodeWithTag("xkeen_runtime_confirm_stop").assertExists()
        compose.runOnIdle { assertEquals(0, stopCount) }
        compose.onNodeWithTag("xkeen_runtime_cancel_stop").performClick()
        compose.runOnIdle { assertEquals(0, stopCount) }
        compose.onNodeWithTag("xkeen_runtime_switch").assertIsOn().performClick()
        compose.onNodeWithTag("xkeen_runtime_confirm_stop").performClick()
        compose.runOnIdle { assertEquals(1, stopCount) }
        compose.onNodeWithTag("xkeen_runtime_switch").assertIsOn().assertIsNotEnabled()
    }
}
