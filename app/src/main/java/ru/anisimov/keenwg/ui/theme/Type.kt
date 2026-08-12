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

// Material 3 defaults use the platform Roboto family and its native type scale.
internal val KeenWgTypography = Typography()

val MonoLabel = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)
