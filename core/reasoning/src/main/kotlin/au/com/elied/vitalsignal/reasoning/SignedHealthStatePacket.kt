package au.com.elied.vitalsignal.reasoning

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

/** A quality-checked metric. Its authority comes only from the enclosing signed packet. */
data class HealthMetricReference(
    val id: String,
    val value: Double,
    val unit: String,
    val quality: Double,
    val windowId: String,
) {
    init {
        requireReasoningId(id, "metric id")
        require(value.isFinite())
        require(unit.isNotBlank() && strictUtf8(unit).size <= MAX_SHORT_TEXT_BYTES)
        require(quality in 0.0..1.0)
        requireReasoningId(windowId, "window id")
    }
}

/**
 * Mutable construction surface for an unsigned health-state description. The
 * issuer snapshots and deep-copies it before signing, so later caller mutation
 * cannot change packet meaning.
 */
class HealthStatePacketBuilder(
    val packetId: String,
    val issuerId: String,
    val subjectPseudonym: String,
    val issuedAtEpochMillis: Long,
    val notBeforeEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val policyHashSha256: String,
) {
    private val metrics = mutableListOf<HealthMetricReference>()
    private val evidence = mutableListOf<CuratedEvidenceReference>()
    private val measurements = linkedSetOf<String>()
    private val questions = linkedSetOf<String>()
    private val narrativeTemplates = linkedSetOf<String>()
    private val gaps = mutableListOf<String>()

    init {
        requireReasoningId(packetId, "packet id")
        requireReasoningId(issuerId, "issuer id")
        requireReasoningId(subjectPseudonym, "subject pseudonym")
        require(issuedAtEpochMillis > 0L)
        require(notBeforeEpochMillis >= issuedAtEpochMillis)
        require(expiresAtEpochMillis > notBeforeEpochMillis)
        requireSha256(policyHashSha256, "policy hash")
    }

    fun addMetric(metric: HealthMetricReference) = apply {
        require(metrics.size < MAX_PACKET_ITEMS)
        metrics += metric
    }

    fun addEvidence(reference: CuratedEvidenceReference) = apply {
        require(evidence.size < MAX_PACKET_ITEMS)
        evidence += reference
    }

    fun approveNextMeasurement(id: String) = apply {
        requireReasoningId(id, "measurement id")
        require(measurements.size < MAX_PACKET_ITEMS)
        measurements += id
    }

    fun approveQuestion(id: String) = apply {
        requireReasoningId(id, "question id")
        require(questions.size < MAX_PACKET_ITEMS)
        questions += id
    }

    fun approveNarrativeTemplate(id: String) = apply {
        requireNarrativeTemplateId(id)
        require(narrativeTemplates.size < MAX_PACKET_ITEMS)
        narrativeTemplates += id
    }

    fun addQualityGap(gap: String) = apply {
        require(gap.isNotBlank() && strictUtf8(gap).size <= MAX_LONG_TEXT_BYTES)
        require(gaps.size < MAX_PACKET_ITEMS)
        gaps += gap
    }

    internal fun snapshot(signingKeyId: String): HealthStatePacketSnapshot {
        requireReasoningId(signingKeyId, "signing key id")
        val metricCopy = immutableList(metrics)
        val evidenceCopy = immutableList(evidence)
        require(metricCopy.map { it.id }.distinct().size == metricCopy.size)
        require(evidenceCopy.map { it.id }.distinct().size == evidenceCopy.size)
        return HealthStatePacketSnapshot(
            schemaVersion = HEALTH_STATE_PACKET_SCHEMA,
            packetId = packetId,
            issuerId = issuerId,
            signingKeyId = signingKeyId,
            subjectPseudonym = subjectPseudonym,
            issuedAtEpochMillis = issuedAtEpochMillis,
            notBeforeEpochMillis = notBeforeEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            metricReferences = metricCopy,
            evidenceReferences = evidenceCopy,
            approvedNextMeasurementIds = immutableSet(measurements),
            approvedQuestionIds = immutableSet(questions),
            approvedNarrativeTemplateIds = immutableSet(narrativeTemplates),
            qualityGaps = immutableList(gaps),
            policyHashSha256 = policyHashSha256,
        )
    }
}

fun interface HealthStatePacketSigner {
    fun sign(canonicalPayload: ByteArray): ByteArray
}

fun interface HealthStatePacketSignatureVerifier {
    fun verify(signingKeyId: String, canonicalPayload: ByteArray, signature: ByteArray): Boolean
}

/** Signs the exact, length-prefixed canonical payload and returns an immutable envelope. */
class HealthStatePacketIssuer(
    private val signingKeyId: String,
    private val signer: HealthStatePacketSigner,
) {
    init {
        requireReasoningId(signingKeyId, "signing key id")
    }

    fun issue(builder: HealthStatePacketBuilder): SignedHealthStatePacket {
        val snapshot = builder.snapshot(signingKeyId)
        val canonical = CanonicalHealthStatePacket.encode(snapshot)
        require(canonical.size <= MAX_CANONICAL_PACKET_BYTES)
        val signature = signer.sign(canonical.copyOf()).copyOf()
        require(signature.isNotEmpty() && signature.size <= MAX_SIGNATURE_BYTES)
        return SignedHealthStatePacket(snapshot, canonical, signature)
    }
}

/**
 * Immutable signed envelope. Byte arrays are never exposed by reference and
 * every collection getter returns a detached copy.
 */
class SignedHealthStatePacket internal constructor(
    snapshot: HealthStatePacketSnapshot,
    canonicalPayload: ByteArray,
    signature: ByteArray,
) {
    private val snapshot = snapshot.deepCopy()
    private val canonicalPayload = canonicalPayload.copyOf()
    private val signature = signature.copyOf()

    val packetId: String get() = snapshot.packetId
    val issuerId: String get() = snapshot.issuerId
    val signingKeyId: String get() = snapshot.signingKeyId
    val subjectPseudonym: String get() = snapshot.subjectPseudonym
    val issuedAtEpochMillis: Long get() = snapshot.issuedAtEpochMillis
    val notBeforeEpochMillis: Long get() = snapshot.notBeforeEpochMillis
    val expiresAtEpochMillis: Long get() = snapshot.expiresAtEpochMillis

    fun signatureBytes(): ByteArray = signature.copyOf()

    fun canonicalPayloadBytes(): ByteArray = canonicalPayload.copyOf()

    fun canonicalPayloadSha256(): String = sha256Hex(canonicalPayload)

    internal fun snapshotForVerification(): HealthStatePacketSnapshot = snapshot.deepCopy()
}

enum class HealthStateAuthorityFailureCode {
    CANONICAL_PAYLOAD_MISMATCH,
    SIGNATURE_INVALID,
    UNSUPPORTED_SCHEMA,
    INVALID_TIME_WINDOW,
    ISSUED_IN_FUTURE,
    NOT_YET_VALID,
    EXPIRED,
    TTL_EXCEEDED,
}

class HealthStateAuthorityException(
    val failureCode: HealthStateAuthorityFailureCode,
) : IllegalStateException("Signed health-state authority was rejected: ${failureCode.name}")

/**
 * Verifies authority immediately before model use. The snapshot digest is
 * always recomputed from the exact signed bytes; callers never supply it.
 */
class HealthStatePacketAuthority(
    private val signatureVerifier: HealthStatePacketSignatureVerifier,
    private val nowEpochMillis: () -> Long,
    private val maxTtlMillis: Long = 120_000L,
) {
    init {
        require(maxTtlMillis in 1L..300_000L)
    }

    fun verify(packet: SignedHealthStatePacket): LocalReasoningRequest {
        val snapshot = packet.snapshotForVerification()
        val suppliedCanonical = packet.canonicalPayloadBytes()
        val recomputedCanonical = CanonicalHealthStatePacket.encode(snapshot)
        if (!MessageDigest.isEqual(suppliedCanonical, recomputedCanonical)) {
            rejected(HealthStateAuthorityFailureCode.CANONICAL_PAYLOAD_MISMATCH)
        }
        val signature = packet.signatureBytes()
        val signatureValid = try {
            signatureVerifier.verify(
                snapshot.signingKeyId,
                suppliedCanonical.copyOf(),
                signature.copyOf(),
            )
        } catch (_: Exception) {
            false
        }
        if (!signatureValid) rejected(HealthStateAuthorityFailureCode.SIGNATURE_INVALID)

        val now = nowEpochMillis()
        if (snapshot.schemaVersion != HEALTH_STATE_PACKET_SCHEMA) {
            rejected(HealthStateAuthorityFailureCode.UNSUPPORTED_SCHEMA)
        }
        if (
            now <= 0L ||
            snapshot.issuedAtEpochMillis <= 0L ||
            snapshot.notBeforeEpochMillis < snapshot.issuedAtEpochMillis ||
            snapshot.expiresAtEpochMillis <= snapshot.notBeforeEpochMillis
        ) {
            rejected(HealthStateAuthorityFailureCode.INVALID_TIME_WINDOW)
        }
        if (snapshot.issuedAtEpochMillis > now) rejected(HealthStateAuthorityFailureCode.ISSUED_IN_FUTURE)
        if (snapshot.notBeforeEpochMillis > now) rejected(HealthStateAuthorityFailureCode.NOT_YET_VALID)
        if (snapshot.expiresAtEpochMillis <= now) rejected(HealthStateAuthorityFailureCode.EXPIRED)
        val ttl = try {
            Math.subtractExact(snapshot.expiresAtEpochMillis, snapshot.issuedAtEpochMillis)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        if (ttl > maxTtlMillis) rejected(HealthStateAuthorityFailureCode.TTL_EXCEEDED)

        return LocalReasoningRequest.fromVerifiedPacket(snapshot, sha256Hex(suppliedCanonical))
    }

    private fun rejected(code: HealthStateAuthorityFailureCode): Nothing =
        throw HealthStateAuthorityException(code)
}

internal data class HealthStatePacketSnapshot(
    val schemaVersion: String,
    val packetId: String,
    val issuerId: String,
    val signingKeyId: String,
    val subjectPseudonym: String,
    val issuedAtEpochMillis: Long,
    val notBeforeEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val metricReferences: List<HealthMetricReference>,
    val evidenceReferences: List<CuratedEvidenceReference>,
    val approvedNextMeasurementIds: Set<String>,
    val approvedQuestionIds: Set<String>,
    val approvedNarrativeTemplateIds: Set<String>,
    val qualityGaps: List<String>,
    val policyHashSha256: String,
) {
    fun deepCopy() = copy(
        metricReferences = immutableList(metricReferences),
        evidenceReferences = immutableList(evidenceReferences),
        approvedNextMeasurementIds = immutableSet(approvedNextMeasurementIds),
        approvedQuestionIds = immutableSet(approvedQuestionIds),
        approvedNarrativeTemplateIds = immutableSet(approvedNarrativeTemplateIds),
        qualityGaps = immutableList(qualityGaps),
    )
}

/** Canonical field tags + unsigned 32-bit lengths eliminate delimiter ambiguity. */
internal object CanonicalHealthStatePacket {
    fun encode(snapshot: HealthStatePacketSnapshot): ByteArray = CanonicalRecord().apply {
        field(1, strictUtf8("VITALSIGNAL_HEALTH_STATE_PACKET"))
        field(2, strictUtf8(snapshot.schemaVersion))
        field(3, strictUtf8(snapshot.packetId))
        field(4, strictUtf8(snapshot.issuerId))
        field(5, strictUtf8(snapshot.signingKeyId))
        field(6, strictUtf8(snapshot.subjectPseudonym))
        field(7, longBytes(snapshot.issuedAtEpochMillis))
        field(8, longBytes(snapshot.notBeforeEpochMillis))
        field(9, longBytes(snapshot.expiresAtEpochMillis))
        field(10, listBytes(snapshot.metricReferences.sortedBy { it.id }) { metric ->
            CanonicalRecord().apply {
                field(1, strictUtf8(metric.id))
                field(2, longBytes(java.lang.Double.doubleToLongBits(metric.value)))
                field(3, strictUtf8(metric.unit))
                field(4, longBytes(java.lang.Double.doubleToLongBits(metric.quality)))
                field(5, strictUtf8(metric.windowId))
            }.bytes()
        })
        field(11, listBytes(snapshot.evidenceReferences.sortedBy { it.id }) { reference ->
            CanonicalRecord().apply {
                field(1, strictUtf8(reference.id))
                field(2, strictUtf8(reference.kind.name))
                field(3, strictUtf8(reference.contentSha256))
                field(4, strictUtf8(reference.title))
                field(5, strictUtf8(reference.sourceUri))
                field(6, strictUtf8(reference.populationAndDevice))
                field(7, strictUtf8(reference.limitations))
                field(8, longBytes(reference.verifiedAtEpochMillis))
            }.bytes()
        })
        field(12, stringListBytes(snapshot.approvedNextMeasurementIds.sorted()))
        field(13, stringListBytes(snapshot.approvedQuestionIds.sorted()))
        field(14, stringListBytes(snapshot.approvedNarrativeTemplateIds.sorted()))
        field(15, stringListBytes(snapshot.qualityGaps.sorted()))
        field(16, strictUtf8(snapshot.policyHashSha256))
    }.bytes()
}

internal class CanonicalRecord {
    private val output = ByteArrayOutputStream()
    private val data = DataOutputStream(output)
    private var lastTag = 0

    fun field(tag: Int, value: ByteArray) {
        require(tag > lastTag) { "Canonical field tags must be strictly increasing" }
        require(value.size <= MAX_CANONICAL_PACKET_BYTES)
        lastTag = tag
        data.writeInt(tag)
        data.writeInt(value.size)
        data.write(value)
    }

    fun bytes(): ByteArray = output.toByteArray()
}

internal fun <T> listBytes(values: List<T>, encode: (T) -> ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    val data = DataOutputStream(output)
    data.writeInt(values.size)
    values.forEach { value ->
        val bytes = encode(value)
        require(bytes.size <= MAX_CANONICAL_PACKET_BYTES)
        data.writeInt(bytes.size)
        data.write(bytes)
    }
    return output.toByteArray()
}

internal fun stringListBytes(values: List<String>): ByteArray = listBytes(values, ::strictUtf8)

internal fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES)
    .order(ByteOrder.BIG_ENDIAN)
    .putLong(value)
    .array()

internal fun strictUtf8(value: String): ByteArray {
    val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val buffer = encoder.encode(java.nio.CharBuffer.wrap(value))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(digits[unsigned ushr 4])
            append(digits[unsigned and 0x0f])
        }
    }
}

internal fun requireReasoningId(value: String, label: String) {
    require(value.matches(REASONING_ID)) { "$label is invalid" }
}

internal fun requireSha256(value: String, label: String) {
    require(value.matches(SHA256)) { "$label is invalid" }
}

internal fun requireNarrativeTemplateId(value: String) {
    require(value.matches(NARRATIVE_TEMPLATE_ID)) { "narrative template id is invalid" }
}

private fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

internal const val HEALTH_STATE_PACKET_SCHEMA = "health-state-packet-v2"
private const val MAX_PACKET_ITEMS = 256
private const val MAX_SHORT_TEXT_BYTES = 256
private const val MAX_LONG_TEXT_BYTES = 4_096
private const val MAX_SIGNATURE_BYTES = 16 * 1024
private const val MAX_CANONICAL_PACKET_BYTES = 1024 * 1024
private val REASONING_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}")
private val NARRATIVE_TEMPLATE_ID = Regex("[a-z][a-z0-9-]*(?:\\.[a-z0-9-]+){2,}")
private val SHA256 = Regex("[a-f0-9]{64}")
