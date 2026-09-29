package dev.warsha.remoteble.agent.ui

import androidx.compose.ui.platform.ClipEntry

/**
 * A plain-text clipboard entry for a credential. Building a [ClipEntry] is platform-specific, and
 * each platform also has its own way to keep a secret out of clipboard previews and sync.
 */
internal expect fun secretClipEntry(text: String): ClipEntry
