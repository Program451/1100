package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = SenimCobaltLight,
    onPrimary = OnSenimCobaltContainer,
    primaryContainer = SenimCobaltDeep,
    onPrimaryContainer = SenimCobaltContainer,
    secondary = SenimIndigoContainer,
    onSecondary = OnSenimIndigoContainer,
    secondaryContainer = SenimIndigo,
    onSecondaryContainer = SenimIndigoContainer,
    background = DarkBackground,
    onBackground = TextOnDark,
    surface = DarkSurface,
    onSurface = TextOnDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SenimCobalt,
    onPrimary = Color.White,
    primaryContainer = SenimCobaltContainer,
    onPrimaryContainer = OnSenimCobaltContainer,
    secondary = SenimIndigo,
    onSecondary = Color.White,
    secondaryContainer = SenimIndigoContainer,
    onSecondaryContainer = OnSenimIndigoContainer,
    background = SurfaceIvory,
    onBackground = SlateInk,
    surface = SurfaceCard,
    onSurface = SlateInk,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = SlateMuted,
    error = AlertRed,
    onError = Color.White,
    errorContainer = AlertRedContainer,
    onErrorContainer = Color(0xFF7F1D1D),
  )

@Composable
fun SenimTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Отключаем dynamicColor по умолчанию для предсказуемого фирменного вида
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = SenimTypography, content = content)
}
