package com.sam.openclone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 with the device's dynamic colour scheme.
 *
 * minSdk is 33, so dynamic colour is always available and there is no static
 * palette to fall back to — which also means no hand-written colour tables to
 * carry in the APK.
 */
@Composable
internal fun OpenCloneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (darkTheme) dynamicDarkColorScheme(context)
        else dynamicLightColorScheme(context),
        content = content,
    )
}
