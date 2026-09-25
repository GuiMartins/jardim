package com.jardim.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Verde de folha no primário e âmbar de girassol no terciário. Sem cor dinâmica
// do Android 12: o app fica com a mesma cara em qualquer papel de parede.

private val Claro = lightColorScheme(
    primary = Color(0xFF2E6B30),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB1F1A9),
    onPrimaryContainer = Color(0xFF002204),
    secondary = Color(0xFF52634F),
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F10),
    tertiary = Color(0xFF8A5100),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFF7FBF1),
    surface = Color(0xFFF7FBF1),
    surfaceContainerLow = Color(0xFFF1F5EB),
    surfaceContainer = Color(0xFFEBEFE6),
)

private val Escuro = darkColorScheme(
    primary = Color(0xFF96D58F),
    onPrimary = Color(0xFF00390A),
    primaryContainer = Color(0xFF145219),
    onPrimaryContainer = Color(0xFFB1F1A9),
    secondary = Color(0xFFB9CCB4),
    secondaryContainer = Color(0xFF3B4B39),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFFFFB870),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF10140F),
    surface = Color(0xFF10140F),
    surfaceContainerLow = Color(0xFF181D17),
    surfaceContainer = Color(0xFF1C211B),
)

@Composable
fun JardimTema(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Escuro else Claro, content = content)
}
