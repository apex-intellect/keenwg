package ru.anisimov.keenwg.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ru.anisimov.keenwg.R

fun bytesLabel(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
}

@Composable
fun durationLabel(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val days = (safe / 86_400).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val hours = ((safe % 86_400) / 3_600).toInt()
    val minutes = ((safe % 3_600) / 60).toInt()
    val parts = mutableListOf<String>()
    if (days > 0) parts += pluralStringResource(R.plurals.duration_days, days, days)
    if (hours > 0) parts += pluralStringResource(R.plurals.duration_hours, hours, hours)
    if (minutes > 0) parts += pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    return if (parts.isEmpty()) stringResource(R.string.duration_less_than_minute) else parts.take(2).joinToString(" ")
}

@Composable
fun historyPeriodLabel(seconds: Long): String = when (seconds) {
    86_400L -> stringResource(R.string.history_range_day)
    604_800L -> stringResource(R.string.history_range_week)
    2_592_000L -> stringResource(R.string.history_range_month)
    else -> durationLabel(seconds)
}
