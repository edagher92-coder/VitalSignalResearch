package au.com.elied.vitalsignal.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPresentationContractTest {
    @Test
    fun availabilityStatesAreExplicitAndReviewed() {
        assertEquals(
            AssistantPresentationState.DISABLED,
            AssistantPresentationFactory.availability(false, false).state,
        )
        assertEquals(
            AssistantPresentationState.BLOCKED,
            AssistantPresentationFactory.availability(true, false).state,
        )
        assertEquals(
            AssistantPresentationState.READY,
            AssistantPresentationFactory.availability(true, true).state,
        )
        assertEquals(
            AssistantPresentationState.PROCESSING,
            AssistantPresentationFactory.processing().state,
        )
        ReviewedAssistantDisclosures.mandatoryVisibleIds.forEach {
            assertNotNull(ReviewedAssistantDisclosures.resolve(it))
        }
    }

    @Test
    fun mandatorySafetyDisclosuresCannotBeRemovedThroughAReadOnlyDowncast() {
        val before = ReviewedAssistantDisclosures.mandatoryVisibleIds.toSet()
        var rejected = false

        try {
            @Suppress("UNCHECKED_CAST")
            (ReviewedAssistantDisclosures.mandatoryVisibleIds as MutableSet<String>).clear()
        } catch (_: UnsupportedOperationException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(before, ReviewedAssistantDisclosures.mandatoryVisibleIds)
        assertEquals(4, ReviewedAssistantDisclosures.mandatoryVisibleIds.size)
    }

    @Test
    fun verifiedPresentationCollectionsCannotBeMutatedThroughAReadOnlyDowncast() {
        val packet = ReasoningTestFixtures.packet()
        val presentation = AssistantPresentationFactory.fromOutcome(
            ProviderReasoningTestFixtures.orchestrator(packet)
                .run(packet, ProviderReasoningTestFixtures.invocation(packet)),
        )
        var disclosureRejected = false
        var narrativeRejected = false

        try {
            @Suppress("UNCHECKED_CAST")
            (presentation.disclosureTemplateIds as MutableSet<String>).clear()
        } catch (_: UnsupportedOperationException) {
            disclosureRejected = true
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (presentation.narrativeTemplateIds as MutableList<String>).clear()
        } catch (_: UnsupportedOperationException) {
            narrativeRejected = true
        }

        assertTrue(disclosureRejected)
        assertTrue(narrativeRejected)
        assertTrue(
            presentation.disclosureTemplateIds.containsAll(
                ReviewedAssistantDisclosures.mandatoryVisibleIds,
            ),
        )
        assertEquals(1, presentation.narrativeTemplateIds.size)
    }

    @Test
    fun verifiedPresentationContainsOnlyReviewedTemplatesAndEvidenceCitations() {
        val packet = ReasoningTestFixtures.packet()
        val outcome = ProviderReasoningTestFixtures.orchestrator(packet)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        val presentation = AssistantPresentationFactory.fromOutcome(outcome)

        assertEquals(AssistantPresentationState.VERIFIED, presentation.state)
        assertEquals(
            listOf(ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1),
            presentation.narrativeTemplateIds,
        )
        assertEquals(setOf("personal-1"), presentation.citationEvidenceIds)
        assertEquals(setOf("sleeping-hr"), presentation.metricReferenceIds)
        assertTrue(presentation.providerRunReceiptSha256!!.matches(Regex("[a-f0-9]{64}")))
        assertNull(presentation.safeStatusTemplateId)
    }

    @Test
    fun cleanAbstentionShowsNoClaimsOrCitations() {
        val packet = ReasoningTestFixtures.packet()
        val abstention = ReasoningTestFixtures.candidate(packet).copy(
            claims = emptyList(),
            nextMeasurementIds = emptyList(),
            abstain = true,
            abstainReason = AbstainReasonCode.INSUFFICIENT_EVIDENCE,
        )
        val transport = ProviderReasoningTransport { request ->
            ProviderReasoningTestFixtures.successResponse(request, packet, abstention)
        }
        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, transport)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        val presentation = AssistantPresentationFactory.fromOutcome(outcome)

        assertEquals(AssistantPresentationState.ABSTAINED, presentation.state)
        assertTrue(presentation.narrativeTemplateIds.isEmpty())
        assertTrue(presentation.citationEvidenceIds.isEmpty())
    }

    @Test
    fun blockedFailureUsesReviewedStaticStatusAndNoModelContent() {
        val packet = ReasoningTestFixtures.packet()
        val outcome = ProviderReasoningTestFixtures.orchestrator(packet, rateAllowed = false)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))

        val presentation = AssistantPresentationFactory.fromOutcome(outcome)

        assertEquals(AssistantPresentationState.BLOCKED, presentation.state)
        assertEquals("governed-assistant-unavailable-v1", presentation.safeStatusTemplateId)
        assertTrue(presentation.narrativeTemplateIds.isEmpty())
        assertTrue(presentation.citationEvidenceIds.isEmpty())
    }

    @Test
    fun shadowOutcomeIsNeverRenderedAsAssistantAdvice() {
        val packet = ReasoningTestFixtures.packet()
        val outcome = ProviderReasoningTestFixtures.orchestrator(packet).run(
            packet,
            ProviderReasoningTestFixtures.invocation(
                packet = packet,
                executionMode = ProviderExecutionMode.SHADOW_CHALLENGER,
            ),
        )

        val presentation = AssistantPresentationFactory.fromOutcome(outcome)

        assertEquals(AssistantPresentationState.DISABLED, presentation.state)
        assertEquals(setOf(ReviewedAssistantDisclosures.SHADOW_NOT_VISIBLE_V1), presentation.disclosureTemplateIds)
        assertTrue(presentation.narrativeTemplateIds.isEmpty())
        assertNull(presentation.providerRunReceiptSha256)
    }

    @Test
    fun personaContractCannotStoreNamesOrHealthFacts() {
        val propertyNames = AssistantPersonaPreferences::class.java.declaredFields
            .map { it.name.lowercase() }
            .toSet()
        val forbiddenFragments = setOf(
            "name",
            "symptom",
            "diagnosis",
            "medication",
            "disease",
            "healthrecord",
            "freetext",
        )

        assertFalse(propertyNames.any { name -> forbiddenFragments.any(name::contains) })
    }

    @Test
    fun syntacticallyValidButUnreviewedTemplateCannotEnterPresentation() {
        var rejected = false
        try {
            AssistantPresentationModel(
                state = AssistantPresentationState.VERIFIED,
                disclosureTemplateIds = ReviewedAssistantDisclosures.mandatoryVisibleIds,
                narrativeTemplateIds = listOf("invented.valid-template.v1"),
                citationEvidenceIds = setOf("evidence-1"),
                metricReferenceIds = setOf("metric-1"),
                safeStatusTemplateId = null,
                providerRunReceiptSha256 = "a".repeat(64),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun callerMutationOfReturnedCandidateCannotChangeVerifiedPresentation() {
        val packet = ReasoningTestFixtures.packet()
        val outcome = ProviderReasoningTestFixtures.orchestrator(packet)
            .run(packet, ProviderReasoningTestFixtures.invocation(packet))
        val detached = outcome.candidate!!
        (detached.claims as MutableList).clear()

        val presentation = AssistantPresentationFactory.fromOutcome(outcome)

        assertEquals(AssistantPresentationState.VERIFIED, presentation.state)
        assertEquals(
            listOf(ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1),
            presentation.narrativeTemplateIds,
        )
    }
}
