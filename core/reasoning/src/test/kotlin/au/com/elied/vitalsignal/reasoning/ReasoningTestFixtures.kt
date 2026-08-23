package au.com.elied.vitalsignal.reasoning

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object ReasoningTestFixtures {
    const val NOW = 10_000L
    const val KEY_ID = "test-key-v1"
    private val key = "reasoning-test-key-material".toByteArray(Charsets.UTF_8)

    fun signer(keyBytes: ByteArray = key.copyOf()) = HealthStatePacketSigner { payload ->
        hmac(keyBytes, payload)
    }

    fun verifier(keyBytes: ByteArray = key.copyOf()) = HealthStatePacketSignatureVerifier { keyId, payload, signature ->
        keyId == KEY_ID && java.security.MessageDigest.isEqual(hmac(keyBytes, payload), signature)
    }

    fun authority(
        now: () -> Long = { NOW },
        keyBytes: ByteArray = key.copyOf(),
        maxTtlMillis: Long = 120_000L,
    ) = HealthStatePacketAuthority(verifier(keyBytes), now, maxTtlMillis)

    fun packet(
        issuedAt: Long = 1_000L,
        notBefore: Long = issuedAt,
        expiresAt: Long = 60_000L,
        packetId: String = "packet-1",
        metricId: String = "sleeping-hr",
        metricValue: Double = 72.0,
        approvedTemplateIds: Set<String> = setOf(ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1),
        signer: HealthStatePacketSigner = signer(),
        extraQualityGaps: List<String> = emptyList(),
    ): SignedHealthStatePacket {
        val builder = HealthStatePacketBuilder(
            packetId = packetId,
            issuerId = "fixture-engine",
            subjectPseudonym = "subject-1",
            issuedAtEpochMillis = issuedAt,
            notBeforeEpochMillis = notBefore,
            expiresAtEpochMillis = expiresAt,
            policyHashSha256 = "c".repeat(64),
        )
            .addMetric(HealthMetricReference(metricId, metricValue, "bpm", 0.94, "window-1"))
            .addEvidence(evidence("personal-1"))
            .approveNextMeasurement("repeat-resting-capture")
            .approveQuestion("confirm-symptoms")
        approvedTemplateIds.forEach(builder::approveNarrativeTemplate)
        extraQualityGaps.forEach(builder::addQualityGap)
        return HealthStatePacketIssuer(KEY_ID, signer).issue(builder)
    }

    fun request(packet: SignedHealthStatePacket = packet()): LocalReasoningRequest = authority().verify(packet)

    fun candidate(
        packet: SignedHealthStatePacket,
        templateId: String = ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1,
        kind: NarrativeClaimKind = NarrativeClaimKind.OBSERVATION,
    ) = LocalReasoningCandidate(
        inputSnapshotSha256 = packet.canonicalPayloadSha256(),
        claims = listOf(
            NarrativeClaim(
                id = "claim-1",
                kind = kind,
                templateId = templateId,
                metricReferenceIds = listOf("sleeping-hr"),
                evidenceReferenceIds = listOf("personal-1"),
                certainty = NarrativeCertainty.MODERATE,
            ),
        ),
        nextMeasurementIds = listOf("repeat-resting-capture"),
        questionIdsForUser = emptyList(),
        abstain = false,
        abstainReason = null,
    )

    fun evidence(id: String) = CuratedEvidenceReference(
        id = id,
        kind = EvidenceKind.PERSONAL_EPISODE,
        contentSha256 = "d".repeat(64),
        title = "Qualified personal episode",
        sourceUri = "personal://episode-1",
        populationAndDevice = "Pilot fixture",
        limitations = "Research-only fixture",
        verifiedAtEpochMillis = 1_000L,
    )

    fun receipt() = OllamaRunReceipt(
        ollamaVersion = "0.12.3",
        modelName = "fixture:q4",
        modelDigest = "b".repeat(64),
        quantization = "Q4_K_M",
        promptSha256 = "d".repeat(64),
        jsonSchemaSha256 = "e".repeat(64),
        seed = 73L,
        temperature = 0.0,
        contextTokens = 4_096,
        completedAtEpochMillis = 9_000L,
    )

    private fun hmac(keyBytes: ByteArray, payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keyBytes.copyOf(), "HmacSHA256"))
            doFinal(payload.copyOf())
        }
}
