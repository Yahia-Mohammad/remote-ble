package dev.warsha.remoteble.agent

import com.juul.kable.Peripheral
import dev.warsha.remoteble.protocol.ConnParamHint
import dev.warsha.remoteble.protocol.ConnProfile

/**
 * Whether this agent's platform can apply a connection-parameter/priority request to a live link.
 * [EngineBleBackend] uses this to advertise `conn.params`/`conn.priority` only where real.
 *
 * - Android -> `true` (`AndroidPeripheral.requestConnectionPriority`, declared on the Android-only
 *   `AndroidPeripheral` interface — Kable's common `Peripheral` has no portable priority method).
 * - iOS / JVM -> `false` (no portable or platform API to request link parameters; btleplug exposes
 *   none either).
 */
internal expect fun agentConnParamsSupported(): Boolean

/**
 * Applies [profile] to [peripheral]'s live link, ignoring [hint] (no shipping engine honors
 * fine-grained interval/latency/timeout parameters — it's reserved wire space). Returns whether the
 * platform's stack *accepted* the request: Android's `requestConnectionPriority` reports
 * accept/reject, not the resulting interval, so a `false` here means "reached the radio and it said
 * no" as opposed to a thrown exception meaning "never reached the radio". Platforms with no
 * connection-parameter control at all (iOS, JVM/btleplug) throw
 * [dev.warsha.remoteble.protocol.AgentException] with
 * [dev.warsha.remoteble.protocol.ErrorKind.UNSUPPORTED] — callers should gate on
 * [agentConnParamsSupported] first so this stays unreachable there in practice.
 */
internal expect suspend fun applyConnParams(
    peripheral: Peripheral,
    profile: ConnProfile,
    hint: ConnParamHint?,
): Boolean
