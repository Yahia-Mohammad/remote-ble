package dev.warsha.remoteble.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-principal write allowlist (U7) — the only *control* on writes, since CLI-side policy is
 * advisory by construction: it lives in a file the calling agent can edit, so anything it refuses
 * can still be done by a second client on the same token. Only a check made here, keyed to the
 * authenticating principal, holds regardless of which client shows up.
 *
 * See `docs/proposals/agent-write-policy.md` for the full design rationale.
 *
 * ### Three states, not two
 * - **Not configured** ([permissive]) — every write allowed. Today's behaviour, so no existing
 *   consumer breaks on upgrade.
 * - **Configured, principal unlisted** — every write denied. An operator who configured a policy
 *   at all almost certainly did not intend an unlisted principal to have full access.
 * - **Configured, principal listed with an empty rule list** — every write denied. The same
 *   outcome as "unlisted", reached without a special case: an absent or empty rule set both fail
 *   every `any { }` match.
 *
 * These are deliberately three distinct configurations rather than two: "I have not set this up
 * yet" and "I have set this up to permit nothing" are opposite intentions, and collapsing them
 * would eventually make one read as the other.
 *
 * ### Matching
 * Exact, case-insensitive string equality against the wire form of [dev.warsha.remoteble.protocol.CharRef]
 * / [dev.warsha.remoteble.protocol.DescRef] — in this codebase, the **full 128-bit UUID**
 * (`0000180d-0000-1000-8000-00805f9b34fb`), never the short form — or the wildcard `"*"`.
 * `instance` (the duplicate-UUID disambiguator) is deliberately not part of matching: a rule is
 * about the characteristic, not which duplicate. `maximumBytes = null` means unlimited;
 * `withResponse = null` matches either write type.
 *
 * [WriteRule.device] scopes a rule to one peripheral and defaults to `"*"`, so a policy file
 * written without it keeps its previous meaning. It matches against the device *handle* — the same
 * value the registry leases and `agent.status` reports — so an operator writes the identity the
 * agent already shows them rather than a second naming scheme.
 */
class WritePolicy private constructor(private val principals: Map<String, PrincipalPolicy>?) {

    /** Whether this agent enforces a policy at all — surfaced in `agent.status`. */
    val enforced: Boolean get() = principals != null

    /** Whether [principal] may write [size] bytes to `service`/`characteristic` on [device]. */
    fun authorizesWrite(
        principal: String,
        device: String,
        service: String,
        characteristic: String,
        size: Int,
        withResponse: Boolean,
    ): Boolean {
        val rules = principals ?: return true
        return rules[principal]?.writes.orEmpty().any {
            it.matchesChar(device, service, characteristic) && it.permits(size, withResponse)
        }
    }

    /** Whether [principal] may write [size] bytes to the descriptor at `service`/`characteristic` on [device]. */
    fun authorizesDescriptorWrite(
        principal: String,
        device: String,
        service: String,
        characteristic: String,
        descriptor: String,
        size: Int,
    ): Boolean {
        val rules = principals ?: return true
        return rules[principal]?.descriptorWrites.orEmpty().any {
            it.matchesDesc(device, service, characteristic, descriptor) &&
                (it.maximumBytes == null || size <= it.maximumBytes)
        }
    }

    /** Whether [principal] may pair/unpair with a peripheral. */
    fun authorizesPairing(principal: String): Boolean {
        val rules = principals ?: return true
        return rules[principal]?.pairing ?: false
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        private val json = Json { ignoreUnknownKeys = false; isLenient = false }

        /** No policy configured: every write is allowed, matching pre-U7 behaviour. */
        fun permissive(): WritePolicy = WritePolicy(null)

        /**
         * Decodes and validates a policy file before the agent opens a listener — the same
         * "malformed input never opens a port" posture as [SimulationProfile.decode].
         *
         * [knownPrincipals] is the actual set of principals this agent process was configured
         * with (see [ClientCredentials]); a policy naming anyone outside it is almost certainly a
         * typo; naming nobody by accident is exactly the failure mode a security feature must not
         * silently tolerate, so this throws rather than starting up in a state the operator did
         * not intend.
         */
        fun decode(raw: String, knownPrincipals: Set<String>): WritePolicy {
            val file = try {
                json.decodeFromString<WritePolicyFile>(raw)
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Throwable) {
                throw IllegalArgumentException("invalid write policy: ${error.message}", error)
            }
            require(file.version == CURRENT_SCHEMA_VERSION) {
                "unsupported write policy version ${file.version}; expected $CURRENT_SCHEMA_VERSION"
            }
            val unknown = file.principals.keys - knownPrincipals
            require(unknown.isEmpty()) {
                "write policy names unknown principal(s): ${unknown.sorted().joinToString()}"
            }
            file.principals.forEach { (principal, policy) ->
                policy.writes.forEachIndexed { index, rule ->
                    validateMaximumBytes(rule.maximumBytes, "$principal.writes[$index]")
                }
                policy.descriptorWrites.forEachIndexed { index, rule ->
                    validateMaximumBytes(rule.maximumBytes, "$principal.descriptorWrites[$index]")
                }
            }
            return WritePolicy(file.principals)
        }

        private fun validateMaximumBytes(maximumBytes: Int?, rule: String) {
            require(maximumBytes == null || maximumBytes >= 0) {
                "write policy $rule maximumBytes must be non-negative"
            }
        }
    }
}

@Serializable
data class WritePolicyFile(
    val version: Int,
    val principals: Map<String, PrincipalPolicy> = emptyMap(),
)

@Serializable
data class PrincipalPolicy(
    val writes: List<WriteRule> = emptyList(),
    val descriptorWrites: List<DescriptorWriteRule> = emptyList(),
    val pairing: Boolean = false,
)

@Serializable
data class WriteRule(
    val service: String,
    val characteristic: String,
    /**
     * The peripheral this rule is about, as its device handle, or `"*"` for any. Optional and
     * wildcard by default, so a policy written before this field existed keeps meaning exactly what
     * it meant — but on a shared rig it is the difference between "lab-a may write this control
     * point" and "lab-a may write this control point *on its own device*".
     */
    val device: String = "*",
    val maximumBytes: Int? = null,
    val withResponse: Boolean? = null,
) {
    fun matchesChar(device: String, service: String, characteristic: String): Boolean =
        matchesField(this.device, device) &&
            matchesField(this.service, service) &&
            matchesField(this.characteristic, characteristic)

    fun permits(size: Int, withResponse: Boolean): Boolean =
        (maximumBytes == null || size <= maximumBytes) &&
            (this.withResponse == null || this.withResponse == withResponse)
}

@Serializable
data class DescriptorWriteRule(
    val service: String,
    val characteristic: String,
    val descriptor: String,
    /** The peripheral this rule is about, or `"*"` for any — see [WriteRule.device]. */
    val device: String = "*",
    val maximumBytes: Int? = null,
) {
    fun matchesDesc(device: String, service: String, characteristic: String, descriptor: String): Boolean =
        matchesField(this.device, device) &&
            matchesField(this.service, service) &&
            matchesField(this.characteristic, characteristic) &&
            matchesField(this.descriptor, descriptor)
}

private fun matchesField(rule: String, actual: String): Boolean =
    rule == "*" || rule.equals(actual, ignoreCase = true)
