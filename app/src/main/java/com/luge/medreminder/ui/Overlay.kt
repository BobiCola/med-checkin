package com.luge.medreminder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*

/**
 * Full-screen layers rendered inside the activity's own (edge-to-edge) window instead of a Dialog window,
 * so WindowInsets (navigation bar / IME / status bar) are reported reliably on every OEM.
 */
object Overlay {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

@Composable
fun FullScreenLayer(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = rememberUpdatedState(content)
    val dismiss = rememberUpdatedState(onDismiss)
    val holder: @Composable () -> Unit = remember { { BackHandler { dismiss.value() }; c.value() } }
    DisposableEffect(Unit) {
        Overlay.content = holder
        onDispose { if (Overlay.content === holder) Overlay.content = null }
    }
}
