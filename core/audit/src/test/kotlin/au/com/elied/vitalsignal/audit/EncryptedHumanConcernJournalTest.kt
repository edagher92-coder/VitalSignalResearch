package au.com.elied.vitalsignal.audit

import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EncryptedHumanConcernJournalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun activeConcernSurvivesEncryptedRestartAndCanOnlyResolveWithExactAuthority() {
        val root = temporaryFolder.newFolder("concern-journal").toPath()
        val key = aesKey()
        val verifier = HumanConcernAuthorityVerifier { event ->
            event.authorityReceiptId == "authority-${event.eventId}"
        }
        val first = HumanConcernLedger(journal(root, key), verifier)
        assertTrue(first.mutate(event("report", HumanConcernAction.REPORT)) is HumanConcernMutationResult.Applied)

        val restarted = HumanConcernLedger(journal(root, key), verifier)
        assertEquals(HumanConcernLatchState.ACTIVE, restarted.projection("concern-1")?.state)
        assertTrue(
            restarted.mutate(
                event(
                    eventId = "resolve",
                    action = HumanConcernAction.RESOLVE_BY_HUMAN,
                    expectedVersion = 1L,
                    occurredAt = 2_000L,
                ),
            ) is HumanConcernMutationResult.Applied,
        )

        val resolvedRestart = HumanConcernLedger(journal(root, key), verifier)
        assertEquals(HumanConcernLatchState.RESOLVED, resolvedRestart.projection("concern-1")?.state)
    }

    @Test
    fun wrongKeyAndTamperingMakeTheConcernLedgerUnavailable() {
        val root = temporaryFolder.newFolder("concern-tamper").toPath()
        val key = aesKey()
        val verifier = HumanConcernAuthorityVerifier { true }
        val first = HumanConcernLedger(journal(root, key), verifier)
        assertTrue(first.mutate(event("report", HumanConcernAction.REPORT)) is HumanConcernMutationResult.Applied)

        val wrongKey = HumanConcernLedger(journal(root, aesKey()), verifier)
        assertFalse(wrongKey.isAvailable())

        val committed = Files.list(root.resolve("records")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".vsr") }.findFirst().orElseThrow()
        }
        val bytes = Files.readAllBytes(committed)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(committed, bytes, WRITE)

        val tampered = HumanConcernLedger(journal(root, key), verifier)
        assertFalse(tampered.isAvailable())
        assertTrue(
            tampered.mutate(event("new-report", HumanConcernAction.REPORT)) is
                HumanConcernMutationResult.Unavailable,
        )
    }

    private fun journal(root: java.nio.file.Path, key: SecretKey) = EncryptedHumanConcernJournal(
        EncryptedAppendOnlyRecordStore(
            rootDirectory = root,
            secretKey = key,
            keyId = "concern-key-v1",
            secureRandom = SecureRandom(),
        ),
    )

    private fun event(
        eventId: String,
        action: HumanConcernAction,
        expectedVersion: Long = 0L,
        occurredAt: Long = 1_000L,
    ) = HumanConcernAuditEvent(
        eventId = eventId,
        concernId = "concern-1",
        subjectPseudonym = "subject-1",
        sessionId = "session-1",
        consentGeneration = 1L,
        expectedConcernVersion = expectedVersion,
        action = action,
        actorPrincipalId = "participant-1",
        actorRole = HumanConcernActorRole.PARTICIPANT,
        occurredAtEpochMillis = occurredAt,
        contextSnapshotSha256 = "b".repeat(64),
        authorityReceiptId = "authority-$eventId",
    )

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").run {
        init(256)
        generateKey()
    }
}
