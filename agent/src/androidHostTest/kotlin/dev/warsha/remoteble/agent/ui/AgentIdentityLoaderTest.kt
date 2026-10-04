package dev.warsha.remoteble.agent.ui

import dev.warsha.remoteble.agent.AgentTls
import dev.warsha.remoteble.agent.AgentTlsProvider
import dev.warsha.remoteble.agent.TlsFront
import dev.warsha.remoteble.protocol.AgentFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.runBlocking

class AgentIdentityLoaderTest {
    private val unused = object : TlsFront.Factory {
        override val fingerprint = AgentFingerprint.ofSpkiSha256(ByteArray(32) { 1 })
        override suspend fun start(host: String, port: Int, upstreamPort: Int, onFailure: (reason: String) -> Unit): TlsFront =
            error("not started in this test")
    }
    private val first = AgentTls(unused)

    /** A key store that holds [first] until a reset, which deletes it and then fails to create one. */
    private val failingReset = object : AgentTlsProvider {
        override suspend fun load(reset: Boolean): AgentTls = if (reset) error("key store unavailable") else first
    }

    @Test
    fun aFailedResetNoLongerOffersTheDeletedIdentity() = runBlocking<Unit> {
        val loader = AgentIdentityLoader(failingReset)
        assertSame(first, loader.load(reset = false))

        assertNull(loader.load(reset = true))

        assertNull(loader.identity, "the reset deleted it; Start must load afresh, not serve it")
        assertNotNull(loader.failure)
    }

    @Test
    fun aSuccessfulLoadClearsAnEarlierFailure() = runBlocking<Unit> {
        val loader = AgentIdentityLoader(failingReset)
        loader.load(reset = true)

        assertSame(first, loader.load(reset = false))

        assertSame(first, loader.identity)
        assertNull(loader.failure)
    }

    @Test
    fun withoutAProviderThereIsNothingToLoad() = runBlocking<Unit> {
        val loader = AgentIdentityLoader(null)
        assertNull(loader.load(reset = false))
        assertEquals(null, loader.failure)
    }
}
