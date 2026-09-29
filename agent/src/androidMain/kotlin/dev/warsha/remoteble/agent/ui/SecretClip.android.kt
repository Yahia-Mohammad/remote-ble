package dev.warsha.remoteble.agent.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.ui.platform.ClipEntry

/**
 * Flagged sensitive, which Android 13+ honours by masking the value in the copy confirmation
 * overlay and keyboard clipboard suggestions; older versions ignore the extra.
 */
internal actual fun secretClipEntry(text: String): ClipEntry =
    ClipEntry(
        ClipData.newPlainText("RemoteBLE token", text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        },
    )
