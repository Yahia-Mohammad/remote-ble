package dev.warsha.remoteble.androidclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.warsha.remoteble.androidclient.model.UiState
import dev.warsha.remoteble.androidclient.ui.components.AccentButton
import dev.warsha.remoteble.androidclient.ui.components.MonoText
import dev.warsha.remoteble.androidclient.ui.components.PulsingDot
import dev.warsha.remoteble.androidclient.ui.components.SectionLabel
import dev.warsha.remoteble.androidclient.model.DiscoveredDevice
import dev.warsha.remoteble.androidclient.ui.components.SurfaceCard

@Composable
fun ScanScreen(
    state: UiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onConnectDevice: (DiscoveredDevice) -> Unit,
    onHideUnnamedChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Header(isScanning = state.isScanning)

        AgentEndpointCard(
            url = state.agentUrl,
            isScanning = state.isScanning,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onUrlChanged = onUrlChanged,
        )

        Text(
            text = state.status,
            color = AppColors.onSurfaceMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(text = "DISCOVERED DEVICES", modifier = Modifier.weight(1f).padding(start = 4.dp))
            Text(text = "Hide unnamed", color = AppColors.onSurfaceMuted, fontSize = 12.sp)
            Checkbox(
                checked = state.hideUnnamed,
                onCheckedChange = onHideUnnamedChanged,
                colors = CheckboxDefaults.colors(
                    checkedColor = AppColors.accent,
                    uncheckedColor = AppColors.onSurfaceMuted,
                ),
            )
        }

        if (state.discovered.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxWidth().weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.discovered, key = { it.identifier }) { adv ->
                    DeviceCard(adv = adv, onConnect = { onConnectDevice(adv) })
                }
            }
        }
    }
}

@Composable
private fun Header(isScanning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "RemoteBLE Central",
                color = AppColors.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = "BLE over WebSocket Agent", color = AppColors.onSurfaceFaint, fontSize = 12.sp)
        }
        if (isScanning) PulsingDot(color = AppColors.heart)
    }
}

@Composable
private fun AgentEndpointCard(
    url: String,
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onUrlChanged: (String) -> Unit,
) {
    SurfaceCard(modifier = Modifier.padding(bottom = 20.dp)) {
        SectionLabel(text = "AGENT ENDPOINT", modifier = Modifier.padding(bottom = 6.dp))

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = endpointFieldColors(),
            shape = RoundedCornerShape(8.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // One toggle, not two look-alike buttons: the action and the colour change with state,
        // so it's always clear whether a scan is running.
        AccentButton(
            text = if (isScanning) "Stop Scan" else "Start Scan",
            onClick = if (isScanning) onStopScan else onStartScan,
            modifier = Modifier.fillMaxWidth(),
            container = if (isScanning) AppColors.danger else AppColors.accent,
        )
    }
}

@Composable
private fun DeviceCard(adv: DiscoveredDevice, onConnect: () -> Unit) {
    SurfaceCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = adv.name ?: "Unnamed Device",
                    color = AppColors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                MonoText(
                    text = adv.identifier,
                    color = AppColors.onSurfaceFaint,
                    fontSize = 11,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Text(
                text = rssiLabel(adv.rssi),
                color = rssiColor(adv.rssi),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            AccentButton(text = "Connect", onClick = onConnect, modifier = Modifier.height(36.dp))
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Text(
            text = "No peripherals found nearby yet. Tap Start Scan to search.",
            color = AppColors.onSurfaceFaint,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 32.dp),
        )
    }
}

private fun rssiLabel(rssi: Int): String = if (rssi == Int.MIN_VALUE) "-- dBm" else "$rssi dBm"

private fun rssiColor(rssi: Int): Color = when {
    rssi == Int.MIN_VALUE -> AppColors.onSurfaceFaint
    rssi > -60 -> AppColors.positive
    rssi > -80 -> AppColors.warning
    else -> AppColors.onSurfaceFaint
}

@Composable
private fun endpointFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedContainerColor = AppColors.background,
    unfocusedContainerColor = AppColors.background,
    focusedBorderColor = AppColors.accent,
    unfocusedBorderColor = AppColors.surfaceAlt,
)
