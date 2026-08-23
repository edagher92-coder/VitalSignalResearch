package au.com.elied.vitalsignal.governance

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyCommandLedgerTest {
    private val signer = PrivacyTargetReceiptSigner { keyId, target, payload ->
        require(keyId == keyId(target))
        mac(key(target), payload)
    }
    private val verifier = PrivacyTargetReceiptSignatureVerifier { keyId, target, payload, signature ->
        keyId == keyId(target) && MessageDigest.isEqual(mac(key(target), payload), signature)
    }
    private val ledger = PrivacyCommandLedger(verifier)

    @Test
    fun deleteStaysPendingUntilEveryLocationReportsAuthenticatedCompletion() {
        val requested = ledger.request(deleteCommand())
        assertEquals(PrivacyCommandState.PENDING, requested.state)
        assertEquals(PrivacyCommand.REQUIRED_DELETE_TARGETS, requested.pendingTargets)

        val partial = ledger.record(receipt(PrivacyTarget.PHONE_ENCRYPTED_STORE))
        assertEquals(PrivacyCommandState.PARTIAL, partial.state)
        assertTrue(PrivacyTarget.WATCH_OUTBOX in partial.pendingTargets)
        assertTrue(PrivacyTarget.EXPORT_ARCHIVE in partial.pendingTargets)
        assertTrue(PrivacyTarget.PROVIDER_REASONING_AUDIT in partial.pendingTargets)
        assertTrue(PrivacyTarget.OBSERVER_BACKEND in partial.pendingTargets)
    }

    @Test
    fun offlineWatchPreventsFalseCompleteDeletion() {
        ledger.request(deleteCommand())
        val watchTargets = setOf(
            PrivacyTarget.WATCH_OUTBOX,
            PrivacyTarget.WATCH_LOCAL_CACHE,
            PrivacyTarget.WATCH_CONTINUITY_JOURNAL,
        )
        (PrivacyCommand.REQUIRED_DELETE_TARGETS - watchTargets).forEach {
            ledger.record(receipt(it))
        }
        val result = ledger.get("delete-1")!!
        assertEquals(PrivacyCommandState.PARTIAL, result.state)
        assertEquals(watchTargets, result.pendingTargets)
    }

    @Test
    fun deletionCompletesOnlyAfterAllRequiredAuthenticatedReceipts() {
        ledger.request(deleteCommand())
        PrivacyCommand.REQUIRED_DELETE_TARGETS.forEach { ledger.record(receipt(it)) }
        val result = ledger.get("delete-1")!!
        assertEquals(PrivacyCommandState.COMPLETE, result.state)
        assertTrue(result.pendingTargets.isEmpty())
    }

    @Test
    fun identicalReceiptRetryIsIdempotent() {
        ledger.request(deleteCommand())
        val first = receipt(PrivacyTarget.PHONE_ENCRYPTED_STORE)
        ledger.record(first)
        val result = ledger.record(first)
        assertEquals(PrivacyCommandState.PARTIAL, result.state)
        assertEquals(1, result.receipts.size)
    }

    @Test
    fun forgedReceiptCannotCompleteATarget() {
        val command = deleteCommand()
        ledger.request(command)
        val forged = PrivacyTargetReceipt(
            command.commandId,
            privacyCommandSha256(command),
            PrivacyTarget.PHONE_ENCRYPTED_STORE,
            command.consentGeneration,
            1_100L,
            1L,
            PrivacyTargetDisposition.DELETED,
            "c".repeat(64),
            keyId(PrivacyTarget.PHONE_ENCRYPTED_STORE),
            ByteArray(32) { 9 },
        )
        val result = ledger.record(forged)
        assertEquals(PrivacyCommandState.CONFLICT, result.state)
        assertTrue(result.completedTargets.isEmpty())
    }

    @Test
    fun receiptForAnotherExactCommandCannotReplay() {
        val original = deleteCommand()
        val signed = receipt(PrivacyTarget.PHONE_ENCRYPTED_STORE)
        val replacementLedger = PrivacyCommandLedger(verifier)
        replacementLedger.request(original.copy(subjectPseudonym = "other-pilot"))
        val result = replacementLedger.record(signed)
        assertEquals(PrivacyCommandState.CONFLICT, result.state)
        assertTrue(result.completedTargets.isEmpty())
    }

    @Test
    fun authorityForOneTargetCannotCompleteAnotherTarget() {
        val command = deleteCommand()
        ledger.request(command)
        val unsigned = PrivacyTargetReceipt(
            command.commandId,
            privacyCommandSha256(command),
            PrivacyTarget.WATCH_OUTBOX,
            command.consentGeneration,
            1_100L,
            1L,
            PrivacyTargetDisposition.DELETED,
            "c".repeat(64),
            keyId(PrivacyTarget.PHONE_ENCRYPTED_STORE),
            ByteArray(32),
        )
        val forged = PrivacyTargetReceipt(
            unsigned.commandId,
            unsigned.commandSha256,
            unsigned.target,
            unsigned.consentGeneration,
            unsigned.completedAtEpochMillis,
            unsigned.affectedRecordCount,
            unsigned.disposition,
            unsigned.executionSha256,
            unsigned.issuerKeyId,
            mac(key(PrivacyTarget.PHONE_ENCRYPTED_STORE), canonicalPrivacyReceipt(unsigned)),
        )

        val result = ledger.record(forged)
        assertEquals(PrivacyCommandState.CONFLICT, result.state)
        assertTrue(result.completedTargets.isEmpty())
    }

    @Test
    fun changingACommandWithTheSameIdCreatesConflict() {
        ledger.request(deleteCommand())
        val result = ledger.request(deleteCommand().copy(subjectPseudonym = "other-pilot"))
        assertEquals(PrivacyCommandState.CONFLICT, result.state)
    }

    @Test
    fun exportRequiresEveryDataLocationAndFinalArchive() {
        val command = exportCommand()
        ledger.request(command)
        (command.requiredTargets - PrivacyTarget.EXPORT_ARCHIVE).forEach { target ->
            ledger.record(exportReceipt(command, target, PrivacyTargetDisposition.NOT_PRESENT))
        }
        assertEquals(PrivacyCommandState.PARTIAL, ledger.get(command.commandId)!!.state)

        val result = ledger.record(
            exportReceipt(command, PrivacyTarget.EXPORT_ARCHIVE, PrivacyTargetDisposition.EXPORTED),
        )
        assertEquals(PrivacyCommandState.COMPLETE, result.state)
    }

    @Test
    fun wrongDispositionFailsClosed() {
        val command = deleteCommand()
        ledger.request(command)
        val result = ledger.record(
            issuer(PrivacyTarget.PHONE_ENCRYPTED_STORE).issue(
                command,
                PrivacyTarget.PHONE_ENCRYPTED_STORE,
                1_100L,
                1L,
                PrivacyTargetDisposition.EXPORTED,
                "c".repeat(64),
            ),
        )
        assertEquals(PrivacyCommandState.CONFLICT, result.state)
        assertTrue(result.completedTargets.isEmpty())
    }

    @Test
    fun returnedSignatureCannotMutateEvidence() {
        val signed = receipt(PrivacyTarget.PHONE_ENCRYPTED_STORE)
        signed.signatureBytes().fill(0)
        ledger.request(deleteCommand())
        assertEquals(PrivacyCommandState.PARTIAL, ledger.record(signed).state)
    }

    @Test
    fun callerCannotMutateRequiredTargetsAfterCommandAcceptance() {
        val mutableTargets = PrivacyCommand.REQUIRED_DELETE_TARGETS.toMutableSet()
        val command = PrivacyCommand(
            "delete-mutation",
            PrivacyCommandType.DELETE,
            "pilot-1",
            1L,
            1_000L,
            mutableTargets,
        )

        ledger.request(command)
        mutableTargets.clear()

        val result = ledger.get(command.commandId)!!
        assertEquals(PrivacyCommandState.PENDING, result.state)
        assertEquals(PrivacyCommand.REQUIRED_DELETE_TARGETS, result.pendingTargets)
    }

    @Test
    fun publishedCommandAndLedgerCollectionsRejectDowncastMutation() {
        val command = deleteCommand()
        val view = ledger.request(command)

        assertThrows(UnsupportedOperationException::class.java) {
            (command.requiredTargets as MutableSet<PrivacyTarget>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (PrivacyCommand.REQUIRED_DELETE_TARGETS as MutableSet<PrivacyTarget>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (view.pendingTargets as MutableSet<PrivacyTarget>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (view.completedTargets as MutableSet<PrivacyTarget>).add(
                PrivacyTarget.PHONE_ENCRYPTED_STORE,
            )
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (view.receipts as MutableList<PrivacyTargetReceipt>).clear()
        }

        val unchanged = ledger.get(command.commandId)!!
        assertEquals(PrivacyCommandState.PENDING, unchanged.state)
        assertEquals(PrivacyCommand.REQUIRED_DELETE_TARGETS, unchanged.pendingTargets)
    }

    private fun deleteCommand() = PrivacyCommand(
        "delete-1",
        PrivacyCommandType.DELETE,
        "pilot-1",
        1L,
        1_000L,
        PrivacyCommand.REQUIRED_DELETE_TARGETS,
    )

    private fun exportCommand() = PrivacyCommand(
        "export-1",
        PrivacyCommandType.EXPORT,
        "pilot-1",
        1L,
        1_000L,
        PrivacyCommand.REQUIRED_EXPORT_TARGETS,
    )

    private fun receipt(target: PrivacyTarget) = issuer(target).issue(
        deleteCommand(),
        target,
        1_100L,
        1L,
        PrivacyTargetDisposition.DELETED,
        "c".repeat(64),
    )

    private fun exportReceipt(
        command: PrivacyCommand,
        target: PrivacyTarget,
        disposition: PrivacyTargetDisposition,
    ) = issuer(target).issue(command, target, 1_100L, 0L, disposition, "e".repeat(64))

    private fun issuer(target: PrivacyTarget) = PrivacyTargetReceiptIssuer(
        keyId(target),
        target,
        signer,
    )

    private fun keyId(target: PrivacyTarget): String = "privacy-${target.name.lowercase()}-v1"

    private fun key(target: PrivacyTarget): ByteArray =
        ByteArray(32) { index -> (index + 41 + target.ordinal).toByte() }

    private fun mac(keyBytes: ByteArray, payload: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(keyBytes.copyOf(), "HmacSHA256"))
            doFinal(payload.copyOf())
        }

}
