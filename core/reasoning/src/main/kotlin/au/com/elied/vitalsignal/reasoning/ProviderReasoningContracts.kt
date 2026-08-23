package au.com.elied.vitalsignal.reasoning

import java.security.MessageDigest
import java.util.Collections

/**
 * A language provider is an advisory semantic selector only. Numerical health
 * interpretation remains upstream in the deterministic/specialised pipeline.
 */
enum class ReasoningProvider {
    OLLAMA_LOCAL,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
}

enum class ProviderExecutionMode {
    PRIMARY_ADVISORY,
    SHADOW_CHALLENGER,
}

enum class ReasoningPurpose {
    EXPLAIN_VERIFIED_HEALTH_STATE,
    SELECT_REVIEWED_FOLLOW_UP,
    OFFLINE_SHADOW_EVALUATION,
}

enum class ProviderRetentionMode {
    LOCAL_ONLY,
    PROVIDER_STANDARD,
    MODIFIED_ABUSE_MONITORING,
    ZERO_DATA_RETENTION,
}

enum class ResidencyRegion {
    LOCAL_PRIVATE_NETWORK,
    AUSTRALIA,
    EUROPEAN_UNION,
    UNITED_STATES,
    PROVIDER_CONTROLLED,
}

enum class AttestationEvidenceBasis {
    LOCAL_OPERATOR_CONFIGURATION,
    PUBLIC_POLICY_AND_TENANT_REVIEW,
    PROVIDER_ADMIN_EVIDENCE,
    EXECUTED_DATA_PROCESSING_AGREEMENT,
}

enum class SchemaDefinitionDataClass {
    GENERIC_NO_PERSONAL_DATA,
}

enum class GatewayCredentialBoundary {
    VITALSIGNAL_BACKEND_ONLY,
}

enum class EvidenceRetrievalMode {
    CURATED_EVIDENCE_BACKEND_ONLY,
}

/** Fields that may cross the provider boundary after packet verification. */
enum class ProviderPayloadField {
    INPUT_SNAPSHOT_HASH,
    METRIC_ID_VALUE_UNIT_QUALITY_WINDOW,
    EVIDENCE_ID_KIND_CONTENT_HASH,
    APPROVED_TEMPLATE_MEASUREMENT_QUESTION_IDS,
    QUALITY_GAP_COUNT,
    POLICY_HASH,
    PERSONA_ENUMS,
}

enum class ProviderPayloadClass {
    SYNTHETIC_FIXTURE,
    PERSONAL_HEALTH_MINIMIZED,
}

val MINIMIZED_PROVIDER_FIELDS: Set<ProviderPayloadField> = Collections.unmodifiableSet(
    ProviderPayloadField.entries.toCollection(linkedSetOf()),
)

/**
 * Operator-supplied evidence about the provider account/tenant. It is signed
 * outside the Android apps and verified immediately before use. An enum value
 * alone never proves that ZDR/MAM or another retention programme is active.
 */
data class ProviderPolicyAttestationDraft(
    val attestationId: String,
    val provider: ReasoningProvider,
    val providerAccountPolicyId: String,
    val allowedPurposes: Set<ReasoningPurpose>,
    val retentionMode: ProviderRetentionMode,
    val residencyRegion: ResidencyRegion,
    val evidenceBasis: AttestationEvidenceBasis,
    val evidenceSha256: String,
    val providerTrainingUseDisabled: Boolean,
    val strictStructuredOutputEnabled: Boolean,
    val providerBrowsingDisabled: Boolean,
    val providerToolsDisabled: Boolean,
    val credentialBoundary: GatewayCredentialBoundary,
    /** Must be false for OpenAI Responses. Null for other providers. */
    val openAiStoreResponse: Boolean?,
    /** Must be generic and PHI-free for Anthropic structured-output schemas. */
    val anthropicSchemaDefinitionDataClass: SchemaDefinitionDataClass?,
    val policySha256: String,
    val attestedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        requireReasoningId(attestationId, "provider attestation id")
        requireReasoningId(providerAccountPolicyId, "provider account policy id")
        require(allowedPurposes.isNotEmpty())
        requireSha256(evidenceSha256, "provider policy evidence hash")
        requireSha256(policySha256, "provider policy hash")
        require(attestedAtEpochMillis > 0L)
        require(expiresAtEpochMillis > attestedAtEpochMillis)
        require(providerTrainingUseDisabled)
        require(strictStructuredOutputEnabled)
        require(providerBrowsingDisabled)
        require(providerToolsDisabled)
        require(credentialBoundary == GatewayCredentialBoundary.VITALSIGNAL_BACKEND_ONLY)
        when (provider) {
            ReasoningProvider.OLLAMA_LOCAL -> {
                require(retentionMode == ProviderRetentionMode.LOCAL_ONLY)
                require(residencyRegion == ResidencyRegion.LOCAL_PRIVATE_NETWORK)
                require(evidenceBasis == AttestationEvidenceBasis.LOCAL_OPERATOR_CONFIGURATION)
                require(openAiStoreResponse == null)
                require(anthropicSchemaDefinitionDataClass == null)
            }
            ReasoningProvider.OPENAI_RESPONSES -> {
                require(retentionMode != ProviderRetentionMode.LOCAL_ONLY)
                require(openAiStoreResponse == false) { "OpenAI Responses must set store=false" }
                require(anthropicSchemaDefinitionDataClass == null)
                if (
                    retentionMode == ProviderRetentionMode.MODIFIED_ABUSE_MONITORING ||
                    retentionMode == ProviderRetentionMode.ZERO_DATA_RETENTION
                ) {
                    require(
                        evidenceBasis == AttestationEvidenceBasis.PROVIDER_ADMIN_EVIDENCE ||
                            evidenceBasis == AttestationEvidenceBasis.EXECUTED_DATA_PROCESSING_AGREEMENT,
                    ) { "MAM/ZDR requires external account evidence; it cannot be assumed" }
                }
            }
            ReasoningProvider.ANTHROPIC_MESSAGES -> {
                require(retentionMode != ProviderRetentionMode.LOCAL_ONLY)
                require(openAiStoreResponse == null)
                require(
                    anthropicSchemaDefinitionDataClass ==
                        SchemaDefinitionDataClass.GENERIC_NO_PERSONAL_DATA,
                ) { "Anthropic schema definitions must contain no personal or health data" }
                if (retentionMode == ProviderRetentionMode.ZERO_DATA_RETENTION) {
                    require(
                        evidenceBasis == AttestationEvidenceBasis.PROVIDER_ADMIN_EVIDENCE ||
                            evidenceBasis == AttestationEvidenceBasis.EXECUTED_DATA_PROCESSING_AGREEMENT,
                    ) { "Anthropic ZDR requires external account evidence; it cannot be assumed" }
                }
            }
        }
    }
}

fun interface ProviderPolicyAttestationSigner {
    fun sign(canonicalPayload: ByteArray): ByteArray
}

fun interface ProviderPolicyAttestationSignatureVerifier {
    fun verify(signingKeyId: String, canonicalPayload: ByteArray, signature: ByteArray): Boolean
}

class SignedProviderPolicyAttestation internal constructor(
    draft: ProviderPolicyAttestationDraft,
    val signingKeyId: String,
    canonicalPayload: ByteArray,
    signature: ByteArray,
) {
    internal val draft = draft.copy(allowedPurposes = immutableProviderSet(draft.allowedPurposes))
    private val canonicalPayload = canonicalPayload.copyOf()
    private val signature = signature.copyOf()

    init {
        requireReasoningId(signingKeyId, "provider policy signing key id")
        require(canonicalPayload.isNotEmpty() && canonicalPayload.size <= 64 * 1024)
        require(signature.isNotEmpty() && signature.size <= 8 * 1024)
    }

    fun canonicalPayloadBytes(): ByteArray = canonicalPayload.copyOf()

    fun signatureBytes(): ByteArray = signature.copyOf()
}

class ProviderPolicyAttestationIssuer(
    private val signingKeyId: String,
    private val signer: ProviderPolicyAttestationSigner,
) {
    init {
        requireReasoningId(signingKeyId, "provider policy signing key id")
    }

    fun issue(draft: ProviderPolicyAttestationDraft): SignedProviderPolicyAttestation {
        val canonical = CanonicalProviderPolicyAttestation.encode(draft, signingKeyId)
        val signature = signer.sign(canonical.copyOf()).copyOf()
        return SignedProviderPolicyAttestation(draft, signingKeyId, canonical, signature)
    }
}

enum class ProviderPolicyAttestationFailureCode {
    CANONICAL_PAYLOAD_MISMATCH,
    SIGNATURE_INVALID,
    NOT_YET_VALID,
    EXPIRED,
    TTL_EXCEEDED,
}

class ProviderPolicyAttestationException(
    val failureCode: ProviderPolicyAttestationFailureCode,
) : IllegalStateException("Provider policy attestation rejected: ${failureCode.name}")

class VerifiedProviderPolicy internal constructor(
    val draft: ProviderPolicyAttestationDraft,
    val canonicalPayloadSha256: String,
) {
    init {
        requireSha256(canonicalPayloadSha256, "provider attestation canonical hash")
    }
}

class ProviderPolicyAttestationAuthority(
    private val signatureVerifier: ProviderPolicyAttestationSignatureVerifier,
    private val nowEpochMillis: () -> Long,
    private val maxTtlMillis: Long = 31L * 24L * 60L * 60L * 1_000L,
) {
    init {
        require(maxTtlMillis in 1L..(366L * 24L * 60L * 60L * 1_000L))
    }

    fun verify(attestation: SignedProviderPolicyAttestation): VerifiedProviderPolicy {
        val supplied = attestation.canonicalPayloadBytes()
        val recomputed = CanonicalProviderPolicyAttestation.encode(attestation.draft, attestation.signingKeyId)
        if (!MessageDigest.isEqual(supplied, recomputed)) {
            rejected(ProviderPolicyAttestationFailureCode.CANONICAL_PAYLOAD_MISMATCH)
        }
        val validSignature = try {
            signatureVerifier.verify(
                attestation.signingKeyId,
                supplied.copyOf(),
                attestation.signatureBytes(),
            )
        } catch (_: Exception) {
            false
        }
        if (!validSignature) rejected(ProviderPolicyAttestationFailureCode.SIGNATURE_INVALID)

        val now = nowEpochMillis()
        if (now < attestation.draft.attestedAtEpochMillis) {
            rejected(ProviderPolicyAttestationFailureCode.NOT_YET_VALID)
        }
        if (now >= attestation.draft.expiresAtEpochMillis) {
            rejected(ProviderPolicyAttestationFailureCode.EXPIRED)
        }
        val ttl = try {
            Math.subtractExact(
                attestation.draft.expiresAtEpochMillis,
                attestation.draft.attestedAtEpochMillis,
            )
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        if (ttl > maxTtlMillis) rejected(ProviderPolicyAttestationFailureCode.TTL_EXCEEDED)
        return VerifiedProviderPolicy(
            draft = attestation.draft,
            canonicalPayloadSha256 = sha256Hex(supplied),
        )
    }

    private fun rejected(code: ProviderPolicyAttestationFailureCode): Nothing =
        throw ProviderPolicyAttestationException(code)
}

/**
 * Exact privacy/consent receipt for one provider invocation. It binds the
 * signed input snapshot without transmitting the subject pseudonym.
 */
data class ReasoningPrivacyReceiptDraft(
    val receiptId: String,
    val boundInputSnapshotSha256: String,
    val consentGeneration: Long,
    val consentReceiptSha256: String,
    val purpose: ReasoningPurpose,
    val payloadClass: ProviderPayloadClass,
    val transmittedFields: Set<ProviderPayloadField>,
    val dataMinimizationPolicySha256: String,
    val redactionPolicySha256: String,
    val policySha256: String,
    val residencyRegion: ResidencyRegion,
    val retentionMode: ProviderRetentionMode,
    val providerPolicyAttestationId: String,
    val evidenceRetrievalMode: EvidenceRetrievalMode,
    val personaPreferencesSeparateFromHealthRecord: Boolean,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        requireReasoningId(receiptId, "reasoning privacy receipt id")
        requireSha256(boundInputSnapshotSha256, "privacy receipt input snapshot hash")
        require(consentGeneration > 0L)
        requireSha256(consentReceiptSha256, "consent receipt hash")
        require(transmittedFields.isNotEmpty())
        require(transmittedFields.all { it in MINIMIZED_PROVIDER_FIELDS })
        requireSha256(dataMinimizationPolicySha256, "data minimization policy hash")
        requireSha256(redactionPolicySha256, "redaction policy hash")
        requireSha256(policySha256, "reasoning policy hash")
        requireReasoningId(providerPolicyAttestationId, "provider policy attestation id")
        require(evidenceRetrievalMode == EvidenceRetrievalMode.CURATED_EVIDENCE_BACKEND_ONLY)
        require(personaPreferencesSeparateFromHealthRecord)
        require(issuedAtEpochMillis > 0L)
        require(expiresAtEpochMillis > issuedAtEpochMillis)
    }
}

fun interface ReasoningPrivacyReceiptSigner {
    fun sign(canonicalPayload: ByteArray): ByteArray
}

fun interface ReasoningPrivacyReceiptSignatureVerifier {
    fun verify(signingKeyId: String, canonicalPayload: ByteArray, signature: ByteArray): Boolean
}

/** Authenticated immutable privacy receipt; public callers cannot construct one directly. */
class ReasoningPrivacyReceipt internal constructor(
    draft: ReasoningPrivacyReceiptDraft,
    val signingKeyId: String,
    canonicalPayload: ByteArray,
    signature: ByteArray,
) {
    internal val draft = draft.copy(transmittedFields = immutableProviderSet(draft.transmittedFields))
    private val canonicalPayload = canonicalPayload.copyOf()
    private val signature = signature.copyOf()

    val receiptId: String get() = draft.receiptId
    val boundInputSnapshotSha256: String get() = draft.boundInputSnapshotSha256
    val consentGeneration: Long get() = draft.consentGeneration
    val consentReceiptSha256: String get() = draft.consentReceiptSha256
    val purpose: ReasoningPurpose get() = draft.purpose
    val payloadClass: ProviderPayloadClass get() = draft.payloadClass
    val transmittedFields: Set<ProviderPayloadField> get() = immutableProviderSet(draft.transmittedFields)
    val dataMinimizationPolicySha256: String get() = draft.dataMinimizationPolicySha256
    val redactionPolicySha256: String get() = draft.redactionPolicySha256
    val policySha256: String get() = draft.policySha256
    val residencyRegion: ResidencyRegion get() = draft.residencyRegion
    val retentionMode: ProviderRetentionMode get() = draft.retentionMode
    val providerPolicyAttestationId: String get() = draft.providerPolicyAttestationId
    val evidenceRetrievalMode: EvidenceRetrievalMode get() = draft.evidenceRetrievalMode
    val personaPreferencesSeparateFromHealthRecord: Boolean
        get() = draft.personaPreferencesSeparateFromHealthRecord
    val issuedAtEpochMillis: Long get() = draft.issuedAtEpochMillis
    val expiresAtEpochMillis: Long get() = draft.expiresAtEpochMillis

    init {
        requireReasoningId(signingKeyId, "privacy receipt signing key id")
        require(canonicalPayload.isNotEmpty() && canonicalPayload.size <= 64 * 1024)
        require(signature.isNotEmpty() && signature.size <= 8 * 1024)
    }

    fun canonicalPayloadBytes(): ByteArray = canonicalPayload.copyOf()

    fun signatureBytes(): ByteArray = signature.copyOf()
}

class ReasoningPrivacyReceiptIssuer(
    private val signingKeyId: String,
    private val signer: ReasoningPrivacyReceiptSigner,
) {
    init {
        requireReasoningId(signingKeyId, "privacy receipt signing key id")
    }

    fun issue(draft: ReasoningPrivacyReceiptDraft): ReasoningPrivacyReceipt {
        val canonical = CanonicalReasoningPrivacyReceipt.encode(draft, signingKeyId)
        return ReasoningPrivacyReceipt(
            draft = draft,
            signingKeyId = signingKeyId,
            canonicalPayload = canonical,
            signature = signer.sign(canonical.copyOf()).copyOf(),
        )
    }
}

enum class ReasoningPrivacyReceiptFailureCode {
    CANONICAL_PAYLOAD_MISMATCH,
    SIGNATURE_INVALID,
    NOT_YET_VALID,
    EXPIRED,
    TTL_EXCEEDED,
}

class ReasoningPrivacyReceiptException(
    val failureCode: ReasoningPrivacyReceiptFailureCode,
) : IllegalStateException("Reasoning privacy receipt rejected: ${failureCode.name}")

class VerifiedReasoningPrivacyReceipt internal constructor(
    val receipt: ReasoningPrivacyReceipt,
    val canonicalPayloadSha256: String,
) {
    init {
        requireSha256(canonicalPayloadSha256, "privacy receipt canonical hash")
    }
}

class ReasoningPrivacyReceiptAuthority(
    private val signatureVerifier: ReasoningPrivacyReceiptSignatureVerifier,
    private val nowEpochMillis: () -> Long,
    private val maxTtlMillis: Long = 15L * 60L * 1_000L,
) {
    init {
        require(maxTtlMillis in 1L..(24L * 60L * 60L * 1_000L))
    }

    fun verify(receipt: ReasoningPrivacyReceipt): VerifiedReasoningPrivacyReceipt {
        val supplied = receipt.canonicalPayloadBytes()
        val recomputed = CanonicalReasoningPrivacyReceipt.encode(receipt.draft, receipt.signingKeyId)
        if (!MessageDigest.isEqual(supplied, recomputed)) {
            rejected(ReasoningPrivacyReceiptFailureCode.CANONICAL_PAYLOAD_MISMATCH)
        }
        val signatureValid = try {
            signatureVerifier.verify(
                receipt.signingKeyId,
                supplied.copyOf(),
                receipt.signatureBytes(),
            )
        } catch (_: Exception) {
            false
        }
        if (!signatureValid) rejected(ReasoningPrivacyReceiptFailureCode.SIGNATURE_INVALID)
        val now = nowEpochMillis()
        if (now < receipt.issuedAtEpochMillis) rejected(ReasoningPrivacyReceiptFailureCode.NOT_YET_VALID)
        if (now >= receipt.expiresAtEpochMillis) rejected(ReasoningPrivacyReceiptFailureCode.EXPIRED)
        val ttl = try {
            Math.subtractExact(receipt.expiresAtEpochMillis, receipt.issuedAtEpochMillis)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        if (ttl > maxTtlMillis) rejected(ReasoningPrivacyReceiptFailureCode.TTL_EXCEEDED)
        return VerifiedReasoningPrivacyReceipt(receipt, sha256Hex(supplied))
    }

    private fun rejected(code: ReasoningPrivacyReceiptFailureCode): Nothing =
        throw ReasoningPrivacyReceiptException(code)
}

enum class AssistantTone { CALM, DIRECT, TECHNICAL }

enum class AssistantDetail { CONCISE, BALANCED, DETAILED }

enum class AssistantQuestionStyle { ONE_AT_A_TIME, GROUPED }

/**
 * User-controlled communication preferences only. There are deliberately no
 * name, symptom, diagnosis, medication, demographic or free-text fields.
 */
data class AssistantPersonaPreferences(
    val preferenceVersion: String,
    val tone: AssistantTone,
    val detail: AssistantDetail,
    val questionStyle: AssistantQuestionStyle,
    val readNumbersAloud: Boolean,
    val updatedAtEpochMillis: Long,
) {
    init {
        requireReasoningId(preferenceVersion, "persona preference version")
        require(updatedAtEpochMillis > 0L)
    }

    fun canonicalSha256(): String = sha256Hex(
        CanonicalRecord().apply {
            field(1, strictUtf8("VITALSIGNAL_PERSONA_PREFERENCES_V1"))
            field(2, strictUtf8(preferenceVersion))
            field(3, strictUtf8(tone.name))
            field(4, strictUtf8(detail.name))
            field(5, strictUtf8(questionStyle.name))
            field(6, byteArrayOf(if (readNumbersAloud) 1 else 0))
            field(7, longBytes(updatedAtEpochMillis))
        }.bytes(),
    )
}

data class PinnedProviderModel(
    val provider: ReasoningProvider,
    /** Exact provider snapshot/tag. Mutable aliases are forbidden by release policy. */
    val snapshotId: String,
    val modelManifestSha256: String,
    val runtimeVersion: String,
) {
    init {
        requireReasoningId(snapshotId, "provider model snapshot")
        requireSha256(modelManifestSha256, "provider model manifest hash")
        require(runtimeVersion.isNotBlank() && strictUtf8(runtimeVersion).size <= 256)
    }
}

data class ProviderRuntimeLimits(
    val connectTimeoutMillis: Int = 5_000,
    val overallTimeoutMillis: Int = 45_000,
    val maxRequestBodyBytes: Int = 256 * 1024,
    val maxResponseBodyBytes: Int = 256 * 1024,
    val maxRequestsPerMinute: Int = 10,
) {
    init {
        require(connectTimeoutMillis in 1..30_000)
        require(overallTimeoutMillis in connectTimeoutMillis..120_000)
        require(maxRequestBodyBytes in 1_024..(1024 * 1024))
        require(maxResponseBodyBytes in 1_024..(1024 * 1024))
        require(maxRequestsPerMinute in 1..600)
    }
}

data class ProviderReasoningConfig(
    val model: PinnedProviderModel,
    val gatewayRouteId: String,
    val systemPrompt: PinnedUtf8Document = OllamaReasoningProtocol.systemPrompt(),
    val strictJsonSchema: PinnedUtf8Document = OllamaReasoningProtocol.jsonSchema(),
    val runtimeLimits: ProviderRuntimeLimits = ProviderRuntimeLimits(),
) {
    init {
        requireReasoningId(gatewayRouteId, "assistant gateway route id")
        require(strictJsonSchema.text.contains("\"additionalProperties\": false"))
        require(!strictJsonSchema.text.contains("subjectPseudonym", ignoreCase = true))
        require(!strictJsonSchema.text.contains("patientName", ignoreCase = true))
        require(!strictJsonSchema.text.contains("healthRecord", ignoreCase = true))
    }
}

/**
 * Credential-free DTO delivered to an injected VitalSignal backend transport.
 * The transport implementation owns provider credentials and must enforce the
 * supplied timeout/size contract. Provider tools, browsing and state storage
 * are disabled by construction.
 */
class ProviderWireRequest internal constructor(
    val provider: ReasoningProvider,
    val gatewayRouteId: String,
    val modelSnapshotId: String,
    val modelManifestSha256: String,
    val executionMode: ProviderExecutionMode,
    val purpose: ReasoningPurpose,
    val payloadClass: ProviderPayloadClass,
    val consentGeneration: Long,
    val privacyReceiptId: String,
    val idempotencyKey: String,
    val requestSha256: String,
    val inputSnapshotSha256: String,
    val promptSha256: String,
    val jsonSchemaSha256: String,
    val policySha256: String,
    val providerPolicyAttestationSha256: String,
    val privacyReceiptCanonicalSha256: String,
    val personaPreferencesSha256: String,
    minimizedPromptUtf8: ByteArray,
    strictJsonSchemaUtf8: ByteArray,
    val connectTimeoutMillis: Int,
    val overallTimeoutMillis: Int,
    val maxResponseBodyBytes: Int,
) {
    private val minimizedPrompt = minimizedPromptUtf8.copyOf()
    private val strictJsonSchema = strictJsonSchemaUtf8.copyOf()

    val storeResponse: Boolean get() = false
    val backgroundMode: Boolean get() = false
    val strictStructuredOutput: Boolean get() = true
    val providerBrowsingEnabled: Boolean get() = false
    val providerToolsEnabled: Boolean get() = false
    val credentialsIncluded: Boolean get() = false
    val evidenceRetrievalMode: EvidenceRetrievalMode
        get() = EvidenceRetrievalMode.CURATED_EVIDENCE_BACKEND_ONLY

    init {
        requireReasoningId(gatewayRouteId, "assistant gateway route id")
        requireReasoningId(modelSnapshotId, "provider model snapshot")
        require(consentGeneration > 0L)
        requireReasoningId(privacyReceiptId, "privacy receipt id")
        requireReasoningId(idempotencyKey, "provider idempotency key")
        requireSha256(requestSha256, "provider request hash")
        requireSha256(inputSnapshotSha256, "provider input snapshot hash")
        requireSha256(promptSha256, "provider prompt hash")
        requireSha256(jsonSchemaSha256, "provider schema hash")
        requireSha256(policySha256, "provider policy hash")
        requireSha256(providerPolicyAttestationSha256, "provider policy attestation hash")
        requireSha256(privacyReceiptCanonicalSha256, "privacy receipt canonical hash")
        requireSha256(personaPreferencesSha256, "persona preferences hash")
        require(minimizedPrompt.isNotEmpty())
        require(strictJsonSchema.isNotEmpty())
        require(connectTimeoutMillis > 0)
        require(overallTimeoutMillis >= connectTimeoutMillis)
        require(maxResponseBodyBytes > 0)
    }

    val minimizedPromptSizeBytes: Int get() = minimizedPrompt.size

    fun minimizedPromptBytes(): ByteArray = minimizedPrompt.copyOf()

    fun strictJsonSchemaBytes(): ByteArray = strictJsonSchema.copyOf()
}

enum class ProviderRefusalCode {
    SAFETY_REFUSAL,
    POLICY_REFUSAL,
    INSUFFICIENT_CONTEXT,
    CAPACITY_UNAVAILABLE,
}

data class ProviderWireResponseDraft(
    val requestSha256: String,
    val providerRequestIdSha256: String,
    val provider: ReasoningProvider,
    val modelSnapshotId: String,
    val modelManifestSha256: String,
    val promptSha256: String,
    val jsonSchemaSha256: String,
    val policySha256: String,
    val strictStructuredOutputValidated: Boolean,
    val responseBodySizeBytes: Int,
    val candidate: LocalReasoningCandidate?,
    val refusalCode: ProviderRefusalCode?,
    val completedAtEpochMillis: Long,
) {
    init {
        requireSha256(requestSha256, "provider response request hash")
        requireSha256(providerRequestIdSha256, "provider request id hash")
        requireReasoningId(modelSnapshotId, "provider response model snapshot")
        requireSha256(modelManifestSha256, "provider response model manifest hash")
        requireSha256(promptSha256, "provider response prompt hash")
        requireSha256(jsonSchemaSha256, "provider response schema hash")
        requireSha256(policySha256, "provider response policy hash")
        require(responseBodySizeBytes >= 0)
        require((candidate == null) == (refusalCode != null))
        require(completedAtEpochMillis > 0L)
    }
}

fun interface ProviderGatewayResponseSigner {
    fun sign(canonicalPayload: ByteArray): ByteArray
}

fun interface ProviderGatewayResponseSignatureVerifier {
    fun verify(signingKeyId: String, canonicalPayload: ByteArray, signature: ByteArray): Boolean
}

class ProviderWireResponse internal constructor(
    draft: ProviderWireResponseDraft,
    val gatewaySigningKeyId: String,
    canonicalPayload: ByteArray,
    signature: ByteArray,
) {
    internal val draft = draft.deepCopy()
    private val canonicalPayload = canonicalPayload.copyOf()
    private val signature = signature.copyOf()

    init {
        requireReasoningId(gatewaySigningKeyId, "gateway response signing key id")
        require(canonicalPayload.isNotEmpty() && canonicalPayload.size <= 512 * 1024)
        require(signature.isNotEmpty() && signature.size <= 8 * 1024)
    }

    fun canonicalPayloadBytes(): ByteArray = canonicalPayload.copyOf()

    fun signatureBytes(): ByteArray = signature.copyOf()
}

class ProviderWireResponseIssuer(
    private val gatewaySigningKeyId: String,
    private val signer: ProviderGatewayResponseSigner,
) {
    init {
        requireReasoningId(gatewaySigningKeyId, "gateway response signing key id")
    }

    fun issue(draft: ProviderWireResponseDraft): ProviderWireResponse {
        val canonical = CanonicalProviderWireResponse.encode(draft, gatewaySigningKeyId)
        return ProviderWireResponse(
            draft = draft,
            gatewaySigningKeyId = gatewaySigningKeyId,
            canonicalPayload = canonical,
            signature = signer.sign(canonical.copyOf()).copyOf(),
        )
    }
}

enum class ProviderGatewayResponseFailureCode {
    CANONICAL_PAYLOAD_MISMATCH,
    SIGNATURE_INVALID,
}

class ProviderGatewayResponseException(
    val failureCode: ProviderGatewayResponseFailureCode,
) : IllegalStateException("Provider gateway response rejected: ${failureCode.name}")

class VerifiedProviderWireResponse internal constructor(
    draft: ProviderWireResponseDraft,
    val gatewayResponseCanonicalSha256: String,
) {
    private val draft = draft.deepCopy()

    val requestSha256: String get() = draft.requestSha256
    val providerRequestIdSha256: String get() = draft.providerRequestIdSha256
    val provider: ReasoningProvider get() = draft.provider
    val modelSnapshotId: String get() = draft.modelSnapshotId
    val modelManifestSha256: String get() = draft.modelManifestSha256
    val promptSha256: String get() = draft.promptSha256
    val jsonSchemaSha256: String get() = draft.jsonSchemaSha256
    val policySha256: String get() = draft.policySha256
    val strictStructuredOutputValidated: Boolean get() = draft.strictStructuredOutputValidated
    val responseBodySizeBytes: Int get() = draft.responseBodySizeBytes
    val candidate: LocalReasoningCandidate? get() = draft.candidate?.deepCopyForProvider()
    val refusalCode: ProviderRefusalCode? get() = draft.refusalCode
    val completedAtEpochMillis: Long get() = draft.completedAtEpochMillis

    init {
        requireSha256(gatewayResponseCanonicalSha256, "gateway response canonical hash")
    }
}

class ProviderGatewayResponseAuthority(
    private val signatureVerifier: ProviderGatewayResponseSignatureVerifier,
) {
    fun verify(response: ProviderWireResponse): VerifiedProviderWireResponse {
        val supplied = response.canonicalPayloadBytes()
        val recomputed = CanonicalProviderWireResponse.encode(response.draft, response.gatewaySigningKeyId)
        if (!MessageDigest.isEqual(supplied, recomputed)) {
            rejected(ProviderGatewayResponseFailureCode.CANONICAL_PAYLOAD_MISMATCH)
        }
        val signatureValid = try {
            signatureVerifier.verify(
                response.gatewaySigningKeyId,
                supplied.copyOf(),
                response.signatureBytes(),
            )
        } catch (_: Exception) {
            false
        }
        if (!signatureValid) rejected(ProviderGatewayResponseFailureCode.SIGNATURE_INVALID)
        return VerifiedProviderWireResponse(response.draft, sha256Hex(supplied))
    }

    private fun rejected(code: ProviderGatewayResponseFailureCode): Nothing =
        throw ProviderGatewayResponseException(code)
}

fun interface ProviderReasoningTransport {
    fun execute(request: ProviderWireRequest): ProviderWireResponse
}

data class ProviderRunReceipt(
    val provider: ReasoningProvider,
    val modelSnapshotId: String,
    val modelManifestSha256: String,
    val runtimeVersion: String,
    val inputSnapshotSha256: String,
    val requestSha256: String,
    val providerRequestIdSha256: String,
    val promptSha256: String,
    val jsonSchemaSha256: String,
    val policySha256: String,
    val providerPolicyAttestationSha256: String,
    val privacyReceiptCanonicalSha256: String,
    val gatewayResponseCanonicalSha256: String,
    val privacyReceiptId: String,
    val consentGeneration: Long,
    val purpose: ReasoningPurpose,
    val payloadClass: ProviderPayloadClass,
    val executionMode: ProviderExecutionMode,
    val strictStructuredOutputValidated: Boolean,
    val storeResponse: Boolean,
    val providerBrowsingEnabled: Boolean,
    val providerToolsEnabled: Boolean,
    val completedAtEpochMillis: Long,
) {
    init {
        requireReasoningId(modelSnapshotId, "run receipt model snapshot")
        requireSha256(modelManifestSha256, "run receipt model manifest hash")
        require(runtimeVersion.isNotBlank())
        requireSha256(inputSnapshotSha256, "run receipt input snapshot hash")
        requireSha256(requestSha256, "run receipt request hash")
        requireSha256(providerRequestIdSha256, "run receipt provider request id hash")
        requireSha256(promptSha256, "run receipt prompt hash")
        requireSha256(jsonSchemaSha256, "run receipt schema hash")
        requireSha256(policySha256, "run receipt policy hash")
        requireSha256(providerPolicyAttestationSha256, "run receipt provider policy hash")
        requireSha256(privacyReceiptCanonicalSha256, "run receipt privacy canonical hash")
        requireSha256(gatewayResponseCanonicalSha256, "run receipt gateway response canonical hash")
        requireReasoningId(privacyReceiptId, "run receipt privacy receipt id")
        require(consentGeneration > 0L)
        require(strictStructuredOutputValidated)
        require(!storeResponse)
        require(!providerBrowsingEnabled)
        require(!providerToolsEnabled)
        require(completedAtEpochMillis > 0L)
    }
}

private object CanonicalProviderPolicyAttestation {
    fun encode(draft: ProviderPolicyAttestationDraft, signingKeyId: String): ByteArray =
        CanonicalRecord().apply {
            field(1, strictUtf8("VITALSIGNAL_PROVIDER_POLICY_ATTESTATION_V1"))
            field(2, strictUtf8(draft.attestationId))
            field(3, strictUtf8(signingKeyId))
            field(4, strictUtf8(draft.provider.name))
            field(5, strictUtf8(draft.providerAccountPolicyId))
            field(6, stringListBytes(draft.allowedPurposes.map { it.name }.sorted()))
            field(7, strictUtf8(draft.retentionMode.name))
            field(8, strictUtf8(draft.residencyRegion.name))
            field(9, strictUtf8(draft.evidenceBasis.name))
            field(10, strictUtf8(draft.evidenceSha256))
            field(11, byteArrayOf(if (draft.providerTrainingUseDisabled) 1 else 0))
            field(12, byteArrayOf(if (draft.strictStructuredOutputEnabled) 1 else 0))
            field(13, byteArrayOf(if (draft.providerBrowsingDisabled) 1 else 0))
            field(14, byteArrayOf(if (draft.providerToolsDisabled) 1 else 0))
            field(15, strictUtf8(draft.credentialBoundary.name))
            field(16, strictUtf8(draft.openAiStoreResponse?.toString() ?: "NOT_APPLICABLE"))
            field(
                17,
                strictUtf8(draft.anthropicSchemaDefinitionDataClass?.name ?: "NOT_APPLICABLE"),
            )
            field(18, strictUtf8(draft.policySha256))
            field(19, longBytes(draft.attestedAtEpochMillis))
            field(20, longBytes(draft.expiresAtEpochMillis))
        }.bytes()
}

private object CanonicalReasoningPrivacyReceipt {
    fun encode(draft: ReasoningPrivacyReceiptDraft, signingKeyId: String): ByteArray =
        CanonicalRecord().apply {
            field(1, strictUtf8("VITALSIGNAL_REASONING_PRIVACY_RECEIPT_V1"))
            field(2, strictUtf8(draft.receiptId))
            field(3, strictUtf8(signingKeyId))
            field(4, strictUtf8(draft.boundInputSnapshotSha256))
            field(5, longBytes(draft.consentGeneration))
            field(6, strictUtf8(draft.consentReceiptSha256))
            field(7, strictUtf8(draft.purpose.name))
            field(8, strictUtf8(draft.payloadClass.name))
            field(9, stringListBytes(draft.transmittedFields.map { it.name }.sorted()))
            field(10, strictUtf8(draft.dataMinimizationPolicySha256))
            field(11, strictUtf8(draft.redactionPolicySha256))
            field(12, strictUtf8(draft.policySha256))
            field(13, strictUtf8(draft.residencyRegion.name))
            field(14, strictUtf8(draft.retentionMode.name))
            field(15, strictUtf8(draft.providerPolicyAttestationId))
            field(16, strictUtf8(draft.evidenceRetrievalMode.name))
            field(17, byteArrayOf(if (draft.personaPreferencesSeparateFromHealthRecord) 1 else 0))
            field(18, longBytes(draft.issuedAtEpochMillis))
            field(19, longBytes(draft.expiresAtEpochMillis))
        }.bytes()
}

private object CanonicalProviderWireResponse {
    fun encode(draft: ProviderWireResponseDraft, signingKeyId: String): ByteArray =
        CanonicalRecord().apply {
            field(1, strictUtf8("VITALSIGNAL_PROVIDER_GATEWAY_RESPONSE_V1"))
            field(2, strictUtf8(signingKeyId))
            field(3, strictUtf8(draft.requestSha256))
            field(4, strictUtf8(draft.providerRequestIdSha256))
            field(5, strictUtf8(draft.provider.name))
            field(6, strictUtf8(draft.modelSnapshotId))
            field(7, strictUtf8(draft.modelManifestSha256))
            field(8, strictUtf8(draft.promptSha256))
            field(9, strictUtf8(draft.jsonSchemaSha256))
            field(10, strictUtf8(draft.policySha256))
            field(11, byteArrayOf(if (draft.strictStructuredOutputValidated) 1 else 0))
            field(12, longBytes(draft.responseBodySizeBytes.toLong()))
            field(13, draft.candidate?.let(CanonicalReasoningCandidate::bytes) ?: ByteArray(0))
            field(14, strictUtf8(draft.refusalCode?.name ?: "NONE"))
            field(15, longBytes(draft.completedAtEpochMillis))
        }.bytes()
}

private fun ProviderWireResponseDraft.deepCopy(): ProviderWireResponseDraft = copy(
    candidate = candidate?.deepCopyForProvider(),
)

internal fun LocalReasoningCandidate.deepCopyForProvider(): LocalReasoningCandidate = copy(
    claims = claims.map { claim ->
        claim.copy(
            metricReferenceIds = claim.metricReferenceIds.toList(),
            evidenceReferenceIds = claim.evidenceReferenceIds.toList(),
            disconfirmingEvidenceReferenceIds = claim.disconfirmingEvidenceReferenceIds.toList(),
        )
    },
    nextMeasurementIds = nextMeasurementIds.toList(),
    questionIdsForUser = questionIdsForUser.toList(),
)

private fun <T> immutableProviderSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))
