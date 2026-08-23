package au.com.elied.vitalsignal.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPolicyAttestationTest {
    @Test
    fun signedExternalPolicyEvidenceVerifies() {
        val draft = ProviderReasoningTestFixtures.policyDraft(ReasoningProvider.OPENAI_RESPONSES)
        val verified = ProviderReasoningTestFixtures.attestationAuthority()
            .verify(ProviderReasoningTestFixtures.attestation(draft))

        assertEquals(draft.attestationId, verified.draft.attestationId)
        assertTrue(verified.canonicalPayloadSha256.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun forgedExternalPolicyEvidenceFailsClosed() {
        val draft = ProviderReasoningTestFixtures.policyDraft(ReasoningProvider.OPENAI_RESPONSES)
        val forged = ProviderPolicyAttestationIssuer(
            ProviderReasoningTestFixtures.SIGNING_KEY_ID,
            ProviderReasoningTestFixtures.attestationSigner("wrong-key".toByteArray()),
        ).issue(draft)

        val error = expectFailure<ProviderPolicyAttestationException> {
            ProviderReasoningTestFixtures.attestationAuthority().verify(forged)
        }

        assertEquals(ProviderPolicyAttestationFailureCode.SIGNATURE_INVALID, error.failureCode)
    }

    @Test
    fun expiredEvidenceFailsClosed() {
        val draft = ProviderReasoningTestFixtures.policyDraft(ReasoningProvider.ANTHROPIC_MESSAGES)
        val error = expectFailure<ProviderPolicyAttestationException> {
            ProviderReasoningTestFixtures.attestationAuthority(now = { 20_000L })
                .verify(ProviderReasoningTestFixtures.attestation(draft))
        }

        assertEquals(ProviderPolicyAttestationFailureCode.EXPIRED, error.failureCode)
    }

    @Test
    fun openAiStoreTrueCannotBeRepresented() {
        val base = ProviderReasoningTestFixtures.policyDraft(ReasoningProvider.OPENAI_RESPONSES)
        expectFailure<IllegalArgumentException> {
            base.copy(openAiStoreResponse = true)
        }
    }

    @Test
    fun openAiMamOrZdrCannotBeSelfAssertedFromPublicPolicy() {
        val base = ProviderReasoningTestFixtures.policyDraft(ReasoningProvider.OPENAI_RESPONSES)
        listOf(
            ProviderRetentionMode.MODIFIED_ABUSE_MONITORING,
            ProviderRetentionMode.ZERO_DATA_RETENTION,
        ).forEach { retention ->
            expectFailure<IllegalArgumentException> {
                base.copy(
                    retentionMode = retention,
                    evidenceBasis = AttestationEvidenceBasis.PUBLIC_POLICY_AND_TENANT_REVIEW,
                )
            }
        }
    }

    @Test
    fun anthropicZdrAndSchemaClassificationRequireExternalEvidence() {
        val base = ProviderReasoningTestFixtures.policyDraft(ReasoningProvider.ANTHROPIC_MESSAGES)
        expectFailure<IllegalArgumentException> {
            base.copy(anthropicSchemaDefinitionDataClass = null)
        }
        expectFailure<IllegalArgumentException> {
            base.copy(
                retentionMode = ProviderRetentionMode.ZERO_DATA_RETENTION,
                evidenceBasis = AttestationEvidenceBasis.PUBLIC_POLICY_AND_TENANT_REVIEW,
            )
        }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
            return error
        }
        throw AssertionError("unreachable")
    }
}
