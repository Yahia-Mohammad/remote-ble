package dev.warsha.ble.remoteble.androidclient.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.warsha.ble.remoteble.androidclient.ble.BleUuids
import dev.warsha.ble.remoteble.androidclient.ble.HexCodec
import dev.warsha.ble.remoteble.androidclient.model.DeviceState
import dev.warsha.ble.remoteble.androidclient.ui.components.MonoText
import dev.warsha.ble.remoteble.androidclient.ui.components.NeutralButton
import dev.warsha.ble.remoteble.androidclient.ui.components.PropertyChip
import dev.warsha.ble.remoteble.androidclient.ui.components.SectionLabel
import dev.warsha.ble.remoteble.androidclient.ui.components.SurfaceCard
import dev.warsha.ble.remoteble.androidclient.ui.components.ValueBlock
import dev.warsha.ble.remoteble.client.TransportState
import com.juul.kable.DiscoveredCharacteristic
import com.juul.kable.DiscoveredService
import com.juul.kable.State
import com.juul.kable.indicate
import com.juul.kable.notify
import com.juul.kable.read
import com.juul.kable.write
import com.juul.kable.writeWithoutResponse

@Composable
fun DeviceScreen(
    device: DeviceState,
    agentState: TransportState,
    onDisconnect: () -> Unit,
    onReadChar: (DiscoveredCharacteristic) -> Unit,
    onWriteChar: (DiscoveredCharacteristic, ByteArray) -> Unit,
    onToggleSub: (DiscoveredCharacteristic) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background),
    ) {
        Text(
            text = "← Back to Peripherals",
            color = AppColors.onSurfaceMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(onClick = onDisconnect)
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 16.dp),
        )

        // The BLE-level connectionState above only updates from events the agent sends — if the
        // agent process/socket itself is gone, nothing tells it, so it can be stuck showing
        // "Connected" indefinitely. This is a separate, always-visible signal for that case.
        if (agentState != TransportState.CONNECTED) {
            AgentStateBanner(agentState = agentState, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DeviceHeader(device = device, onDisconnect = onDisconnect) }

            if (device.isConnected && device.services != null) {
                connectedContent(device, onReadChar, onWriteChar, onToggleSub)
            } else {
                item { ConnectionPlaceholder(device) }
            }
        }
    }
}

/** The well-known profile cards plus the generic GATT explorer, in list order. */
private fun androidx.compose.foundation.lazy.LazyListScope.connectedContent(
    device: DeviceState,
    onReadChar: (DiscoveredCharacteristic) -> Unit,
    onWriteChar: (DiscoveredCharacteristic, ByteArray) -> Unit,
    onToggleSub: (DiscoveredCharacteristic) -> Unit,
) {
    device.characteristic(BleUuids.HEART_RATE_MEASUREMENT)?.let { char ->
        item { HeartRateCard(device = device, char = char, onToggleSub = onToggleSub) }
    }
    device.characteristic(BleUuids.BATTERY_LEVEL)?.let { char ->
        item { BatteryCard(device = device, char = char, onToggleSub = onToggleSub) }
    }
    if (device.manufacturer != null || device.model != null ||
        device.characteristic(BleUuids.MANUFACTURER_NAME) != null
    ) {
        item { DeviceInfoCard(device) }
    }

    item {
        SectionLabel(
            text = "DISCOVERED GATT SERVICES",
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
        )
    }
    items(device.services.orEmpty(), key = { it.serviceUuid.toString() }) { service ->
        ServiceCard(service, device, onReadChar, onWriteChar, onToggleSub)
    }
}

@Composable
private fun AgentStateBanner(agentState: TransportState, modifier: Modifier = Modifier) {
    val (text, color) = when (agentState) {
        TransportState.CONNECTING -> "Reconnecting to agent…" to AppColors.warning
        TransportState.DISCONNECTED -> "Agent disconnected — device status below may be stale" to AppColors.danger
        TransportState.CONNECTED -> return // caller doesn't show the banner in this case
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text = text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DeviceHeader(device: DeviceState, onDisconnect: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(AppColors.accent, AppColors.accentSecondary)))
            .padding(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Unnamed Device",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                MonoText(
                    text = device.handle.value,
                    color = Color(0xFFE0E7FF),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Connection: ${connectionLabel(device.connectionState)}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.danger),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Disconnect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HeartRateCard(
    device: DeviceState,
    char: DiscoveredCharacteristic,
    onToggleSub: (DiscoveredCharacteristic) -> Unit,
) {
    SurfaceCard {
        SectionLabel(text = "HEART RATE MONITOR", color = AppColors.heart, modifier = Modifier.padding(bottom = 12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val bpm = device.heartRateBpm
            val scale = if (device.isHeartRateSubscribed && bpm != null) beatScale() else 1f
            Text(
                text = "❤",
                color = AppColors.heart,
                fontSize = 36.sp,
                modifier = Modifier.scale(scale).padding(end = 12.dp),
            )
            Text(
                text = bpm?.toString() ?: "--",
                color = AppColors.onSurface,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = " bpm", color = AppColors.onSurfaceMuted, fontSize = 16.sp, modifier = Modifier.align(Alignment.Bottom))
        }

        Text(
            text = "Sensor Location: ${device.heartRateLocation ?: "Reading…"}",
            color = AppColors.onSurfaceMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        NeutralButton(
            text = if (device.isHeartRateSubscribed) "Unsubscribe" else "Subscribe",
            onClick = { onToggleSub(char) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BatteryCard(
    device: DeviceState,
    char: DiscoveredCharacteristic,
    onToggleSub: (DiscoveredCharacteristic) -> Unit,
) {
    SurfaceCard {
        SectionLabel(text = "BATTERY STATUS", color = AppColors.positive, modifier = Modifier.padding(bottom = 12.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val level = device.batteryLevel
            Text(
                text = level?.let { "$it%" } ?: "--%",
                color = AppColors.onSurface,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            LinearProgressIndicator(
                progress = { (level ?: 0) / 100f },
                modifier = Modifier.width(120.dp).height(14.dp).clip(RoundedCornerShape(7.dp)),
                color = AppColors.positive,
                trackColor = AppColors.background,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        NeutralButton(
            text = if (device.isBatterySubscribed) "Unsubscribe" else "Subscribe",
            onClick = { onToggleSub(char) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DeviceInfoCard(device: DeviceState) {
    SurfaceCard {
        SectionLabel(text = "DEVICE INFORMATION", modifier = Modifier.padding(bottom = 8.dp))
        Text(
            text = "Manufacturer: ${device.manufacturer ?: "Reading…"}\nModel: ${device.model ?: "Reading…"}",
            color = AppColors.onSurface,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun ServiceCard(
    service: DiscoveredService,
    device: DeviceState,
    onReadChar: (DiscoveredCharacteristic) -> Unit,
    onWriteChar: (DiscoveredCharacteristic, ByteArray) -> Unit,
    onToggleSub: (DiscoveredCharacteristic) -> Unit,
) {
    SurfaceCard(bordered = true) {
        SectionLabel(text = "SERVICE", color = AppColors.accent)
        MonoText(
            text = service.serviceUuid.toString(),
            fontSize = 13,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
        )
        for (char in service.characteristics) {
            CharacteristicRow(char, device, onReadChar, onWriteChar, onToggleSub)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacteristicRow(
    char: DiscoveredCharacteristic,
    device: DeviceState,
    onReadChar: (DiscoveredCharacteristic) -> Unit,
    onWriteChar: (DiscoveredCharacteristic, ByteArray) -> Unit,
    onToggleSub: (DiscoveredCharacteristic) -> Unit,
) {
    val props = char.properties
    val canWrite = props.write || props.writeWithoutResponse
    val canNotify = props.notify || props.indicate

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        SectionLabel(text = "CHARACTERISTIC", color = AppColors.onSurfaceMuted)
        MonoText(text = char.characteristicUuid.toString(), modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (props.read) PropertyChip(label = "READ", color = AppColors.readChip)
            if (canWrite) PropertyChip(label = "WRITE", color = AppColors.writeChip)
            if (canNotify) PropertyChip(label = "NOTIFY", color = AppColors.notifyChip)
        }

        if (props.read) {
            ActionChipButton(text = "Read Value", onClick = { onReadChar(char) })
        }
        if (canWrite) {
            WriteRow(onWrite = { bytes -> onWriteChar(char, bytes) })
        }
        if (canNotify) {
            ActionChipButton(
                text = if (device.isSubscribed(char)) "Unsubscribe" else "Subscribe",
                onClick = { onToggleSub(char) },
            )
        }

        ValueBlock(text = HexCodec.describe(device.valueOf(char)))
    }
}

@Composable
private fun ActionChipButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.padding(bottom = 6.dp).height(32.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.surfaceAlt),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, color = AppColors.onSurface, fontSize = 12.sp)
    }
}

@Composable
private fun WriteRow(onWrite: (ByteArray) -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("Enter text or 0xHex", fontSize = 13.sp, color = AppColors.onSurfaceFaint) },
            modifier = Modifier.weight(1f).height(48.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = AppColors.background,
                unfocusedContainerColor = AppColors.background,
                focusedBorderColor = AppColors.accent,
                unfocusedBorderColor = AppColors.surfaceAlt,
            ),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                if (input.isNotEmpty()) {
                    onWrite(HexCodec.parseInput(input))
                    input = ""
                }
            },
            modifier = Modifier.height(42.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("Write", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ConnectionPlaceholder(device: DeviceState) {
    val message = when {
        device.connectionState is State.Disconnected && device.everConnected ->
            "Disconnected. Tap ← Back to return to peripherals."
        device.connectionState is State.Disconnecting -> "Disconnecting…"
        else -> "Discovering services…"
    }
    Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
        Text(text = message, color = AppColors.onSurfaceMuted, fontSize = 14.sp)
    }
}

@Composable
private fun beatScale(): Float {
    val transition = rememberInfiniteTransition(label = "heartPulse")
    val scale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "heartPulseScale",
    )
    return scale
}

private fun connectionLabel(state: State): String = when (state) {
    is State.Connecting -> "Connecting…"
    is State.Connected -> "Connected"
    is State.Disconnecting -> "Disconnecting…"
    is State.Disconnected -> "Disconnected"
}
