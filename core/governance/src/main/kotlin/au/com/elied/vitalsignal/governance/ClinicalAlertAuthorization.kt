package au.com.elied.vitalsignal.governance

enum class ClinicalAlertAction {
    CREATE,
    ROUTE,
    ACKNOWLEDGE,
    ESCALATE,
    RESOLVE,
    CANCEL,
}

enum class ClinicalAlertActorRole {
    ALERT_ENGINE,
    ROUTING_SERVICE,
    CLINICIAN_OBSERVER,
    ESCALATION_SERVICE,
    OPERATIONS,
}

/** Signed authority for exactly one alert action at one canonical alert version. */
class ClinicalAlertActionReceipt(
    val receiptId: String,
    val alertId: String,
    val sessionId: String,
    val subjectPseudonym: String,
    val expectedAlertVersion: Long?,
    val actorPrincipalId: String,
    val actorRole: ClinicalAlertActorRole,
    val action: ClinicalAlertAction,
    val issuedAtEpochMillis: Long,
    val startsAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val issuerKeyId: String,
    signature: ByteArray,
) {
    private val signatureSnapshot = signature.copyOf()
    val signature: ByteArray get() = signatureSnapshot.copyOf()

    init {
        requireIdentifier(receiptId, "receiptId")
        requireIdentifier(alertId, "alertId")
        requireIdentifier(sessionId, "sessionId")
        requireIdentifier(subjectPseudonym, "subjectPseudonym")
        requireIdentifier(actorPrincipalId, "actorPrincipalId")
        if (action == ClinicalAlertAction.CREATE) {
            require(expectedAlertVersion == null) { "Create authority cannot target an existing version" }
        } else {
            requireNotNull(expectedAlertVersion) { "Transition authority requires an expected version" }
            require(expectedAlertVersion >= 0L)
        }
        require(issuedAtEpochMillis > 0L)
        require(startsAtEpochMillis >= issuedAtEpochMillis)
        require(expiresAtEpochMillis > startsAtEpochMillis)
        require(issuerKeyId.isNotBlank())
        require(signatureSnapshot.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean =
        other is ClinicalAlertActionReceipt &&
            receiptId == other.receiptId &&
            alertId == other.alertId &&
            sessionId == other.sessionId &&
            subjectPseudonym == other.subjectPseudonym &&
            expectedAlertVersion == other.expectedAlertVersion &&
            actorPrincipalId == other.actorPrincipalId &&
            actorRole == other.actorRole &&
            action == other.action &&
            issuedAtEpochMillis == other.issuedAtEpochMillis &&
            startsAtEpochMillis == other.startsAtEpochMillis &&
            expiresAtEpochMillis == other.expiresAtEpochMillis &&
            issuerKeyId == other.issuerKeyId &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int = 31 * listOf(
        receiptId,
        alertId,
        sessionId,
        subjectPseudonym,
        expectedAlertVersion,
        actorPrincipalId,
        actorRole,
        action,
        issuedAtEpochMillis,
        startsAtEpochMillis,
        expiresAtEpochMillis,
        issuerKeyId,
    ).hashCode() + signatureSnapshot.contentHashCode()

    fun copy(
        receiptId: String = this.receiptId,
        alertId: String = this.alertId,
        sessionId: String = this.sessionId,
        subjectPseudonym: String = this.subjectPseudonym,
        expectedAlertVersion: Long? = this.expectedAlertVersion,
        actorPrincipalId: String = this.actorPrincipalId,
        actorRole: ClinicalAlertActorRole = this.actorRole,
        action: ClinicalAlertAction = this.action,
        issuedAtEpochMillis: Long = this.issuedAtEpochMillis,
        startsAtEpochMillis: Long = this.startsAtEpochMillis,
        expiresAtEpochMillis: Long = this.expiresAtEpochMillis,
        issuerKeyId: String = this.issuerKeyId,
        signature: ByteArray = this.signature,
    ) = ClinicalAlertActionReceipt(
        receiptId,
        alertId,
        sessionId,
        subjectPseudonym,
        expectedAlertVersion,
        actorPrincipalId,
        actorRole,
        action,
        issuedAtEpochMillis,
        startsAtEpochMillis,
        expiresAtEpochMillis,
        issuerKeyId,
        signature,
    )
}

fun interface ClinicalAlertActionReceiptVerifier {
    fun verify(receipt: ClinicalAlertActionReceipt): Boolean
}

/** Non-copyable, short-lived authority consumed by the alert ledger. */
class ClinicalAlertActionPermit private constructor(
    val receiptId: String,
    val alertId: String,
    val sessionId: String,
    val subjectPseudonym: String,
    val expectedAlertVersion: Long?,
    val actorPrincipalId: String,
    val actorRole: ClinicalAlertActorRole,
    val action: ClinicalAlertAction,
    val issuedAtEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val clinicianShareGrantId: String?,
) {
    fun isCurrentAt(epochMillis: Long): Boolean =
        epochMillis >= issuedAtEpochMillis && epochMillis < validUntilEpochMillis

    companion object {
        internal fun issue(
            receiptId: String,
            alertId: String,
            sessionId: String,
            subjectPseudonym: String,
            expectedAlertVersion: Long?,
            actorPrincipalId: String,
            actorRole: ClinicalAlertActorRole,
            action: ClinicalAlertAction,
            issuedAtEpochMillis: Long,
            validUntilEpochMillis: Long,
            clinicianShareGrantId: String?,
        ) = ClinicalAlertActionPermit(
            receiptId,
            alertId,
            sessionId,
            subjectPseudonym,
            expectedAlertVersion,
            actorPrincipalId,
            actorRole,
            action,
            issuedAtEpochMillis,
            validUntilEpochMillis,
            clinicianShareGrantId,
        )
    }
}

enum class ClinicalAlertActionPermitDenialReason {
    SIGNATURE_INVALID,
    NOT_YET_ACTIVE,
    EXPIRED,
    ROLE_ACTION_MISMATCH,
    CLINICIAN_SHARE_REQUIRED,
    CLINICIAN_SHARE_BINDING_MISMATCH,
    CLINICIAN_SHARE_NOT_CURRENT,
    CLINICIAN_SHARE_NOT_ALLOWED,
}

sealed interface ClinicalAlertActionPermitDecision {
    class Allowed internal constructor(
        val permit: ClinicalAlertActionPermit,
    ) : ClinicalAlertActionPermitDecision

    data class Denied(
        val reason: ClinicalAlertActionPermitDenialReason,
    ) : ClinicalAlertActionPermitDecision
}

class ClinicalAlertActionPermitIssuer(
    private val verifier: ClinicalAlertActionReceiptVerifier,
    private val maximumPermitLifetimeMillis: Long = DEFAULT_ALERT_ACTION_PERMIT_LIFETIME_MILLIS,
) {
    init {
        require(maximumPermitLifetimeMillis in 1L..MAXIMUM_ALERT_ACTION_PERMIT_LIFETIME_MILLIS)
    }

    fun issue(
        receipt: ClinicalAlertActionReceipt,
        evaluatedAtEpochMillis: Long,
        clinicianSharePermit: ClinicianSharePermit? = null,
    ): ClinicalAlertActionPermitDecision {
        require(evaluatedAtEpochMillis > 0L)
        if (!verifier.verify(receipt)) {
            return ClinicalAlertActionPermitDecision.Denied(
                ClinicalAlertActionPermitDenialReason.SIGNATURE_INVALID,
            )
        }
        if (evaluatedAtEpochMillis < receipt.startsAtEpochMillis) {
            return ClinicalAlertActionPermitDecision.Denied(
                ClinicalAlertActionPermitDenialReason.NOT_YET_ACTIVE,
            )
        }
        if (evaluatedAtEpochMillis >= receipt.expiresAtEpochMillis) {
            return ClinicalAlertActionPermitDecision.Denied(
                ClinicalAlertActionPermitDenialReason.EXPIRED,
            )
        }
        if (receipt.action !in allowedActions(receipt.actorRole)) {
            return ClinicalAlertActionPermitDecision.Denied(
                ClinicalAlertActionPermitDenialReason.ROLE_ACTION_MISMATCH,
            )
        }

        var validUntil = minOf(
            receipt.expiresAtEpochMillis,
            saturatedAlertActionAdd(evaluatedAtEpochMillis, maximumPermitLifetimeMillis),
        )
        var shareGrantId: String? = null
        if (receipt.actorRole == ClinicalAlertActorRole.CLINICIAN_OBSERVER) {
            val sharePermit = clinicianSharePermit
                ?: return ClinicalAlertActionPermitDecision.Denied(
                    ClinicalAlertActionPermitDenialReason.CLINICIAN_SHARE_REQUIRED,
                )
            if (sharePermit.capability != PilotCapability.CLINICIAN_LIVE_SHARE) {
                return ClinicalAlertActionPermitDecision.Denied(
                    ClinicalAlertActionPermitDenialReason.CLINICIAN_SHARE_NOT_ALLOWED,
                )
            }
            if (sharePermit.sessionId != receipt.sessionId ||
                sharePermit.subjectPseudonym != receipt.subjectPseudonym ||
                sharePermit.observerPrincipalId != receipt.actorPrincipalId
            ) {
                return ClinicalAlertActionPermitDecision.Denied(
                    ClinicalAlertActionPermitDenialReason.CLINICIAN_SHARE_BINDING_MISMATCH,
                )
            }
            if (!sharePermit.isCurrentAt(evaluatedAtEpochMillis)) {
                return ClinicalAlertActionPermitDecision.Denied(
                    ClinicalAlertActionPermitDenialReason.CLINICIAN_SHARE_NOT_CURRENT,
                )
            }
            validUntil = minOf(validUntil, sharePermit.validUntilEpochMillis)
            shareGrantId = sharePermit.grantId
        } else if (clinicianSharePermit != null) {
            return ClinicalAlertActionPermitDecision.Denied(
                ClinicalAlertActionPermitDenialReason.CLINICIAN_SHARE_NOT_ALLOWED,
            )
        }

        return ClinicalAlertActionPermitDecision.Allowed(
            ClinicalAlertActionPermit.issue(
                receiptId = receipt.receiptId,
                alertId = receipt.alertId,
                sessionId = receipt.sessionId,
                subjectPseudonym = receipt.subjectPseudonym,
                expectedAlertVersion = receipt.expectedAlertVersion,
                actorPrincipalId = receipt.actorPrincipalId,
                actorRole = receipt.actorRole,
                action = receipt.action,
                issuedAtEpochMillis = evaluatedAtEpochMillis,
                validUntilEpochMillis = validUntil,
                clinicianShareGrantId = shareGrantId,
            ),
        )
    }

    private fun allowedActions(role: ClinicalAlertActorRole): Set<ClinicalAlertAction> = when (role) {
        ClinicalAlertActorRole.ALERT_ENGINE -> setOf(ClinicalAlertAction.CREATE)
        ClinicalAlertActorRole.ROUTING_SERVICE -> setOf(ClinicalAlertAction.ROUTE)
        ClinicalAlertActorRole.CLINICIAN_OBSERVER -> setOf(
            ClinicalAlertAction.ACKNOWLEDGE,
            ClinicalAlertAction.RESOLVE,
        )
        ClinicalAlertActorRole.ESCALATION_SERVICE -> setOf(ClinicalAlertAction.ESCALATE)
        ClinicalAlertActorRole.OPERATIONS -> setOf(ClinicalAlertAction.CANCEL)
    }
}

private fun saturatedAlertActionAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private const val DEFAULT_ALERT_ACTION_PERMIT_LIFETIME_MILLIS = 60_000L
private const val MAXIMUM_ALERT_ACTION_PERMIT_LIFETIME_MILLIS = 5 * 60_000L
