package ru.anisimov.keenwg.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.anisimov.keenwg.R

private val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_variable_wght, FontWeight.Normal),
    Font(R.font.jetbrains_mono_variable_wght, FontWeight.Medium),
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

internal val KeenWgTypography = Typography(
    headlineLarge = type(32, 38, FontWeight.SemiBold),
    headlineMedium = type(28, 34, FontWeight.SemiBold),
    headlineSmall = type(26, 32, FontWeight.SemiBold),
    titleLarge = type(26, 32, FontWeight.SemiBold).copy(letterSpacing = (-0.8).sp),
    titleMedium = type(17, 24, FontWeight.SemiBold),
    titleSmall = type(15, 22, FontWeight.Medium),
    bodyLarge = type(16, 24),
    bodyMedium = type(15, 23),
    bodySmall = type(13, 19),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 17, FontWeight.Medium),
    labelSmall = type(11, 16, FontWeight.Medium),
)

val MonoLabel = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)
