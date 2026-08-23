package au.com.elied.vitalsignal.reasoning

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedHealthStatePacketTest {
    @Test
    fun validExactSignatureBuildsRequestAndRecomputesSnapshotHash() {
        val packet = ReasoningTestFixtures.packet()

        val request = ReasoningTestFixtures.authority().verify(packet)

        assertEquals(packet.canonicalPayloadSha256(), request.inputSnapshotSha256)
        assertEquals("packet-1", request.packetId)
        assertEquals(setOf(ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1), request.approvedNarrativeTemplateIds)
    }

    @Test
    fun forgedSignatureIsRejected() {
        val packet = ReasoningTestFixtures.packet(
            signer = ReasoningTestFixtures.signer("wrong-key".toByteArray()),
        )

        val error = assertThrows(HealthStateAuthorityException::class.java) {
            ReasoningTestFixtures.authority().verify(packet)
        }

        assertEquals(HealthStateAuthorityFailureCode.SIGNATURE_INVALID, error.failureCode)
    }

    @Test
    fun arbitrarySnapshotCannotBePassedToRequest() {
        val publicConstructors = LocalReasoningRequest::class.java.constructors
        assertTrue(publicConstructors.none { it.parameterCount == 2 })
        assertTrue(publicConstructors.all { it.isSynthetic })
        assertTrue(
            LocalReasoningRequest::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .all { Modifier.isPrivate(it.modifiers) },
        )
        assertFalse(LocalReasoningRequest::class.java.declaredFields.any { it.name == "inputSnapshotOverride" })
    }

    @Test
    fun fieldMutationChangesCanonicalHashAndSignature() {
        val first = ReasoningTestFixtures.packet(metricValue = 72.0)
        val changed = ReasoningTestFixtures.packet(metricValue = 73.0)

        assertNotEquals(first.canonicalPayloadSha256(), changed.canonicalPayloadSha256())
        assertFalse(java.security.MessageDigest.isEqual(first.signatureBytes(), changed.signatureBytes()))
    }

    @Test
    fun sourceCollectionAndReturnedSignatureMutationCannotChangePacket() {
        val builder = baseBuilder().addQualityGap("initial-gap")
        val packet = HealthStatePacketIssuer(
            ReasoningTestFixtures.KEY_ID,
            ReasoningTestFixtures.signer(),
        ).issue(builder)
        val originalHash = packet.canonicalPayloadSha256()
        val returnedSignature = packet.signatureBytes()

        builder.addQualityGap("later-caller-mutation")
        returnedSignature.fill(0)

        assertEquals(originalHash, packet.canonicalPayloadSha256())
        assertFalse(packet.signatureBytes().all { it == 0.toByte() })
        val request = ReasoningTestFixtures.authority().verify(packet)
        assertEquals(listOf("initial-gap"), request.qualityGaps)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (request.qualityGaps as MutableList<String>).clear()
        }
    }

    @Test
    fun verifierCannotMutatePacketThroughItsByteArrays() {
        val packet = ReasoningTestFixtures.packet()
        val originalPayload = packet.canonicalPayloadBytes()
        val originalSignature = packet.signatureBytes()
        val mutatingVerifier = HealthStatePacketSignatureVerifier { _, payload, signature ->
            val valid = ReasoningTestFixtures.verifier().verify(
                ReasoningTestFixtures.KEY_ID,
                payload.copyOf(),
                signature.copyOf(),
            )
            payload.fill(0)
            signature.fill(0)
            valid
        }

        HealthStatePacketAuthority(mutatingVerifier, { ReasoningTestFixtures.NOW }).verify(packet)

        assertTrue(java.security.MessageDigest.isEqual(originalPayload, packet.canonicalPayloadBytes()))
        assertTrue(java.security.MessageDigest.isEqual(originalSignature, packet.signatureBytes()))
    }

    @Test
    fun canonicalPayloadMismatchIsRejectedBeforeSignatureMeaning() {
        val good = ReasoningTestFixtures.packet()
        val changedCanonical = good.canonicalPayloadBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val malformed = SignedHealthStatePacket(
            good.snapshotForVerification(),
            changedCanonical,
            good.signatureBytes(),
        )

        val error = assertThrows(HealthStateAuthorityException::class.java) {
            ReasoningTestFixtures.authority().verify(malformed)
        }

        assertEquals(HealthStateAuthorityFailureCode.CANONICAL_PAYLOAD_MISMATCH, error.failureCode)
    }

    @Test
    fun lengthPrefixesEliminateConcatenationAmbiguity() {
        val left = ReasoningTestFixtures.packet(extraQualityGaps = listOf("a", "bc"))
        val right = ReasoningTestFixtures.packet(extraQualityGaps = listOf("ab", "c"))

        assertNotEquals(left.canonicalPayloadSha256(), right.canonicalPayloadSha256())
    }

    @Test
    fun approvedNarrativeTemplatesAreInsideSignedHash() {
        val first = ReasoningTestFixtures.packet()
        val changed = ReasoningTestFixtures.packet(
            approvedTemplateIds = setOf(ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1),
        )

        assertNotEquals(first.canonicalPayloadSha256(), changed.canonicalPayloadSha256())
    }

    @Test
    fun futureIssuedPacketIsRejected() {
        val packet = ReasoningTestFixtures.packet(issuedAt = 11_000L, notBefore = 11_000L, expiresAt = 20_000L)

        assertAuthorityFailure(packet, HealthStateAuthorityFailureCode.ISSUED_IN_FUTURE)
    }

    @Test
    fun futureNotBeforePacketIsRejected() {
        val packet = ReasoningTestFixtures.packet(issuedAt = 9_000L, notBefore = 11_000L, expiresAt = 20_000L)

        assertAuthorityFailure(packet, HealthStateAuthorityFailureCode.NOT_YET_VALID)
    }

    @Test
    fun expiredPacketIsRejected() {
        val packet = ReasoningTestFixtures.packet(issuedAt = 1_000L, notBefore = 1_000L, expiresAt = 10_000L)

        assertAuthorityFailure(packet, HealthStateAuthorityFailureCode.EXPIRED)
    }

    @Test
    fun packetExceedingShortMaximumTtlIsRejected() {
        val packet = ReasoningTestFixtures.packet(issuedAt = 1_000L, notBefore = 1_000L, expiresAt = 130_000L)

        assertAuthorityFailure(packet, HealthStateAuthorityFailureCode.TTL_EXCEEDED)
    }

    private fun assertAuthorityFailure(
        packet: SignedHealthStatePacket,
        expected: HealthStateAuthorityFailureCode,
    ) {
        val error = assertThrows(HealthStateAuthorityException::class.java) {
            ReasoningTestFixtures.authority().verify(packet)
        }
        assertEquals(expected, error.failureCode)
    }

    private fun baseBuilder() = HealthStatePacketBuilder(
        packetId = "packet-copy-test",
        issuerId = "fixture-engine",
        subjectPseudonym = "subject-1",
        issuedAtEpochMillis = 1_000L,
        notBeforeEpochMillis = 1_000L,
        expiresAtEpochMillis = 60_000L,
        policyHashSha256 = "c".repeat(64),
    )
        .addMetric(HealthMetricReference("sleeping-hr", 72.0, "bpm", 0.94, "window-1"))
        .addEvidence(ReasoningTestFixtures.evidence("personal-1"))
        .approveNarrativeTemplate(ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1)
}
