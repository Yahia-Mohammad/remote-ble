package dev.warsha.remoteble.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentPairingTest {
    private val fp = AgentFingerprint.parse("sha256:358407d7ae647b06f91f3a3d3db1ab7879ef416491e260e2b878dbb8183e6ba7")

    @Test
    fun theSharedExampleParsesAndIsWrittenBackIdentically() {
        // The Rust agent's `pairing_uri` test formats this same pairing to this same string.
        val pairing = AgentPairing.parse(SHARED_EXAMPLE)

        assertEquals("192.168.1.20", pairing.host)
        assertEquals(8080, pairing.port)
        assertEquals("a+b&c=d%e f/é", pairing.token)
        assertEquals(fp, pairing.fingerprint)
        assertEquals(SHARED_EXAMPLE, pairing.toUri())
    }

    @Test
    fun aPinnedPairingIsWssAndAnUnpinnedOneWs() {
        val pinned = AgentPairing("192.168.1.20", 8080, "t", fp)
        val cleartext = AgentPairing("192.168.1.20", 8080, "t", null)

        assertTrue(pinned.encrypted)
        assertEquals("wss://192.168.1.20:8080/agent", pinned.url)
        assertFalse(cleartext.encrypted)
        assertEquals("ws://192.168.1.20:8080/agent", cleartext.url)
    }

    @Test
    fun ipv6HostsAreBracketedInTheUriAndTheUrl() {
        val pairing = AgentPairing("fe80::1c2d:3e4f", 8080, null, fp)

        assertEquals("remoteble://[fe80::1c2d:3e4f]:8080?fp=$fp", pairing.toUri())
        assertEquals("wss://[fe80::1c2d:3e4f]:8080/agent", pairing.url)
        assertEquals(pairing, AgentPairing.parse(pairing.toUri()))
    }

    @Test
    fun bothParametersAreOptional() {
        val bare = AgentPairing.parse("remoteble://agent.local:9000")

        assertNull(bare.token)
        assertNull(bare.fingerprint)
        assertEquals("remoteble://agent.local:9000", bare.toUri())
        assertNull(AgentPairing.parse("remoteble://agent.local:9000/?token=").token, "an empty token is none")
    }

    @Test
    fun plusIsItselfAndUnknownParametersAreIgnored() {
        val pairing = AgentPairing.parse("REMOTEBLE://10.0.0.2:8080?name=Lab&token=a+b&v=2")

        assertEquals("a+b", pairing.token)
        assertEquals("10.0.0.2", pairing.host)
    }

    @Test
    fun theTokenNeverAppearsInToString() {
        val text = AgentPairing("10.0.0.2", 8080, "s3cret-token", fp).toString()

        assertFalse("s3cret" in text, text)
        assertTrue("10.0.0.2:8080" in text && fp.toString() in text, text)
    }

    @Test
    fun malformedPairingsAreRefused() {
        listOf(
            "https://10.0.0.2:8080",
            "remoteble://10.0.0.2",
            "remoteble://10.0.0.2:",
            "remoteble://10.0.0.2:0",
            "remoteble://10.0.0.2:65536",
            "remoteble://10.0.0.2:80a",
            "remoteble://:8080",
            "remoteble://fe80::1:8080",
            "remoteble://[fe80::1]8080",
            "remoteble://[fe80::1%25en0]:8080",
            "remoteble://host_name:8080",
            "remoteble://-host:8080",
            "remoteble://user@10.0.0.2:8080",
            "remoteble://10.0.0.2:8080/agent",
            "remoteble://10.0.0.2:8080#fragment",
            "remoteble://10.0.0.2:8080?token=a&token=b",
            "remoteble://10.0.0.2:8080?token=%zz",
            "remoteble://10.0.0.2:8080?token=%",
            "remoteble://10.0.0.2:8080?token=%C3",
            "remoteble://10.0.0.2:8080?fp=sha256:00",
            "remoteble://10.0.0.2:8080?fp=md5:" + "0".repeat(64),
            "remoteble://[:]:8080",
            "remoteble://[1:]:8080",
            "remoteble://[1::2::3]:8080",
            "remoteble://[1:2:3:4:5:6:7]:8080",
            "remoteble://[1:2:3:4:5:6:7:8:9]:8080",
            "remoteble://[12345::1]:8080",
            "remoteble://[::1.2.3]:8080",
            "remoteble://[::300.0.0.1]:8080",
        ).forEach { uri ->
            assertFailsWith<IllegalArgumentException>(uri) { AgentPairing.parse(uri) }
            assertNull(AgentPairing.parseOrNull(uri), uri)
        }
    }

    @Test
    fun ipv6AddressesInEveryValidFormAreAccepted() {
        listOf("::", "::1", "fe80::1", "1::", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::", "::ffff:192.168.1.20", "FE80::A")
            .forEach { host -> assertEquals(host, AgentPairing.parse("remoteble://[$host]:8080").host) }
    }

    @Test
    fun anUnescapedCharacterOutsideTheBmpSurvivesParsing() {
        // toUri always escapes, but a link typed by hand, or decoded by whatever handed it over,
        // may carry the raw character: a surrogate pair, which must not be split apart.
        val pairing = AgentPairing.parse("remoteble://10.0.0.2:8080?token=key\uD83D\uDD11%20one")

        assertEquals("key\uD83D\uDD11 one", pairing.token)
    }

    @Test
    fun aPairingCannotBeBuiltBroken() {
        assertFailsWith<IllegalArgumentException> { AgentPairing("10.0.0.2", 0, null, null) }
        assertFailsWith<IllegalArgumentException> { AgentPairing("", 8080, null, null) }
        assertFailsWith<IllegalArgumentException> { AgentPairing("10.0.0.2", 8080, "", null) }
    }

    private companion object {
        const val SHARED_EXAMPLE =
            "remoteble://192.168.1.20:8080?token=a%2Bb%26c%3Dd%25e%20f%2F%C3%A9" +
                "&fp=sha256:358407d7ae647b06f91f3a3d3db1ab7879ef416491e260e2b878dbb8183e6ba7"
    }
}
