package ru.anisimov.keenwg.ui.network

import ru.anisimov.keenwg.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.anisimov.keenwg.data.network.DomainRule
import ru.anisimov.keenwg.data.network.DomainRuleDraft

@Composable
fun DomainRuleDialog(
    editor: DomainEditorState,
    busy: Boolean,
    onDraft: (DomainRuleDraft) -> Unit,
    onReview: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = editor.draft
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (editor.original == null) stringResource(R.string.ui_domainrulesheet_86885217ae) else stringResource(R.string.ui_domainrulesheet_01ec28b25e)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editor.reviewing) {
                    Text(stringResource(R.string.ui_domainrulesheet_badb2f85d6), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.domain_rule_summary, stringResource(domainRuleKindResource(draft.kind)), displayValue(draft)))
                        Text(stringResource(R.string.domain_rule_route, stringResource(domainEffectResource(draft.effect))))
                    Text(stringResource(R.string.ui_domainrulesheet_73ff0c584c), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.ui_domainrulesheet_d4a6795c19), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "domain" to R.string.rules_site_domain,
                            "suffix" to R.string.rules_site_zone,
                            "geosite" to R.string.rules_site_category,
                        ).forEach { (kind, labelResource) ->
                            FilterChip(selected = draft.kind == kind, onClick = {
                                val value = when (kind) { "geosite" -> "category-gov-ru"; "suffix" -> "ru"; else -> "" }
                                onDraft(draft.copy(kind = kind, value = value))
                            }, label = { Text(stringResource(labelResource)) })
                        }
                    }
                    when (draft.kind) {
                        "geosite" -> Text(stringResource(R.string.ui_domainrulesheet_ae3bd2f37c), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        "suffix" -> {
                            Text(stringResource(R.string.ui_domainrulesheet_08069c7b92), style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("ru", "su", "xn--p1ai", "moscow").forEach { zone ->
                                    FilterChip(selected = draft.value == zone, onClick = { onDraft(draft.copy(value = zone)) }, label = { Text(if (zone == "xn--p1ai") stringResource(R.string.ui_domainrulesheet_68365bcf3e) else ".$zone") })
                                }
                            }
                        }
                        else -> OutlinedTextField(draft.value, { onDraft(draft.copy(value = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_domainrulesheet_6d0b5e48e0)) }, placeholder = { Text("example.com") }, singleLine = true)
                    }
                    OutlinedTextField(draft.label, { onDraft(draft.copy(label = it)) }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.ui_domainrulesheet_cc8ac85ff0)) }, singleLine = true)
                    Text(stringResource(R.string.ui_domainrulesheet_9c2c7eeb43), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.effect == "direct", onClick = { onDraft(draft.copy(effect = "direct")) }, label = { Text(stringResource(R.string.ui_domainrulesheet_a672e5372a)) })
                        FilterChip(selected = draft.effect == "vpn", onClick = { onDraft(draft.copy(effect = "vpn")) }, label = { Text(stringResource(R.string.ui_domainrulesheet_569264348e)) })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.ui_domainrulesheet_290a4904f7))
                        Switch(checked = draft.enabled, onCheckedChange = { onDraft(draft.copy(enabled = it)) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = if (editor.reviewing) onConfirm else onReview, enabled = !busy && draft.value.isNotBlank()) {
                Text(if (editor.reviewing) stringResource(R.string.ui_domainrulesheet_e15f960d93) else stringResource(R.string.ui_domainrulesheet_e4424a6df6))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_domainrulesheet_8fbe9b75cb)) } },
    )
}

@Composable
fun DomainDeleteDialog(rule: DomainRule, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ui_domainrulesheet_3f123137fa)) },
            text = { Text(stringResource(R.string.domain_rule_delete_detail, rule.label.ifBlank { rule.value }, stringResource(domainEffectResource(rule.effect)))) },
        confirmButton = { Button(onClick = onConfirm, enabled = !busy) { Text(stringResource(R.string.ui_domainrulesheet_be99b13612)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ui_domainrulesheet_8fbe9b75cb)) } },
    )
}

private fun displayValue(draft: DomainRuleDraft) = if (draft.kind == "suffix") ".${draft.value}" else draft.value
