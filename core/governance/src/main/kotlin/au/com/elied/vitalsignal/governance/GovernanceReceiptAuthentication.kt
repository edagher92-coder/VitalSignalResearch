package au.com.elied.vitalsignal.governance

import au.com.elied.vitalsignal.model.SensorMetric
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun interface GovernanceKeyResolver {
    /** Returns isolated key material for one exact receipt purpose and key id. */
    fun resolve(purpose: GovernanceReceiptPurpose, keyId: String): ByteArray?
}

/** A signing root is authorized for exactly one semantic receipt family. */
enum class GovernanceReceiptPurpose {
    CONSENT,
    VALIDATION,
    PROMOTION_EVIDENCE,
    CLINICIAN_SHARE,
    OBSERVER_HEARTBEAT,
    CLINICAL_RULE_APPROVAL,
    CLINICAL_ALERT_ACTION,
}

/**
 * Private-pilot receipt authority. Production/public releases should replace
 * this symmetric authority with a separately controlled signing service.
 */
class HmacGovernanceAuthority(
    private val keyId: String,
    private val purpose: GovernanceReceiptPurpose,
    keyMaterial: ByteArray,
) {
    private val secret = keyMaterial.copyOf()

    init {
        require(keyId.isNotBlank())
        require(secret.size >= MINIMUM_KEY_BYTES)
    }

    fun issueConsent(
        subjectPseudonym: String,
        generation: Long,
        scopes: Set<ConsentScope>,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long?,
        consentTextSha256: String,
    ): ConsentGrant {
        requirePurpose(GovernanceReceiptPurpose.CONSENT)
        val unsigned = ConsentGrant(
            subjectPseudonym = subjectPseudonym,
            generation = generation,
            scopes = scopes,
            issuedAtEpochMillis = issuedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            consentTextSha256 = consentTextSha256,
            signerKeyId = keyId,
            signature = UNSIGNED_PLACEHOLDER,
        )
        return unsigned.copy(signature = mac(secret, canonicalConsent(unsigned)))
    }

    fun issueValidation(
        receiptId: String,
        capability: PilotCapability,
        appVersion: String,
        deviceModel: String,
        firmwareGeneration: String,
        dataSchemaVersion: String,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        evidenceIds: List<String>,
        evidenceBundleSha256: String,
    ): ValidationReceipt {
        requirePurpose(GovernanceReceiptPurpose.VALIDATION)
        val unsigned = ValidationReceipt(
            receiptId = receiptId,
            capability = capability,
            appVersion = appVersion,
            deviceModel = deviceModel,
            firmwareGeneration = firmwareGeneration,
            dataSchemaVersion = dataSchemaVersion,
            issuedAtEpochMillis = issuedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            evidenceIds = evidenceIds,
            evidenceBundleSha256 = evidenceBundleSha256,
            issuerKeyId = keyId,
            signature = UNSIGNED_PLACEHOLDER,
        )
        return unsigned.copy(signature = mac(secret, canonicalValidation(unsigned)))
    }

    fun issuePromotionEvidence(
        receiptId: String,
        featureId: String,
        featureVersion: String,
        evidenceType: PromotionEvidenceType,
        result: EvidenceResult,
        environmentFingerprintSha256: String,
        protocolOrDatasetSha256: String,
        completedAtEpochMillis: Long,
        expiresAtEpochMillis: Long?,
    ): PromotionEvidenceReceipt {
        requirePurpose(GovernanceReceiptPurpose.PROMOTION_EVIDENCE)
        val unsigned = PromotionEvidenceReceipt(
            receiptId = receiptId,
            featureId = featureId,
            featureVersion = featureVersion,
            evidenceType = evidenceType,
            result = result,
            environmentFingerprintSha256 = environmentFingerprintSha256,
            protocolOrDatasetSha256 = protocolOrDatasetSha256,
            completedAtEpochMillis = completedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            issuerKeyId = keyId,
            signature = UNSIGNED_PLACEHOLDER,
        )
        return unsigned.copy(signature = mac(secret, canonicalPromotion(unsigned)))
    }

    fun issueClinicianShareGrant(
        grantId: String,
        subjectPseudonym: String,
        consentGeneration: Long,
        sessionId: String,
        observerPrincipalId: String,
        metric: SensorMetric,
        dataClass: ClinicalDataClass,
        destinationId: String,
        issuedAtEpochMillis: Long,
        startsAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
        termsSha256: String,
    ): ClinicianShareGrant {
        requirePurpose(GovernanceReceiptPurpose.CLINICIAN_SHARE)
        val unsigned = ClinicianShareGrant(
            grantId = grantId,
            subjectPseudonym = subjectPseudonym,
            consentGeneration = consentGeneration,
            sessionId = sessionId,
            observerPrincipalId = observerPrincipalId,
            metric = metric,
            dataClass = dataClass,
            destinationId = destinationId,
            issuedAtEpochMillis = issuedAtEpochMillis,
            startsAtEpochMillis = startsAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            termsSha256 = termsSha256,
            signerKeyId = keyId,
            signature = UNSIGNED_PLACEHOLDER,
        )
        return unsigned.copy(signature = mac(secret, canonicalClinicianShareGrant(unsigned)))
    }

    fun issueObserverHeartbeat(
        receiptId: String,
        sessionId: String,
        observerPrincipalId: String,
        destinationId: String,
        recordedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): ObserverHeartbeatReceipt {
        requirePurpose(GovernanceReceiptPurpose.OBSERVER_HEARTBEAT)
        val unsigned = ObserverHeartbeatReceipt(
            receiptId = receiptId,
            sessionId = sessionId,
            observerPrincipalId = observerPrincipalId,
            destinationId = destinationId,
            recordedAtEpochMillis = recordedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            issuerKeyId = keyId,
            signature = UNSIGNED_PLACEHOLDER,
        )
        return unsigned.copy(signature = mac(secret, canonicalObserverHeartbeat(unsigned)))
    }

    fun issueClinicalRuleApproval(
        receiptId: String,
        ruleId: String,
        ruleVersion: String,
        medicalFeatureId: String,
        medicalFeatureVersion: String,
        environmentFingerprintSha256: String,
        sessionId: String,
        subjectPseudonym: String,
        approvedAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): ClinicalRuleApprovalReceipt {
        requirePurpose(GovernanceReceiptPurpose.CLINICAL_RULE_APPROVAL)
        val unsigned = ClinicalRuleApprovalReceipt(
            receiptId = receiptId,
            ruleId = ruleId,
            ruleVersion = ruleVersion,
            medicalFeatureId = medicalFeatureId,
            medicalFeatureVersion = medicalFeatureVersion,
            environmentFingerprintSha256 = environmentFingerprintSha256,
            sessionId = sessionId,
            subjectPseudonym = subjectPseudonym,
            approvedAtEpochMillis = approvedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            issuerKeyId = keyId,
            signature = UNSIGNED_PLACEHOLDER,
        )
        return unsigned.copy(signature = mac(secret, canonicalClinicalRuleApproval(unsigned)))
    }

    fun issueClinicalAlertAction(
        receiptId: String,
        alertId: String,
        sessionId: String,
        subjectPseudonym: String,
        expectedAlertVersion: Long?,
        actorPrincipalId: String,
        actorRole: ClinicalAlertActorRole,
        action: ClinicalAlertAction,
        issuedAtEpochMillis: Long,
        startsAtEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): ClinicalAlertActionReceipt {
        requirePurpose(GovernanceReceiptPurpose.CLINICAL_ALERT_ACTION)
        val unsigned = ClinicalAlertActionReceipt(
            receiptId = receiptId,
            alertId = alertId,
            sessionId = sessionId,
            subjectPseudonym = subjectPseudonym,
            expectedAlertVersion = expectedAlertVersion,
            actorPrincipalId = actorPrincipalId,
            actorRole = actorRole,
            action = action,
            issuedAtEpochMillis = issuedAtEpochMillis,
            startsAtEpochMillis = startsAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            issuerKeyId = keyId,
            signature = UNSIGNED_PLACEHOLDER,
        )
        return unsigned.copy(signature = mac(secret, canonicalClinicalAlertAction(unsigned)))
    }

    private fun requirePurpose(required: GovernanceReceiptPurpose) {
        require(purpose == required) {
            "Governance signing authority for $purpose cannot mint $required receipts"
        }
    }

    companion object {
        private const val MINIMUM_KEY_BYTES = 32
        private val UNSIGNED_PLACEHOLDER = byteArrayOf(0)
    }
}

class HmacGovernanceVerifier(
    private val keyResolver: GovernanceKeyResolver,
) : ConsentGrantVerifier,
    ValidationReceiptVerifier,
    PromotionEvidenceVerifier,
    ClinicianShareGrantVerifier,
    ObserverHeartbeatReceiptVerifier,
    ClinicalRuleApprovalVerifier,
    ClinicalAlertActionReceiptVerifier {
    override fun verify(grant: ConsentGrant): Boolean = verifyMac(
        purpose = GovernanceReceiptPurpose.CONSENT,
        keyId = grant.signerKeyId,
        payload = canonicalConsent(grant),
        signature = grant.signature,
    )

    override fun verify(receipt: ValidationReceipt): Boolean = verifyMac(
        purpose = GovernanceReceiptPurpose.VALIDATION,
        keyId = receipt.issuerKeyId,
        payload = canonicalValidation(receipt),
        signature = receipt.signature,
    )

    override fun verify(receipt: PromotionEvidenceReceipt): Boolean = verifyMac(
        purpose = GovernanceReceiptPurpose.PROMOTION_EVIDENCE,
        keyId = receipt.issuerKeyId,
        payload = canonicalPromotion(receipt),
        signature = receipt.signature,
    )

    override fun verify(grant: ClinicianShareGrant): Boolean = verifyMac(
        purpose = GovernanceReceiptPurpose.CLINICIAN_SHARE,
        keyId = grant.signerKeyId,
        payload = canonicalClinicianShareGrant(grant),
        signature = grant.signature,
    )

    override fun verify(receipt: ObserverHeartbeatReceipt): Boolean = verifyMac(
        purpose = GovernanceReceiptPurpose.OBSERVER_HEARTBEAT,
        keyId = receipt.issuerKeyId,
        payload = canonicalObserverHeartbeat(receipt),
        signature = receipt.signature,
    )

    override fun verify(receipt: ClinicalRuleApprovalReceipt): Boolean = verifyMac(
        purpose = GovernanceReceiptPurpose.CLINICAL_RULE_APPROVAL,
        keyId = receipt.issuerKeyId,
        payload = canonicalClinicalRuleApproval(receipt),
        signature = receipt.signature,
    )

    override fun verify(receipt: ClinicalAlertActionReceipt): Boolean = verifyMac(
        purpose = GovernanceReceiptPurpose.CLINICAL_ALERT_ACTION,
        keyId = receipt.issuerKeyId,
        payload = canonicalClinicalAlertAction(receipt),
        signature = receipt.signature,
    )

    private fun verifyMac(
        purpose: GovernanceReceiptPurpose,
        keyId: String,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean {
        val key = keyResolver.resolve(purpose, keyId)?.copyOf() ?: return false
        if (key.size < 32) return false
        return MessageDigest.isEqual(mac(key, payload), signature)
    }
}

private fun canonicalConsent(grant: ConsentGrant): ByteArray = canonicalBytes {
    writeString("vitalsignal.consent.v1")
    writeString(grant.subjectPseudonym)
    writeLong(grant.generation)
    writeStrings(grant.scopes.map { it.name }.sorted())
    writeLong(grant.issuedAtEpochMillis)
    writeNullableLong(grant.expiresAtEpochMillis)
    writeString(grant.consentTextSha256)
    writeString(grant.signerKeyId)
}

private fun canonicalValidation(receipt: ValidationReceipt): ByteArray = canonicalBytes {
    writeString("vitalsignal.validation.v1")
    writeString(receipt.receiptId)
    writeString(receipt.capability.name)
    writeString(receipt.appVersion)
    writeString(receipt.deviceModel)
    writeString(receipt.firmwareGeneration)
    writeString(receipt.dataSchemaVersion)
    writeLong(receipt.issuedAtEpochMillis)
    writeLong(receipt.expiresAtEpochMillis)
    writeStrings(receipt.evidenceIds.sorted())
    writeString(receipt.evidenceBundleSha256)
    writeString(receipt.issuerKeyId)
}

private fun canonicalPromotion(receipt: PromotionEvidenceReceipt): ByteArray = canonicalBytes {
    writeString("vitalsignal.promotion-evidence.v1")
    writeString(receipt.receiptId)
    writeString(receipt.featureId)
    writeString(receipt.featureVersion)
    writeString(receipt.evidenceType.name)
    writeString(receipt.result.name)
    writeString(receipt.environmentFingerprintSha256)
    writeString(receipt.protocolOrDatasetSha256)
    writeLong(receipt.completedAtEpochMillis)
    writeNullableLong(receipt.expiresAtEpochMillis)
    writeString(receipt.issuerKeyId)
}

private fun canonicalClinicianShareGrant(grant: ClinicianShareGrant): ByteArray = canonicalBytes {
    writeString("vitalsignal.clinician-share-grant.v1")
    writeString(grant.grantId)
    writeString(grant.subjectPseudonym)
    writeLong(grant.consentGeneration)
    writeString(grant.sessionId)
    writeString(grant.observerPrincipalId)
    writeString(grant.metric.name)
    writeString(grant.dataClass.name)
    writeString(grant.destinationId)
    writeLong(grant.issuedAtEpochMillis)
    writeLong(grant.startsAtEpochMillis)
    writeLong(grant.expiresAtEpochMillis)
    writeString(grant.termsSha256)
    writeString(grant.signerKeyId)
}

private fun canonicalObserverHeartbeat(receipt: ObserverHeartbeatReceipt): ByteArray = canonicalBytes {
    writeString("vitalsignal.observer-heartbeat.v1")
    writeString(receipt.receiptId)
    writeString(receipt.sessionId)
    writeString(receipt.observerPrincipalId)
    writeString(receipt.destinationId)
    writeLong(receipt.recordedAtEpochMillis)
    writeLong(receipt.expiresAtEpochMillis)
    writeString(receipt.issuerKeyId)
}

private fun canonicalClinicalRuleApproval(receipt: ClinicalRuleApprovalReceipt): ByteArray = canonicalBytes {
    writeString("vitalsignal.clinical-rule-approval.v1")
    writeString(receipt.receiptId)
    writeString(receipt.ruleId)
    writeString(receipt.ruleVersion)
    writeString(receipt.medicalFeatureId)
    writeString(receipt.medicalFeatureVersion)
    writeString(receipt.environmentFingerprintSha256)
    writeString(receipt.sessionId)
    writeString(receipt.subjectPseudonym)
    writeLong(receipt.approvedAtEpochMillis)
    writeLong(receipt.expiresAtEpochMillis)
    writeString(receipt.issuerKeyId)
}

private fun canonicalClinicalAlertAction(receipt: ClinicalAlertActionReceipt): ByteArray = canonicalBytes {
    writeString("vitalsignal.clinical-alert-action.v1")
    writeString(receipt.receiptId)
    writeString(receipt.alertId)
    writeString(receipt.sessionId)
    writeString(receipt.subjectPseudonym)
    writeNullableLong(receipt.expectedAlertVersion)
    writeString(receipt.actorPrincipalId)
    writeString(receipt.actorRole.name)
    writeString(receipt.action.name)
    writeLong(receipt.issuedAtEpochMillis)
    writeLong(receipt.startsAtEpochMillis)
    writeLong(receipt.expiresAtEpochMillis)
    writeString(receipt.issuerKeyId)
}

private fun canonicalBytes(block: CanonicalWriter.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output -> CanonicalWriter(output).block() }
    return bytes.toByteArray()
}

private class CanonicalWriter(private val output: DataOutputStream) {
    fun writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    fun writeStrings(values: List<String>) {
        output.writeInt(values.size)
        values.forEach(::writeString)
    }

    fun writeLong(value: Long) = output.writeLong(value)

    fun writeNullableLong(value: Long?) {
        output.writeBoolean(value != null)
        if (value != null) output.writeLong(value)
    }
}

private fun mac(key: ByteArray, payload: ByteArray): ByteArray = Mac.getInstance(MAC_ALGORITHM).run {
    init(SecretKeySpec(key, MAC_ALGORITHM))
    doFinal(payload)
}

private const val MAC_ALGORITHM = "HmacSHA256"
