package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.DeviceHandle
import com.juul.kable.Advertisement
import com.juul.kable.ExperimentalApi
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder

/** Selects whether a [Peripheral] talks to a local radio or a remote agent. */
public enum class BleMode { LOCAL, REMOTE }

/**
 * Builds remote [Peripheral]s from agent handles/advertisements. Mints the same
 * Kable [Peripheral] type local code already uses — switching to remote is a
 * construction choice, not an app-code change.
 */
public class RemotePeripheralFactory(
    private val session: AgentSession,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider,
) {

    public fun create(advertisement: RemoteAdvertisement): Peripheral =
        create(advertisement.handle, advertisement.name)

    public fun create(handle: DeviceHandle, name: String? = null): Peripheral =
        RemotePeripheral(handle, session, name, dispatchers = dispatchers)
}

/**
 * The one place the local-vs-remote decision lives. The returned [Peripheral] is
 * the same type either way, so the app logic consuming it is identical.
 *
 * - [BleMode.LOCAL] uses Kable's platform [Peripheral] builder (local radio), with
 *   [applyPlatformWorkarounds] applied first — the caller's [kableLogging] block runs last and can
 *   therefore still override anything it sets.
 * - [BleMode.REMOTE] requires a [RemoteAdvertisement] (from a [RemoteScanner]) and
 *   an [AgentSession].
 */
@OptIn(ExperimentalApi::class)
public fun peripheralFor(
    mode: BleMode,
    advertisement: Advertisement,
    session: AgentSession? = null,
    kableLogging: (PeripheralBuilder.() -> Unit)? = null,
): Peripheral = when (mode) {
    BleMode.LOCAL -> Peripheral(advertisement) {
        applyPlatformWorkarounds()
        kableLogging?.invoke(this)
    }
    BleMode.REMOTE -> {
        require(advertisement is RemoteAdvertisement) { "REMOTE mode requires a RemoteAdvertisement" }
        requireNotNull(session) { "REMOTE mode requires an AgentSession" }
        RemotePeripheral(advertisement.handle, session, advertisement.name)
    }
}
