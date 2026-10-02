package dev.warsha.remoteble.androidclient

import dev.warsha.remoteble.androidclient.model.UiState
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Taking in a pairing link, pasted or opened from a QR code: nothing changes until confirmed. */
class PairingFlowTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val controller = RemoteBleController(scope)
    private val pin = "sha256:" + "ab".repeat(32)
    private val link = "remoteble://192.168.1.20:8080?token=t0k&fp=$pin"
    private val state: UiState get() = controller.uiState.value

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun aLinkIsOnlyOfferedUntilTheUserConfirmsIt() {
        assertTrue(controller.offerPairing(link))

        val offer = assertNotNull(state.pendingPairing)
        assertEquals("wss://192.168.1.20:8080/agent", offer.address)
        assertTrue(offer.encrypted && offer.hasToken)
        assertEquals(pin, offer.fingerprint)
        assertEquals(UiState.DEFAULT_AGENT_URL, state.agentUrl, "nothing applied before confirmation")
        assertNull(state.agentFingerprint)
    }

    @Test
    fun confirmingAppliesTheAddressTokenAndPin() {
        controller.offerPairing(link)

        controller.confirmPairing()

        assertEquals("wss://192.168.1.20:8080/agent", state.agentUrl)
        assertEquals("t0k", state.agentToken)
        assertEquals(pin, state.agentFingerprint)
        assertNull(state.pendingPairing)
    }

    @Test
    fun dismissingKeepsTheCurrentAgent() {
        controller.offerPairing(link)

        controller.dismissPairing()

        assertNull(state.pendingPairing)
        assertEquals(UiState.DEFAULT_AGENT_URL, state.agentUrl)
    }

    @Test
    fun aMalformedLinkIsReportedNotOffered() {
        assertFalse(controller.offerPairing("remoteble://192.168.1.20?token=t0k"))

        assertNull(state.pendingPairing)
        assertTrue(state.status.startsWith("Not a pairing link"), state.status)
    }

    @Test
    fun typingAnotherAddressDropsThePin() {
        controller.offerPairing(link)
        controller.confirmPairing()

        controller.updateToken("other")
        assertEquals(pin, state.agentFingerprint, "a new token is still the same agent")
        controller.updateUrl("wss://10.0.0.9:8080/agent")

        assertNull(state.agentFingerprint, "the pin belongs to the paired agent, not to any address")
    }
}
