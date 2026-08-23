package au.com.elied.vitalsignal.monitoring

import au.com.elied.vitalsignal.governance.ClinicalRuleApprovalReceipt
import au.com.elied.vitalsignal.governance.ClinicalRuleApprovalVerifier
import au.com.elied.vitalsignal.governance.ClinicalRulePermit
import au.com.elied.vitalsignal.governance.ClinicalRulePermitDecision
import au.com.elied.vitalsignal.governance.ClinicalRulePermitIssuer
import au.com.elied.vitalsignal.governance.ClinicalAlertAction
import au.com.elied.vitalsignal.governance.ClinicalAlertActorRole
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringAlertLedgerTest {
    private val fixture = MonitoringTestFixture()

    @Test
    fun alertDraftSnapshotsCallerEvidence() {
        val evidence = mutableListOf("sample-1")
        val draft = draft().copy(evidenceIds = evidence)
        evidence += "injected"

        assertEquals(listOf("sample-1"), draft.evidenceIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (draft.evidenceIds as MutableList<String>).add("injected-2")
        }
    }

    @Test
    fun `creation route acknowledgement and resolution are atomically audited`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)

        val created = create(ledger)
        val routed = transition(ledger, created, MonitoringAlertState.ROUTED, "route-1", 1_100L)
        val acknowledged = transition(
            ledger,
            routed,
            MonitoringAlertState.ACKNOWLEDGED,
            "ack-1",
            1_200L,
        )
        val resolved = transition(
            ledger,
            acknowledged,
            MonitoringAlertState.RESOLVED,
            "resolve-1",
            1_300L,
        )

        assertEquals(MonitoringAlertState.RESOLVED, resolved.state)
        assertEquals(3L, resolved.version)
        assertEquals("resolve-1", resolved.lastTransitionId)
        assertEquals(1_300L, resolved.lastTransitionAtEpochMillis)
        assertEquals(4, store.auditEntries.size)
        assertNull(store.auditEntries.first().from)
        assertEquals(0L, store.auditEntries.first().toVersion)
        assertEquals(1L, store.auditEntries[1].toVersion)
        assertEquals(MonitoringAlertState.ACKNOWLEDGED, store.auditEntries[2].to)
    }

    @Test
    fun `failed create transaction persists neither alert nor audit`() {
        val store = InMemoryAlertStore().apply { failNextWrite = true }
        val ledger = MonitoringAlertLedger(store)

        assertThrows(IllegalStateException::class.java) {
            create(ledger)
        }

        assertNull(store.load("alert-1"))
        assertTrue(store.auditEntries.isEmpty())
    }

    @Test
    fun `failed transition transaction preserves both prior state and prior audit`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val created = create(ledger)
        store.failNextWrite = true

        assertThrows(IllegalStateException::class.java) {
            transition(ledger, created, MonitoringAlertState.ROUTED, "route-1", 1_100L)
        }

        val canonical = store.load(created.alertId)
        assertEquals(MonitoringAlertState.CREATED, canonical?.state)
        assertEquals(0L, canonical?.version)
        assertEquals(1, store.auditEntries.size)
        assertEquals(MonitoringAlertState.CREATED, store.auditEntries.single().to)
    }

    @Test
    fun `same expected version cannot fork under concurrent transitions`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val routed = transition(
            ledger,
            create(ledger),
            MonitoringAlertState.ROUTED,
            "route-1",
            1_100L,
        )
        store.loadBarrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)

        val acknowledgement = executor.submit(Callable {
            runCatching {
                transition(
                    ledger,
                    routed,
                    MonitoringAlertState.ACKNOWLEDGED,
                    "ack-1",
                    1_200L,
                )
            }
        })
        val escalation = executor.submit(Callable {
            runCatching {
                transition(
                    ledger,
                    routed,
                    MonitoringAlertState.ESCALATED,
                    "escalate-1",
                    1_900L,
                )
            }
        })
        val outcomes = listOf(acknowledgement.get(), escalation.get())
        store.loadBarrier = null
        executor.shutdown()
        assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS))

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(
            1,
            outcomes.count { it.exceptionOrNull() is MonitoringAlertConflictException },
        )
        val canonical = store.load(routed.alertId)
        assertEquals(2L, canonical?.version)
        assertTrue(
            canonical?.state == MonitoringAlertState.ACKNOWLEDGED ||
                canonical?.state == MonitoringAlertState.ESCALATED,
        )
        assertEquals(3, store.auditEntries.size)
        assertEquals(1, store.auditEntries.count { it.fromVersion == 1L })
    }

    @Test
    fun `stale expected version is rejected without another audit`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val created = create(ledger)
        transition(ledger, created, MonitoringAlertState.ROUTED, "route-1", 1_100L)

        assertThrows(MonitoringAlertConflictException::class.java) {
            transition(ledger, created, MonitoringAlertState.CANCELLED, "cancel-1", 1_200L)
        }

        assertEquals(2, store.auditEntries.size)
        assertEquals(MonitoringAlertState.ROUTED, store.load(created.alertId)?.state)
    }

    @Test
    fun `alert action permit must match exact alert action and time`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val routed = transition(
            ledger,
            create(ledger),
            MonitoringAlertState.ROUTED,
            "route-1",
            1_100L,
        )
        val session = fixture.session()
        val wrongAlert = fixture.alertActionPermit(
            alertId = "alert-2",
            session = session,
            expectedAlertVersion = routed.version,
            actorPrincipalId = "observer-1",
            actorRole = ClinicalAlertActorRole.CLINICIAN_OBSERVER,
            action = ClinicalAlertAction.ACKNOWLEDGE,
            nowEpochMillis = 1_200L,
            clinicianSharePermit = fixture.rawClinicianSharePermit(session, 1_200L),
        )
        val wrongAction = fixture.alertActionPermit(
            alertId = routed.alertId,
            session = session,
            expectedAlertVersion = routed.version,
            actorPrincipalId = "router",
            actorRole = ClinicalAlertActorRole.ROUTING_SERVICE,
            action = ClinicalAlertAction.ROUTE,
            nowEpochMillis = 1_200L,
        )
        val expired = fixture.alertActionPermit(
            alertId = routed.alertId,
            session = session,
            expectedAlertVersion = routed.version,
            actorPrincipalId = "observer-1",
            actorRole = ClinicalAlertActorRole.CLINICIAN_OBSERVER,
            action = ClinicalAlertAction.ACKNOWLEDGE,
            nowEpochMillis = 1_200L,
            clinicianSharePermit = fixture.rawClinicianSharePermit(session, 1_200L),
            expiresAtEpochMillis = 1_250L,
        )

        listOf(wrongAlert, wrongAction, expired).forEachIndexed { index, permit ->
            assertThrows(IllegalArgumentException::class.java) {
                ledger.transition(
                    alertId = routed.alertId,
                    expectedVersion = routed.version,
                    expectedState = routed.state,
                    to = MonitoringAlertState.ACKNOWLEDGED,
                    transitionId = "denied-$index",
                    actionPermit = permit,
                    atEpochMillis = if (permit === expired) 1_250L else 1_200L,
                    reason = "must-fail",
                )
            }
        }
        assertEquals(2, store.auditEntries.size)
    }

    @Test
    fun `transition time cannot precede prior committed transition`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val routed = transition(
            ledger,
            create(ledger),
            MonitoringAlertState.ROUTED,
            "route-1",
            1_200L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            transition(
                ledger,
                routed,
                MonitoringAlertState.ACKNOWLEDGED,
                "ack-1",
                1_199L,
            )
        }

        assertEquals(2, store.auditEntries.size)
    }

    @Test
    fun `overdue routed alert escalates`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val routed = transition(
            ledger,
            create(ledger),
            MonitoringAlertState.ROUTED,
            "route-1",
            1_100L,
        )

        val escalated = ledger.escalateIfOverdue(
            alertId = routed.alertId,
            expectedVersion = routed.version,
            nowEpochMillis = 2_000L,
            transitionId = "escalate-1",
            actionPermit = escalationPermit(routed, 2_000L),
        )

        assertEquals(MonitoringAlertState.ESCALATED, escalated.state)
        assertEquals("acknowledgement-deadline-exceeded", store.auditEntries.last().reason)
    }

    @Test
    fun `not yet overdue alert remains unchanged and unaudited`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val routed = transition(
            ledger,
            create(ledger),
            MonitoringAlertState.ROUTED,
            "route-1",
            1_100L,
        )

        val unchanged = ledger.escalateIfOverdue(
            alertId = routed.alertId,
            expectedVersion = routed.version,
            nowEpochMillis = 1_500L,
            transitionId = "escalate-1",
            actionPermit = escalationPermit(routed, 1_500L),
        )

        assertEquals(routed, unchanged)
        assertEquals(2, store.auditEntries.size)
    }

    @Test
    fun `cannot resolve a routed alert`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val routed = transition(
            ledger,
            create(ledger),
            MonitoringAlertState.ROUTED,
            "route-1",
            1_100L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            transition(ledger, routed, MonitoringAlertState.RESOLVED, "resolve-1", 1_200L)
        }
    }

    @Test
    fun `escalation does not substitute for acknowledgement before resolution`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val routed = transition(
            ledger,
            create(ledger),
            MonitoringAlertState.ROUTED,
            "route-1",
            1_100L,
        )
        val escalated = ledger.escalateIfOverdue(
            alertId = routed.alertId,
            expectedVersion = routed.version,
            nowEpochMillis = 2_000L,
            transitionId = "escalate-1",
            actionPermit = escalationPermit(routed, 2_000L),
        )

        assertThrows(IllegalArgumentException::class.java) {
            transition(
                ledger,
                escalated,
                MonitoringAlertState.RESOLVED,
                "resolve-1",
                2_100L,
            )
        }

        val acknowledged = transition(
            ledger,
            escalated,
            MonitoringAlertState.ACKNOWLEDGED,
            "ack-1",
            2_100L,
        )
        val resolved = transition(
            ledger,
            acknowledged,
            MonitoringAlertState.RESOLVED,
            "resolve-1",
            2_200L,
        )
        assertEquals(MonitoringAlertState.RESOLVED, resolved.state)
    }

    @Test
    fun `terminal alert cannot be reopened`() {
        val store = InMemoryAlertStore()
        val ledger = MonitoringAlertLedger(store)
        val cancelled = transition(
            ledger,
            create(ledger),
            MonitoringAlertState.CANCELLED,
            "cancel-1",
            1_100L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            transition(ledger, cancelled, MonitoringAlertState.ROUTED, "route-1", 1_200L)
        }
    }

    @Test
    fun `clinical alert requires a verified opaque permit`() {
        assertThrows(IllegalArgumentException::class.java) {
            draft().copy(
                kind = MonitoringAlertKind.VALIDATED_CLINICAL_RULE,
                ruleId = "clinical-rule",
                ruleVersion = "7",
                clinicalRulePermit = null,
            )
        }

        val denied = ClinicalRulePermitIssuer(ClinicalRuleApprovalVerifier { false }).issue(
            receipt = clinicalReceipt(),
            medicalPromotionPermit = fixture.medicalPermit(fixture.session(), 1_000L),
            evaluatedAtEpochMillis = 1_000L,
        )
        assertTrue(denied is ClinicalRulePermitDecision.Denied)
    }

    @Test
    fun `clinical permit is bound to exact rule environment session and subject`() {
        val permit = clinicalPermit()
        val valid = clinicalDraft(permit)
        assertEquals(permit, valid.clinicalRulePermit)

        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(ruleVersion = "8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(sessionId = "different-session")
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(environmentFingerprintSha256 = "b".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            valid.copy(subjectPseudonym = "different-subject")
        }
    }

    @Test
    fun `expired clinical permit cannot authorize alert creation`() {
        val permit = clinicalPermit(expiresAtEpochMillis = 1_500L)

        assertThrows(IllegalArgumentException::class.java) {
            clinicalDraft(permit).copy(createdAtEpochMillis = 1_500L)
        }
    }

    private fun create(
        ledger: MonitoringAlertLedger,
        draft: MonitoringAlertDraft = draft(),
    ): MonitoringAlert = ledger.create(
        draft = draft,
        transitionId = "create-1",
        actionPermit = fixture.alertActionPermit(
            alertId = draft.alertId,
            session = fixture.session(),
            expectedAlertVersion = null,
            actorPrincipalId = "pattern-engine",
            actorRole = ClinicalAlertActorRole.ALERT_ENGINE,
            action = ClinicalAlertAction.CREATE,
            nowEpochMillis = draft.createdAtEpochMillis,
        ),
        reason = "alert-detected",
    )

    private fun transition(
        ledger: MonitoringAlertLedger,
        current: MonitoringAlert,
        to: MonitoringAlertState,
        transitionId: String,
        atEpochMillis: Long,
    ): MonitoringAlert {
        val action = actionFor(to)
        val clinicianAction = action == ClinicalAlertAction.ACKNOWLEDGE ||
            action == ClinicalAlertAction.RESOLVE
        val actorId = when (action) {
            ClinicalAlertAction.ROUTE -> "router"
            ClinicalAlertAction.ACKNOWLEDGE, ClinicalAlertAction.RESOLVE -> "observer-1"
            ClinicalAlertAction.ESCALATE -> "escalation-service"
            ClinicalAlertAction.CANCEL -> "operations-1"
            ClinicalAlertAction.CREATE -> error("create is not a transition")
        }
        val role = when (action) {
            ClinicalAlertAction.ROUTE -> ClinicalAlertActorRole.ROUTING_SERVICE
            ClinicalAlertAction.ACKNOWLEDGE, ClinicalAlertAction.RESOLVE -> {
                ClinicalAlertActorRole.CLINICIAN_OBSERVER
            }
            ClinicalAlertAction.ESCALATE -> ClinicalAlertActorRole.ESCALATION_SERVICE
            ClinicalAlertAction.CANCEL -> ClinicalAlertActorRole.OPERATIONS
            ClinicalAlertAction.CREATE -> error("create is not a transition")
        }
        return ledger.transition(
            alertId = current.alertId,
            expectedVersion = current.version,
            expectedState = current.state,
            to = to,
            transitionId = transitionId,
            actionPermit = fixture.alertActionPermit(
                alertId = current.alertId,
                session = fixture.session(),
                expectedAlertVersion = current.version,
                actorPrincipalId = actorId,
                actorRole = role,
                action = action,
                nowEpochMillis = atEpochMillis,
                clinicianSharePermit = if (clinicianAction) {
                    fixture.rawClinicianSharePermit(fixture.session(), atEpochMillis)
                } else {
                    null
                },
            ),
            atEpochMillis = atEpochMillis,
            reason = "test-transition",
        )
    }

    private fun draft() = MonitoringAlertDraft(
        alertId = "alert-1",
        sessionId = "session-1",
        subjectPseudonym = "subject-1",
        kind = MonitoringAlertKind.RESEARCH_PATTERN_REVIEW,
        ruleId = "research-pattern",
        ruleVersion = "1",
        environmentFingerprintSha256 = ENVIRONMENT_FINGERPRINT,
        createdAtEpochMillis = 1_000L,
        acknowledgeByEpochMillis = 1_800L,
        evidenceIds = listOf("sample-1", "quality-1"),
        clinicalRulePermit = null,
    )

    private fun clinicalDraft(permit: ClinicalRulePermit) = draft().copy(
        kind = MonitoringAlertKind.VALIDATED_CLINICAL_RULE,
        ruleId = "clinical-rule",
        ruleVersion = "7",
        clinicalRulePermit = permit,
    )

    private fun clinicalPermit(
        expiresAtEpochMillis: Long = 5_000L,
    ): ClinicalRulePermit {
        val decision = ClinicalRulePermitIssuer(ClinicalRuleApprovalVerifier { true }).issue(
            receipt = clinicalReceipt(expiresAtEpochMillis),
            medicalPromotionPermit = fixture.medicalPermit(fixture.session(), 1_000L),
            evaluatedAtEpochMillis = 1_000L,
        )
        return (decision as ClinicalRulePermitDecision.Allowed).permit
    }

    private fun clinicalReceipt(
        expiresAtEpochMillis: Long = 5_000L,
    ) = ClinicalRuleApprovalReceipt(
        receiptId = "clinical-approval-1",
        ruleId = "clinical-rule",
        ruleVersion = "7",
        medicalFeatureId = "clinical-live-monitor",
        medicalFeatureVersion = "clinical-live-monitor-v1",
        environmentFingerprintSha256 = ENVIRONMENT_FINGERPRINT,
        sessionId = "session-1",
        subjectPseudonym = "subject-1",
        approvedAtEpochMillis = 900L,
        expiresAtEpochMillis = expiresAtEpochMillis,
        issuerKeyId = "clinical-safety-board",
        signature = byteArrayOf(1, 2, 3),
    )

    private fun escalationPermit(
        current: MonitoringAlert,
        atEpochMillis: Long,
    ) = fixture.alertActionPermit(
        alertId = current.alertId,
        session = fixture.session(),
        expectedAlertVersion = current.version,
        actorPrincipalId = "escalation-service",
        actorRole = ClinicalAlertActorRole.ESCALATION_SERVICE,
        action = ClinicalAlertAction.ESCALATE,
        nowEpochMillis = atEpochMillis,
    )

    private fun actionFor(state: MonitoringAlertState): ClinicalAlertAction = when (state) {
        MonitoringAlertState.ROUTED -> ClinicalAlertAction.ROUTE
        MonitoringAlertState.ACKNOWLEDGED -> ClinicalAlertAction.ACKNOWLEDGE
        MonitoringAlertState.ESCALATED -> ClinicalAlertAction.ESCALATE
        MonitoringAlertState.RESOLVED -> ClinicalAlertAction.RESOLVE
        MonitoringAlertState.CANCELLED -> ClinicalAlertAction.CANCEL
        MonitoringAlertState.CREATED -> error("created is not a transition target")
    }

    private class InMemoryAlertStore : MonitoringAlertStore {
        private val alerts = mutableMapOf<String, MonitoringAlert>()
        private val committedTransitionIds = mutableSetOf<String>()
        val auditEntries = mutableListOf<MonitoringAlertAuditEntry>()

        @Volatile
        var failNextWrite: Boolean = false

        @Volatile
        var loadBarrier: CyclicBarrier? = null

        override fun load(alertId: String): MonitoringAlert? {
            val snapshot = synchronized(this) { alerts[alertId] }
            loadBarrier?.await(5L, TimeUnit.SECONDS)
            return snapshot
        }

        @Synchronized
        override fun createAndAppendAudit(
            alert: MonitoringAlert,
            auditEntry: MonitoringAlertAuditEntry,
        ): MonitoringAlertCommitResult {
            if (consumeFailure()) return MonitoringAlertCommitResult.Failed
            val current = alerts[alert.alertId]
            if (current != null || auditEntry.transitionId in committedTransitionIds) {
                return MonitoringAlertCommitResult.Conflict(current)
            }
            alerts[alert.alertId] = alert
            auditEntries += auditEntry
            committedTransitionIds += auditEntry.transitionId
            return MonitoringAlertCommitResult.Committed(alert)
        }

        @Synchronized
        override fun compareAndSetAndAppendAudit(
            alertId: String,
            expectedVersion: Long,
            expectedState: MonitoringAlertState,
            updatedAlert: MonitoringAlert,
            auditEntry: MonitoringAlertAuditEntry,
        ): MonitoringAlertCommitResult {
            if (consumeFailure()) return MonitoringAlertCommitResult.Failed
            val current = alerts[alertId]
            if (current == null ||
                current.version != expectedVersion ||
                current.state != expectedState ||
                auditEntry.transitionId in committedTransitionIds
            ) {
                return MonitoringAlertCommitResult.Conflict(current)
            }
            alerts[alertId] = updatedAlert
            auditEntries += auditEntry
            committedTransitionIds += auditEntry.transitionId
            return MonitoringAlertCommitResult.Committed(updatedAlert)
        }

        private fun consumeFailure(): Boolean {
            if (!failNextWrite) return false
            failNextWrite = false
            return true
        }
    }

    private companion object {
        val ENVIRONMENT_FINGERPRINT = "e".repeat(64)
    }
}
