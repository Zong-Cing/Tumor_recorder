package com.tumorrecorder.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val MedicalTeal = Color(0xFF0F766E)
val MedicalTealDark = Color(0xFF115E59)
val MedicalTealLight = Color(0xFFCCFBF1)
val ClinicalSlate = Color(0xFF334155)
val ClinicalSurface = Color(0xFFF8FAFC)
val UrgentRed = Color(0xFFDC2626)
val UrgentRedContainer = Color(0xFFFEE2E2)
val WarningAmber = Color(0xFFD97706)
val WarningAmberContainer = Color(0xFFFEF3C7)
val SuccessGreen = Color(0xFF15803D)
val SuccessGreenContainer = Color(0xFFDCFCE7)

private val LightColors = lightColorScheme(
    primary = MedicalTeal,
    onPrimary = Color.White,
    primaryContainer = MedicalTealLight,
    onPrimaryContainer = MedicalTealDark,
    secondary = ClinicalSlate,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A),
    background = ClinicalSurface,
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = UrgentRed,
    onError = Color.White,
    errorContainer = UrgentRedContainer,
    onErrorContainer = Color(0xFF7F1D1D)
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
)

@Composable
fun TumorRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content
    )
}
