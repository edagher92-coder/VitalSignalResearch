package au.com.elied.vitalsignal.reasoning

import java.util.Collections

enum class AssistantPresentationState {
    DISABLED,
    READY,
    PROCESSING,
    VERIFIED,
    ABSTAINED,
    BLOCKED,
}

object ReviewedAssistantDisclosures {
    const val RESEARCH_COPILOT_V1 = "assistant-disclosure.research-copilot.v1"
    const val NOT_MEDICAL_PROFESSIONAL_V1 = "assistant-disclosure.not-medical-professional.v1"
    const val NOT_EMERGENCY_MONITOR_V1 = "assistant-disclosure.not-emergency-monitor.v1"
    const val VERIFIED_EVIDENCE_ONLY_V1 = "assistant-disclosure.verified-evidence-only.v1"
    const val SHADOW_NOT_VISIBLE_V1 = "assistant-disclosure.shadow-not-visible.v1"

    private val reviewedCopy = mapOf(
        RESEARCH_COPILOT_V1 to
            "VitalSignal is a research health copilot that explains verified application results.",
        NOT_MEDICAL_PROFESSIONAL_V1 to
            "It is not a doctor, nurse, diagnosis, treatment instruction or medical clearance.",
        NOT_EMERGENCY_MONITOR_V1 to
            "It is not an emergency monitor. Symptoms and professional advice override this assistant.",
        VERIFIED_EVIDENCE_ONLY_V1 to
            "Shown statements are limited to reviewed templates and the cited evidence IDs.",
        SHADOW_NOT_VISIBLE_V1 to
            "A challenger model may be evaluated privately, but its output is not shown or treated as truth.",
    )

    val mandatoryVisibleIds: Set<String> = Collections.unmodifiableSet(
        linkedSetOf(
            RESEARCH_COPILOT_V1,
            NOT_MEDICAL_PROFESSIONAL_V1,
            NOT_EMERGENCY_MONITOR_V1,
            VERIFIED_EVIDENCE_ONLY_V1,
        ),
    )

    fun resolve(templateId: String): String? = reviewedCopy[templateId]
}

/** Phone/UI state contains reviewed semantic IDs and citations, not AI prose. */
class AssistantPresentationModel internal constructor(
    val state: AssistantPresentationState,
    disclosureTemplateIds: Set<String>,
    narrativeTemplateIds: List<String>,
    citationEvidenceIds: Set<String>,
    metricReferenceIds: Set<String>,
    val safeStatusTemplateId: String?,
    val providerRunReceiptSha256: String?,
) {
    private val disclosureSnapshot = Collections.unmodifiableSet(LinkedHashSet(disclosureTemplateIds))
    private val narrativeSnapshot = Collections.unmodifiableList(ArrayList(narrativeTemplateIds))
    private val citationSnapshot = Collections.unmodifiableSet(LinkedHashSet(citationEvidenceIds))
    private val metricSnapshot = Collections.unmodifiableSet(LinkedHashSet(metricReferenceIds))

    val disclosureTemplateIds: Set<String> get() = disclosureSnapshot
    val narrativeTemplateIds: List<String> get() = narrativeSnapshot
    val citationEvidenceIds: Set<String> get() = citationSnapshot
    val metricReferenceIds: Set<String> get() = metricSnapshot

    init {
        disclosureSnapshot.forEach {
            require(ReviewedAssistantDisclosures.resolve(it) != null) {
                "Assistant disclosure must be reviewed"
            }
        }
        narrativeSnapshot.forEach {
            require(ReviewedNarrativeTemplates.kindFor(it) != null) {
                "Assistant narrative template must exist in the reviewed semantic registry"
            }
        }
        citationSnapshot.forEach { requireReasoningId(it, "assistant citation evidence id") }
        metricSnapshot.forEach { requireReasoningId(it, "assistant metric reference id") }
        require(providerRunReceiptSha256 == null || providerRunReceiptSha256.matches(Regex("[a-f0-9]{64}")))
        if (state == AssistantPresentationState.VERIFIED) {
            require(disclosureSnapshot.containsAll(ReviewedAssistantDisclosures.mandatoryVisibleIds))
            require(narrativeSnapshot.isNotEmpty())
            require(safeStatusTemplateId == null)
            require(providerRunReceiptSha256 != null)
        } else {
            require(narrativeSnapshot.isEmpty())
            require(citationSnapshot.isEmpty())
            require(metricSnapshot.isEmpty())
        }
        require((state == AssistantPresentationState.BLOCKED) == (safeStatusTemplateId != null))
    }
}

object AssistantPresentationFactory {
    fun availability(providerEnabled: Boolean, validConsentAndPolicy: Boolean): AssistantPresentationModel =
        when {
            !providerEnabled -> empty(AssistantPresentationState.DISABLED)
            !validConsentAndPolicy -> empty(
                AssistantPresentationState.BLOCKED,
                safeStatusTemplateId = "assistant-consent-or-policy-required-v1",
            )
            else -> empty(AssistantPresentationState.READY)
        }

    fun processing(): AssistantPresentationModel = empty(AssistantPresentationState.PROCESSING)

    fun fromOutcome(outcome: VerifiedProviderReasoningOutcome): AssistantPresentationModel =
        when (outcome.state) {
            ProviderReasoningDeliveryState.VERIFIED -> {
                val candidate = requireNotNull(outcome.candidate)
                AssistantPresentationModel(
                    state = AssistantPresentationState.VERIFIED,
                    disclosureTemplateIds = ReviewedAssistantDisclosures.mandatoryVisibleIds,
                    narrativeTemplateIds = candidate.claims.map { it.templateId },
                    citationEvidenceIds = candidate.claims
                        .flatMap { it.evidenceReferenceIds + it.disconfirmingEvidenceReferenceIds }
                        .toSet(),
                    metricReferenceIds = candidate.claims.flatMap { it.metricReferenceIds }.toSet(),
                    safeStatusTemplateId = null,
                    providerRunReceiptSha256 = requireNotNull(outcome.runReceipt).canonicalSha256(),
                )
            }
            ProviderReasoningDeliveryState.ABSTAINED -> empty(AssistantPresentationState.ABSTAINED)
            ProviderReasoningDeliveryState.SAFE_FALLBACK -> empty(
                AssistantPresentationState.BLOCKED,
                safeStatusTemplateId = requireNotNull(outcome.safeTemplateId),
            )
            ProviderReasoningDeliveryState.SHADOW_RECORDED -> AssistantPresentationModel(
                state = AssistantPresentationState.DISABLED,
                disclosureTemplateIds = setOf(ReviewedAssistantDisclosures.SHADOW_NOT_VISIBLE_V1),
                narrativeTemplateIds = emptyList(),
                citationEvidenceIds = emptySet(),
                metricReferenceIds = emptySet(),
                safeStatusTemplateId = null,
                providerRunReceiptSha256 = null,
            )
        }

    private fun empty(
        state: AssistantPresentationState,
        safeStatusTemplateId: String? = null,
    ) = AssistantPresentationModel(
        state = state,
        disclosureTemplateIds = ReviewedAssistantDisclosures.mandatoryVisibleIds,
        narrativeTemplateIds = emptyList(),
        citationEvidenceIds = emptySet(),
        metricReferenceIds = emptySet(),
        safeStatusTemplateId = safeStatusTemplateId,
        providerRunReceiptSha256 = null,
    )
}

internal fun ProviderRunReceipt.canonicalSha256(): String = sha256Hex(
    CanonicalRecord().apply {
        field(1, strictUtf8("VITALSIGNAL_PROVIDER_RUN_RECEIPT_V1"))
        field(2, strictUtf8(provider.name))
        field(3, strictUtf8(modelSnapshotId))
        field(4, strictUtf8(modelManifestSha256))
        field(5, strictUtf8(runtimeVersion))
        field(6, strictUtf8(inputSnapshotSha256))
        field(7, strictUtf8(requestSha256))
        field(8, strictUtf8(providerRequestIdSha256))
        field(9, strictUtf8(promptSha256))
        field(10, strictUtf8(jsonSchemaSha256))
        field(11, strictUtf8(policySha256))
        field(12, strictUtf8(providerPolicyAttestationSha256))
        field(13, strictUtf8(privacyReceiptCanonicalSha256))
        field(14, strictUtf8(gatewayResponseCanonicalSha256))
        field(15, strictUtf8(privacyReceiptId))
        field(16, longBytes(consentGeneration))
        field(17, strictUtf8(purpose.name))
        field(18, strictUtf8(payloadClass.name))
        field(19, strictUtf8(executionMode.name))
        field(20, byteArrayOf(if (strictStructuredOutputValidated) 1 else 0))
        field(21, byteArrayOf(if (storeResponse) 1 else 0))
        field(22, byteArrayOf(if (providerBrowsingEnabled) 1 else 0))
        field(23, byteArrayOf(if (providerToolsEnabled) 1 else 0))
        field(24, longBytes(completedAtEpochMillis))
    }.bytes(),
)
