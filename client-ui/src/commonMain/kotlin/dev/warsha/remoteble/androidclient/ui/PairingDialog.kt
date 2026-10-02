package dev.warsha.remoteble.androidclient.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import dev.warsha.remoteble.androidclient.model.PairingOffer

/**
 * Asks before a pairing replaces the agent this app talks to. A link can be opened by anything on the
 * phone, so the user sees where it points, and whether it is encrypted, before it takes effect.
 */
@Composable
fun PairingDialog(offer: PairingOffer, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with this agent?") },
        text = {
            Column {
                Text(offer.address)
                Text(
                    if (offer.encrypted) {
                        "Encrypted. This app will trust only the agent holding ${offer.fingerprint}."
                    } else {
                        "Not encrypted: anyone on the network can read this connection, token included."
                    },
                    fontSize = 13.sp,
                )
                Text(if (offer.hasToken) "Includes the agent's token." else "No token: for an agent that needs none.", fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
