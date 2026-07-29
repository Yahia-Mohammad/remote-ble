package dev.warsha.remoteble.agent.ui

import dev.warsha.remoteble.protocol.BleRadioState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host coverage for the two pure decisions the mobile agent UI makes about radio state. Both live in
 * `mobileMain`, which `androidMain` depends on, so they are reachable from this Android host test —
 * there is no `iosTest`/`mobileTest` source set, and this is the only place the iOS Start gate can be
 * asserted without a device.
 *
 * The gate exists because `IosAgentEntry` passed none of `startEnabled`/`permissionWarning`/
 * `onRequestPermissionSettings` while `MainActivity` passed all three (Rig B case 6, follow-up 8).
 * What is worth pinning is not that `UNAUTHORIZED` blocks Start — it is the four states that must
 * **not** block it, since each one would be a plausible-looking overreach.
 */
class AgentAppGatingTest {

    @Test
    fun onlyADeniedPermissionGatesStart() {
        assertTrue(
            bluetoothPermissionDenied(BleRadioState.UNAUTHORIZED),
            "a denied Bluetooth permission is the one radio condition that should disable Start",
        )
    }

    @Test
    fun bluetoothBeingOffDoesNotGateStart() {
        // The subtle one. Android does not gate Start on the adapter either, the user can switch
        // Bluetooth on without leaving the app, and a client that asks a radio-off agent to scan now
        // gets a typed RADIO_OFF. Disabling Start here would strand the user with a dead button and
        // no explanation of which of the two conditions they were in.
        assertFalse(
            bluetoothPermissionDenied(BleRadioState.OFF),
            "Bluetooth being switched off must not disable Start — it is not a permission denial",
        )
    }

    @Test
    fun anUnknownOrUnobservableRadioDoesNotGateStart() {
        // Absence of evidence is not denial. On Apple, UNKNOWN is the normal value for the first
        // moments after launch, so gating on it would disable Start on every cold start until the
        // CoreBluetooth delegate fires.
        assertFalse(
            bluetoothPermissionDenied(BleRadioState.UNKNOWN),
            "UNKNOWN is the pre-initialisation value, not a denial",
        )
        assertFalse(
            bluetoothPermissionDenied(null),
            "a platform that cannot observe the radio must not have Start disabled",
        )
    }

    @Test
    fun aDeviceWithoutBleDoesNotGateStartEither() {
        // Nothing in Settings fixes a missing radio, so routing the user there would be a dead end.
        // The notice carries this case instead.
        assertFalse(
            bluetoothPermissionDenied(BleRadioState.UNSUPPORTED),
            "UNSUPPORTED should be explained by the notice, not turned into a settings dead end",
        )
    }

    @Test
    fun theNoticeAndTheGateDisagreeOnPurpose() {
        // Three states produce a notice; exactly one of them also gates Start. If these two ever
        // collapse into one decision, this is the test that should fail.
        val noticed = BleRadioState.entries.filter { radioNoticeFor(it) != null }
        val gated = BleRadioState.entries.filter { bluetoothPermissionDenied(it) }

        assertEquals(
            listOf(BleRadioState.OFF, BleRadioState.UNAUTHORIZED, BleRadioState.UNSUPPORTED).sorted(),
            noticed.sorted(),
            "the states that warrant an on-screen notice",
        )
        assertEquals(listOf(BleRadioState.UNAUTHORIZED), gated, "the states that warrant gating Start")
        assertNull(radioNoticeFor(BleRadioState.ON), "a working radio should say nothing at all")
    }
}
