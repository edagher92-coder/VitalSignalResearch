package au.com.elied.vitalsignal.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReasoningPolicyTest {
    private val policy = LocalReasoningPolicy()

    @Test
    fun groundedReviewedTemplatePasses() {
        val packet = ReasoningTestFixtures.packet()
        val result = policy.validate(
            ReasoningTestFixtures.request(packet),
            ReasoningTestFixtures.candidate(packet),
        )

        assertEquals(ReasoningDisposition.PASS, result.disposition)
        assertTrue(result.failureCodes.isEmpty())
    }

    @Test
    fun changedSnapshotFailsClosed() {
        val packet = ReasoningTestFixtures.packet()
        val changed = ReasoningTestFixtures.candidate(packet).copy(inputSnapshotSha256 = "b".repeat(64))

        val result = policy.validate(ReasoningTestFixtures.request(packet), changed)

        assertEquals(ReasoningDisposition.REWRITE, result.disposition)
        assertTrue(ReasoningFailureCode.SNAPSHOT_MISMATCH in result.failureCodes)
    }

    @Test
    fun inventedMetricReferenceIsRejected() {
        val packet = ReasoningTestFixtures.packet()
        val base = ReasoningTestFixtures.candidate(packet)
        val changed = base.copy(
            claims = listOf(base.claims.single().copy(metricReferenceIds = listOf("invented"))),
        )

        val result = policy.validate(ReasoningTestFixtures.request(packet), changed)

        assertTrue(ReasoningFailureCode.UNKNOWN_METRIC_REFERENCE in result.failureCodes)
    }

    @Test
    fun unknownNarrativeTemplateIsRejected() {
        val packet = ReasoningTestFixtures.packet(approvedTemplateIds = setOf("observation.unreviewed.v1"))
        val candidate = ReasoningTestFixtures.candidate(packet, templateId = "observation.unreviewed.v1")

        val result = policy.validate(ReasoningTestFixtures.request(packet), candidate)

        assertTrue(ReasoningFailureCode.UNKNOWN_NARRATIVE_TEMPLATE in result.failureCodes)
    }

    @Test
    fun reviewedButUnapprovedTemplateIsRejected() {
        val packet = ReasoningTestFixtures.packet()
        val candidate = ReasoningTestFixtures.candidate(
            packet = packet,
            templateId = ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
            kind = NarrativeClaimKind.TREND,
        )

        val result = policy.validate(ReasoningTestFixtures.request(packet), candidate)

        assertTrue(ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE in result.failureCodes)
    }

    @Test
    fun kindTemplateBindingMismatchIsRejected() {
        val packet = ReasoningTestFixtures.packet()
        val candidate = ReasoningTestFixtures.candidate(
            packet = packet,
            templateId = ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1,
            kind = NarrativeClaimKind.TREND,
        )

        val result = policy.validate(ReasoningTestFixtures.request(packet), candidate)

        assertTrue(ReasoningFailureCode.NARRATIVE_TEMPLATE_KIND_MISMATCH in result.failureCodes)
    }

    @Test
    fun medicalAndTreatmentProseIsStructurallyImpossible() {
        assertFalse(NarrativeClaim::class.java.declaredFields.any { it.name == "text" })
        assertFalse(LocalReasoningCandidate::class.java.declaredFields.any { it.name == "text" })
        assertFalse(OllamaReasoningProtocol.JSON_SCHEMA.contains("\"text\""))

        listOf("sepsis", "infection likely", "take aspirin", "avoid exercise").forEach { prose ->
            assertThrows(IllegalArgumentException::class.java) {
                NarrativeClaim(
                    id = "claim-unsafe",
                    kind = NarrativeClaimKind.OBSERVATION,
                    templateId = prose,
                    metricReferenceIds = listOf("sleeping-hr"),
                    evidenceReferenceIds = listOf("personal-1"),
                    certainty = NarrativeCertainty.LOW,
                )
            }
        }
    }

    @Test
    fun unsupportedNextMeasurementIsRejected() {
        val packet = ReasoningTestFixtures.packet()
        val result = policy.validate(
            ReasoningTestFixtures.request(packet),
            ReasoningTestFixtures.candidate(packet).copy(nextMeasurementIds = listOf("take-blood-pressure")),
        )

        assertTrue(ReasoningFailureCode.UNAPPROVED_MEASUREMENT in result.failureCodes)
    }

    @Test
    fun hypothesisRequiresDisconfirmingEvidence() {
        val packet = ReasoningTestFixtures.packet(
            approvedTemplateIds = setOf(ReviewedNarrativeTemplates.CONTEXTUAL_HYPOTHESIS_V1),
        )
        val candidate = ReasoningTestFixtures.candidate(
            packet,
            ReviewedNarrativeTemplates.CONTEXTUAL_HYPOTHESIS_V1,
            NarrativeClaimKind.HYPOTHESIS,
        )

        val result = policy.validate(ReasoningTestFixtures.request(packet), candidate)

        assertTrue(ReasoningFailureCode.MISSING_HYPOTHESIS_COUNTEREVIDENCE in result.failureCodes)
    }

    @Test
    fun cleanAbstentionPassesAsAbstention() {
        val packet = ReasoningTestFixtures.packet()
        val abstention = ReasoningTestFixtures.candidate(packet).copy(
            claims = emptyList(),
            nextMeasurementIds = emptyList(),
            abstain = true,
            abstainReason = AbstainReasonCode.INSUFFICIENT_EVIDENCE,
        )

        val result = policy.validate(ReasoningTestFixtures.request(packet), abstention)

        assertEquals(ReasoningDisposition.ABSTAIN, result.disposition)
        assertTrue(result.failureCodes.isEmpty())
    }

    @Test
    fun abstentionCannotCarryHiddenSelections() {
        val packet = ReasoningTestFixtures.packet()
        val result = policy.validate(
            ReasoningTestFixtures.request(packet),
            ReasoningTestFixtures.candidate(packet).copy(
                abstain = true,
                abstainReason = AbstainReasonCode.INSUFFICIENT_EVIDENCE,
            ),
        )

        assertEquals(ReasoningDisposition.REWRITE, result.disposition)
        assertTrue(ReasoningFailureCode.INVALID_ABSTENTION in result.failureCodes)
    }

    @Test
    fun modelAuthoredQuestionCannotBypassReviewedCopy() {
        val packet = ReasoningTestFixtures.packet()
        val result = policy.validate(
            ReasoningTestFixtures.request(packet),
            ReasoningTestFixtures.candidate(packet).copy(questionIdsForUser = listOf("invented-question")),
        )

        assertTrue(ReasoningFailureCode.UNAPPROVED_QUESTION in result.failureCodes)
    }

    @Test
    fun languageModelCannotPromoteItselfToHighCertainty() {
        val packet = ReasoningTestFixtures.packet()
        val base = ReasoningTestFixtures.candidate(packet)
        val changed = base.copy(
            claims = listOf(base.claims.single().copy(certainty = NarrativeCertainty.HIGH)),
        )

        val result = policy.validate(ReasoningTestFixtures.request(packet), changed)

        assertTrue(ReasoningFailureCode.OVERSTATED_CERTAINTY in result.failureCodes)
    }
}
