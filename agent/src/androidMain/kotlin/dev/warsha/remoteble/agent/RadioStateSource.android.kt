package dev.warsha.remoteble.agent

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import dev.warsha.remoteble.protocol.BleRadioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Android's adapter state, seeded from [BluetoothAdapter] and then kept current by
 * `ACTION_STATE_CHANGED`.
 *
 * Note this is a *different question* from the one `bluetoothPermissionsGranted` answers in the
 * agent UI, which is about runtime permissions. Both can independently deny a scan, and conflating
 * them is what made the Android side of this gap invisible for a whole rig (Rig B case 6): the app
 * had permission gating and no adapter gating, so an adapter switched off showed nothing at all.
 */
internal actual fun agentRadioStateSource(scope: CoroutineScope): StateFlow<BleRadioState>? {
    val context = androidAgentContext ?: return null
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
    // No adapter means no radio on this device — a stable fact, so a constant flow is honest here
    // (unlike the `null` return above, which means "cannot observe" rather than "known to be off").
    val adapter = manager.adapter ?: return MutableStateFlow(BleRadioState.UNSUPPORTED)

    fun read(): BleRadioState = when {
        !context.hasScanPermission() -> BleRadioState.UNAUTHORIZED
        adapter.isEnabled -> BleRadioState.ON
        else -> BleRadioState.OFF
    }

    return callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) trySend(read())
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        // RECEIVER_NOT_EXPORTED is mandatory from API 34 for a non-system broadcast, and this one is
        // a system broadcast we only ever receive — never one another app should be able to send us.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        trySend(read())
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }.stateIn(scope, SharingStarted.Eagerly, read())
}

private fun Context.hasScanPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        true // pre-12 the scan permissions are install-time, so there is nothing to have been denied.
    }
