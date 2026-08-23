package au.com.elied.vitalsignal.reasoning

enum class ProviderReplayReservation {
    ACQUIRED,
    DUPLICATE,
    CONFLICT,
}

/**
 * Implementations persist idempotency key + request hash. `markFailed` must
 * retain that binding so a conflicting replay can never reuse the key.
 */
interface ProviderReplayGuard {
    fun reserve(idempotencyKey: String, requestSha256: String): ProviderReplayReservation

    fun markCompleted(idempotencyKey: String, requestSha256: String)

    fun markFailed(idempotencyKey: String, requestSha256: String)
}

fun interface ProviderRateLimiter {
    fun tryAcquire(
        provider: ReasoningProvider,
        purpose: ReasoningPurpose,
        maxRequestsPerMinute: Int,
        nowEpochMillis: Long,
    ): Boolean
}

interface ProviderCircuitBreaker {
    fun allow(provider: ReasoningProvider, nowEpochMillis: Long): Boolean

    fun recordSuccess(provider: ReasoningProvider, atEpochMillis: Long)

    fun recordFailure(provider: ReasoningProvider, atEpochMillis: Long)
}

fun interface CurrentReasoningConsentGate {
    /** Checks the current authenticated consent ledger, including revocation/generation. */
    fun isAuthorized(receipt: ReasoningPrivacyReceipt): Boolean
}

data class ProviderInvocation(
    val config: ProviderReasoningConfig,
    val executionMode: ProviderExecutionMode,
    val privacyReceipt: ReasoningPrivacyReceipt,
    val providerPolicyAttestation: SignedProviderPolicyAttestation,
    val personaPreferences: AssistantPersonaPreferences,
    val idempotencyKey: String,
) {
    init {
        requireReasoningId(idempotencyKey, "provider idempotency key")
    }
}

enum class ProviderPolicyFailureCode {
    PROVIDER_ATTESTATION_INVALID,
    PROVIDER_MISMATCH,
    PURPOSE_NOT_ATTESTED,
    PURPOSE_MODE_MISMATCH,
    PRIVACY_RECEIPT_AUTHENTICATION_FAILED,
    PRIVACY_RECEIPT_EXPIRED,
    CURRENT_CONSENT_REJECTED,
    INPUT_SNAPSHOT_MISMATCH,
    POLICY_HASH_MISMATCH,
    PROVIDER_ATTESTATION_ID_MISMATCH,
    RETENTION_MISMATCH,
    RESIDENCY_MISMATCH,
    DATA_MINIMIZATION_MISMATCH,
    UNCURATED_RETRIEVAL,
    PERSONA_STORAGE_NOT_SEPARATE,
    REQUEST_TOO_LARGE,
}

enum class ProviderOrchestrationFailure {
    NONE,
    HEALTH_STATE_AUTHORITY_REJECTED,
    PROVIDER_POLICY_REJECTED,
    RATE_LIMITED,
    CIRCUIT_OPEN,
    REPLAY_REJECTED,
    TRANSPORT_FAILED,
    TIMEOUT,
    RESPONSE_TOO_LARGE,
    RESPONSE_BINDING_MISMATCH,
    STRUCTURED_OUTPUT_INVALID,
    GATEWAY_RESPONSE_AUTHENTICATION_FAILED,
    PROVIDER_REFUSAL,
    DETERMINISTIC_POLICY_REJECTED,
    AUDIT_COMMIT_FAILED,
}

enum class ProviderReasoningDeliveryState {
    VERIFIED,
    ABSTAINED,
    SAFE_FALLBACK,
    SHADOW_RECORDED,
}

class ProviderReasoningAuditRecord(
    val provider: ReasoningProvider,
    val executionMode: ProviderExecutionMode,
    val purpose: ReasoningPurpose,
    val payloadClass: ProviderPayloadClass,
    val privacyReceiptId: String,
    val consentGeneration: Long,
    val inputSnapshotSha256: String,
    val requestSha256: String?,
    val candidateSha256: String?,
    val runReceipt: ProviderRunReceipt?,
    val disposition: ReasoningDisposition,
    deterministicPolicyFailures: Set<ReasoningFailureCode>,
    providerPolicyFailures: Set<ProviderPolicyFailureCode>,
    val orchestrationFailure: ProviderOrchestrationFailure,
    val authorityFailureCode: HealthStateAuthorityFailureCode?,
    val providerAttestationFailureCode: ProviderPolicyAttestationFailureCode?,
    val privacyReceiptFailureCode: ReasoningPrivacyReceiptFailureCode?,
    val gatewayResponseFailureCode: ProviderGatewayResponseFailureCode?,
    val providerRefusalCode: ProviderRefusalCode?,
    val completedAtEpochMillis: Long,
    val orchestrationPolicyVersion: String = "governed-provider-reasoning-v1",
) {
    val deterministicPolicyFailures: Set<ReasoningFailureCode> =
        java.util.Set.copyOf(deterministicPolicyFailures)
    val providerPolicyFailures: Set<ProviderPolicyFailureCode> =
        java.util.Set.copyOf(providerPolicyFailures)

    init {
        requireReasoningId(privacyReceiptId, "provider audit privacy receipt id")
        require(consentGeneration > 0L)
        requireSha256(inputSnapshotSha256, "provider audit input snapshot hash")
        require(requestSha256 == null || requestSha256.matches(Regex("[a-f0-9]{64}")))
        require(candidateSha256 == null || candidateSha256.matches(Regex("[a-f0-9]{64}")))
        require(
            (orchestrationFailure == ProviderOrchestrationFailure.HEALTH_STATE_AUTHORITY_REJECTED) ==
                (authorityFailureCode != null),
        )
        require(
            (ProviderPolicyFailureCode.PROVIDER_ATTESTATION_INVALID in providerPolicyFailures) ==
                (providerAttestationFailureCode != null),
        )
        require(
            (ProviderPolicyFailureCode.PRIVACY_RECEIPT_AUTHENTICATION_FAILED in providerPolicyFailures) ==
                (privacyReceiptFailureCode != null),
        )
        require(
            (orchestrationFailure == ProviderOrchestrationFailure.GATEWAY_RESPONSE_AUTHENTICATION_FAILED) ==
                (gatewayResponseFailureCode != null),
        )
        require(
            (orchestrationFailure == ProviderOrchestrationFailure.PROVIDER_REFUSAL) ==
                (providerRefusalCode != null),
        )
        require(completedAtEpochMillis > 0L)
        require(orchestrationPolicyVersion.isNotBlank())
    }
}

fun interface ProviderReasoningAuditSink {
    /** True only after a durable encrypted append has committed. */
    fun commit(record: ProviderReasoningAuditRecord): Boolean
}

class VerifiedProviderReasoningOutcome internal constructor(
    val state: ProviderReasoningDeliveryState,
    /** Present only for a primary result that passed policy and durable audit. */
    candidate: LocalReasoningCandidate?,
    val runReceipt: ProviderRunReceipt?,
    deterministicPolicyFailures: Set<ReasoningFailureCode>,
    providerPolicyFailures: Set<ProviderPolicyFailureCode>,
    val orchestrationFailure: ProviderOrchestrationFailure,
    val authorityFailureCode: HealthStateAuthorityFailureCode?,
    val providerAttestationFailureCode: ProviderPolicyAttestationFailureCode?,
    val privacyReceiptFailureCode: ReasoningPrivacyReceiptFailureCode?,
    val gatewayResponseFailureCode: ProviderGatewayResponseFailureCode?,
    val providerRefusalCode: ProviderRefusalCode?,
    /** Digest only; challengers can be compared offline but never displayed. */
    val shadowCandidateSha256: String?,
    val safeTemplateId: String?,
) {
    private val candidateSnapshot = candidate?.deepCopyForProvider()
    val candidate: LocalReasoningCandidate? get() = candidateSnapshot?.deepCopyForProvider()
    val deterministicPolicyFailures: Set<ReasoningFailureCode> =
        java.util.Set.copyOf(deterministicPolicyFailures)
    val providerPolicyFailures: Set<ProviderPolicyFailureCode> =
        java.util.Set.copyOf(providerPolicyFailures)

    init {
        require((state == ProviderReasoningDeliveryState.VERIFIED) == (candidate != null))
        require((state == ProviderReasoningDeliveryState.SHADOW_RECORDED) == (shadowCandidateSha256 != null))
        require((state == ProviderReasoningDeliveryState.SAFE_FALLBACK) == (safeTemplateId != null))
        require(state != ProviderReasoningDeliveryState.SHADOW_RECORDED || candidate == null)
    }
}

/**
 * Governs local Ollama and remote OpenAI/Anthropic selectors through one exact
 * contract. Remote access is backend-only: this class has no URL, header, API
 * key, tool or browser field. It never generates or accepts free clinical
 * prose, a treatment, a diagnosis, a physiological number or an emergency
 * clearance.
 */
class GovernedProviderReasoningOrchestrator(
    private val transport: ProviderReasoningTransport,
    private val healthStateAuthority: HealthStatePacketAuthority,
    private val providerPolicyAuthority: ProviderPolicyAttestationAuthority,
    private val privacyReceiptAuthority: ReasoningPrivacyReceiptAuthority,
    private val currentConsentGate: CurrentReasoningConsentGate,
    private val gatewayResponseAuthority: ProviderGatewayResponseAuthority,
    private val deterministicPolicy: LocalReasoningPolicy,
    private val replayGuard: ProviderReplayGuard,
    private val rateLimiter: ProviderRateLimiter,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val auditSink: ProviderReasoningAuditSink,
    private val nowEpochMillis: () -> Long,
) {
    fun run(
        packet: SignedHealthStatePacket,
        invocation: ProviderInvocation,
    ): VerifiedProviderReasoningOutcome {
        val inputHash = packet.canonicalPayloadSha256()
        val request = try {
            healthStateAuthority.verify(packet)
        } catch (rejected: HealthStateAuthorityException) {
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                failure = ProviderOrchestrationFailure.HEALTH_STATE_AUTHORITY_REJECTED,
                authorityFailure = rejected.failureCode,
            )
        }

        val verifiedPrivacy = try {
            privacyReceiptAuthority.verify(invocation.privacyReceipt)
        } catch (rejected: ReasoningPrivacyReceiptException) {
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                failure = ProviderOrchestrationFailure.PROVIDER_POLICY_REJECTED,
                providerPolicyFailures = setOf(
                    ProviderPolicyFailureCode.PRIVACY_RECEIPT_AUTHENTICATION_FAILED,
                ),
                privacyReceiptFailure = rejected.failureCode,
            )
        }
        if (!consentStillAuthorized(invocation.privacyReceipt)) {
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                failure = ProviderOrchestrationFailure.PROVIDER_POLICY_REJECTED,
                providerPolicyFailures = setOf(ProviderPolicyFailureCode.CURRENT_CONSENT_REJECTED),
            )
        }

        val providerPolicy = try {
            providerPolicyAuthority.verify(invocation.providerPolicyAttestation)
        } catch (rejected: ProviderPolicyAttestationException) {
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                failure = ProviderOrchestrationFailure.PROVIDER_POLICY_REJECTED,
                providerPolicyFailures = setOf(ProviderPolicyFailureCode.PROVIDER_ATTESTATION_INVALID),
                providerAttestationFailure = rejected.failureCode,
            )
        }

        val policyFailures = validateInvocation(request, invocation, providerPolicy)
        if (policyFailures.isNotEmpty()) {
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                failure = ProviderOrchestrationFailure.PROVIDER_POLICY_REJECTED,
                providerPolicyFailures = policyFailures,
            )
        }

        val prompt = MinimizedProviderPrompt.render(request, invocation.personaPreferences)
        val promptBytes = strictUtf8(prompt)
        val schemaBytes = strictUtf8(invocation.config.strictJsonSchema.text)
        val requestBodyBytes = try {
            Math.addExact(promptBytes.size, schemaBytes.size)
        } catch (_: ArithmeticException) {
            Int.MAX_VALUE
        }
        if (requestBodyBytes > invocation.config.runtimeLimits.maxRequestBodyBytes) {
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                failure = ProviderOrchestrationFailure.PROVIDER_POLICY_REJECTED,
                providerPolicyFailures = setOf(ProviderPolicyFailureCode.REQUEST_TOO_LARGE),
            )
        }

        val promptSha256 = OllamaProtocolDigests.promptSha256(
            invocation.config.systemPrompt.text,
            prompt,
        )
        val providerPolicySha256 = providerPolicy.canonicalPayloadSha256
        val personaSha256 = invocation.personaPreferences.canonicalSha256()
        val requestSha256 = CanonicalProviderRequest.sha256(
            invocation = invocation,
            inputHash = inputHash,
            promptSha256 = promptSha256,
            providerPolicySha256 = providerPolicySha256,
            personaSha256 = personaSha256,
            promptBytes = promptBytes,
            schemaBytes = schemaBytes,
        )
        val wireRequest = ProviderWireRequest(
            provider = invocation.config.model.provider,
            gatewayRouteId = invocation.config.gatewayRouteId,
            modelSnapshotId = invocation.config.model.snapshotId,
            modelManifestSha256 = invocation.config.model.modelManifestSha256,
            executionMode = invocation.executionMode,
            purpose = invocation.privacyReceipt.purpose,
            payloadClass = invocation.privacyReceipt.payloadClass,
            consentGeneration = invocation.privacyReceipt.consentGeneration,
            privacyReceiptId = invocation.privacyReceipt.receiptId,
            idempotencyKey = invocation.idempotencyKey,
            requestSha256 = requestSha256,
            inputSnapshotSha256 = inputHash,
            promptSha256 = promptSha256,
            jsonSchemaSha256 = invocation.config.strictJsonSchema.sha256,
            policySha256 = invocation.privacyReceipt.policySha256,
            providerPolicyAttestationSha256 = providerPolicySha256,
            privacyReceiptCanonicalSha256 = verifiedPrivacy.canonicalPayloadSha256,
            personaPreferencesSha256 = personaSha256,
            minimizedPromptUtf8 = promptBytes,
            strictJsonSchemaUtf8 = schemaBytes,
            connectTimeoutMillis = invocation.config.runtimeLimits.connectTimeoutMillis,
            overallTimeoutMillis = invocation.config.runtimeLimits.overallTimeoutMillis,
            maxResponseBodyBytes = invocation.config.runtimeLimits.maxResponseBodyBytes,
        )

        val replay = try {
            replayGuard.reserve(invocation.idempotencyKey, requestSha256)
        } catch (_: Exception) {
            ProviderReplayReservation.CONFLICT
        }
        if (replay != ProviderReplayReservation.ACQUIRED) {
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.REPLAY_REJECTED,
            )
        }

        val startedAt = nowEpochMillis()
        val rateAllowed = try {
            rateLimiter.tryAcquire(
                provider = wireRequest.provider,
                purpose = wireRequest.purpose,
                maxRequestsPerMinute = invocation.config.runtimeLimits.maxRequestsPerMinute,
                nowEpochMillis = startedAt,
            )
        } catch (_: Exception) {
            false
        }
        if (!rateAllowed) {
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.RATE_LIMITED,
            )
        }

        val circuitAllowed = try {
            circuitBreaker.allow(wireRequest.provider, startedAt)
        } catch (_: Exception) {
            false
        }
        if (!circuitAllowed) {
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.CIRCUIT_OPEN,
            )
        }

        val signedResponse = try {
            transport.execute(wireRequest)
        } catch (_: Exception) {
            circuitBreaker.recordFailure(wireRequest.provider, positiveNow())
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.TRANSPORT_FAILED,
            )
        }
        val response = try {
            gatewayResponseAuthority.verify(signedResponse)
        } catch (rejected: ProviderGatewayResponseException) {
            circuitBreaker.recordFailure(wireRequest.provider, positiveNow())
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.GATEWAY_RESPONSE_AUTHENTICATION_FAILED,
                gatewayResponseFailure = rejected.failureCode,
            )
        }
        val returnedAt = positiveNow()
        val elapsed = safeElapsed(startedAt, returnedAt)
        if (
            elapsed > invocation.config.runtimeLimits.overallTimeoutMillis ||
            response.completedAtEpochMillis < startedAt ||
            response.completedAtEpochMillis > returnedAt
        ) {
            circuitBreaker.recordFailure(wireRequest.provider, returnedAt)
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.TIMEOUT,
            )
        }
        if (response.responseBodySizeBytes > invocation.config.runtimeLimits.maxResponseBodyBytes) {
            circuitBreaker.recordFailure(wireRequest.provider, returnedAt)
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.RESPONSE_TOO_LARGE,
            )
        }

        val bindingMatches = response.requestSha256 == requestSha256 &&
            response.provider == wireRequest.provider &&
            response.modelSnapshotId == wireRequest.modelSnapshotId &&
            response.modelManifestSha256 == wireRequest.modelManifestSha256 &&
            response.promptSha256 == wireRequest.promptSha256 &&
            response.jsonSchemaSha256 == wireRequest.jsonSchemaSha256 &&
            response.policySha256 == wireRequest.policySha256
        if (!bindingMatches) {
            circuitBreaker.recordFailure(wireRequest.provider, returnedAt)
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.RESPONSE_BINDING_MISMATCH,
            )
        }
        if (!response.strictStructuredOutputValidated) {
            circuitBreaker.recordFailure(wireRequest.provider, returnedAt)
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                failure = ProviderOrchestrationFailure.STRUCTURED_OUTPUT_INVALID,
            )
        }

        val runReceipt = ProviderRunReceipt(
            provider = wireRequest.provider,
            modelSnapshotId = wireRequest.modelSnapshotId,
            modelManifestSha256 = wireRequest.modelManifestSha256,
            runtimeVersion = invocation.config.model.runtimeVersion,
            inputSnapshotSha256 = inputHash,
            requestSha256 = requestSha256,
            providerRequestIdSha256 = response.providerRequestIdSha256,
            promptSha256 = wireRequest.promptSha256,
            jsonSchemaSha256 = wireRequest.jsonSchemaSha256,
            policySha256 = wireRequest.policySha256,
            providerPolicyAttestationSha256 = wireRequest.providerPolicyAttestationSha256,
            privacyReceiptCanonicalSha256 = wireRequest.privacyReceiptCanonicalSha256,
            gatewayResponseCanonicalSha256 = response.gatewayResponseCanonicalSha256,
            privacyReceiptId = wireRequest.privacyReceiptId,
            consentGeneration = wireRequest.consentGeneration,
            purpose = wireRequest.purpose,
            payloadClass = wireRequest.payloadClass,
            executionMode = wireRequest.executionMode,
            strictStructuredOutputValidated = response.strictStructuredOutputValidated,
            storeResponse = wireRequest.storeResponse,
            providerBrowsingEnabled = wireRequest.providerBrowsingEnabled,
            providerToolsEnabled = wireRequest.providerToolsEnabled,
            completedAtEpochMillis = response.completedAtEpochMillis,
        )

        val deliveryPrivacy = try {
            privacyReceiptAuthority.verify(invocation.privacyReceipt)
        } catch (rejected: ReasoningPrivacyReceiptException) {
            circuitBreaker.recordFailure(wireRequest.provider, returnedAt)
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                runReceipt = runReceipt,
                failure = ProviderOrchestrationFailure.PROVIDER_POLICY_REJECTED,
                providerPolicyFailures = setOf(
                    ProviderPolicyFailureCode.PRIVACY_RECEIPT_AUTHENTICATION_FAILED,
                ),
                privacyReceiptFailure = rejected.failureCode,
            )
        }
        if (
            deliveryPrivacy.canonicalPayloadSha256 != wireRequest.privacyReceiptCanonicalSha256 ||
            !consentStillAuthorized(invocation.privacyReceipt)
        ) {
            circuitBreaker.recordFailure(wireRequest.provider, returnedAt)
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                runReceipt = runReceipt,
                failure = ProviderOrchestrationFailure.PROVIDER_POLICY_REJECTED,
                providerPolicyFailures = setOf(ProviderPolicyFailureCode.CURRENT_CONSENT_REJECTED),
            )
        }

        if (response.refusalCode != null) {
            circuitBreaker.recordSuccess(wireRequest.provider, returnedAt)
            replayGuard.markCompleted(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                runReceipt = runReceipt,
                failure = ProviderOrchestrationFailure.PROVIDER_REFUSAL,
                refusalCode = response.refusalCode,
                abstained = true,
            )
        }
        val candidate = response.candidate ?: error("Provider response invariant failed")
        val deliveryRequest = try {
            healthStateAuthority.verify(packet)
        } catch (rejected: HealthStateAuthorityException) {
            circuitBreaker.recordFailure(wireRequest.provider, returnedAt)
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                runReceipt = runReceipt,
                failure = ProviderOrchestrationFailure.HEALTH_STATE_AUTHORITY_REJECTED,
                authorityFailure = rejected.failureCode,
            )
        }
        val validation = deterministicPolicy.validate(deliveryRequest, candidate)
        if (validation.disposition == ReasoningDisposition.REWRITE) {
            circuitBreaker.recordFailure(wireRequest.provider, returnedAt)
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return finishWithoutCandidate(
                invocation = invocation,
                inputHash = inputHash,
                requestSha256 = requestSha256,
                runReceipt = runReceipt,
                failure = ProviderOrchestrationFailure.DETERMINISTIC_POLICY_REJECTED,
                deterministicPolicyFailures = validation.failureCodes,
            )
        }

        val candidateSha256 = CanonicalReasoningCandidate.sha256(candidate)
        val audit = auditRecord(
            invocation = invocation,
            inputHash = inputHash,
            requestSha256 = requestSha256,
            candidateSha256 = candidateSha256,
            runReceipt = runReceipt,
            disposition = validation.disposition,
            completedAt = returnedAt,
        )
        if (!auditSink.commit(audit)) {
            replayGuard.markFailed(invocation.idempotencyKey, requestSha256)
            return safeFallback(
                runReceipt = runReceipt,
                failure = ProviderOrchestrationFailure.AUDIT_COMMIT_FAILED,
            )
        }
        circuitBreaker.recordSuccess(wireRequest.provider, returnedAt)
        replayGuard.markCompleted(invocation.idempotencyKey, requestSha256)

        if (invocation.executionMode == ProviderExecutionMode.SHADOW_CHALLENGER) {
            return VerifiedProviderReasoningOutcome(
                state = ProviderReasoningDeliveryState.SHADOW_RECORDED,
                candidate = null,
                runReceipt = runReceipt,
                deterministicPolicyFailures = emptySet(),
                providerPolicyFailures = emptySet(),
                orchestrationFailure = ProviderOrchestrationFailure.NONE,
                authorityFailureCode = null,
                providerAttestationFailureCode = null,
                privacyReceiptFailureCode = null,
                gatewayResponseFailureCode = null,
                providerRefusalCode = null,
                shadowCandidateSha256 = candidateSha256,
                safeTemplateId = null,
            )
        }
        if (validation.disposition == ReasoningDisposition.ABSTAIN) {
            return VerifiedProviderReasoningOutcome(
                state = ProviderReasoningDeliveryState.ABSTAINED,
                candidate = null,
                runReceipt = runReceipt,
                deterministicPolicyFailures = emptySet(),
                providerPolicyFailures = emptySet(),
                orchestrationFailure = ProviderOrchestrationFailure.NONE,
                authorityFailureCode = null,
                providerAttestationFailureCode = null,
                privacyReceiptFailureCode = null,
                gatewayResponseFailureCode = null,
                providerRefusalCode = null,
                shadowCandidateSha256 = null,
                safeTemplateId = null,
            )
        }
        return VerifiedProviderReasoningOutcome(
            state = ProviderReasoningDeliveryState.VERIFIED,
            candidate = candidate,
            runReceipt = runReceipt,
            deterministicPolicyFailures = emptySet(),
            providerPolicyFailures = emptySet(),
            orchestrationFailure = ProviderOrchestrationFailure.NONE,
            authorityFailureCode = null,
            providerAttestationFailureCode = null,
            privacyReceiptFailureCode = null,
            gatewayResponseFailureCode = null,
            providerRefusalCode = null,
            shadowCandidateSha256 = null,
            safeTemplateId = null,
        )
    }

    private fun validateInvocation(
        request: LocalReasoningRequest,
        invocation: ProviderInvocation,
        providerPolicy: VerifiedProviderPolicy,
    ): Set<ProviderPolicyFailureCode> {
        val failures = linkedSetOf<ProviderPolicyFailureCode>()
        val receipt = invocation.privacyReceipt
        val attested = providerPolicy.draft
        val now = nowEpochMillis()
        if (invocation.config.model.provider != attested.provider) {
            failures += ProviderPolicyFailureCode.PROVIDER_MISMATCH
        }
        if (receipt.purpose !in attested.allowedPurposes) {
            failures += ProviderPolicyFailureCode.PURPOSE_NOT_ATTESTED
        }
        if (
            (invocation.executionMode == ProviderExecutionMode.SHADOW_CHALLENGER) !=
            (receipt.purpose == ReasoningPurpose.OFFLINE_SHADOW_EVALUATION)
        ) {
            failures += ProviderPolicyFailureCode.PURPOSE_MODE_MISMATCH
        }
        if (now < receipt.issuedAtEpochMillis || now >= receipt.expiresAtEpochMillis) {
            failures += ProviderPolicyFailureCode.PRIVACY_RECEIPT_EXPIRED
        }
        if (
            receipt.boundInputSnapshotSha256 != request.inputSnapshotSha256 ||
            receipt.boundInputSnapshotSha256 != invocation.privacyReceipt.boundInputSnapshotSha256
        ) {
            failures += ProviderPolicyFailureCode.INPUT_SNAPSHOT_MISMATCH
        }
        if (
            receipt.policySha256 != request.policyHashSha256 ||
            receipt.policySha256 != attested.policySha256
        ) {
            failures += ProviderPolicyFailureCode.POLICY_HASH_MISMATCH
        }
        if (receipt.providerPolicyAttestationId != attested.attestationId) {
            failures += ProviderPolicyFailureCode.PROVIDER_ATTESTATION_ID_MISMATCH
        }
        if (receipt.retentionMode != attested.retentionMode) {
            failures += ProviderPolicyFailureCode.RETENTION_MISMATCH
        }
        if (receipt.payloadClass == ProviderPayloadClass.PERSONAL_HEALTH_MINIMIZED) {
            val personalRetentionAllowed = when (attested.provider) {
                ReasoningProvider.OLLAMA_LOCAL ->
                    attested.retentionMode == ProviderRetentionMode.LOCAL_ONLY
                ReasoningProvider.OPENAI_RESPONSES,
                ReasoningProvider.ANTHROPIC_MESSAGES,
                -> attested.retentionMode == ProviderRetentionMode.ZERO_DATA_RETENTION
            }
            if (!personalRetentionAllowed) {
                failures += ProviderPolicyFailureCode.RETENTION_MISMATCH
            }
        }
        if (receipt.residencyRegion != attested.residencyRegion) {
            failures += ProviderPolicyFailureCode.RESIDENCY_MISMATCH
        }
        if (receipt.transmittedFields != MINIMIZED_PROVIDER_FIELDS) {
            failures += ProviderPolicyFailureCode.DATA_MINIMIZATION_MISMATCH
        }
        if (receipt.evidenceRetrievalMode != EvidenceRetrievalMode.CURATED_EVIDENCE_BACKEND_ONLY) {
            failures += ProviderPolicyFailureCode.UNCURATED_RETRIEVAL
        }
        if (!receipt.personaPreferencesSeparateFromHealthRecord) {
            failures += ProviderPolicyFailureCode.PERSONA_STORAGE_NOT_SEPARATE
        }
        return failures
    }

    private fun finishWithoutCandidate(
        invocation: ProviderInvocation,
        inputHash: String,
        requestSha256: String? = null,
        runReceipt: ProviderRunReceipt? = null,
        failure: ProviderOrchestrationFailure,
        authorityFailure: HealthStateAuthorityFailureCode? = null,
        providerPolicyFailures: Set<ProviderPolicyFailureCode> = emptySet(),
        providerAttestationFailure: ProviderPolicyAttestationFailureCode? = null,
        privacyReceiptFailure: ReasoningPrivacyReceiptFailureCode? = null,
        gatewayResponseFailure: ProviderGatewayResponseFailureCode? = null,
        deterministicPolicyFailures: Set<ReasoningFailureCode> = emptySet(),
        refusalCode: ProviderRefusalCode? = null,
        abstained: Boolean = false,
    ): VerifiedProviderReasoningOutcome {
        val disposition = if (abstained) ReasoningDisposition.ABSTAIN else ReasoningDisposition.REWRITE
        val audit = auditRecord(
            invocation = invocation,
            inputHash = inputHash,
            requestSha256 = requestSha256,
            candidateSha256 = null,
            runReceipt = runReceipt,
            disposition = disposition,
            deterministicPolicyFailures = deterministicPolicyFailures,
            providerPolicyFailures = providerPolicyFailures,
            failure = failure,
            authorityFailure = authorityFailure,
            providerAttestationFailure = providerAttestationFailure,
            privacyReceiptFailure = privacyReceiptFailure,
            gatewayResponseFailure = gatewayResponseFailure,
            refusalCode = refusalCode,
            completedAt = positiveNow(),
        )
        if (!auditSink.commit(audit)) {
            return safeFallback(
                runReceipt = runReceipt,
                failure = ProviderOrchestrationFailure.AUDIT_COMMIT_FAILED,
            )
        }
        if (abstained) {
            return VerifiedProviderReasoningOutcome(
                state = ProviderReasoningDeliveryState.ABSTAINED,
                candidate = null,
                runReceipt = runReceipt,
                deterministicPolicyFailures = deterministicPolicyFailures,
                providerPolicyFailures = providerPolicyFailures,
                orchestrationFailure = failure,
                authorityFailureCode = authorityFailure,
                providerAttestationFailureCode = providerAttestationFailure,
                privacyReceiptFailureCode = privacyReceiptFailure,
                gatewayResponseFailureCode = gatewayResponseFailure,
                providerRefusalCode = refusalCode,
                shadowCandidateSha256 = null,
                safeTemplateId = null,
            )
        }
        return safeFallback(
            runReceipt = runReceipt,
            failure = failure,
            deterministicPolicyFailures = deterministicPolicyFailures,
            providerPolicyFailures = providerPolicyFailures,
            authorityFailure = authorityFailure,
            providerAttestationFailure = providerAttestationFailure,
            privacyReceiptFailure = privacyReceiptFailure,
            gatewayResponseFailure = gatewayResponseFailure,
            refusalCode = refusalCode,
        )
    }

    private fun auditRecord(
        invocation: ProviderInvocation,
        inputHash: String,
        requestSha256: String?,
        candidateSha256: String?,
        runReceipt: ProviderRunReceipt?,
        disposition: ReasoningDisposition,
        deterministicPolicyFailures: Set<ReasoningFailureCode> = emptySet(),
        providerPolicyFailures: Set<ProviderPolicyFailureCode> = emptySet(),
        failure: ProviderOrchestrationFailure = ProviderOrchestrationFailure.NONE,
        authorityFailure: HealthStateAuthorityFailureCode? = null,
        providerAttestationFailure: ProviderPolicyAttestationFailureCode? = null,
        privacyReceiptFailure: ReasoningPrivacyReceiptFailureCode? = null,
        gatewayResponseFailure: ProviderGatewayResponseFailureCode? = null,
        refusalCode: ProviderRefusalCode? = null,
        completedAt: Long,
    ) = ProviderReasoningAuditRecord(
        provider = invocation.config.model.provider,
        executionMode = invocation.executionMode,
        purpose = invocation.privacyReceipt.purpose,
        payloadClass = invocation.privacyReceipt.payloadClass,
        privacyReceiptId = invocation.privacyReceipt.receiptId,
        consentGeneration = invocation.privacyReceipt.consentGeneration,
        inputSnapshotSha256 = inputHash,
        requestSha256 = requestSha256,
        candidateSha256 = candidateSha256,
        runReceipt = runReceipt,
        disposition = disposition,
        deterministicPolicyFailures = deterministicPolicyFailures,
        providerPolicyFailures = providerPolicyFailures,
        orchestrationFailure = failure,
        authorityFailureCode = authorityFailure,
        providerAttestationFailureCode = providerAttestationFailure,
        privacyReceiptFailureCode = privacyReceiptFailure,
        gatewayResponseFailureCode = gatewayResponseFailure,
        providerRefusalCode = refusalCode,
        completedAtEpochMillis = completedAt,
    )

    private fun safeFallback(
        runReceipt: ProviderRunReceipt?,
        failure: ProviderOrchestrationFailure,
        deterministicPolicyFailures: Set<ReasoningFailureCode> = emptySet(),
        providerPolicyFailures: Set<ProviderPolicyFailureCode> = emptySet(),
        authorityFailure: HealthStateAuthorityFailureCode? = null,
        providerAttestationFailure: ProviderPolicyAttestationFailureCode? = null,
        privacyReceiptFailure: ReasoningPrivacyReceiptFailureCode? = null,
        gatewayResponseFailure: ProviderGatewayResponseFailureCode? = null,
        refusalCode: ProviderRefusalCode? = null,
    ) = VerifiedProviderReasoningOutcome(
        state = ProviderReasoningDeliveryState.SAFE_FALLBACK,
        candidate = null,
        runReceipt = runReceipt,
        deterministicPolicyFailures = deterministicPolicyFailures,
        providerPolicyFailures = providerPolicyFailures,
        orchestrationFailure = failure,
        authorityFailureCode = authorityFailure,
        providerAttestationFailureCode = providerAttestationFailure,
        privacyReceiptFailureCode = privacyReceiptFailure,
        gatewayResponseFailureCode = gatewayResponseFailure,
        providerRefusalCode = refusalCode,
        shadowCandidateSha256 = null,
        safeTemplateId = "governed-assistant-unavailable-v1",
    )

    private fun positiveNow(): Long = nowEpochMillis().takeIf { it > 0L } ?: 1L

    private fun consentStillAuthorized(receipt: ReasoningPrivacyReceipt): Boolean = try {
        currentConsentGate.isAuthorized(receipt)
    } catch (_: Exception) {
        false
    }

    private fun safeElapsed(startedAt: Long, endedAt: Long): Long = try {
        Math.subtractExact(endedAt, startedAt).takeIf { it >= 0L } ?: Long.MAX_VALUE
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

/**
 * Cloud projection intentionally omits packet/issuer/subject IDs, evidence
 * titles/URIs/population/free text, and quality-gap text. Curated evidence may
 * be resolved only inside the separately governed VitalSignal evidence backend.
 */
internal object MinimizedProviderPrompt {
    fun render(request: LocalReasoningRequest, persona: AssistantPersonaPreferences): String =
        buildString {
            append("{\"task\":\"select-reviewed-health-explanation\",\"request\":{")
            field("schemaVersion", request.schemaVersion)
            append(',')
            field("inputSnapshotSha256", request.inputSnapshotSha256)
            append(",\"metricReferences\":[")
            request.metricReferences.sortedBy { it.id }.forEachIndexed { index, metric ->
                if (index > 0) append(',')
                append('{')
                field("id", metric.id)
                append(",\"value\":").append(metric.value)
                append(',')
                field("unit", metric.unit)
                append(",\"quality\":").append(metric.quality)
                append(',')
                field("windowId", metric.windowId)
                append('}')
            }
            append("],\"evidenceReferences\":[")
            request.evidenceReferences.sortedBy { it.id }.forEachIndexed { index, evidence ->
                if (index > 0) append(',')
                append('{')
                field("id", evidence.id)
                append(',')
                field("kind", evidence.kind.name)
                append(',')
                field("contentSha256", evidence.contentSha256)
                append('}')
            }
            append("],\"approvedNextMeasurementIds\":")
            stringArray(request.approvedNextMeasurementIds.sorted())
            append(",\"approvedQuestionIds\":")
            stringArray(request.approvedQuestionIds.sorted())
            append(",\"approvedNarrativeTemplateIds\":")
            stringArray(request.approvedNarrativeTemplateIds.sorted())
            append(",\"qualityGapCount\":").append(request.qualityGaps.size)
            append(',')
            field("policyHashSha256", request.policyHashSha256)
            append(",\"persona\":{")
            field("tone", persona.tone.name)
            append(',')
            field("detail", persona.detail.name)
            append(',')
            field("questionStyle", persona.questionStyle.name)
            append(",\"readNumbersAloud\":").append(persona.readNumbersAloud)
            append("}}}")
        }

    private fun StringBuilder.field(name: String, value: String) {
        append(jsonQuote(name)).append(':').append(jsonQuote(value))
    }

    private fun StringBuilder.stringArray(values: List<String>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(jsonQuote(value))
        }
        append(']')
    }

    private fun jsonQuote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

private object CanonicalProviderRequest {
    fun sha256(
        invocation: ProviderInvocation,
        inputHash: String,
        promptSha256: String,
        providerPolicySha256: String,
        personaSha256: String,
        promptBytes: ByteArray,
        schemaBytes: ByteArray,
    ): String = sha256Hex(
        CanonicalRecord().apply {
            field(1, strictUtf8("VITALSIGNAL_PROVIDER_REQUEST_V1"))
            field(2, strictUtf8(invocation.config.model.provider.name))
            field(3, strictUtf8(invocation.config.gatewayRouteId))
            field(4, strictUtf8(invocation.config.model.snapshotId))
            field(5, strictUtf8(invocation.config.model.modelManifestSha256))
            field(6, strictUtf8(invocation.executionMode.name))
            field(7, strictUtf8(invocation.privacyReceipt.purpose.name))
            field(8, strictUtf8(invocation.privacyReceipt.payloadClass.name))
            field(9, longBytes(invocation.privacyReceipt.consentGeneration))
            field(10, strictUtf8(invocation.privacyReceipt.receiptId))
            field(11, strictUtf8(invocation.idempotencyKey))
            field(12, strictUtf8(inputHash))
            field(13, strictUtf8(promptSha256))
            field(14, strictUtf8(invocation.config.strictJsonSchema.sha256))
            field(15, strictUtf8(invocation.privacyReceipt.policySha256))
            field(16, strictUtf8(providerPolicySha256))
            field(17, strictUtf8(personaSha256))
            field(18, promptBytes)
            field(19, schemaBytes)
            field(20, longBytes(invocation.config.runtimeLimits.connectTimeoutMillis.toLong()))
            field(21, longBytes(invocation.config.runtimeLimits.overallTimeoutMillis.toLong()))
            field(22, longBytes(invocation.config.runtimeLimits.maxResponseBodyBytes.toLong()))
            field(23, byteArrayOf(0)) // storeResponse=false
            field(24, byteArrayOf(0)) // provider browsing=false
            field(25, byteArrayOf(0)) // provider tools=false
            field(26, byteArrayOf(1)) // strict structured output=true
            field(27, strictUtf8(sha256Hex(invocation.privacyReceipt.canonicalPayloadBytes())))
        }.bytes(),
    )
}
