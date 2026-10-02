package dev.warsha.remoteble.agent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.warsha.remoteble.agent.QUIET_ZONE_MODULES
import dev.warsha.remoteble.agent.qrModules
import dev.warsha.remoteble.protocol.AgentPairing
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How a client pairs with the running agent: a QR code of the [pairing] URI, which a phone's camera
 * opens in the client app, and the same URI to copy. Hidden until asked for, like the token field,
 * because the URI carries the token. [unavailable] says why there is no pairing to show.
 */
@Composable
internal fun PairingPanel(pairing: AgentPairing?, unavailable: String?) {
    var shown by remember { mutableStateOf(false) }
    TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide pairing code" else "Show pairing code") }
    if (!shown) return
    if (pairing == null) {
        Text(unavailable ?: "No pairing to show.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val uri = pairing.toUri()
    Text(
        if (pairing.encrypted) {
            "Scan with the client phone's camera, or copy the link into the client. It carries the token " +
                "and this agent's fingerprint, so show it only to someone you would give the token."
        } else {
            "Scan with the client phone's camera, or copy the link into the client. It carries the token, " +
                "and this agent is not encrypted: turn on Encrypt connections for a pinned pairing."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    QrCode(uri, Modifier.fillMaxWidth().widthIn(max = 320.dp).padding(vertical = 8.dp))
    CopyPairingButton(uri)
}

/**
 * [text] as a QR code: black modules on white whatever the theme, since scanners expect dark on light,
 * with the quiet zone the specification requires. Modules are whole pixels, so no seams appear
 * between them.
 */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier.aspectRatio(1f)) {
        val count = modules.size + 2 * QUIET_ZONE_MODULES
        val cell = floor(size.minDimension / count)
        val origin = (size.minDimension - cell * count) / 2
        drawRect(Color.White, Offset.Zero, Size(size.minDimension, size.minDimension))
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                if (dark) {
                    drawRect(
                        Color.Black,
                        Offset(origin + (x + QUIET_ZONE_MODULES) * cell, origin + (y + QUIET_ZONE_MODULES) * cell),
                        Size(cell, cell),
                    )
                }
            }
        }
    }
}

@Composable
private fun CopyPairingButton(uri: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    TextButton(onClick = {
        scope.launch {
            // Marked sensitive like the token, since it carries it.
            clipboard.setClipEntry(secretClipEntry(uri))
            copied = true
        }
    }) {
        Text(if (copied) "Copied" else "Copy pairing link")
    }
}
