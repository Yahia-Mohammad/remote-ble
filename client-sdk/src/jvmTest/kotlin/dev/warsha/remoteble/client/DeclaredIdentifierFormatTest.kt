package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.IdentifierFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class DeclaredIdentifierFormatTest {

    @Test
    fun defaultsToTheHostFormat() = runTest {
        val link = InMemoryTransport()
        val codec = CborProtocolCodec()
        val session = DefaultAgentSession(link.client, codec, backgroundScope)

        val hello = assertIs<ClientHello>(codec.decode(link.agentIncoming.first()))
        // Unchanged for a Kable-facing consumer: it needs handles its own Identifier can hold.
        assertEquals(currentIdentifierFormat(), hello.identifierFormat)
        session.close()
    }

    @Test
    fun aDeclaredFormatIsWhatTheAgentIsTold() = runTest {
        // A non-Kable consumer — a CLI, a test harness, anything that treats a handle as an opaque
        // routing token — declares STRING so the agent stops synthesizing per-client handles and
        // its own stay valid across this client's separate processes.
        val link = InMemoryTransport()
        val codec = CborProtocolCodec()
        val session = DefaultAgentSession(
            link.client,
            codec,
            backgroundScope,
            identifierFormat = IdentifierFormat.STRING,
        )

        val hello = assertIs<ClientHello>(codec.decode(link.agentIncoming.first()))
        assertEquals(IdentifierFormat.STRING, hello.identifierFormat)
        session.close()
    }
}
