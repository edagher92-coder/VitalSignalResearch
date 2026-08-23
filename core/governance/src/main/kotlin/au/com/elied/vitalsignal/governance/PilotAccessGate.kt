package au.com.elied.vitalsignal.governance

import java.nio.ByteBuffer
import java.security.MessageDigest

enum class PilotCapability {
    WATCH_PASSIVE_COLLECTION,
    WATCH_RESEARCH_CAPTURE,
    PHONE_SAMSUNG_HEALTH_HISTORY,
    PHONE_HEALTH_CONNECT_HISTORY,
    PHONE_FHIR_MEDICAL_RECORDS,
    LOCAL_REASONING,
    PERSONAL_INTERPRETATION,
    RESEARCH_EXPORT,
    CLINICIAN_LIVE_SHARE,
}

enum class ConsentScope {
    PASSIVE_WATCH_DATA,
    RAW_RESEARCH_SIGNALS,
    SAMSUNG_HEALTH_HISTORY,
    HEALTH_CONNECT_HISTORY,
    MEDICAL_RECORDS,
    LOCAL_AI_PROCESSING,
    PERSONAL_INSIGHTS,
    DATA_EXPORT,
    CLINICIAN_LIVE_DATA_SHARE,
}

class ConsentGrant(
    val subjectPseudonym: String,
    val generation: Long,
    scopes: Set<ConsentScope>,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val consentTextSha256: String,
    val signerKeyId: String,
    signature: ByteArray,
) {
    val scopes: Set<ConsentScope> = java.util.Set.copyOf(scopes)
    private val signatureSnapshot: ByteArray = signature.copyOf()
    val signature: ByteArray get() = signatureSnapshot.copyOf()

    init {
        require(subjectPseudonym.isNotBlank())
        require(generation > 0L)
        require(this.scopes.isNotEmpty())
        require(issuedAtEpochMillis > 0L)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > issuedAtEpochMillis)
        require(consentTextSha256.matches(Regex("[a-f0-9]{64}")))
        require(signerKeyId.isNotBlank())
        require(signatureSnapshot.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean =
        other is ConsentGrant &&
            subjectPseudonym == other.subjectPseudonym &&
            generation == other.generation &&
            scopes == other.scopes &&
            issuedAtEpochMillis == other.issuedAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            consentTextSha256 == other.consentTextSha256 &&
            signerKeyId == other.signerKeyId &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * listOf(
        subjectPseudonym,
        generation,
        scopes,
        issuedAtEpochMillis,
        expiresAtEpochMillis,
        consentTextSha256,
        signerKeyId,
    ).hashCode() + signatureSnapshot.contentHashCode()

    fun copy(
        subjectPseudonym: String = this.subjectPseudonym,
        generation: Long = this.generation,
        scopes: Set<ConsentScope> = this.scopes,
        issuedAtEpochMillis: Long = this.issuedAtEpochMillis,
        expiresAtEpochMillis: Long? = this.expiresAtEpochMillis,
        consentTextSha256: String = this.consentTextSha256,
        signerKeyId: String = this.signerKeyId,
        signature: ByteArray = this.signature,
    ) = ConsentGrant(
        subjectPseudonym,
        generation,
        scopes,
        issuedAtEpochMillis,
        expiresAtEpochMillis,
        consentTextSha256,
        signerKeyId,
        signature,
    )
}

fun interface ConsentGrantVerifier {
    fun verify(grant: ConsentGrant): Boolean
}

class ValidationReceipt(
    val receiptId: String,
    val capability: PilotCapability,
    val appVersion: String,
    val deviceModel: String,
    val firmwareGeneration: String,
    val dataSchemaVersion: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    evidenceIds: List<String>,
    val evidenceBundleSha256: String,
    val issuerKeyId: String,
    signature: ByteArray,
) {
    val evidenceIds: List<String> = java.util.List.copyOf(evidenceIds)
    private val signatureSnapshot: ByteArray = signature.copyOf()
    val signature: ByteArray get() = signatureSnapshot.copyOf()

    init {
        require(receiptId.isNotBlank())
        require(appVersion.isNotBlank())
        require(deviceModel.isNotBlank())
        require(firmwareGeneration.isNotBlank())
        require(dataSchemaVersion.isNotBlank())
        require(issuedAtEpochMillis > 0L)
        require(expiresAtEpochMillis > issuedAtEpochMillis)
        require(this.evidenceIds.isNotEmpty())
        require(this.evidenceIds.all { it.isNotBlank() })
        require(this.evidenceIds.distinct().size == this.evidenceIds.size)
        require(evidenceBundleSha256.matches(Regex("[a-f0-9]{64}")))
        require(issuerKeyId.isNotBlank())
        require(signatureSnapshot.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean =
        other is ValidationReceipt &&
            receiptId == other.receiptId &&
            capability == other.capability &&
            appVersion == other.appVersion &&
            deviceModel == other.deviceModel &&
            firmwareGeneration == other.firmwareGeneration &&
            dataSchemaVersion == other.dataSchemaVersion &&
            issuedAtEpochMillis == other.issuedAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            evidenceIds == other.evidenceIds &&
            evidenceBundleSha256 == other.evidenceBundleSha256 &&
            issuerKeyId == other.issuerKeyId &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * listOf(
        receiptId,
        capability,
        appVersion,
        deviceModel,
        firmwareGeneration,
        dataSchemaVersion,
        issuedAtEpochMillis,
        expiresAtEpochMillis,
        evidenceIds,
        evidenceBundleSha256,
        issuerKeyId,
    ).hashCode() + signatureSnapshot.contentHashCode()

    fun copy(
        receiptId: String = this.receiptId,
        capability: PilotCapability = this.capability,
        appVersion: String = this.appVersion,
        deviceModel: String = this.deviceModel,
        firmwareGeneration: String = this.firmwareGeneration,
        dataSchemaVersion: String = this.dataSchemaVersion,
        issuedAtEpochMillis: Long = this.issuedAtEpochMillis,
        expiresAtEpochMillis: Long = this.expiresAtEpochMillis,
        evidenceIds: List<String> = this.evidenceIds,
        evidenceBundleSha256: String = this.evidenceBundleSha256,
        issuerKeyId: String = this.issuerKeyId,
        signature: ByteArray = this.signature,
    ) = ValidationReceipt(
        receiptId,
        capability,
        appVersion,
        deviceModel,
        firmwareGeneration,
        dataSchemaVersion,
        issuedAtEpochMillis,
        expiresAtEpochMillis,
        evidenceIds,
        evidenceBundleSha256,
        issuerKeyId,
        signature,
    )
}

fun interface ValidationReceiptVerifier {
    fun verify(receipt: ValidationReceipt): Boolean
}

data class PilotGateRequest(
    val capability: PilotCapability,
    val subjectPseudonym: String,
    val consentGeneration: Long,
    val appVersion: String,
    val deviceModel: String,
    val firmwareGeneration: String,
    val dataSchemaVersion: String,
    val evaluatedAtEpochMillis: Long,
    val collectionPaused: Boolean,
    val recoveryRequired: Boolean,
) {
    init {
        require(subjectPseudonym.isNotBlank())
        require(consentGeneration > 0L)
        require(appVersion.isNotBlank())
        require(deviceModel.isNotBlank())
        require(firmwareGeneration.isNotBlank())
        require(dataSchemaVersion.isNotBlank())
        require(evaluatedAtEpochMillis > 0L)
    }
}

enum class PilotGateReason {
    ALLOWED,
    PAUSED,
    RECOVERY_REQUIRED,
    SUBJECT_MISMATCH,
    CONSENT_GENERATION_MISMATCH,
    CONSENT_NOT_YET_ACTIVE,
    CONSENT_EXPIRED,
    CONSENT_SIGNATURE_INVALID,
    CONSENT_SCOPE_MISSING,
    VALIDATION_RECEIPT_MISSING,
    VALIDATION_RECEIPT_NOT_YET_ACTIVE,
    VALIDATION_RECEIPT_EXPIRED,
    VALIDATION_SIGNATURE_INVALID,
    VALIDATION_ENVIRONMENT_MISMATCH,
}

/**
 * Opaque result of [PilotAccessGate]. The sole implementation is private to the gate, so callers
 * cannot construct, copy, subclass, or deserialize an `allowed` decision themselves.
 *
 * An allowed decision is a short-lived capability lease bound to the exact subject, capability,
 * consent generation, signed consent grant, and signed validation receipt that the gate verified.
 */
sealed interface PilotGateDecision {
    val allowed: Boolean
    val reason: PilotGateReason
    val capability: PilotCapability
    val subjectPseudonym: String
    val consentGeneration: Long
    val validationReceiptId: String?
    val evaluatedAtEpochMillis: Long
    val authorizationExpiresAtEpochMillis: Long?
    val consentGrantSha256: String?
    val validationReceiptSha256: String?

    /** Exact, time-bounded use check for a downstream capability boundary. */
    fun authorizes(
        capability: PilotCapability,
        subjectPseudonym: String,
        consentGeneration: Long,
        atEpochMillis: Long,
    ): Boolean
}

/**
 * Central fail-closed activation gate. A UI toggle, permission grant or model
 * result can never substitute for matching consent and validation receipts.
 */
class PilotAccessGate(
    private val consentVerifier: ConsentGrantVerifier,
    private val validationVerifier: ValidationReceiptVerifier,
) {
    fun evaluate(
        request: PilotGateRequest,
        consent: ConsentGrant,
        validationReceipts: List<ValidationReceipt>,
    ): PilotGateDecision {
        fun deny(reason: PilotGateReason, receiptId: String? = null) = GateIssuedDecision(
            allowed = false,
            reason = reason,
            capability = request.capability,
            subjectPseudonym = request.subjectPseudonym,
            consentGeneration = request.consentGeneration,
            validationReceiptId = receiptId,
            evaluatedAtEpochMillis = request.evaluatedAtEpochMillis,
            authorizationExpiresAtEpochMillis = null,
            consentGrantSha256 = null,
            validationReceiptSha256 = null,
        )

        if (request.recoveryRequired) return deny(PilotGateReason.RECOVERY_REQUIRED)
        if (request.collectionPaused) return deny(PilotGateReason.PAUSED)
        if (request.subjectPseudonym != consent.subjectPseudonym) {
            return deny(PilotGateReason.SUBJECT_MISMATCH)
        }
        if (request.consentGeneration != consent.generation) {
            return deny(PilotGateReason.CONSENT_GENERATION_MISMATCH)
        }
        if (request.evaluatedAtEpochMillis < consent.issuedAtEpochMillis) {
            return deny(PilotGateReason.CONSENT_NOT_YET_ACTIVE)
        }
        if (consent.expiresAtEpochMillis != null && request.evaluatedAtEpochMillis >= consent.expiresAtEpochMillis) {
            return deny(PilotGateReason.CONSENT_EXPIRED)
        }
        if (!consentVerifier.verify(consent)) {
            return deny(PilotGateReason.CONSENT_SIGNATURE_INVALID)
        }
        if (requiredScope(request.capability) !in consent.scopes) {
            return deny(PilotGateReason.CONSENT_SCOPE_MISSING)
        }

        val candidates = validationReceipts.filter { it.capability == request.capability }
        if (candidates.isEmpty()) return deny(PilotGateReason.VALIDATION_RECEIPT_MISSING)
        val environmentMatched = candidates.filter { receipt ->
            receipt.appVersion == request.appVersion &&
                receipt.deviceModel == request.deviceModel &&
                receipt.firmwareGeneration == request.firmwareGeneration &&
                receipt.dataSchemaVersion == request.dataSchemaVersion
        }
        if (environmentMatched.isEmpty()) {
            return deny(PilotGateReason.VALIDATION_ENVIRONMENT_MISMATCH)
        }
        val current = environmentMatched.maxBy { it.issuedAtEpochMillis }
        if (request.evaluatedAtEpochMillis < current.issuedAtEpochMillis) {
            return deny(PilotGateReason.VALIDATION_RECEIPT_NOT_YET_ACTIVE, current.receiptId)
        }
        if (request.evaluatedAtEpochMillis >= current.expiresAtEpochMillis) {
            return deny(PilotGateReason.VALIDATION_RECEIPT_EXPIRED, current.receiptId)
        }
        if (!validationVerifier.verify(current)) {
            return deny(PilotGateReason.VALIDATION_SIGNATURE_INVALID, current.receiptId)
        }
        val authorizationExpiresAt = minOf(
            saturatedAdd(request.evaluatedAtEpochMillis, MAXIMUM_DECISION_LIFETIME_MILLIS),
            consent.expiresAtEpochMillis ?: Long.MAX_VALUE,
            current.expiresAtEpochMillis,
        )
        if (authorizationExpiresAt <= request.evaluatedAtEpochMillis) {
            return deny(PilotGateReason.VALIDATION_RECEIPT_EXPIRED, current.receiptId)
        }
        return GateIssuedDecision(
            allowed = true,
            reason = PilotGateReason.ALLOWED,
            capability = request.capability,
            subjectPseudonym = request.subjectPseudonym,
            consentGeneration = request.consentGeneration,
            validationReceiptId = current.receiptId,
            evaluatedAtEpochMillis = request.evaluatedAtEpochMillis,
            authorizationExpiresAtEpochMillis = authorizationExpiresAt,
            consentGrantSha256 = consent.exactBindingSha256(),
            validationReceiptSha256 = current.exactBindingSha256(),
        )
    }

    /** Private implementation makes an allowed decision impossible to mint outside this gate. */
    private class GateIssuedDecision(
        override val allowed: Boolean,
        override val reason: PilotGateReason,
        override val capability: PilotCapability,
        override val subjectPseudonym: String,
        override val consentGeneration: Long,
        override val validationReceiptId: String?,
        override val evaluatedAtEpochMillis: Long,
        override val authorizationExpiresAtEpochMillis: Long?,
        override val consentGrantSha256: String?,
        override val validationReceiptSha256: String?,
    ) : PilotGateDecision {
        override fun authorizes(
            capability: PilotCapability,
            subjectPseudonym: String,
            consentGeneration: Long,
            atEpochMillis: Long,
        ): Boolean {
            val expiresAt = authorizationExpiresAtEpochMillis ?: return false
            return allowed &&
                reason == PilotGateReason.ALLOWED &&
                this.capability == capability &&
                this.subjectPseudonym == subjectPseudonym &&
                this.consentGeneration == consentGeneration &&
                validationReceiptId?.isNotBlank() == true &&
                consentGrantSha256?.matches(SHA256_PATTERN) == true &&
                validationReceiptSha256?.matches(SHA256_PATTERN) == true &&
                atEpochMillis in evaluatedAtEpochMillis until expiresAt
        }
    }

    private fun requiredScope(capability: PilotCapability): ConsentScope = when (capability) {
        PilotCapability.WATCH_PASSIVE_COLLECTION -> ConsentScope.PASSIVE_WATCH_DATA
        PilotCapability.WATCH_RESEARCH_CAPTURE -> ConsentScope.RAW_RESEARCH_SIGNALS
        PilotCapability.PHONE_SAMSUNG_HEALTH_HISTORY -> ConsentScope.SAMSUNG_HEALTH_HISTORY
        PilotCapability.PHONE_HEALTH_CONNECT_HISTORY -> ConsentScope.HEALTH_CONNECT_HISTORY
        PilotCapability.PHONE_FHIR_MEDICAL_RECORDS -> ConsentScope.MEDICAL_RECORDS
        PilotCapability.LOCAL_REASONING -> ConsentScope.LOCAL_AI_PROCESSING
        PilotCapability.PERSONAL_INTERPRETATION -> ConsentScope.PERSONAL_INSIGHTS
        PilotCapability.RESEARCH_EXPORT -> ConsentScope.DATA_EXPORT
        PilotCapability.CLINICIAN_LIVE_SHARE -> ConsentScope.CLINICIAN_LIVE_DATA_SHARE
    }

    private companion object {
        const val MAXIMUM_DECISION_LIFETIME_MILLIS = 60_000L
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
    }
}

private fun ConsentGrant.exactBindingSha256(): String = exactSha256(
    listOf(
        subjectPseudonym.utf8(),
        generation.toString().utf8(),
        scopes.map { it.name }.sorted().joinToString(",").utf8(),
        issuedAtEpochMillis.toString().utf8(),
        expiresAtEpochMillis?.toString()?.utf8(),
        consentTextSha256.utf8(),
        signerKeyId.utf8(),
        signature,
    ),
)

private fun ValidationReceipt.exactBindingSha256(): String = exactSha256(
    listOf(
        receiptId.utf8(),
        capability.name.utf8(),
        appVersion.utf8(),
        deviceModel.utf8(),
        firmwareGeneration.utf8(),
        dataSchemaVersion.utf8(),
        issuedAtEpochMillis.toString().utf8(),
        expiresAtEpochMillis.toString().utf8(),
        evidenceIds.joinToString("\u001f").utf8(),
        evidenceBundleSha256.utf8(),
        issuerKeyId.utf8(),
        signature,
    ),
)

private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

/** Length-prefixing avoids ambiguous concatenation while retaining the exact signed byte values. */
private fun exactSha256(parts: List<ByteArray?>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part ->
        digest.update((if (part == null) 0 else 1).toByte())
        if (part != null) {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(part.size).array())
            digest.update(part)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun saturatedAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
