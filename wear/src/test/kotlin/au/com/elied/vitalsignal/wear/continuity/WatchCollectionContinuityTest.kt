package au.com.elied.vitalsignal.wear.continuity

import au.com.elied.vitalsignal.storage.EncryptedAppendOnlyRecordStore
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchCollectionContinuityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recoveryIssuer = WatchRecoveryEvidenceIssuer(
        RECOVERY_KEY_ID,
        WatchRecoveryEvidenceSigner { _, payload -> recoveryMac(payload) },
    )
    private val engine = WatchCollectionContinuityEngine(
        recoveryEvidenceVerifier = WatchRecoveryEvidenceSignatureVerifier { keyId, payload, signature ->
            keyId == RECOVERY_KEY_ID && MessageDigest.isEqual(recoveryMac(payload), signature)
        },
        currentRecoveryMaterialProvider = CurrentWatchRecoveryMaterialProvider { _, _ ->
            RECOVERY_MATERIAL_SHA256
        },
    )

    @Test
    fun batteryDrainAndVerifiedRebootResumeKeepSequenceProvenanceAndExplicitGap() {
        val root = temporaryFolder.newFolder("battery-reboot").toPath()
        val firstJournal = journal(root)
        val initial = (engine.start(STREAM, signal()) as WatchContinuityDecision.Continuing).snapshot
        assertCommitted(firstJournal, initial)
        val firstCommit = commit(initial, 1L, "whs:hr-1", 10_001L)
        assertCommitted(firstJournal, firstCommit)

        val lowBattery = (engine.observe(
            firstCommit,
            signal(id = "battery-low", wall = 20_000L, elapsed = 15_000L, battery = 4),
        ) as WatchContinuityDecision.Paused).snapshot
        assertCommitted(firstJournal, lowBattery)
        assertEquals(1L, lowBattery.lastCommittedSequence)
        val lowBatteryGap = requireNotNull(lowBattery.latestGap)
        assertEquals(GapInterpretation.EXPLICIT_MISSING_NEVER_IMPUTE_NORMAL, lowBatteryGap.interpretation)
        assertTrue(CollectionInterruptionReason.LOW_BATTERY in lowBatteryGap.reasons)

        val afterRebootSignal = signal(
            id = "boot-after-charge",
            wall = 30_000L,
            elapsed = 100L,
            battery = 82,
            boot = "boot-2",
        )
        val afterReboot = engine.observe(
            lowBattery,
            afterRebootSignal,
            recoveryEvidence(lowBattery, afterRebootSignal),
        ) as WatchContinuityDecision.ResumeReady
        assertCommitted(firstJournal, afterReboot.snapshot)
        assertEquals(2L, afterReboot.permit.nextSequence)
        assertTrue(CollectionInterruptionReason.REBOOT in afterReboot.snapshot.latestGap!!.reasons)

        val confirmSignal = signal(
            id = "resume-confirm",
            wall = 30_001L,
            elapsed = 101L,
            battery = 82,
            boot = "boot-2",
        )
        val resumed = (engine.confirmResume(
            afterReboot.snapshot,
            afterReboot.permit,
            confirmSignal,
            recoveryEvidence(afterReboot.snapshot, confirmSignal),
        ) as WatchContinuityDecision.Continuing).snapshot
        assertCommitted(firstJournal, resumed)
        assertFalse(resumed.latestGap!!.active)
        val secondCommit = commit(resumed, 2L, "whs:hr-2", 30_002L)
        assertCommitted(firstJournal, secondCommit)

        val reopened = journal(root).recover() as WatchContinuityRecoveryResult.Available
        val recoveredSnapshot = requireNotNull(reopened.latest)
        assertEquals(2L, recoveredSnapshot.lastCommittedSequence)
        assertEquals(secondCommit.sha256(), recoveredSnapshot.sha256())
        assertNotEquals(firstCommit.provenanceChainSha256, secondCommit.provenanceChainSha256)
    }

    @Test
    fun chargingThermalAndOffWristAreExplicitPauseStatesNeverNormalData() {
        val cases = listOf(
            signal(id = "charging", charging = true) to CollectionInterruptionReason.CHARGING,
            signal(id = "thermal", thermal = WatchThermalState.ELEVATED) to
                CollectionInterruptionReason.THERMAL_LIMIT,
            signal(id = "off-wrist", wrist = WatchWristState.OFF_WRIST) to
                CollectionInterruptionReason.OFF_WRIST,
        )

        cases.forEach { (runtime, expectedReason) ->
            val paused = engine.start("stream-${runtime.signalId}", runtime) as WatchContinuityDecision.Paused
            val gap = requireNotNull(paused.snapshot.latestGap)
            assertEquals(ContinuityState.PAUSED, paused.snapshot.state)
            assertTrue(expectedReason in gap.reasons)
            assertEquals(
                GapInterpretation.EXPLICIT_MISSING_NEVER_IMPUTE_NORMAL,
                gap.interpretation,
            )
            assertEquals(0L, paused.snapshot.lastCommittedSequence)
        }
    }

    @Test
    fun unknownContactOrThermalStateFailsClosedUntilVerifiedRecovery() {
        val unknownWrist = engine.start(
            "stream-unknown-wrist",
            signal(id = "unknown-wrist", wrist = WatchWristState.UNKNOWN),
        ) as WatchContinuityDecision.RecoveryRequired
        assertTrue(CollectionInterruptionReason.WRIST_STATE_UNKNOWN in unknownWrist.snapshot.latestGap!!.reasons)

        val stillBlocked = engine.observe(
            unknownWrist.snapshot,
            signal(id = "safe-but-unverified", wall = 10_001L, elapsed = 5_001L),
        )
        assertTrue(stillBlocked is WatchContinuityDecision.RecoveryRequired)

        val blockedSnapshot = (stillBlocked as WatchContinuityDecision.RecoveryRequired).snapshot
        val verifiedSignal = signal(id = "safe-and-verified", wall = 10_002L, elapsed = 5_002L)
        val ready = engine.observe(
            blockedSnapshot,
            verifiedSignal,
            recoveryEvidence(blockedSnapshot, verifiedSignal),
        )
        assertTrue(ready is WatchContinuityDecision.ResumeReady)
    }

    @Test
    fun forgedExpiredCrossSnapshotAndCrossRuntimeRecoveryEvidenceFailClosed() {
        val current = (engine.start(STREAM, signal()) as WatchContinuityDecision.Continuing).snapshot
        val rebootSignal = signal(
            id = "reboot-unverified",
            wall = 20_000L,
            elapsed = 100L,
            boot = "boot-2",
        )
        val blocked = (engine.observe(current, rebootSignal) as
            WatchContinuityDecision.RecoveryRequired).snapshot
        val safeSignal = signal(
            id = "recovery-check",
            wall = 20_100L,
            elapsed = 200L,
            boot = "boot-2",
        )
        val valid = recoveryEvidence(blocked, safeSignal)
        val forged = WatchRecoveryEvidence(
            evidenceId = valid.evidenceId,
            issuerKeyId = valid.issuerKeyId,
            requiredSnapshotSha256 = valid.requiredSnapshotSha256,
            runtimeSignalSha256 = valid.runtimeSignalSha256,
            recoveryMaterialSha256 = valid.recoveryMaterialSha256,
            issuedAtEpochMillis = valid.issuedAtEpochMillis,
            expiresAtEpochMillis = valid.expiresAtEpochMillis,
            signature = ByteArray(32) { 9 },
        )
        val expired = recoveryIssuer.issue(
            current = blocked,
            signal = safeSignal,
            recoveryMaterialSha256 = "d".repeat(64),
            issuedAtEpochMillis = safeSignal.observedAtEpochMillis - 1_000L,
            expiresAtEpochMillis = safeSignal.observedAtEpochMillis,
        )
        val crossSnapshot = recoveryEvidence(current, safeSignal)
        val otherRuntime = safeSignal.copy(signalId = "different-runtime")

        assertTrue(engine.observe(blocked, safeSignal, forged) is WatchContinuityDecision.RecoveryRequired)
        assertTrue(engine.observe(blocked, safeSignal, expired) is WatchContinuityDecision.RecoveryRequired)
        assertTrue(engine.observe(blocked, safeSignal, crossSnapshot) is WatchContinuityDecision.RecoveryRequired)
        assertTrue(engine.observe(blocked, otherRuntime, valid) is WatchContinuityDecision.RecoveryRequired)
        assertTrue(engine.observe(blocked, safeSignal, valid) is WatchContinuityDecision.ResumeReady)
    }

    @Test
    fun recoveryEvidenceMustMatchIndependentlyRebuiltCurrentMaterial() {
        val current = (engine.start(STREAM, signal()) as WatchContinuityDecision.Continuing).snapshot
        val rebootSignal = signal(
            id = "reboot-material-check",
            wall = 20_000L,
            elapsed = 100L,
            boot = "boot-2",
        )
        val blocked = (engine.observe(current, rebootSignal) as
            WatchContinuityDecision.RecoveryRequired).snapshot
        val safeSignal = signal(
            id = "material-check",
            wall = 20_100L,
            elapsed = 200L,
            boot = "boot-2",
        )
        val staleMaterialEvidence = recoveryIssuer.issue(
            current = blocked,
            signal = safeSignal,
            recoveryMaterialSha256 = "e".repeat(64),
        )

        assertTrue(
            engine.observe(blocked, safeSignal, staleMaterialEvidence) is
                WatchContinuityDecision.RecoveryRequired,
        )
        assertTrue(
            engine.observe(blocked, safeSignal, recoveryEvidence(blocked, safeSignal)) is
                WatchContinuityDecision.ResumeReady,
        )
    }

    @Test
    fun gapReasonsAndCommitProvenanceAreImmutableSnapshots() {
        val mutableReasons = mutableSetOf(CollectionInterruptionReason.LOW_BATTERY)
        val gap = CollectionGap(
            gapId = "gap-immutable",
            detectedAtEpochMillis = 1_000L,
            endedAtEpochMillis = null,
            afterSequence = 0L,
            reasons = mutableReasons,
        )
        val mutableProvenance = mutableListOf("sensor:immutable")
        val current = (engine.start(STREAM, signal()) as WatchContinuityDecision.Continuing).snapshot
        val commit = collectionCommit(current, 1L, "sensor:immutable", 10_001L).copy(
            provenanceIds = mutableProvenance,
        )

        mutableReasons.clear()
        mutableProvenance.clear()

        assertEquals(setOf(CollectionInterruptionReason.LOW_BATTERY), gap.reasons)
        assertEquals(listOf("sensor:immutable"), commit.provenanceIds)
        assertThrows(UnsupportedOperationException::class.java) {
            (gap.reasons as MutableSet<CollectionInterruptionReason>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (commit.provenanceIds as MutableList<String>).clear()
        }
        assertTrue(engine.commit(current, commit) is WatchCommitDecision.Committed)
    }

    @Test
    fun sameBootProcessRestartUsesExactPermitAndDoesNotResetSequence() {
        val initial = (engine.start(STREAM, signal()) as WatchContinuityDecision.Continuing).snapshot
        val committed = commit(initial, 1L, "sensor:1", 10_001L)
        val pending = engine.observe(
            committed,
            signal(id = "process-restart", wall = 10_101L, elapsed = 5_100L, processRestarted = true),
        ) as WatchContinuityDecision.ResumeReady

        assertEquals(2L, pending.permit.nextSequence)
        assertTrue(CollectionInterruptionReason.PROCESS_RESTART in pending.snapshot.latestGap!!.reasons)
        val wrong = pending.permit.copy(permitId = "resume-wrong")
        assertEquals(
            "resume_permit_mismatch",
            (engine.confirmResume(
                pending.snapshot,
                wrong,
                signal(id = "confirm", wall = 10_102L, elapsed = 5_101L),
            ) as WatchContinuityDecision.Rejected).code,
        )
        val validConfirmSignal = signal(id = "confirm", wall = 10_102L, elapsed = 5_101L)
        val resumed = engine.confirmResume(
            pending.snapshot,
            pending.permit,
            validConfirmSignal,
            recoveryEvidence(pending.snapshot, validConfirmSignal),
        ) as WatchContinuityDecision.Continuing
        assertEquals(1L, resumed.snapshot.lastCommittedSequence)
        assertEquals(2L, resumed.snapshot.nextSequence)
    }

    @Test
    fun rebootClockAndConsentDiscontinuitiesFailClosed() {
        val current = (engine.start(STREAM, signal()) as WatchContinuityDecision.Continuing).snapshot
        val unverifiedReboot = engine.observe(
            current,
            signal(id = "reboot", wall = 20_000L, elapsed = 1L, boot = "boot-2"),
        ) as WatchContinuityDecision.RecoveryRequired
        assertTrue(CollectionInterruptionReason.REBOOT in unverifiedReboot.snapshot.latestGap!!.reasons)

        val clockJump = engine.observe(
            current,
            signal(id = "clock-jump", wall = 900_000L, elapsed = 5_100L),
        ) as WatchContinuityDecision.RecoveryRequired
        assertTrue(CollectionInterruptionReason.CLOCK_DISCONTINUITY in clockJump.snapshot.latestGap!!.reasons)

        val changedConsent = engine.observe(
            current,
            signal(id = "new-consent", generation = 8L),
        ) as WatchContinuityDecision.ConsentClosed
        assertEquals(ContinuityState.CONSENT_CLOSED, changedConsent.snapshot.state)
        assertTrue(CollectionInterruptionReason.CONSENT_GENERATION_CHANGED in changedConsent.snapshot.latestGap!!.reasons)
        assertTrue(engine.observe(changedConsent.snapshot, signal(id = "reopen")) is WatchContinuityDecision.Rejected)
    }

    @Test
    fun commitsRequireExactContinuityDigestNextSequenceAndChronologicalProvenance() {
        val current = (engine.start(STREAM, signal()) as WatchContinuityDecision.Continuing).snapshot
        val wrongDigest = collectionCommit(current, 1L, "sensor:1", 10_001L).copy(
            expectedContinuitySha256 = "f".repeat(64),
        )
        assertEquals(
            "commit_identity_mismatch",
            (engine.commit(current, wrongDigest) as WatchCommitDecision.Rejected).code,
        )
        val skipped = collectionCommit(current, 2L, "sensor:2", 10_001L)
        assertEquals("sequence_not_next", (engine.commit(current, skipped) as WatchCommitDecision.Rejected).code)
        val clockChanged = collectionCommit(current, 1L, "sensor:3", 10_001L).copy(
            committedAtElapsedRealtimeMillis = 900_000L,
        )
        assertEquals(
            "commit_clock_discontinuity",
            (engine.commit(current, clockChanged) as WatchCommitDecision.Rejected).code,
        )
    }

    @Test
    fun encryptedJournalRejectsForgedSuccessorAndTamperAcrossRestart() {
        val root = temporaryFolder.newFolder("journal-tamper").toPath()
        val journal = journal(root)
        val initial = (engine.start(STREAM, signal()) as WatchContinuityDecision.Continuing).snapshot
        assertCommitted(journal, initial)
        val forged = initial.copy(
            revision = 2L,
            lastCommittedSequence = 2L,
            lastMeasurementEpochMillis = 10_001L,
            provenanceChainSha256 = "a".repeat(64),
            previousSnapshotSha256 = initial.sha256(),
        )
        assertEquals(
            "continuity_successor_invalid",
            (journal.append(forged) as WatchContinuityJournalAppendResult.Rejected).code,
        )

        val record = Files.list(root.resolve("records")).use { files ->
            files.filter { it.fileName.toString().endsWith(".vsr") }.findFirst().orElseThrow()
        }
        val bytes = Files.readAllBytes(record)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        Files.write(record, bytes, WRITE)
        assertEquals(
            "continuity_store_quarantined",
            (journal(root).recover() as WatchContinuityRecoveryResult.Unavailable).code,
        )
    }

    @Test
    fun coordinatorPersistsBeforeReturningResumePermitAcrossProcessShapedRestart() {
        val root = temporaryFolder.newFolder("coordinator-restart").toPath()
        val first = DurableWatchContinuityCoordinator(engine, journal(root))
        val started = first.start(STREAM, signal()) as DurableWatchContinuityResult.Applied
        val initial = (started.decision as WatchContinuityDecision.Continuing).snapshot
        val committed = first.commit(
            collectionCommit(initial, 1L, "sensor:coordinator-1", 10_001L),
        ) as DurableWatchContinuityResult.MeasurementCommitted

        val reopened = DurableWatchContinuityCoordinator(engine, journal(root))
        val pending = reopened.observe(
            signal(id = "coordinator-process-restart", wall = 10_101L, elapsed = 5_100L, processRestarted = true),
        ) as DurableWatchContinuityResult.Applied
        val resume = pending.decision as WatchContinuityDecision.ResumeReady
        val durablePending = (journal(root).recover() as WatchContinuityRecoveryResult.Available).latest
        assertEquals(resume.snapshot.sha256(), requireNotNull(durablePending).sha256())
        assertEquals(committed.snapshot.nextSequence, resume.permit.nextSequence)

        val afterSecondRestart = DurableWatchContinuityCoordinator(engine, journal(root))
        val coordinatorConfirmSignal = signal(
            id = "coordinator-confirm",
            wall = 10_102L,
            elapsed = 5_101L,
        )
        val resumed = afterSecondRestart.confirmResume(
            resume.permit,
            coordinatorConfirmSignal,
            recoveryEvidence(resume.snapshot, coordinatorConfirmSignal),
        ) as DurableWatchContinuityResult.Applied
        assertEquals(
            ContinuityState.COLLECTING,
            (resumed.decision as WatchContinuityDecision.Continuing).snapshot.state,
        )
    }

    private fun commit(
        snapshot: WatchContinuitySnapshot,
        sequence: Long,
        provenance: String,
        committedAt: Long,
    ): WatchContinuitySnapshot = (engine.commit(
        snapshot,
        collectionCommit(snapshot, sequence, provenance, committedAt),
    ) as WatchCommitDecision.Committed).snapshot

    private fun collectionCommit(
        snapshot: WatchContinuitySnapshot,
        sequence: Long,
        provenance: String,
        committedAt: Long,
    ) = WatchCollectionCommit(
        streamId = snapshot.streamId,
        consentGeneration = snapshot.consentGeneration,
        sequence = sequence,
        measurementStartEpochMillis = committedAt - 1L,
        measurementEndEpochMillis = committedAt - 1L,
        committedAtEpochMillis = committedAt,
        committedAtElapsedRealtimeMillis = snapshot.elapsedRealtimeMillis +
            (committedAt - snapshot.observedAtEpochMillis),
        provenanceIds = listOf(provenance),
        expectedContinuitySha256 = snapshot.sha256(),
    )

    private fun signal(
        id: String = "runtime-1",
        wall: Long = 10_000L,
        elapsed: Long = 5_000L,
        battery: Int = 80,
        charging: Boolean = false,
        thermal: WatchThermalState = WatchThermalState.NOMINAL,
        wrist: WatchWristState = WatchWristState.ON_WRIST,
        boot: String = "boot-1",
        generation: Long = 7L,
        processRestarted: Boolean = false,
    ) = WatchRuntimeSignal(
        signalId = id,
        deviceAlias = "ultra2-pilot-1",
        firmwareGeneration = "fw-1",
        consentGeneration = generation,
        consentActive = true,
        bootSessionId = boot,
        observedAtEpochMillis = wall,
        elapsedRealtimeMillis = elapsed,
        batteryPercent = battery,
        charging = charging,
        thermalState = thermal,
        wristState = wrist,
        storageReady = true,
        encryptionKeyReady = true,
        permissionsReady = true,
        registrationReady = true,
        processRestarted = processRestarted,
    )

    private fun recoveryEvidence(
        current: WatchContinuitySnapshot,
        signal: WatchRuntimeSignal,
    ): WatchRecoveryEvidence = recoveryIssuer.issue(
        current = current,
        signal = signal,
        recoveryMaterialSha256 = RECOVERY_MATERIAL_SHA256,
    )

    private fun recoveryMac(payload: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(RECOVERY_KEY.copyOf(), "HmacSHA256"))
        doFinal(payload.copyOf())
    }

    private fun journal(root: java.nio.file.Path) = EncryptedWatchContinuityJournal(
        EncryptedAppendOnlyRecordStore(
            rootDirectory = root,
            secretKey = KEY,
            keyId = "continuity-key-1",
            secureRandom = SecureRandom(),
            maxPayloadBytes = 16 * 1024,
        ),
    )

    private fun assertCommitted(
        journal: EncryptedWatchContinuityJournal,
        snapshot: WatchContinuitySnapshot,
    ) = assertTrue(journal.append(snapshot) is WatchContinuityJournalAppendResult.Committed)

    private companion object {
        const val STREAM = "pilot-stream-1"
        const val RECOVERY_KEY_ID = "watch-recovery-v1"
        val RECOVERY_MATERIAL_SHA256 = "d".repeat(64)
        val KEY = SecretKeySpec(ByteArray(32) { (it + 19).toByte() }, "AES")
        val RECOVERY_KEY = ByteArray(32) { (it + 73).toByte() }
    }
}
