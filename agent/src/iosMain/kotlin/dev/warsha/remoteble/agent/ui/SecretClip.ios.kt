package dev.warsha.remoteble.agent.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun secretClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)
