package au.com.elied.vitalsignal.reasoning

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object ProviderReasoningTestFixtures {
    const val NOW = 10_000L
    const val SIGNING_KEY_ID = "provider-policy-test-key-v1"
    const val PRIVACY_SIGNING_KEY_ID = "privacy-receipt-test-key-v1"
    const val GATEWAY_SIGNING_KEY_ID = "assistant-gateway-test-key-v1"
    private val policyKey = "provider-policy-test-key-material".toByteArray()
    private val privacyKey = "privacy-receipt-test-key-material".toByteArray()
    private val gatewayKey = "assistant-gateway-test-key-material".toByteArray()

    fun attestationSigner(key: ByteArray = policyKey.copyOf()) =
        ProviderPolicyAttestationSigner { payload -> hmac(key, payload) }

    fun attestationVerifier(key: ByteArray = policyKey.copyOf()) =
        ProviderPolicyAttestationSignatureVerifier { keyId, payload, signature ->
            keyId == SIGNING_KEY_ID &&
                java.security.MessageDigest.isEqual(hmac(key, payload), signature)
        }

    fun attestationAuthority(now: () -> Long = { NOW }) =
        ProviderPolicyAttestationAuthority(attestationVerifier(), now)

    fun policyDraft(
        provider: ReasoningProvider,
        retentionMode: ProviderRetentionMode = when (provider) {
            ReasoningProvider.OLLAMA_LOCAL -> ProviderRetentionMode.LOCAL_ONLY
            else -> ProviderRetentionMode.PROVIDER_STANDARD
        },
        allowedPurposes: Set<ReasoningPurpose> = setOf(ReasoningPurpose.EXPLAIN_VERIFIED_HEALTH_STATE),
        evidenceBasis: AttestationEvidenceBasis = when {
            provider == ReasoningProvider.OLLAMA_LOCAL ->
                AttestationEvidenceBasis.LOCAL_OPERATOR_CONFIGURATION
            retentionMode == ProviderRetentionMode.ZERO_DATA_RETENTION ||
                retentionMode == ProviderRetentionMode.MODIFIED_ABUSE_MONITORING ->
                AttestationEvidenceBasis.PROVIDER_ADMIN_EVIDENCE
            else -> AttestationEvidenceBasis.PUBLIC_POLICY_AND_TENANT_REVIEW
        },
    ) = ProviderPolicyAttestationDraft(
        attestationId = "attestation-${provider.name.lowercase()}",
        provider = provider,
        providerAccountPolicyId = "account-policy-v1",
        allowedPurposes = allowedPurposes,
        retentionMode = retentionMode,
        residencyRegion = if (provider == ReasoningProvider.OLLAMA_LOCAL) {
            ResidencyRegion.LOCAL_PRIVATE_NETWORK
        } else {
            ResidencyRegion.AUSTRALIA
        },
        evidenceBasis = evidenceBasis,
        evidenceSha256 = "a".repeat(64),
        providerTrainingUseDisabled = true,
        strictStructuredOutputEnabled = true,
        providerBrowsingDisabled = true,
        providerToolsDisabled = true,
        credentialBoundary = GatewayCredentialBoundary.VITALSIGNAL_BACKEND_ONLY,
        openAiStoreResponse = if (provider == ReasoningProvider.OPENAI_RESPONSES) false else null,
        anthropicSchemaDefinitionDataClass =
            if (provider == ReasoningProvider.ANTHROPIC_MESSAGES) {
                SchemaDefinitionDataClass.GENERIC_NO_PERSONAL_DATA
            } else {
                null
            },
        policySha256 = "c".repeat(64),
        attestedAtEpochMillis = 5_000L,
        expiresAtEpochMillis = 20_000L,
    )

    fun attestation(draft: ProviderPolicyAttestationDraft): SignedProviderPolicyAttestation =
        ProviderPolicyAttestationIssuer(SIGNING_KEY_ID, attestationSigner()).issue(draft)

    fun config(provider: ReasoningProvider) = ProviderReasoningConfig(
        model = PinnedProviderModel(
            provider = provider,
            snapshotId = when (provider) {
                ReasoningProvider.OLLAMA_LOCAL -> "fixture-model:q4"
                ReasoningProvider.OPENAI_RESPONSES -> "openai-snapshot-2026-08-01"
                ReasoningProvider.ANTHROPIC_MESSAGES -> "anthropic-snapshot-2026-08-01"
            },
            modelManifestSha256 = "b".repeat(64),
            runtimeVersion = "fixture-runtime-1",
        ),
        gatewayRouteId = "assistant-gateway-v1",
        runtimeLimits = ProviderRuntimeLimits(
            connectTimeoutMillis = 1_000,
            overallTimeoutMillis = 5_000,
            maxRequestBodyBytes = 256 * 1024,
            maxResponseBodyBytes = 8_192,
            maxRequestsPerMinute = 10,
        ),
    )

    fun privacyReceiptDraft(
        packet: SignedHealthStatePacket,
        draft: ProviderPolicyAttestationDraft,
        payloadClass: ProviderPayloadClass = ProviderPayloadClass.SYNTHETIC_FIXTURE,
        purpose: ReasoningPurpose = ReasoningPurpose.EXPLAIN_VERIFIED_HEALTH_STATE,
    ) = ReasoningPrivacyReceiptDraft(
        receiptId = "privacy-receipt-v1",
        boundInputSnapshotSha256 = packet.canonicalPayloadSha256(),
        consentGeneration = 3L,
        consentReceiptSha256 = "d".repeat(64),
        purpose = purpose,
        payloadClass = payloadClass,
        transmittedFields = MINIMIZED_PROVIDER_FIELDS,
        dataMinimizationPolicySha256 = "e".repeat(64),
        redactionPolicySha256 = "f".repeat(64),
        policySha256 = draft.policySha256,
        residencyRegion = draft.residencyRegion,
        retentionMode = draft.retentionMode,
        providerPolicyAttestationId = draft.attestationId,
        evidenceRetrievalMode = EvidenceRetrievalMode.CURATED_EVIDENCE_BACKEND_ONLY,
        personaPreferencesSeparateFromHealthRecord = true,
        issuedAtEpochMillis = 9_000L,
        expiresAtEpochMillis = 15_000L,
    )

    fun privacyReceipt(
        packet: SignedHealthStatePacket,
        draft: ProviderPolicyAttestationDraft,
        payloadClass: ProviderPayloadClass = ProviderPayloadClass.SYNTHETIC_FIXTURE,
        purpose: ReasoningPurpose = ReasoningPurpose.EXPLAIN_VERIFIED_HEALTH_STATE,
    ): ReasoningPrivacyReceipt = ReasoningPrivacyReceiptIssuer(
        PRIVACY_SIGNING_KEY_ID,
        ReasoningPrivacyReceiptSigner { payload -> hmac(privacyKey, payload) },
    ).issue(privacyReceiptDraft(packet, draft, payloadClass, purpose))

    fun privacyReceiptAuthority(
        now: () -> Long = { NOW },
        validKey: Boolean = true,
    ) = ReasoningPrivacyReceiptAuthority(
        signatureVerifier = ReasoningPrivacyReceiptSignatureVerifier { keyId, payload, signature ->
            val key = if (validKey) privacyKey else "wrong-privacy-key".toByteArray()
            keyId == PRIVACY_SIGNING_KEY_ID &&
                java.security.MessageDigest.isEqual(hmac(key, payload), signature)
        },
        nowEpochMillis = now,
    )

    fun gatewayResponseSigner(key: ByteArray = gatewayKey.copyOf()) =
        ProviderGatewayResponseSigner { payload -> hmac(key, payload) }

    fun gatewayResponseAuthority(validKey: Boolean = true) = ProviderGatewayResponseAuthority(
        ProviderGatewayResponseSignatureVerifier { keyId, payload, signature ->
            val key = if (validKey) gatewayKey else "wrong-gateway-key".toByteArray()
            keyId == GATEWAY_SIGNING_KEY_ID &&
                java.security.MessageDigest.isEqual(hmac(key, payload), signature)
        },
    )

    fun persona() = AssistantPersonaPreferences(
        preferenceVersion = "persona-v1",
        tone = AssistantTone.CALM,
        detail = AssistantDetail.BALANCED,
        questionStyle = AssistantQuestionStyle.ONE_AT_A_TIME,
        readNumbersAloud = false,
        updatedAtEpochMillis = 8_000L,
    )

    fun invocation(
        packet: SignedHealthStatePacket,
        provider: ReasoningProvider = ReasoningProvider.OPENAI_RESPONSES,
        retentionMode: ProviderRetentionMode = when (provider) {
            ReasoningProvider.OLLAMA_LOCAL -> ProviderRetentionMode.LOCAL_ONLY
            else -> ProviderRetentionMode.PROVIDER_STANDARD
        },
        payloadClass: ProviderPayloadClass = ProviderPayloadClass.SYNTHETIC_FIXTURE,
        executionMode: ProviderExecutionMode = ProviderExecutionMode.PRIMARY_ADVISORY,
        purpose: ReasoningPurpose = if (executionMode == ProviderExecutionMode.SHADOW_CHALLENGER) {
            ReasoningPurpose.OFFLINE_SHADOW_EVALUATION
        } else {
            ReasoningPurpose.EXPLAIN_VERIFIED_HEALTH_STATE
        },
    ): ProviderInvocation {
        val draft = policyDraft(
            provider = provider,
            retentionMode = retentionMode,
            allowedPurposes = setOf(purpose),
        )
        return ProviderInvocation(
            config = config(provider),
            executionMode = executionMode,
            privacyReceipt = privacyReceipt(packet, draft, payloadClass, purpose),
            providerPolicyAttestation = attestation(draft),
            personaPreferences = persona(),
            idempotencyKey = "invocation-key-v1",
        )
    }

    fun successResponseDraft(
        request: ProviderWireRequest,
        packet: SignedHealthStatePacket,
        candidate: LocalReasoningCandidate = ReasoningTestFixtures.candidate(packet),
        completedAtEpochMillis: Long = NOW,
    ) = ProviderWireResponseDraft(
        requestSha256 = request.requestSha256,
        providerRequestIdSha256 = "9".repeat(64),
        provider = request.provider,
        modelSnapshotId = request.modelSnapshotId,
        modelManifestSha256 = request.modelManifestSha256,
        promptSha256 = request.promptSha256,
        jsonSchemaSha256 = request.jsonSchemaSha256,
        policySha256 = request.policySha256,
        strictStructuredOutputValidated = true,
        responseBodySizeBytes = 1_024,
        candidate = candidate,
        refusalCode = null,
        completedAtEpochMillis = completedAtEpochMillis,
    )

    fun signedResponse(draft: ProviderWireResponseDraft): ProviderWireResponse =
        ProviderWireResponseIssuer(GATEWAY_SIGNING_KEY_ID, gatewayResponseSigner()).issue(draft)

    fun successResponse(
        request: ProviderWireRequest,
        packet: SignedHealthStatePacket,
        candidate: LocalReasoningCandidate = ReasoningTestFixtures.candidate(packet),
        completedAtEpochMillis: Long = NOW,
    ): ProviderWireResponse = signedResponse(
        successResponseDraft(request, packet, candidate, completedAtEpochMillis),
    )

    fun orchestrator(
        packet: SignedHealthStatePacket,
        transport: ProviderReasoningTransport = ProviderReasoningTransport { request ->
            successResponse(request, packet)
        },
        now: () -> Long = { NOW },
        replayGuard: RecordingReplayGuard = RecordingReplayGuard(),
        rateAllowed: Boolean = true,
        circuit: RecordingCircuitBreaker = RecordingCircuitBreaker(),
        audit: RecordingProviderAuditSink = RecordingProviderAuditSink(),
        privacyReceiptValid: Boolean = true,
        currentConsentAuthorized: () -> Boolean = { true },
    ) = GovernedProviderReasoningOrchestrator(
        transport = transport,
        healthStateAuthority = ReasoningTestFixtures.authority(now = now),
        providerPolicyAuthority = attestationAuthority(now),
        privacyReceiptAuthority = privacyReceiptAuthority(now, validKey = privacyReceiptValid),
        currentConsentGate = CurrentReasoningConsentGate { currentConsentAuthorized() },
        gatewayResponseAuthority = gatewayResponseAuthority(),
        deterministicPolicy = LocalReasoningPolicy(),
        replayGuard = replayGuard,
        rateLimiter = ProviderRateLimiter { _, _, _, _ -> rateAllowed },
        circuitBreaker = circuit,
        auditSink = audit,
        nowEpochMillis = now,
    )

    private fun hmac(keyBytes: ByteArray, payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keyBytes.copyOf(), "HmacSHA256"))
            doFinal(payload.copyOf())
        }
}

internal class RecordingReplayGuard(
    var decision: ProviderReplayReservation = ProviderReplayReservation.ACQUIRED,
) : ProviderReplayGuard {
    val completed = mutableListOf<Pair<String, String>>()
    val failed = mutableListOf<Pair<String, String>>()

    override fun reserve(idempotencyKey: String, requestSha256: String): ProviderReplayReservation = decision

    override fun markCompleted(idempotencyKey: String, requestSha256: String) {
        completed += idempotencyKey to requestSha256
    }

    override fun markFailed(idempotencyKey: String, requestSha256: String) {
        failed += idempotencyKey to requestSha256
    }
}

internal class RecordingCircuitBreaker(
    var allowed: Boolean = true,
) : ProviderCircuitBreaker {
    var successes = 0
    var failures = 0

    override fun allow(provider: ReasoningProvider, nowEpochMillis: Long): Boolean = allowed

    override fun recordSuccess(provider: ReasoningProvider, atEpochMillis: Long) {
        successes += 1
    }

    override fun recordFailure(provider: ReasoningProvider, atEpochMillis: Long) {
        failures += 1
    }
}

internal class RecordingProviderAuditSink(
    private val commitSucceeds: Boolean = true,
) : ProviderReasoningAuditSink {
    val records = mutableListOf<ProviderReasoningAuditRecord>()

    override fun commit(record: ProviderReasoningAuditRecord): Boolean {
        records += record
        return commitSucceeds
    }
}
