package ru.anisimov.keenwg.ui.network

import androidx.compose.ui.res.stringResource
import ru.anisimov.keenwg.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import ru.anisimov.keenwg.data.network.DomainRule
import ru.anisimov.keenwg.ui.theme.MonoLabel

@Composable
fun DomainRuleCard(rule: DomainRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    val editable = canEditDomainRule(rule)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.effect == "direct") MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.34f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (editable) Icons.Default.Route else Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rule.label.ifBlank { rule.value }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(domainEffectLabel(rule.effect), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                }
                Text(domainMatcherLabel(rule), style = MonoLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.domain_rule_state, domainSourceLabel(rule.source), stringResource(if (rule.enabled) R.string.state_enabled else R.string.state_disabled)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (editable) {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.rules_site_edit_description, rule.value)) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.rules_site_delete_description, rule.value), tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
