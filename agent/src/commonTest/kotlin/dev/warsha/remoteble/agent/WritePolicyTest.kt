package dev.warsha.remoteble.agent

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WritePolicyTest {

    private val heartRate = "0000180d-0000-1000-8000-00805f9b34fb"
    private val controlPoint = "00002a39-0000-1000-8000-00805f9b34fb"
    private val battery = "0000180f-0000-1000-8000-00805f9b34fb"
    private val cccd = "00002902-0000-1000-8000-00805f9b34fb"
    private val userDescription = "00002901-0000-1000-8000-00805f9b34fb"

    @Test
    fun permissiveAllowsEveryWriteAndIsNotEnforced() {
        val policy = WritePolicy.permissive()
        assertFalse(policy.enforced)
        assertTrue(policy.authorizesWrite("anyone", heartRate, controlPoint, size = 1_000_000, withResponse = false))
        assertTrue(policy.authorizesDescriptorWrite("anyone", heartRate, controlPoint, cccd, size = 1_000_000))
        assertTrue(policy.authorizesPairing("anyone"))
    }

    @Test
    fun unlistedPrincipalIsDeniedOnceAPolicyIsConfigured() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"writes":[]}}}""",
            knownPrincipals = setOf("lab-a", "lab-b"),
        )
        assertTrue(policy.enforced)
        assertFalse(policy.authorizesWrite("lab-b", heartRate, controlPoint, size = 1, withResponse = true))
    }

    @Test
    fun emptyRuleListDeniesExactlyLikeAnUnlistedPrincipal() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"writes":[]}}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertFalse(policy.authorizesWrite("lab-a", heartRate, controlPoint, size = 1, withResponse = true))
    }

    @Test
    fun matchingRuleAllowsWithinItsBounds() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"writes":[
                {"service":"$heartRate","characteristic":"$controlPoint","maximumBytes":1,"withResponse":true}
            ]}}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertTrue(policy.authorizesWrite("lab-a", heartRate, controlPoint, size = 1, withResponse = true))
        // Wrong characteristic, over the byte bound, and the wrong write type each fail independently.
        assertFalse(policy.authorizesWrite("lab-a", heartRate, battery, size = 1, withResponse = true))
        assertFalse(policy.authorizesWrite("lab-a", heartRate, controlPoint, size = 2, withResponse = true))
        assertFalse(policy.authorizesWrite("lab-a", heartRate, controlPoint, size = 1, withResponse = false))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"writes":[
                {"service":"${heartRate.uppercase()}","characteristic":"${controlPoint.uppercase()}"}
            ]}}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertTrue(policy.authorizesWrite("lab-a", heartRate, controlPoint, size = 1, withResponse = true))
    }

    @Test
    fun wildcardMatchesAnyCharacteristicOnAnyService() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"ci":{"writes":[{"service":"*","characteristic":"*","maximumBytes":20}]}}}""",
            knownPrincipals = setOf("ci"),
        )
        assertTrue(policy.authorizesWrite("ci", heartRate, controlPoint, size = 20, withResponse = false))
        assertTrue(policy.authorizesWrite("ci", battery, "anything-at-all", size = 20, withResponse = true))
        assertFalse(policy.authorizesWrite("ci", battery, "anything-at-all", size = 21, withResponse = true))
    }

    @Test
    fun nullMaximumBytesIsUnlimitedAndNullWithResponseMatchesEither() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"writes":[{"service":"$heartRate","characteristic":"$controlPoint","maximumBytes":null,"withResponse":null}]}}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertTrue(policy.authorizesWrite("lab-a", heartRate, controlPoint, size = 10_000, withResponse = true))
        assertTrue(policy.authorizesWrite("lab-a", heartRate, controlPoint, size = 10_000, withResponse = false))
    }

    @Test
    fun descriptorWritesAreMatchedIndependentlyOfCharacteristicWrites() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{
                "writes":[],
                "descriptorWrites":[{"service":"$heartRate","characteristic":"$controlPoint","descriptor":"$cccd","maximumBytes":2}]
            }}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertFalse(policy.authorizesWrite("lab-a", heartRate, controlPoint, size = 1, withResponse = true))
        assertTrue(policy.authorizesDescriptorWrite("lab-a", heartRate, controlPoint, cccd, size = 2))
        assertFalse(policy.authorizesDescriptorWrite("lab-a", heartRate, controlPoint, cccd, size = 3))
        assertFalse(policy.authorizesDescriptorWrite("lab-a", heartRate, controlPoint, userDescription, size = 1))
    }

    @Test
    fun descriptorWildcardIsExplicitAndMatchesOnlyWhenConfigured() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"descriptorWrites":[
                {"service":"$heartRate","characteristic":"$controlPoint","descriptor":"*"}
            ]}}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertTrue(policy.authorizesDescriptorWrite("lab-a", heartRate, controlPoint, cccd, size = 1))
        assertTrue(policy.authorizesDescriptorWrite("lab-a", heartRate, controlPoint, userDescription, size = 1))
    }

    @Test
    fun maximumBytesUsesASignedNonnegative32BitDomain() {
        val zero = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"writes":[
                {"service":"$heartRate","characteristic":"$controlPoint","maximumBytes":0}
            ]}}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertTrue(zero.authorizesWrite("lab-a", heartRate, controlPoint, size = 0, withResponse = true))
        assertFalse(zero.authorizesWrite("lab-a", heartRate, controlPoint, size = 1, withResponse = true))
        for (invalid in listOf(
            """{"version":1,"principals":{"lab-a":{"writes":[{"service":"*","characteristic":"*","maximumBytes":-1}]}}}""",
            """{"version":1,"principals":{"lab-a":{"descriptorWrites":[{"service":"*","characteristic":"*","descriptor":"*","maximumBytes":-1}]}}}""",
            """{"version":1,"principals":{"lab-a":{"writes":[{"service":"*","characteristic":"*","maximumBytes":2147483648}]}}}""",
        )) {
            assertFailsWith<IllegalArgumentException> {
                WritePolicy.decode(invalid, knownPrincipals = setOf("lab-a"))
            }
        }
    }

    @Test
    fun unknownFieldsAreRejectedAtEverySchemaLevel() {
        val invalid = listOf(
            """{"version":1,"principals":{},"unexpected":true}""",
            """{"version":1,"principals":{"lab-a":{"unexpected":true}}}""",
            """{"version":1,"principals":{"lab-a":{"writes":[{"service":"*","characteristic":"*","maximumByte":1}]}}}""",
            """{"version":1,"principals":{"lab-a":{"descriptorWrites":[{"service":"*","characteristic":"*","descriptor":"*","maximumByte":1}]}}}""",
        )
        invalid.forEach { raw ->
            assertFailsWith<IllegalArgumentException> {
                WritePolicy.decode(raw, knownPrincipals = setOf("lab-a"))
            }
        }
    }

    @Test
    fun pairingDefaultsToDeniedForAListedPrincipal() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"writes":[]}}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertFalse(policy.authorizesPairing("lab-a"))
    }

    @Test
    fun pairingCanBeExplicitlyGranted() {
        val policy = WritePolicy.decode(
            """{"version":1,"principals":{"lab-a":{"writes":[],"pairing":true}}}""",
            knownPrincipals = setOf("lab-a"),
        )
        assertTrue(policy.authorizesPairing("lab-a"))
    }

    @Test
    fun malformedJsonFailsToDecode() {
        assertFailsWith<IllegalArgumentException> {
            WritePolicy.decode("not json", knownPrincipals = setOf("lab-a"))
        }
    }

    @Test
    fun unsupportedVersionFailsToDecode() {
        assertFailsWith<IllegalArgumentException> {
            WritePolicy.decode("""{"version":2,"principals":{}}""", knownPrincipals = setOf("lab-a"))
        }
    }

    @Test
    fun aPrincipalNotAmongTheAgentsConfiguredCredentialsFailsToDecode() {
        // A typo'd principal in the policy file is exactly the misconfiguration a security
        // feature must not silently ignore: it must fail startup, not boot with a rule nobody
        // will ever match.
        assertFailsWith<IllegalArgumentException> {
            WritePolicy.decode(
                """{"version":1,"principals":{"lab-x":{"writes":[]}}}""",
                knownPrincipals = setOf("lab-a", "lab-b"),
            )
        }
    }
}
