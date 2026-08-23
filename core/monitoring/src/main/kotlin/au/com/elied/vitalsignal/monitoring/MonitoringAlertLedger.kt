package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalAlertAction
import au.com.elied.vitalsignal.governance.ClinicalAlertActionPermit
import au.com.elied.vitalsignal.governance.ClinicalAlertActorRole
import au.com.elied.vitalsignal.governance.ClinicalRulePermit
import au.com.elied.vitalsignal.governance.ProductSurface

enum class MonitoringAlertKind {
    TECHNICAL_DATA_LOSS,
    RESEARCH_PATTERN_REVIEW,
    VALIDATED_CLINICAL_RULE,
}

enum class MonitoringAlertState {
    CREATED,
    ROUTED,
    ACKNOWLEDGED,
    ESCALATED,
    RESOLVED,
    CANCELLED,
}

/** Input for a new alert. Canonical state and version are assigned only by the ledger. */
class MonitoringAlertDraft(
    val alertId: String,
    val sessionId: String,
    val subjectPseudonym: String,
    val kind: MonitoringAlertKind,
    val ruleId: String,
    val ruleVersion: String,
    val environmentFingerprintSha256: String,
    val createdAtEpochMillis: Long,
    val acknowledgeByEpochMillis: Long,
    evidenceIds: List<String>,
    val clinicalRulePermit: ClinicalRulePermit?,
) {
    val evidenceIds: List<String> = java.util.List.copyOf(evidenceIds)

    init {
        validateDefinition(
            alertId = alertId,
            sessionId = sessionId,
            subjectPseudonym = subjectPseudonym,
            kind = kind,
            ruleId = ruleId,
            ruleVersion = ruleVersion,
            environmentFingerprintSha256 = environmentFingerprintSha256,
            createdAtEpochMillis = createdAtEpochMillis,
            acknowledgeByEpochMillis = acknowledgeByEpochMillis,
            evidenceIds = this.evidenceIds,
            clinicalRulePermit = clinicalRulePermit,
        )
    }

    fun copy(
        alertId: String = this.alertId,
        sessionId: String = this.sessionId,
        subjectPseudonym: String = this.subjectPseudonym,
        kind: MonitoringAlertKind = this.kind,
        ruleId: String = this.ruleId,
        ruleVersion: String = this.ruleVersion,
        environmentFingerprintSha256: String = this.environmentFingerprintSha256,
        createdAtEpochMillis: Long = this.createdAtEpochMillis,
        acknowledgeByEpochMillis: Long = this.acknowledgeByEpochMillis,
        evidenceIds: List<String> = this.evidenceIds,
        clinicalRulePermit: ClinicalRulePermit? = this.clinicalRulePermit,
    ) = MonitoringAlertDraft(
        alertId,
        sessionId,
        subjectPseudonym,
        kind,
        ruleId,
        ruleVersion,
        environmentFingerprintSha256,
        createdAtEpochMillis,
        acknowledgeByEpochMillis,
        evidenceIds,
        clinicalRulePermit,
    )
}

/** Immutable canonical snapshot returned by an atomic [MonitoringAlertStore] commit. */
class MonitoringAlert(
    val alertId: String,
    val sessionId: String,
    val subjectPseudonym: String,
    val kind: MonitoringAlertKind,
    val ruleId: String,
    val ruleVersion: String,
    val environmentFingerprintSha256: String,
    val createdAtEpochMillis: Long,
    val acknowledgeByEpochMillis: Long,
    val state: MonitoringAlertState,
    val version: Long,
    evidenceIds: List<String>,
    val clinicalRulePermit: ClinicalRulePermit?,
    val lastTransitionId: String,
    val lastTransitionAtEpochMillis: Long,
    val lastActorId: String,
    val lastActorRole: ClinicalAlertActorRole,
    val lastActionPermitReceiptId: String,
) {
    val evidenceIds: List<String> = java.util.List.copyOf(evidenceIds)

    init {
        validateDefinition(
            alertId = alertId,
            sessionId = sessionId,
            subjectPseudonym = subjectPseudonym,
            kind = kind,
            ruleId = ruleId,
            ruleVersion = ruleVersion,
            environmentFingerprintSha256 = environmentFingerprintSha256,
            createdAtEpochMillis = createdAtEpochMillis,
            acknowledgeByEpochMillis = acknowledgeByEpochMillis,
            evidenceIds = this.evidenceIds,
            clinicalRulePermit = clinicalRulePermit,
        )
        require(version >= 0L)
        require((state == MonitoringAlertState.CREATED) == (version == 0L))
        require(lastTransitionId.isNotBlank())
        require(lastTransitionAtEpochMillis >= createdAtEpochMillis)
        require(lastActorId.isNotBlank())
        require(lastActionPermitReceiptId.isNotBlank())
    }

    fun copy(
        alertId: String = this.alertId,
        sessionId: String = this.sessionId,
        subjectPseudonym: String = this.subjectPseudonym,
        kind: MonitoringAlertKind = this.kind,
        ruleId: String = this.ruleId,
        ruleVersion: String = this.ruleVersion,
        environmentFingerprintSha256: String = this.environmentFingerprintSha256,
        createdAtEpochMillis: Long = this.createdAtEpochMillis,
        acknowledgeByEpochMillis: Long = this.acknowledgeByEpochMillis,
        state: MonitoringAlertState = this.state,
        version: Long = this.version,
        evidenceIds: List<String> = this.evidenceIds,
        clinicalRulePermit: ClinicalRulePermit? = this.clinicalRulePermit,
        lastTransitionId: String = this.lastTransitionId,
        lastTransitionAtEpochMillis: Long = this.lastTransitionAtEpochMillis,
        lastActorId: String = this.lastActorId,
        lastActorRole: ClinicalAlertActorRole = this.lastActorRole,
        lastActionPermitReceiptId: String = this.lastActionPermitReceiptId,
    ) = MonitoringAlert(
        alertId,
        sessionId,
        subjectPseudonym,
        kind,
        ruleId,
        ruleVersion,
        environmentFingerprintSha256,
        createdAtEpochMillis,
        acknowledgeByEpochMillis,
        state,
        version,
        evidenceIds,
        clinicalRulePermit,
        lastTransitionId,
        lastTransitionAtEpochMillis,
        lastActorId,
        lastActorRole,
        lastActionPermitReceiptId,
    )

    override fun equals(other: Any?): Boolean =
        other is MonitoringAlert &&
            alertId == other.alertId &&
            sessionId == other.sessionId &&
            subjectPseudonym == other.subjectPseudonym &&
            kind == other.kind &&
            ruleId == other.ruleId &&
            ruleVersion == other.ruleVersion &&
            environmentFingerprintSha256 == other.environmentFingerprintSha256 &&
            createdAtEpochMillis == other.createdAtEpochMillis &&
            acknowledgeByEpochMillis == other.acknowledgeByEpochMillis &&
            state == other.state &&
            version == other.version &&
            evidenceIds == other.evidenceIds &&
            clinicalRulePermit == other.clinicalRulePermit &&
            lastTransitionId == other.lastTransitionId &&
            lastTransitionAtEpochMillis == other.lastTransitionAtEpochMillis &&
            lastActorId == other.lastActorId &&
            lastActorRole == other.lastActorRole &&
            lastActionPermitReceiptId == other.lastActionPermitReceiptId

    override fun hashCode(): Int = listOf(
        alertId,
        sessionId,
        subjectPseudonym,
        kind,
        ruleId,
        ruleVersion,
        environmentFingerprintSha256,
        createdAtEpochMillis,
        acknowledgeByEpochMillis,
        state,
        version,
        evidenceIds,
        clinicalRulePermit,
        lastTransitionId,
        lastTransitionAtEpochMillis,
        lastActorId,
        lastActorRole,
        lastActionPermitReceiptId,
    ).hashCode()
}

data class MonitoringAlertAuditEntry(
    val transitionId: String,
    val alertId: String,
    val from: MonitoringAlertState?,
    val to: MonitoringAlertState,
    val fromVersion: Long?,
    val toVersion: Long,
    val actorId: String,
    val actorRole: ClinicalAlertActorRole,
    val actionPermitReceiptId: String,
    val atEpochMillis: Long,
    val reason: String,
) {
    init {
        require(transitionId.isNotBlank())
        require(alertId.isNotBlank())
        require(actorId.isNotBlank())
        require(actionPermitReceiptId.isNotBlank())
        require(atEpochMillis > 0L)
        require(reason.isNotBlank())
        if (from == null) {
            require(fromVersion == null)
            require(to == MonitoringAlertState.CREATED)
            require(toVersion == 0L)
        } else {
            requireNotNull(fromVersion)
            require(fromVersion >= 0L)
            require(fromVersion < Long.MAX_VALUE)
            require(toVersion == fromVersion + 1L)
        }
    }
}

sealed interface MonitoringAlertCommitResult {
    data class Committed(val alert: MonitoringAlert) : MonitoringAlertCommitResult

    /** The current value is null when an expected alert was not found. */
    data class Conflict(val current: MonitoringAlert?) : MonitoringAlertCommitResult

    data object Failed : MonitoringAlertCommitResult
}

/**
 * Durable alert store boundary. Each write method is one transaction: it must update the canonical
 * alert and append the supplied audit entry together, or persist neither. A committed result may be
 * returned only after both records are durable. Transition IDs must be unique and replay-safe.
 */
interface MonitoringAlertStore {
    fun load(alertId: String): MonitoringAlert?

    fun createAndAppendAudit(
        alert: MonitoringAlert,
        auditEntry: MonitoringAlertAuditEntry,
    ): MonitoringAlertCommitResult

    fun compareAndSetAndAppendAudit(
        alertId: String,
        expectedVersion: Long,
        expectedState: MonitoringAlertState,
        updatedAlert: MonitoringAlert,
        auditEntry: MonitoringAlertAuditEntry,
    ): MonitoringAlertCommitResult
}

class MonitoringAlertConflictException internal constructor(
    alertId: String,
    expectedVersion: Long?,
    expectedState: MonitoringAlertState?,
    current: MonitoringAlert?,
) : IllegalStateException(
    buildString {
        append("Alert compare-and-set conflict for ")
        append(alertId)
        append(": expected version=")
        append(expectedVersion)
        append(", state=")
        append(expectedState)
        append("; current version=")
        append(current?.version)
        append(", state=")
        append(current?.state)
    },
)

/** All canonical alert state changes pass through a version-and-state compare-and-set. */
class MonitoringAlertLedger(
    private val store: MonitoringAlertStore,
) {
    fun load(alertId: String): MonitoringAlert? {
        require(alertId.isNotBlank())
        return store.load(alertId)
    }

    /** Creates version zero and its audit record in the same durable transaction. */
    fun create(
        draft: MonitoringAlertDraft,
        transitionId: String,
        actionPermit: ClinicalAlertActionPermit,
        reason: String,
    ): MonitoringAlert {
        validateActionPermit(
            permit = actionPermit,
            alertId = draft.alertId,
            sessionId = draft.sessionId,
            subjectPseudonym = draft.subjectPseudonym,
            expectedVersion = null,
            action = ClinicalAlertAction.CREATE,
            atEpochMillis = draft.createdAtEpochMillis,
        )
        val alert = MonitoringAlert(
            alertId = draft.alertId,
            sessionId = draft.sessionId,
            subjectPseudonym = draft.subjectPseudonym,
            kind = draft.kind,
            ruleId = draft.ruleId,
            ruleVersion = draft.ruleVersion,
            environmentFingerprintSha256 = draft.environmentFingerprintSha256,
            createdAtEpochMillis = draft.createdAtEpochMillis,
            acknowledgeByEpochMillis = draft.acknowledgeByEpochMillis,
            state = MonitoringAlertState.CREATED,
            version = 0L,
            evidenceIds = draft.evidenceIds.toList(),
            clinicalRulePermit = draft.clinicalRulePermit,
            lastTransitionId = transitionId,
            lastTransitionAtEpochMillis = draft.createdAtEpochMillis,
            lastActorId = actionPermit.actorPrincipalId,
            lastActorRole = actionPermit.actorRole,
            lastActionPermitReceiptId = actionPermit.receiptId,
        )
        val entry = MonitoringAlertAuditEntry(
            transitionId = transitionId,
            alertId = alert.alertId,
            from = null,
            to = MonitoringAlertState.CREATED,
            fromVersion = null,
            toVersion = alert.version,
            actorId = actionPermit.actorPrincipalId,
            actorRole = actionPermit.actorRole,
            actionPermitReceiptId = actionPermit.receiptId,
            atEpochMillis = alert.createdAtEpochMillis,
            reason = reason,
        )
        return commitOrThrow(
            result = store.createAndAppendAudit(alert, entry),
            expectedAlert = alert,
            expectedVersion = null,
            expectedState = null,
        )
    }

    fun transition(
        alertId: String,
        expectedVersion: Long,
        expectedState: MonitoringAlertState,
        to: MonitoringAlertState,
        transitionId: String,
        actionPermit: ClinicalAlertActionPermit,
        atEpochMillis: Long,
        reason: String,
    ): MonitoringAlert {
        require(expectedVersion >= 0L)
        val current = loadExpected(alertId, expectedVersion, expectedState)
        require(isAllowed(current.state, to)) {
            "Illegal alert transition ${current.state} -> $to"
        }
        require(current.version < Long.MAX_VALUE) { "Alert version exhausted" }
        require(atEpochMillis >= current.lastTransitionAtEpochMillis) {
            "Alert transition time precedes the prior committed transition"
        }
        validateActionPermit(
            permit = actionPermit,
            alertId = current.alertId,
            sessionId = current.sessionId,
            subjectPseudonym = current.subjectPseudonym,
            expectedVersion = current.version,
            action = actionFor(to),
            atEpochMillis = atEpochMillis,
        )

        val updated = current.copy(
            state = to,
            version = current.version + 1L,
            lastTransitionId = transitionId,
            lastTransitionAtEpochMillis = atEpochMillis,
            lastActorId = actionPermit.actorPrincipalId,
            lastActorRole = actionPermit.actorRole,
            lastActionPermitReceiptId = actionPermit.receiptId,
        )
        val entry = MonitoringAlertAuditEntry(
            transitionId = transitionId,
            alertId = current.alertId,
            from = current.state,
            to = to,
            fromVersion = current.version,
            toVersion = updated.version,
            actorId = actionPermit.actorPrincipalId,
            actorRole = actionPermit.actorRole,
            actionPermitReceiptId = actionPermit.receiptId,
            atEpochMillis = atEpochMillis,
            reason = reason,
        )
        return commitOrThrow(
            result = store.compareAndSetAndAppendAudit(
                alertId = current.alertId,
                expectedVersion = expectedVersion,
                expectedState = expectedState,
                updatedAlert = updated,
                auditEntry = entry,
            ),
            expectedAlert = updated,
            expectedVersion = expectedVersion,
            expectedState = expectedState,
        )
    }

    fun escalateIfOverdue(
        alertId: String,
        expectedVersion: Long,
        nowEpochMillis: Long,
        transitionId: String,
        actionPermit: ClinicalAlertActionPermit,
    ): MonitoringAlert {
        val current = loadExpected(
            alertId = alertId,
            expectedVersion = expectedVersion,
            expectedState = MonitoringAlertState.ROUTED,
        )
        validateActionPermit(
            permit = actionPermit,
            alertId = current.alertId,
            sessionId = current.sessionId,
            subjectPseudonym = current.subjectPseudonym,
            expectedVersion = current.version,
            action = ClinicalAlertAction.ESCALATE,
            atEpochMillis = nowEpochMillis,
        )
        if (nowEpochMillis < current.acknowledgeByEpochMillis) return current
        return transition(
            alertId = alertId,
            expectedVersion = expectedVersion,
            expectedState = MonitoringAlertState.ROUTED,
            to = MonitoringAlertState.ESCALATED,
            transitionId = transitionId,
            actionPermit = actionPermit,
            atEpochMillis = nowEpochMillis,
            reason = "acknowledgement-deadline-exceeded",
        )
    }

    private fun loadExpected(
        alertId: String,
        expectedVersion: Long,
        expectedState: MonitoringAlertState,
    ): MonitoringAlert {
        require(alertId.isNotBlank())
        require(expectedVersion >= 0L)
        val current = store.load(alertId)
        if (current == null || current.version != expectedVersion || current.state != expectedState) {
            throw MonitoringAlertConflictException(
                alertId = alertId,
                expectedVersion = expectedVersion,
                expectedState = expectedState,
                current = current,
            )
        }
        return current
    }

    private fun commitOrThrow(
        result: MonitoringAlertCommitResult,
        expectedAlert: MonitoringAlert,
        expectedVersion: Long?,
        expectedState: MonitoringAlertState?,
    ): MonitoringAlert = when (result) {
        is MonitoringAlertCommitResult.Committed -> {
            check(result.alert == expectedAlert) { "Alert store returned an unexpected committed value" }
            result.alert
        }
        is MonitoringAlertCommitResult.Conflict -> throw MonitoringAlertConflictException(
            alertId = expectedAlert.alertId,
            expectedVersion = expectedVersion,
            expectedState = expectedState,
            current = result.current,
        )
        MonitoringAlertCommitResult.Failed -> error("Alert state and audit transaction failed")
    }

    private fun validateActionPermit(
        permit: ClinicalAlertActionPermit,
        alertId: String,
        sessionId: String,
        subjectPseudonym: String,
        expectedVersion: Long?,
        action: ClinicalAlertAction,
        atEpochMillis: Long,
    ) {
        require(permit.alertId == alertId) { "Alert-action permit alert mismatch" }
        require(permit.sessionId == sessionId) { "Alert-action permit session mismatch" }
        require(permit.subjectPseudonym == subjectPseudonym) { "Alert-action permit subject mismatch" }
        require(permit.expectedAlertVersion == expectedVersion) { "Alert-action permit version mismatch" }
        require(permit.action == action) { "Alert-action permit action mismatch" }
        require(permit.isCurrentAt(atEpochMillis)) { "Alert-action permit is not current" }
        if (action == ClinicalAlertAction.ACKNOWLEDGE || action == ClinicalAlertAction.RESOLVE) {
            require(permit.actorRole == ClinicalAlertActorRole.CLINICIAN_OBSERVER)
            requireNotNull(permit.clinicianShareGrantId) {
                "Clinician alert actions require current clinician-share authority"
            }
        }
    }

    private fun actionFor(to: MonitoringAlertState): ClinicalAlertAction = when (to) {
        MonitoringAlertState.ROUTED -> ClinicalAlertAction.ROUTE
        MonitoringAlertState.ACKNOWLEDGED -> ClinicalAlertAction.ACKNOWLEDGE
        MonitoringAlertState.ESCALATED -> ClinicalAlertAction.ESCALATE
        MonitoringAlertState.RESOLVED -> ClinicalAlertAction.RESOLVE
        MonitoringAlertState.CANCELLED -> ClinicalAlertAction.CANCEL
        MonitoringAlertState.CREATED -> error("Created is not a transition target")
    }

    private fun isAllowed(from: MonitoringAlertState, to: MonitoringAlertState): Boolean = when (from) {
        MonitoringAlertState.CREATED -> to == MonitoringAlertState.ROUTED || to == MonitoringAlertState.CANCELLED
        MonitoringAlertState.ROUTED -> to in setOf(
            MonitoringAlertState.ACKNOWLEDGED,
            MonitoringAlertState.ESCALATED,
            MonitoringAlertState.CANCELLED,
        )
        MonitoringAlertState.ACKNOWLEDGED -> to in setOf(
            MonitoringAlertState.RESOLVED,
            MonitoringAlertState.ESCALATED,
        )
        // An escalation caused by non-response is not acknowledgement; it must be acknowledged first.
        MonitoringAlertState.ESCALATED -> to == MonitoringAlertState.ACKNOWLEDGED
        MonitoringAlertState.RESOLVED,
        MonitoringAlertState.CANCELLED,
        -> false
    }
}

private fun validateDefinition(
    alertId: String,
    sessionId: String,
    subjectPseudonym: String,
    kind: MonitoringAlertKind,
    ruleId: String,
    ruleVersion: String,
    environmentFingerprintSha256: String,
    createdAtEpochMillis: Long,
    acknowledgeByEpochMillis: Long,
    evidenceIds: List<String>,
    clinicalRulePermit: ClinicalRulePermit?,
) {
    require(alertId.isNotBlank())
    require(sessionId.isNotBlank())
    require(subjectPseudonym.isNotBlank())
    require(ruleId.isNotBlank())
    require(ruleVersion.isNotBlank())
    require(environmentFingerprintSha256.matches(Regex("[a-f0-9]{64}")))
    require(createdAtEpochMillis > 0L)
    require(acknowledgeByEpochMillis > createdAtEpochMillis)
    require(evidenceIds.isNotEmpty())
    require(evidenceIds.all { it.isNotBlank() })
    require(evidenceIds.distinct().size == evidenceIds.size)

    if (kind == MonitoringAlertKind.VALIDATED_CLINICAL_RULE) {
        val permit = requireNotNull(clinicalRulePermit) {
            "Validated clinical alerts require a verified clinical-rule permit"
        }
        require(permit.ruleId == ruleId) { "Clinical-rule permit rule mismatch" }
        require(permit.ruleVersion == ruleVersion) { "Clinical-rule permit version mismatch" }
        require(permit.environmentFingerprintSha256 == environmentFingerprintSha256) {
            "Clinical-rule permit environment mismatch"
        }
        require(permit.sessionId == sessionId) { "Clinical-rule permit session mismatch" }
        require(permit.subjectPseudonym == subjectPseudonym) { "Clinical-rule permit subject mismatch" }
        require(permit.medicalSurface == ProductSurface.MEDICAL_INTENDED_USE) {
            "Clinical-rule permit is not promoted for medical intended use"
        }
        require(permit.isCurrentAt(createdAtEpochMillis)) { "Clinical-rule permit is not current" }
    } else {
        require(clinicalRulePermit == null) {
            "Clinical-rule permits may be attached only to validated clinical alerts"
        }
    }
}
