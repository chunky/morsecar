package org.icculus.chunky.morsecar.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Screen { Player, Library }

/** Phone UI root: the player, and the library for choosing a book. */
@Composable
fun MorseCarApp(vm: MainViewModel = viewModel()) {
    MorseCarTheme {
        Surface {
            val context = LocalContext.current
            LaunchedEffect(vm) {
                vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
            var screen by rememberSaveable { mutableStateOf(Screen.Player) }
            when (screen) {
                Screen.Player -> PlayerScreen(vm, onOpenLibrary = {
                    vm.refreshLibrary()
                    screen = Screen.Library
                })
                Screen.Library -> LibraryScreen(vm, onClose = { screen = Screen.Player })
            }
        }
    }
}

@Composable
private fun MorseCarTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
