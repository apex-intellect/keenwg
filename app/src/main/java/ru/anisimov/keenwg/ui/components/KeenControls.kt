package ru.anisimov.keenwg.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import ru.anisimov.keenwg.ui.theme.*

/** Thin adapters keep native semantics, loading/disabled states and feature callbacks. */
@Composable
fun KeenCard(
    modifier: Modifier = Modifier,
    shape: Shape = ContinuousShape(KeenRadius.card),
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = BorderStroke(KeenSize.hairline, MaterialTheme.colorScheme.outlineVariant),
    content: @Composable ColumnScope.() -> Unit,
) = Card(modifier, shape, colors, elevation, border, content)

@Composable
fun KeenCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ContinuousShape(KeenRadius.card),
    colors: CardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = BorderStroke(KeenSize.hairline, MaterialTheme.colorScheme.outlineVariant),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) = Card(onClick, modifier, enabled, shape, colors, elevation, border, interactionSource, content)

@Composable
fun KeenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ContinuousShape(KeenRadius.control),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = KeenSpacing.item, vertical = KeenSpacing.group),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier.heightIn(min = KeenSize.touch), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@Composable
fun KeenOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ContinuousShape(KeenRadius.control),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = BorderStroke(KeenSize.hairline, MaterialTheme.colorScheme.outline),
    contentPadding: PaddingValues = PaddingValues(horizontal = KeenSpacing.item, vertical = KeenSpacing.group),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, modifier.heightIn(min = KeenSize.touch), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeenTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.background,
    ),
) = TopAppBar(
    title = title,
    modifier = modifier.padding(horizontal = KeenSpacing.tight),
    navigationIcon = navigationIcon,
    actions = actions,
    colors = colors,
)
