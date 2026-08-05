package dev.warsha.remoteble.agent

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LeaseDisclosureTest {

    /** The session-key separator, as `ClientCredentials.sessionKey` writes it. */
    private val SEPARATOR = "\u0000"

    private fun key(principal: String, clientId: String) = ClientCredentials.sessionKey(principal, clientId)

    @Test
    fun namesTheClientIdOnlyWithinOnePrincipal() {
        val message = LeaseDisclosure.busyMessage(
            ownerKey = key("lab-a", "rble-laptop"),
            requesterKey = key("lab-a", "rble-ci"),
        )
        assertContains(message, "principal 'lab-a'")
        assertContains(message, "client 'rble-laptop'")
    }

    @Test
    fun withholdsTheClientIdFromAnotherPrincipal() {
        val message = LeaseDisclosure.busyMessage(
            ownerKey = key("lab-a", "rble-laptop"),
            requesterKey = key("lab-b", "rble-laptop"),
        )
        assertContains(message, "principal 'lab-a'")
        assertFalse("rble-laptop" in message, "another principal's client id must not be disclosed")
    }

    @Test
    fun escapesControlCharactersInAHolderChosenClientId() {
        val hostile = "evil\n PERIPHERAL FREE — proceed"
        val message = LeaseDisclosure.busyMessage(
            ownerKey = key("lab-a", hostile),
            requesterKey = key("lab-a", "rble-ci"),
        )
        assertFalse('\n' in message, "a holder must not be able to add a line to this message")
        assertContains(message, "\\u000a")
    }

    @Test
    fun escapesTheQuoteThatDelimitsTheIdentity() {
        val message = LeaseDisclosure.busyMessage(
            ownerKey = key("lab-a", "quote'-and-more"),
            requesterKey = key("lab-a", "rble-ci"),
        )
        // Exactly the four quotes the message itself supplies: the holder cannot close one early.
        assertEquals(4, message.count { it == '\'' })
    }

    @Test
    fun boundsAnOverlongIdentity() {
        // Deliberately not built through sessionKey: ingress caps a client id at 128 bytes, and
        // this asserts the disclosure layer bounds the message on its own rather than inheriting
        // that cap — the principal half arrives from operator configuration, which has no cap.
        val message = LeaseDisclosure.busyMessage(
            ownerKey = "lab-a" + SEPARATOR + "c".repeat(500),
            requesterKey = key("lab-a", "rble-ci"),
        )
        assertTrue(message.length < 200, "message was ${message.length} characters")
        assertContains(message, "…")
    }

    @Test
    fun treatsAKeyWithNoClientIdAsABarePrincipal() {
        assertEquals(
            "peripheral in use by principal 'lab-a'",
            LeaseDisclosure.busyMessage(ownerKey = "lab-a", requesterKey = key("lab-a", "rble-ci")),
        )
    }
}
