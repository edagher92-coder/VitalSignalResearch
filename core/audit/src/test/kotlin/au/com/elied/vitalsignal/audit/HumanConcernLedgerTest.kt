package au.com.elied.vitalsignal.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanConcernLedgerTest {
    private val verifier = HumanConcernAuthorityVerifier { event ->
        event.authorityReceiptId == "authority-${event.eventId}" &&
            event.actorPrincipalId.startsWith("human-")
    }

    @Test
    fun reportedConcernStaysLatchedUntilAnAuthorizedHumanResolution() {
        val journal = InMemoryHumanConcernJournal()
        val ledger = HumanConcernLedger(journal, verifier)

        val reported = ledger.mutate(event(eventId = "event-report", action = HumanConcernAction.REPORT))
        assertTrue(reported is HumanConcernMutationResult.Applied)
        assertEquals(
            HumanConcernLatchState.ACTIVE,
            ledger.activeConcern("subject-1", "session-1", 1L)?.state,
        )

        val forgedResolution = event(
            eventId = "event-forged-resolution",
            action = HumanConcernAction.RESOLVE_BY_HUMAN,
            expectedVersion = 1L,
            authorityReceiptId = "forged",
            occurredAt = 2_000L,
        )
        assertTrue(ledger.mutate(forgedResolution) is HumanConcernMutationResult.Rejected)
        assertEquals(HumanConcernLatchState.ACTIVE, ledger.projection("concern-1")?.state)

        val resolution = event(
            eventId = "event-resolution",
            action = HumanConcernAction.RESOLVE_BY_HUMAN,
            expectedVersion = 1L,
            occurredAt = 2_000L,
        )
        assertTrue(ledger.mutate(resolution) is HumanConcernMutationResult.Applied)
        assertEquals(HumanConcernLatchState.RESOLVED, ledger.projection("concern-1")?.state)
        assertEquals(null, ledger.activeConcern("subject-1", "session-1", 1L))
    }

    @Test
    fun restartReplaysTheLatchedConcernAndExactDuplicateIsIdempotent() {
        val journal = InMemoryHumanConcernJournal()
        val report = event(eventId = "event-report", action = HumanConcernAction.REPORT)
        val first = HumanConcernLedger(journal, verifier)
        assertTrue(first.mutate(report) is HumanConcernMutationResult.Applied)

        val restarted = HumanConcernLedger(journal, verifier)
        assertEquals(HumanConcernLatchState.ACTIVE, restarted.projection("concern-1")?.state)
        assertTrue(restarted.mutate(report) is HumanConcernMutationResult.Idempotent)
    }

    @Test
    fun changedBindingBackdatingAndEventReplayConflictAreRejected() {
        val ledger = HumanConcernLedger(InMemoryHumanConcernJournal(), verifier)
        val report = event(eventId = "event-report", action = HumanConcernAction.REPORT)
        assertTrue(ledger.mutate(report) is HumanConcernMutationResult.Applied)

        val changedSession = event(
            eventId = "event-resolution",
            action = HumanConcernAction.RESOLVE_BY_HUMAN,
            expectedVersion = 1L,
            sessionId = "other-session",
            occurredAt = 2_000L,
        )
        assertTrue(ledger.mutate(changedSession) is HumanConcernMutationResult.Rejected)

        val replayConflict = report.copy(
            action = HumanConcernAction.RESOLVE_BY_HUMAN,
            expectedConcernVersion = 1L,
        )
        assertTrue(ledger.mutate(replayConflict) is HumanConcernMutationResult.Rejected)

        val backdated = event(
            eventId = "event-backdated",
            action = HumanConcernAction.RESOLVE_BY_HUMAN,
            expectedVersion = 1L,
            occurredAt = 999L,
        )
        assertTrue(ledger.mutate(backdated) is HumanConcernMutationResult.Rejected)
        assertEquals(HumanConcernLatchState.ACTIVE, ledger.projection("concern-1")?.state)
    }

    @Test
    fun corruptRecoveredChronologyFailsClosed() {
        val invalidJournal = object : AppendOnlyHumanConcernJournal {
            override fun recover() = HumanConcernJournalRecovery.Recovered(
                listOf(HumanConcernJournalRecord(2L, event("event-report", HumanConcernAction.REPORT))),
            )

            override fun append(
                event: HumanConcernAuditEvent,
                expectedJournalRevision: Long,
            ) = HumanConcernJournalAppendResult.Unavailable("not reachable")
        }
        val ledger = HumanConcernLedger(invalidJournal, verifier)

        assertFalse(ledger.isAvailable())
        assertTrue(
            ledger.queryConcern("subject-1", "session-1", 1L) is
                HumanConcernQueryResult.Unavailable,
        )
        assertTrue(
            ledger.mutate(event("event-new", HumanConcernAction.REPORT)) is
                HumanConcernMutationResult.Unavailable,
        )
    }

    @Test
    fun recoveredEventsAreReverifiedAgainstExactHumanAuthority() {
        val report = event("event-report", HumanConcernAction.REPORT)
        val journal = object : AppendOnlyHumanConcernJournal {
            override fun recover() = HumanConcernJournalRecovery.Recovered(
                listOf(HumanConcernJournalRecord(1L, report)),
            )

            override fun append(
                event: HumanConcernAuditEvent,
                expectedJournalRevision: Long,
            ) = HumanConcernJournalAppendResult.Unavailable("not reachable")
        }
        val rejectingLedger = HumanConcernLedger(journal, HumanConcernAuthorityVerifier { false })

        assertFalse(rejectingLedger.isAvailable())
        assertTrue(
            rejectingLedger.queryConcern("subject-1", "session-1", 1L) is
                HumanConcernQueryResult.Unavailable,
        )
        assertEquals(null, rejectingLedger.projection("concern-1"))
    }

    private fun event(
        eventId: String,
        action: HumanConcernAction,
        expectedVersion: Long = 0L,
        sessionId: String = "session-1",
        authorityReceiptId: String = "authority-$eventId",
        occurredAt: Long = 1_000L,
    ) = HumanConcernAuditEvent(
        eventId = eventId,
        concernId = "concern-1",
        subjectPseudonym = "subject-1",
        sessionId = sessionId,
        consentGeneration = 1L,
        expectedConcernVersion = expectedVersion,
        action = action,
        actorPrincipalId = "human-participant-1",
        actorRole = HumanConcernActorRole.PARTICIPANT,
        occurredAtEpochMillis = occurredAt,
        contextSnapshotSha256 = "a".repeat(64),
        authorityReceiptId = authorityReceiptId,
    )
}
