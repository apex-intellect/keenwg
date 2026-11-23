package ru.anisimov.keenwg.ui.connections

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSize

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import ru.anisimov.keenwg.ui.theme.ContinuousShape
import ru.anisimov.keenwg.ui.theme.KeenRadius
import androidx.compose.material3.AlertDialog
import ru.anisimov.keenwg.ui.components.KeenCard as Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.anisimov.keenwg.R
import ru.anisimov.keenwg.data.xkeen.XkeenRuntimeError

@Composable
fun XkeenRuntimePanel(
    modifier: Modifier = Modifier,
    viewModel: XkeenRuntimeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    XkeenRuntimeCard(state, viewModel::requestEnabled, viewModel::refresh, modifier)
    if (state.confirmStop) XkeenStopConfirmation(viewModel::confirmStop, viewModel::cancelStop)
}

@Composable
internal fun XkeenRuntimeCard(
    state: XkeenRuntimeUiState,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.setupRequired) return
    val title = stringResource(R.string.xkeen_runtime_title)
    Card(modifier.fillMaxWidth().testTag("xkeen_runtime_card"), shape = ContinuousShape(KeenRadius.card)) {
        Column(Modifier.padding(horizontal = KeenSpacing.item, vertical = KeenScale.dp10), verticalArrangement = Arrangement.spacedBy(KeenSpacing.tight)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KeenSpacing.group)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(when {
                            state.changing -> R.string.xkeen_runtime_applying
                            state.loading -> R.string.xkeen_runtime_checking
                            state.running == true -> R.string.xkeen_runtime_running
                            state.running == false -> R.string.xkeen_runtime_stopped
                            else -> R.string.xkeen_runtime_unknown
                        }),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (state.busy) CircularProgressIndicator(Modifier.size(KeenSpacing.section), strokeWidth = KeenScale.dp2)
                // Unknown status is not presented as a false/off switch.
                if (state.running != null) Switch(
                    checked = state.running,
                    onCheckedChange = onEnabledChange,
                    enabled = state.controlEnabled,
                    modifier = Modifier.heightIn(min = KeenSize.touch).testTag("xkeen_runtime_switch")
                        .semantics { contentDescription = title },
                )
            }
            Text(
                stringResource(R.string.xkeen_runtime_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.error != null) Text(
                stringResource(runtimeErrorResource(state.error)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) else if (!state.busy && state.running != null && !state.canControl) Text(
                stringResource(R.string.xkeen_runtime_owner_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.busy && (state.error != null || state.running == null)) TextButton(
                onClick = onRefresh,
                modifier = Modifier.heightIn(min = KeenSize.touch).testTag("xkeen_runtime_retry"),
            ) { Text(stringResource(R.string.xkeen_runtime_refresh)) }
        }
    }
}

@Composable
internal fun XkeenStopConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.xkeen_runtime_stop_title)) },
        text = { Text(stringResource(R.string.xkeen_runtime_stop_warning)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = KeenSize.touch).testTag("xkeen_runtime_confirm_stop")) {
                Text(stringResource(R.string.xkeen_runtime_stop))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = KeenSize.touch).testTag("xkeen_runtime_cancel_stop")) {
                Text(stringResource(R.string.xkeen_runtime_cancel))
            }
        },
    )
}

private fun runtimeErrorResource(error: XkeenRuntimeError): Int = when (error) {
    XkeenRuntimeError.UNSUPPORTED -> R.string.xkeen_runtime_unsupported
    XkeenRuntimeError.UNAUTHORIZED -> R.string.xkeen_runtime_unauthorized
    XkeenRuntimeError.FORBIDDEN -> R.string.xkeen_runtime_owner_only
    XkeenRuntimeError.BUSY -> R.string.xkeen_runtime_busy
    XkeenRuntimeError.INVALID_RESPONSE -> R.string.xkeen_runtime_invalid_response
    XkeenRuntimeError.VERIFICATION_FAILED -> R.string.xkeen_runtime_verification_failed
    XkeenRuntimeError.UNAVAILABLE -> R.string.xkeen_runtime_unavailable
}
