package ru.anisimov.keenwg.ui.network

import ru.anisimov.keenwg.ui.theme.KeenScale

import ru.anisimov.keenwg.ui.theme.KeenSpacing

import ru.anisimov.keenwg.ui.theme.ContinuousShape
import ru.anisimov.keenwg.ui.theme.KeenRadius

import androidx.compose.ui.res.stringResource
import ru.anisimov.keenwg.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Route
import ru.anisimov.keenwg.ui.components.KeenCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import ru.anisimov.keenwg.data.network.DomainRule
import ru.anisimov.keenwg.ui.theme.MonoLabel

@Composable
fun DomainRuleCard(rule: DomainRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    val editable = canEditDomainRule(rule)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ContinuousShape(KeenRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.effect == "direct") MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(KeenScale.dp15), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (editable) Icons.Outlined.Route else Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = KeenSpacing.group)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.label.ifBlank { rule.value }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Surface(shape = ContinuousShape(KeenRadius.control), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(stringResource(domainEffectResource(rule.effect)), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = KeenScale.dp9, vertical = KeenSpacing.tight))
                    }
                }
                Text(stringResource(domainMatcherResource(rule), domainMatcherValue(rule)), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.domain_rule_state, stringResource(domainSourceResource(rule.source)), stringResource(if (rule.enabled) R.string.state_enabled else R.string.state_disabled)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (editable) {
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.rules_site_edit_description, rule.value)) }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.rules_site_delete_description, rule.value), tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
