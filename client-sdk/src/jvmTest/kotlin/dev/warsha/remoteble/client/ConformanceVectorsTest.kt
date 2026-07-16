package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.selectProtocolVersion
import dev.warsha.remoteble.protocol.ProtocolVersionSelection
import kotlin.test.Test
import kotlin.test.assertEquals

/** Kotlin adapter for shared 0.9.1 conformance vectors. Rust consumes the same resource. */
class ConformanceVectorsTest {
    @Test
    fun version01_sharedVectors() {
        val lines = checkNotNull(javaClass.getResourceAsStream("/conformance/0.9.1-version-vectors.txt"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith('#') }

        lines.forEach { line ->
            val (scenario, minimum, maximum, expected) = line.split('|')
            assertEquals("VERSION-01", scenario)
            val actual = when (val selected = selectProtocolVersion(minimum.toInt(), maximum.toInt())) {
                is ProtocolVersionSelection.Selected -> "selected:${selected.version}"
                ProtocolVersionSelection.InvalidRange -> "invalid-range"
                ProtocolVersionSelection.NoCompatibleVersion -> "no-compatible-version"
            }
            assertEquals(expected, actual, line)
        }
    }
}
