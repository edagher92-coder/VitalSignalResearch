package au.com.elied.vitalsignal.wear.continuity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.math.abs

enum class WatchThermalState {
    NOMINAL,
    ELEVATED,
    SEVERE,
    UNKNOWN,
}

enum class WatchWristState {
    ON_WRIST,
    OFF_WRIST,
    UNKNOWN,
}

enum class ContinuityState {
    COLLECTING,
    PAUSED,
    RESUME_PENDING,
    RECOVERY_REQUIRED,
    CONSENT_CLOSED,
}

enum class CollectionInterruptionReason {
    LOW_BATTERY,
    CHARGING,
    THERMAL_LIMIT,
    THERMAL_UNKNOWN,
    OFF_WRIST,
    WRIST_STATE_UNKNOWN,
    PROCESS_RESTART,
    REBOOT,
    CLOCK_DISCONTINUITY,
    CONSENT_REVOKED,
    CONSENT_GENERATION_CHANGED,
    CONSENT_GENERATION_ROLLBACK,
    STORAGE_UNAVAILABLE,
    ENCRYPTION_KEY_UNAVAILABLE,
    PERMISSION_UNAVAILABLE,
    REGISTRATION_UNAVAILABLE,
    DEVICE_IDENTITY_CHANGED,
}

enum class GapInterpretation {
    EXPLICIT_MISSING_NEVER_IMPUTE_NORMAL,
}

class CollectionGap(
    val gapId: String,
    val detectedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val afterSequence: Long,
    reasons: Set<CollectionInterruptionReason>,
    val interpretation: GapInterpretation = GapInterpretation.EXPLICIT_MISSING_NEVER_IMPUTE_NORMAL,
) {
    val reasons: Set<CollectionInterruptionReason> = java.util.Set.copyOf(reasons)

    init {
        require(gapId.matches(SAFE_ID))
        require(detectedAtEpochMillis >= 0L)
        require(endedAtEpochMillis == null || endedAtEpochMillis >= detectedAtEpochMillis)
        require(afterSequence >= 0L)
        require(this.reasons.isNotEmpty())
    }

    val active: Boolean get() = endedAtEpochMillis == null

    fun copy(
        gapId: String = this.gapId,
        detectedAtEpochMillis: Long = this.detectedAtEpochMillis,
        endedAtEpochMillis: Long? = this.endedAtEpochMillis,
        afterSequence: Long = this.afterSequence,
        reasons: Set<CollectionInterruptionReason> = this.reasons,
        interpretation: GapInterpretation = this.interpretation,
    ) = CollectionGap(
        gapId,
        detectedAtEpochMillis,
        endedAtEpochMillis,
        afterSequence,
        reasons,
        interpretation,
    )

    override fun equals(other: Any?): Boolean = other is CollectionGap &&
        gapId == other.gapId &&
        detectedAtEpochMillis == other.detectedAtEpochMillis &&
        endedAtEpochMillis == other.endedAtEpochMillis &&
        afterSequence == other.afterSequence &&
        reasons == other.reasons &&
        interpretation == other.interpretation

    override fun hashCode(): Int = listOf(
        gapId,
        detectedAtEpochMillis,
        endedAtEpochMillis,
        afterSequence,
        reasons,
        interpretation,
    ).hashCode()
}

/**
 * Platform-neutral projection of runtime facts. Android owns how each fact is measured.
 * A boolean here is not a hardware claim: the Android boundary must supply verified values.
 */
data class WatchRuntimeSignal(
    val signalId: String,
    val deviceAlias: String,
    val firmwareGeneration: String,
    val consentGeneration: Long,
    val consentActive: Boolean,
    val bootSessionId: String,
    val observedAtEpochMillis: Long,
    val elapsedRealtimeMillis: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val thermalState: WatchThermalState,
    val wristState: WatchWristState,
    val storageReady: Boolean,
    val encryptionKeyReady: Boolean,
    val permissionsReady: Boolean,
    val registrationReady: Boolean,
    val processRestarted: Boolean = false,
) {
    init {
        require(signalId.matches(SAFE_ID))
        require(deviceAlias.matches(SAFE_ID))
        require(firmwareGeneration.matches(SAFE_ID))
        require(consentGeneration > 0L)
        require(bootSessionId.matches(SAFE_ID))
        require(observedAtEpochMillis >= 0L)
        require(elapsedRealtimeMillis >= 0L)
        require(batteryPercent in 0..100)
    }
}

fun interface WatchRecoveryEvidenceSigner {
    fun sign(issuerKeyId: String, canonicalPayload: ByteArray): ByteArray
}

fun interface WatchRecoveryEvidenceSignatureVerifier {
    fun verify(issuerKeyId: String, canonicalPayload: ByteArray, signature: ByteArray): Boolean
}

/**
 * Platform composition authority for the material that is true at the instant recovery is used.
 * The digest must be rebuilt from the current consent lease, device identity, encryption-key
 * generation, durable journal head, permissions and tracker registration. It must not be copied
 * from the presented recovery evidence.
 */
fun interface CurrentWatchRecoveryMaterialProvider {
    fun currentSha256(
        requiredSnapshot: WatchContinuitySnapshot,
        runtimeSignal: WatchRuntimeSignal,
    ): String?
}

/**
 * Short-lived authenticated recovery evidence. The material digest must bind the current signed
 * consent lease, device identity, encryption key generation, durable journal, permissions and
 * tracker registration as verified by the platform composition root.
 */
class WatchRecoveryEvidence internal constructor(
    val evidenceId: String,
    val issuerKeyId: String,
    val requiredSnapshotSha256: String,
    val runtimeSignalSha256: String,
    val recoveryMaterialSha256: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    signature: ByteArray,
) {
    private val immutableSignature = signature.copyOf()

    init {
        require(evidenceId.matches(SAFE_ID))
        require(issuerKeyId.matches(SAFE_ID))
        require(requiredSnapshotSha256.matches(SHA_256))
        require(runtimeSignalSha256.matches(SHA_256))
        require(recoveryMaterialSha256.matches(SHA_256))
        require(issuedAtEpochMillis >= 0L)
        require(expiresAtEpochMillis > issuedAtEpochMillis)
        require(expiresAtEpochMillis - issuedAtEpochMillis <= MAX_RECOVERY_EVIDENCE_LIFETIME_MILLIS)
        require(immutableSignature.size in 16..512)
    }

    fun signatureBytes(): ByteArray = immutableSignature.copyOf()

    companion object {
        const val MAX_RECOVERY_EVIDENCE_LIFETIME_MILLIS = 5L * 60L * 1_000L
    }
}

class WatchRecoveryEvidenceIssuer(
    private val issuerKeyId: String,
    private val signer: WatchRecoveryEvidenceSigner,
) {
    init { require(issuerKeyId.matches(SAFE_ID)) }

    fun issue(
        current: WatchContinuitySnapshot,
        signal: WatchRuntimeSignal,
        recoveryMaterialSha256: String,
        issuedAtEpochMillis: Long = signal.observedAtEpochMillis,
        expiresAtEpochMillis: Long = issuedAtEpochMillis + 60_000L,
    ): WatchRecoveryEvidence {
        require(signal.consentActive)
        require(signal.storageReady && signal.encryptionKeyReady)
        require(signal.permissionsReady && signal.registrationReady)
        require(recoveryMaterialSha256.matches(SHA_256))
        val unsigned = WatchRecoveryEvidence(
            evidenceId = deterministicId(
                "recovery",
                current.sha256(),
                runtimeSignalSha256(signal),
                recoveryMaterialSha256,
                issuedAtEpochMillis.toString(),
            ),
            issuerKeyId = issuerKeyId,
            requiredSnapshotSha256 = current.sha256(),
            runtimeSignalSha256 = runtimeSignalSha256(signal),
            recoveryMaterialSha256 = recoveryMaterialSha256,
            issuedAtEpochMillis = issuedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            signature = ByteArray(32),
        )
        return WatchRecoveryEvidence(
            evidenceId = unsigned.evidenceId,
            issuerKeyId = unsigned.issuerKeyId,
            requiredSnapshotSha256 = unsigned.requiredSnapshotSha256,
            runtimeSignalSha256 = unsigned.runtimeSignalSha256,
            recoveryMaterialSha256 = unsigned.recoveryMaterialSha256,
            issuedAtEpochMillis = unsigned.issuedAtEpochMillis,
            expiresAtEpochMillis = unsigned.expiresAtEpochMillis,
            signature = signer.sign(issuerKeyId, canonicalRecoveryEvidence(unsigned).copyOf()),
        )
    }
}

data class WatchContinuitySnapshot(
    val revision: Long,
    val streamId: String,
    val deviceAlias: String,
    val firmwareGeneration: String,
    val consentGeneration: Long,
    val bootSessionId: String,
    val state: ContinuityState,
    val observedAtEpochMillis: Long,
    val elapsedRealtimeMillis: Long,
    val lastCommittedSequence: Long,
    val lastMeasurementEpochMillis: Long?,
    val provenanceChainSha256: String,
    val latestGap: CollectionGap?,
    val pendingResumePermitId: String?,
    val previousSnapshotSha256: String,
) {
    init {
        require(revision > 0L)
        require(streamId.matches(SAFE_ID))
        require(deviceAlias.matches(SAFE_ID))
        require(firmwareGeneration.matches(SAFE_ID))
        require(consentGeneration > 0L)
        require(bootSessionId.matches(SAFE_ID))
        require(observedAtEpochMillis >= 0L)
        require(elapsedRealtimeMillis >= 0L)
        require(lastCommittedSequence >= 0L)
        require(lastMeasurementEpochMillis == null || lastMeasurementEpochMillis >= 0L)
        require(provenanceChainSha256.matches(SHA_256))
        require(previousSnapshotSha256.matches(SHA_256))
        require(pendingResumePermitId == null || pendingResumePermitId.matches(SAFE_ID))
        require((state == ContinuityState.RESUME_PENDING) == (pendingResumePermitId != null))
        require(state != ContinuityState.COLLECTING || latestGap?.active != true)
        require(latestGap == null || latestGap.afterSequence <= lastCommittedSequence)
    }

    val nextSequence: Long get() = Math.addExact(lastCommittedSequence, 1L)
    fun sha256(): String = sha256Hex(WatchContinuitySnapshotCodec.encode(this))
}

data class WatchResumePermit(
    val permitId: String,
    val requiredSnapshotSha256: String,
    val streamId: String,
    val consentGeneration: Long,
    val bootSessionId: String,
    val nextSequence: Long,
    val gapId: String,
) {
    init {
        require(permitId.matches(SAFE_ID))
        require(requiredSnapshotSha256.matches(SHA_256))
        require(streamId.matches(SAFE_ID))
        require(consentGeneration > 0L)
        require(bootSessionId.matches(SAFE_ID))
        require(nextSequence > 0L)
        require(gapId.matches(SAFE_ID))
    }
}

class WatchCollectionCommit(
    val streamId: String,
    val consentGeneration: Long,
    val sequence: Long,
    val measurementStartEpochMillis: Long,
    val measurementEndEpochMillis: Long,
    val committedAtEpochMillis: Long,
    val committedAtElapsedRealtimeMillis: Long,
    provenanceIds: List<String>,
    val expectedContinuitySha256: String,
) {
    val provenanceIds: List<String> = java.util.List.copyOf(provenanceIds)

    init {
        require(streamId.matches(SAFE_ID))
        require(consentGeneration > 0L)
        require(sequence > 0L)
        require(measurementStartEpochMillis >= 0L)
        require(measurementEndEpochMillis >= measurementStartEpochMillis)
        require(committedAtEpochMillis >= measurementEndEpochMillis)
        require(committedAtElapsedRealtimeMillis >= 0L)
        require(this.provenanceIds.isNotEmpty() && this.provenanceIds.size <= 256)
        require(this.provenanceIds.distinct().size == this.provenanceIds.size)
        require(this.provenanceIds.all { it.matches(SAFE_PROVENANCE_ID) })
        require(expectedContinuitySha256.matches(SHA_256))
    }

    fun copy(
        streamId: String = this.streamId,
        consentGeneration: Long = this.consentGeneration,
        sequence: Long = this.sequence,
        measurementStartEpochMillis: Long = this.measurementStartEpochMillis,
        measurementEndEpochMillis: Long = this.measurementEndEpochMillis,
        committedAtEpochMillis: Long = this.committedAtEpochMillis,
        committedAtElapsedRealtimeMillis: Long = this.committedAtElapsedRealtimeMillis,
        provenanceIds: List<String> = this.provenanceIds,
        expectedContinuitySha256: String = this.expectedContinuitySha256,
    ) = WatchCollectionCommit(
        streamId,
        consentGeneration,
        sequence,
        measurementStartEpochMillis,
        measurementEndEpochMillis,
        committedAtEpochMillis,
        committedAtElapsedRealtimeMillis,
        provenanceIds,
        expectedContinuitySha256,
    )

    override fun equals(other: Any?): Boolean = other is WatchCollectionCommit &&
        streamId == other.streamId &&
        consentGeneration == other.consentGeneration &&
        sequence == other.sequence &&
        measurementStartEpochMillis == other.measurementStartEpochMillis &&
        measurementEndEpochMillis == other.measurementEndEpochMillis &&
        committedAtEpochMillis == other.committedAtEpochMillis &&
        committedAtElapsedRealtimeMillis == other.committedAtElapsedRealtimeMillis &&
        provenanceIds == other.provenanceIds &&
        expectedContinuitySha256 == other.expectedContinuitySha256

    override fun hashCode(): Int = listOf(
        streamId,
        consentGeneration,
        sequence,
        measurementStartEpochMillis,
        measurementEndEpochMillis,
        committedAtEpochMillis,
        committedAtElapsedRealtimeMillis,
        provenanceIds,
        expectedContinuitySha256,
    ).hashCode()
}

sealed interface WatchContinuityDecision {
    data class Continuing(val snapshot: WatchContinuitySnapshot) : WatchContinuityDecision
    data class Paused(val snapshot: WatchContinuitySnapshot) : WatchContinuityDecision
    data class ResumeReady(
        val snapshot: WatchContinuitySnapshot,
        val permit: WatchResumePermit,
    ) : WatchContinuityDecision
    data class RecoveryRequired(val snapshot: WatchContinuitySnapshot) : WatchContinuityDecision
    data class ConsentClosed(val snapshot: WatchContinuitySnapshot) : WatchContinuityDecision
    data class Rejected(val code: String) : WatchContinuityDecision
}

sealed interface WatchCommitDecision {
    data class Committed(val snapshot: WatchContinuitySnapshot) : WatchCommitDecision
    data class Rejected(val code: String) : WatchCommitDecision
}

/**
 * Deterministic continuity policy. It does not start Android services or infer values in gaps.
 * Every restart crosses an explicit resume permit, and every committed measurement keeps the
 * same monotonically increasing sequence and provenance chain.
 */
class WatchCollectionContinuityEngine(
    private val minimumCollectionBatteryPercent: Int = 15,
    private val maximumClockDiscontinuityMillis: Long = 5L * 60L * 1_000L,
    private val recoveryEvidenceVerifier: WatchRecoveryEvidenceSignatureVerifier =
        WatchRecoveryEvidenceSignatureVerifier { _, _, _ -> false },
    private val currentRecoveryMaterialProvider: CurrentWatchRecoveryMaterialProvider =
        CurrentWatchRecoveryMaterialProvider { _, _ -> null },
) {
    init {
        require(minimumCollectionBatteryPercent in 1..50)
        require(maximumClockDiscontinuityMillis in 1_000L..(60L * 60L * 1_000L))
    }

    fun start(streamId: String, signal: WatchRuntimeSignal): WatchContinuityDecision {
        require(streamId.matches(SAFE_ID))
        val reasons = runtimeReasons(signal)
        val state = stateForReasons(reasons)
        val gap = reasons.takeIf { it.isNotEmpty() }?.let {
            newGap(streamId, signal, afterSequence = 0L, reasons = it)
        }
        val initial = WatchContinuitySnapshot(
            revision = 1L,
            streamId = streamId,
            deviceAlias = signal.deviceAlias,
            firmwareGeneration = signal.firmwareGeneration,
            consentGeneration = signal.consentGeneration,
            bootSessionId = signal.bootSessionId,
            state = state,
            observedAtEpochMillis = signal.observedAtEpochMillis,
            elapsedRealtimeMillis = signal.elapsedRealtimeMillis,
            lastCommittedSequence = 0L,
            lastMeasurementEpochMillis = null,
            provenanceChainSha256 = ZERO_SHA_256,
            latestGap = gap,
            pendingResumePermitId = null,
            previousSnapshotSha256 = ZERO_SHA_256,
        )
        return decisionFor(initial)
    }

    fun observe(
        current: WatchContinuitySnapshot,
        signal: WatchRuntimeSignal,
        recoveryEvidence: WatchRecoveryEvidence? = null,
    ): WatchContinuityDecision {
        if (current.state == ContinuityState.CONSENT_CLOSED) {
            return WatchContinuityDecision.Rejected("consent_closed_requires_new_stream")
        }
        val reasons = linkedSetOf<CollectionInterruptionReason>()
        if (signal.deviceAlias != current.deviceAlias ||
            signal.firmwareGeneration != current.firmwareGeneration
        ) {
            reasons += CollectionInterruptionReason.DEVICE_IDENTITY_CHANGED
        }
        when {
            signal.consentGeneration < current.consentGeneration ->
                reasons += CollectionInterruptionReason.CONSENT_GENERATION_ROLLBACK
            signal.consentGeneration > current.consentGeneration ->
                reasons += CollectionInterruptionReason.CONSENT_GENERATION_CHANGED
            !signal.consentActive -> reasons += CollectionInterruptionReason.CONSENT_REVOKED
        }

        val rebooted = signal.bootSessionId != current.bootSessionId
        if (rebooted) {
            reasons += CollectionInterruptionReason.REBOOT
        } else {
            if (signal.elapsedRealtimeMillis < current.elapsedRealtimeMillis) {
                reasons += CollectionInterruptionReason.CLOCK_DISCONTINUITY
            } else {
                val wallDelta = signal.observedAtEpochMillis - current.observedAtEpochMillis
                val elapsedDelta = signal.elapsedRealtimeMillis - current.elapsedRealtimeMillis
                if (absDifference(wallDelta, elapsedDelta) > maximumClockDiscontinuityMillis) {
                    reasons += CollectionInterruptionReason.CLOCK_DISCONTINUITY
                }
            }
        }
        if (signal.processRestarted) reasons += CollectionInterruptionReason.PROCESS_RESTART
        reasons += runtimeReasons(signal)

        val terminalConsent = reasons.any {
            it == CollectionInterruptionReason.CONSENT_REVOKED ||
                it == CollectionInterruptionReason.CONSENT_GENERATION_CHANGED ||
                it == CollectionInterruptionReason.CONSENT_GENERATION_ROLLBACK
        }
        val recoveryVerified = recoveryMaterialVerified(current, signal, recoveryEvidence)
        val mustRecover = reasons.any { it in RECOVERY_REASONS } ||
            (rebooted && !recoveryVerified) ||
            (current.state == ContinuityState.RECOVERY_REQUIRED && !recoveryVerified)

        if (reasons.isEmpty()) {
            return when (current.state) {
                ContinuityState.PAUSED -> resumePending(current, signal)
                ContinuityState.RECOVERY_REQUIRED -> if (recoveryVerified) {
                    resumePending(current, signal)
                } else {
                    WatchContinuityDecision.RecoveryRequired(
                        advance(
                            current,
                            signal,
                            ContinuityState.RECOVERY_REQUIRED,
                            current.latestGap,
                            null,
                        ),
                    )
                }
                ContinuityState.COLLECTING -> WatchContinuityDecision.Continuing(
                    advance(current, signal, ContinuityState.COLLECTING, current.latestGap, null),
                )
                ContinuityState.RESUME_PENDING -> WatchContinuityDecision.ResumeReady(current, permitFor(current))
                ContinuityState.CONSENT_CLOSED -> WatchContinuityDecision.Rejected(
                    "consent_closed_requires_new_stream",
                )
            }
        }

        val gap = mergeGap(current, signal, reasons)
        val nextState = when {
            terminalConsent -> ContinuityState.CONSENT_CLOSED
            mustRecover -> ContinuityState.RECOVERY_REQUIRED
            reasons.all { it == CollectionInterruptionReason.PROCESS_RESTART } ->
                ContinuityState.RESUME_PENDING
            reasons.all { it in PAUSE_REASONS } -> ContinuityState.PAUSED
            rebooted && recoveryVerified -> ContinuityState.RESUME_PENDING
            else -> ContinuityState.RECOVERY_REQUIRED
        }
        return if (nextState == ContinuityState.RESUME_PENDING) {
            resumePending(current, signal, gap)
        } else {
            decisionFor(advance(current, signal, nextState, gap, null))
        }
    }

    fun confirmResume(
        current: WatchContinuitySnapshot,
        permit: WatchResumePermit,
        signal: WatchRuntimeSignal,
        recoveryEvidence: WatchRecoveryEvidence? = null,
    ): WatchContinuityDecision {
        if (current.state != ContinuityState.RESUME_PENDING) {
            return WatchContinuityDecision.Rejected("resume_not_pending")
        }
        val exact = permit == permitFor(current) &&
            permit.requiredSnapshotSha256 == current.sha256() &&
            current.pendingResumePermitId == permit.permitId
        if (!exact) return WatchContinuityDecision.Rejected("resume_permit_mismatch")
        if (!exactRuntimeIdentity(current, signal) || !signal.consentActive) {
            return WatchContinuityDecision.Rejected("resume_runtime_identity_mismatch")
        }
        if (!recoveryMaterialVerified(current, signal, recoveryEvidence) ||
            runtimeReasons(signal).isNotEmpty()
        ) {
            return WatchContinuityDecision.Rejected("resume_runtime_not_verified")
        }
        if (signal.observedAtEpochMillis < current.observedAtEpochMillis ||
            signal.elapsedRealtimeMillis < current.elapsedRealtimeMillis
        ) {
            return WatchContinuityDecision.Rejected("resume_clock_rollback")
        }
        val wallDelta = signal.observedAtEpochMillis - current.observedAtEpochMillis
        val elapsedDelta = signal.elapsedRealtimeMillis - current.elapsedRealtimeMillis
        if (absDifference(wallDelta, elapsedDelta) > maximumClockDiscontinuityMillis) {
            return WatchContinuityDecision.Rejected("resume_clock_discontinuity")
        }
        val closedGap = current.latestGap?.copy(endedAtEpochMillis = signal.observedAtEpochMillis)
            ?: return WatchContinuityDecision.Rejected("resume_gap_missing")
        return WatchContinuityDecision.Continuing(
            advance(current, signal, ContinuityState.COLLECTING, closedGap, null),
        )
    }

    fun commit(
        current: WatchContinuitySnapshot,
        commit: WatchCollectionCommit,
    ): WatchCommitDecision {
        if (current.state != ContinuityState.COLLECTING) {
            return WatchCommitDecision.Rejected("collection_not_active")
        }
        if (commit.streamId != current.streamId ||
            commit.consentGeneration != current.consentGeneration ||
            commit.expectedContinuitySha256 != current.sha256()
        ) return WatchCommitDecision.Rejected("commit_identity_mismatch")
        if (commit.sequence != current.nextSequence) {
            return WatchCommitDecision.Rejected("sequence_not_next")
        }
        if (commit.measurementStartEpochMillis < (current.lastMeasurementEpochMillis ?: 0L)) {
            return WatchCommitDecision.Rejected("measurement_time_rollback")
        }
        if (commit.committedAtEpochMillis < current.observedAtEpochMillis) {
            return WatchCommitDecision.Rejected("commit_before_continuity_observation")
        }
        if (commit.committedAtElapsedRealtimeMillis < current.elapsedRealtimeMillis) {
            return WatchCommitDecision.Rejected("commit_elapsed_time_rollback")
        }
        val commitWallDelta = commit.committedAtEpochMillis - current.observedAtEpochMillis
        val commitElapsedDelta = commit.committedAtElapsedRealtimeMillis - current.elapsedRealtimeMillis
        if (absDifference(commitWallDelta, commitElapsedDelta) > maximumClockDiscontinuityMillis) {
            return WatchCommitDecision.Rejected("commit_clock_discontinuity")
        }
        val provenanceDigest = sha256Hex(
            buildString {
                append(current.provenanceChainSha256)
                append('|').append(commit.sequence)
                append('|').append(commit.measurementStartEpochMillis)
                append('|').append(commit.measurementEndEpochMillis)
                commit.provenanceIds.sorted().forEach { append('|').append(it) }
            }.toByteArray(Charsets.UTF_8),
        )
        return WatchCommitDecision.Committed(
            current.copy(
                revision = current.revision + 1L,
                observedAtEpochMillis = commit.committedAtEpochMillis,
                elapsedRealtimeMillis = commit.committedAtElapsedRealtimeMillis,
                lastCommittedSequence = commit.sequence,
                lastMeasurementEpochMillis = commit.measurementEndEpochMillis,
                provenanceChainSha256 = provenanceDigest,
                previousSnapshotSha256 = current.sha256(),
            ),
        )
    }

    private fun runtimeReasons(signal: WatchRuntimeSignal): Set<CollectionInterruptionReason> =
        linkedSetOf<CollectionInterruptionReason>().apply {
            if (!signal.consentActive) add(CollectionInterruptionReason.CONSENT_REVOKED)
            if (!signal.storageReady) add(CollectionInterruptionReason.STORAGE_UNAVAILABLE)
            if (!signal.encryptionKeyReady) add(CollectionInterruptionReason.ENCRYPTION_KEY_UNAVAILABLE)
            if (!signal.permissionsReady) add(CollectionInterruptionReason.PERMISSION_UNAVAILABLE)
            if (!signal.registrationReady) add(CollectionInterruptionReason.REGISTRATION_UNAVAILABLE)
            if (signal.batteryPercent < minimumCollectionBatteryPercent) {
                add(CollectionInterruptionReason.LOW_BATTERY)
            }
            if (signal.charging) add(CollectionInterruptionReason.CHARGING)
            when (signal.thermalState) {
                WatchThermalState.NOMINAL -> Unit
                WatchThermalState.ELEVATED,
                WatchThermalState.SEVERE,
                -> add(CollectionInterruptionReason.THERMAL_LIMIT)
                WatchThermalState.UNKNOWN -> add(CollectionInterruptionReason.THERMAL_UNKNOWN)
            }
            when (signal.wristState) {
                WatchWristState.ON_WRIST -> Unit
                WatchWristState.OFF_WRIST -> add(CollectionInterruptionReason.OFF_WRIST)
                WatchWristState.UNKNOWN -> add(CollectionInterruptionReason.WRIST_STATE_UNKNOWN)
            }
        }

    private fun recoveryMaterialVerified(
        current: WatchContinuitySnapshot,
        signal: WatchRuntimeSignal,
        evidence: WatchRecoveryEvidence?,
    ): Boolean {
        evidence ?: return false
        if (evidence.requiredSnapshotSha256 != current.sha256() ||
            evidence.runtimeSignalSha256 != runtimeSignalSha256(signal) ||
            signal.observedAtEpochMillis !in evidence.issuedAtEpochMillis until evidence.expiresAtEpochMillis
        ) return false
        val currentMaterialSha256 = try {
            currentRecoveryMaterialProvider.currentSha256(current, signal)
        } catch (_: RuntimeException) {
            null
        }
        if (currentMaterialSha256?.matches(SHA_256) != true ||
            currentMaterialSha256 != evidence.recoveryMaterialSha256
        ) return false
        return try {
            recoveryEvidenceVerifier.verify(
                evidence.issuerKeyId,
                canonicalRecoveryEvidence(evidence).copyOf(),
                evidence.signatureBytes(),
            )
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun stateForReasons(reasons: Set<CollectionInterruptionReason>): ContinuityState = when {
        CollectionInterruptionReason.CONSENT_REVOKED in reasons -> ContinuityState.CONSENT_CLOSED
        reasons.isEmpty() -> ContinuityState.COLLECTING
        reasons.all { it in PAUSE_REASONS } -> ContinuityState.PAUSED
        else -> ContinuityState.RECOVERY_REQUIRED
    }

    private fun resumePending(
        current: WatchContinuitySnapshot,
        signal: WatchRuntimeSignal,
        gap: CollectionGap = current.latestGap?.takeIf(CollectionGap::active)
            ?: newGap(
                current.streamId,
                signal,
                current.lastCommittedSequence,
                setOf(CollectionInterruptionReason.PROCESS_RESTART),
            ),
    ): WatchContinuityDecision.ResumeReady {
        val permitId = deterministicId(
            "resume",
            current.sha256(),
            signal.signalId,
            gap.gapId,
            current.nextSequence.toString(),
            signal.bootSessionId,
        )
        val pending = advance(current, signal, ContinuityState.RESUME_PENDING, gap, permitId)
        return WatchContinuityDecision.ResumeReady(pending, permitFor(pending))
    }

    private fun permitFor(snapshot: WatchContinuitySnapshot): WatchResumePermit {
        require(snapshot.state == ContinuityState.RESUME_PENDING)
        val gap = requireNotNull(snapshot.latestGap?.takeIf(CollectionGap::active))
        return WatchResumePermit(
            permitId = requireNotNull(snapshot.pendingResumePermitId),
            requiredSnapshotSha256 = snapshot.sha256(),
            streamId = snapshot.streamId,
            consentGeneration = snapshot.consentGeneration,
            bootSessionId = snapshot.bootSessionId,
            nextSequence = snapshot.nextSequence,
            gapId = gap.gapId,
        )
    }

    private fun mergeGap(
        current: WatchContinuitySnapshot,
        signal: WatchRuntimeSignal,
        reasons: Set<CollectionInterruptionReason>,
    ): CollectionGap {
        val open = current.latestGap?.takeIf(CollectionGap::active)
        return if (open != null) {
            open.copy(reasons = open.reasons + reasons)
        } else {
            newGap(current.streamId, signal, current.lastCommittedSequence, reasons)
        }
    }

    private fun newGap(
        streamId: String,
        signal: WatchRuntimeSignal,
        afterSequence: Long,
        reasons: Set<CollectionInterruptionReason>,
    ) = CollectionGap(
        gapId = deterministicId(
            "gap",
            streamId,
            signal.signalId,
            afterSequence.toString(),
            reasons.sortedBy(CollectionInterruptionReason::ordinal).joinToString(",", transform = { it.name }),
        ),
        detectedAtEpochMillis = signal.observedAtEpochMillis,
        endedAtEpochMillis = null,
        afterSequence = afterSequence,
        reasons = reasons.toSet(),
    )

    private fun advance(
        current: WatchContinuitySnapshot,
        signal: WatchRuntimeSignal,
        state: ContinuityState,
        gap: CollectionGap?,
        pendingPermitId: String?,
    ) = current.copy(
        revision = current.revision + 1L,
        bootSessionId = signal.bootSessionId,
        state = state,
        observedAtEpochMillis = signal.observedAtEpochMillis,
        elapsedRealtimeMillis = signal.elapsedRealtimeMillis,
        latestGap = gap,
        pendingResumePermitId = pendingPermitId,
        previousSnapshotSha256 = current.sha256(),
    )

    private fun exactRuntimeIdentity(
        snapshot: WatchContinuitySnapshot,
        signal: WatchRuntimeSignal,
    ): Boolean = signal.deviceAlias == snapshot.deviceAlias &&
        signal.firmwareGeneration == snapshot.firmwareGeneration &&
        signal.consentGeneration == snapshot.consentGeneration &&
        signal.bootSessionId == snapshot.bootSessionId

    private fun decisionFor(snapshot: WatchContinuitySnapshot): WatchContinuityDecision = when (snapshot.state) {
        ContinuityState.COLLECTING -> WatchContinuityDecision.Continuing(snapshot)
        ContinuityState.PAUSED -> WatchContinuityDecision.Paused(snapshot)
        ContinuityState.RESUME_PENDING -> WatchContinuityDecision.ResumeReady(snapshot, permitFor(snapshot))
        ContinuityState.RECOVERY_REQUIRED -> WatchContinuityDecision.RecoveryRequired(snapshot)
        ContinuityState.CONSENT_CLOSED -> WatchContinuityDecision.ConsentClosed(snapshot)
    }

    private companion object {
        val PAUSE_REASONS = setOf(
            CollectionInterruptionReason.LOW_BATTERY,
            CollectionInterruptionReason.CHARGING,
            CollectionInterruptionReason.THERMAL_LIMIT,
            CollectionInterruptionReason.OFF_WRIST,
        )
        val RECOVERY_REASONS = setOf(
            CollectionInterruptionReason.THERMAL_UNKNOWN,
            CollectionInterruptionReason.WRIST_STATE_UNKNOWN,
            CollectionInterruptionReason.CLOCK_DISCONTINUITY,
            CollectionInterruptionReason.STORAGE_UNAVAILABLE,
            CollectionInterruptionReason.ENCRYPTION_KEY_UNAVAILABLE,
            CollectionInterruptionReason.PERMISSION_UNAVAILABLE,
            CollectionInterruptionReason.REGISTRATION_UNAVAILABLE,
            CollectionInterruptionReason.DEVICE_IDENTITY_CHANGED,
            CollectionInterruptionReason.CONSENT_GENERATION_CHANGED,
            CollectionInterruptionReason.CONSENT_GENERATION_ROLLBACK,
        )
    }
}

internal fun runtimeSignalSha256(signal: WatchRuntimeSignal): String = sha256Hex(
    ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeBounded(signal.signalId)
            output.writeBounded(signal.deviceAlias)
            output.writeBounded(signal.firmwareGeneration)
            output.writeLong(signal.consentGeneration)
            output.writeBoolean(signal.consentActive)
            output.writeBounded(signal.bootSessionId)
            output.writeLong(signal.observedAtEpochMillis)
            output.writeLong(signal.elapsedRealtimeMillis)
            output.writeInt(signal.batteryPercent)
            output.writeBoolean(signal.charging)
            output.writeInt(signal.thermalState.ordinal)
            output.writeInt(signal.wristState.ordinal)
            output.writeBoolean(signal.storageReady)
            output.writeBoolean(signal.encryptionKeyReady)
            output.writeBoolean(signal.permissionsReady)
            output.writeBoolean(signal.registrationReady)
            output.writeBoolean(signal.processRestarted)
        }
        buffer.toByteArray()
    },
)

internal fun canonicalRecoveryEvidence(evidence: WatchRecoveryEvidence): ByteArray =
    ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeBounded(evidence.evidenceId)
            output.writeBounded(evidence.issuerKeyId)
            output.writeBounded(evidence.requiredSnapshotSha256)
            output.writeBounded(evidence.runtimeSignalSha256)
            output.writeBounded(evidence.recoveryMaterialSha256)
            output.writeLong(evidence.issuedAtEpochMillis)
            output.writeLong(evidence.expiresAtEpochMillis)
        }
        buffer.toByteArray()
    }

internal object WatchContinuitySnapshotCodec {
    private const val MAGIC = 0x56534354 // VSCT
    private const val VERSION = 1

    fun encode(value: WatchContinuitySnapshot): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeLong(value.revision)
            output.writeBounded(value.streamId)
            output.writeBounded(value.deviceAlias)
            output.writeBounded(value.firmwareGeneration)
            output.writeLong(value.consentGeneration)
            output.writeBounded(value.bootSessionId)
            output.writeInt(value.state.ordinal)
            output.writeLong(value.observedAtEpochMillis)
            output.writeLong(value.elapsedRealtimeMillis)
            output.writeLong(value.lastCommittedSequence)
            output.writeBoolean(value.lastMeasurementEpochMillis != null)
            value.lastMeasurementEpochMillis?.let(output::writeLong)
            output.writeBounded(value.provenanceChainSha256)
            output.writeBoolean(value.latestGap != null)
            value.latestGap?.let { gap ->
                output.writeBounded(gap.gapId)
                output.writeLong(gap.detectedAtEpochMillis)
                output.writeBoolean(gap.endedAtEpochMillis != null)
                gap.endedAtEpochMillis?.let(output::writeLong)
                output.writeLong(gap.afterSequence)
                val reasons = gap.reasons.sortedBy(CollectionInterruptionReason::ordinal)
                output.writeInt(reasons.size)
                reasons.forEach { output.writeInt(it.ordinal) }
                output.writeInt(gap.interpretation.ordinal)
            }
            output.writeBoolean(value.pendingResumePermitId != null)
            value.pendingResumePermitId?.let(output::writeBounded)
            output.writeBounded(value.previousSnapshotSha256)
        }
        buffer.toByteArray()
    }

    fun decode(bytes: ByteArray): WatchContinuitySnapshot {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == MAGIC)
        require(input.readInt() == VERSION)
        val revision = input.readLong()
        val streamId = input.readBounded()
        val deviceAlias = input.readBounded()
        val firmware = input.readBounded()
        val consent = input.readLong()
        val boot = input.readBounded()
        val state = ContinuityState.entries.getOrNull(input.readInt()) ?: error("Unknown state")
        val observed = input.readLong()
        val elapsed = input.readLong()
        val sequence = input.readLong()
        val measurement = if (input.readBoolean()) input.readLong() else null
        val provenance = input.readBounded()
        val gap = if (input.readBoolean()) {
            val gapId = input.readBounded()
            val detected = input.readLong()
            val ended = if (input.readBoolean()) input.readLong() else null
            val after = input.readLong()
            val count = input.readInt().also { require(it in 1..CollectionInterruptionReason.entries.size) }
            val reasons = buildSet {
                repeat(count) {
                    add(CollectionInterruptionReason.entries.getOrNull(input.readInt()) ?: error("Unknown reason"))
                }
            }.also { require(it.size == count) }
            val interpretation = GapInterpretation.entries.getOrNull(input.readInt())
                ?: error("Unknown interpretation")
            CollectionGap(gapId, detected, ended, after, reasons, interpretation)
        } else null
        val permitId = if (input.readBoolean()) input.readBounded() else null
        val previous = input.readBounded()
        require(input.available() == 0)
        return WatchContinuitySnapshot(
            revision,
            streamId,
            deviceAlias,
            firmware,
            consent,
            boot,
            state,
            observed,
            elapsed,
            sequence,
            measurement,
            provenance,
            gap,
            permitId,
            previous,
        )
    }
}

private fun DataOutputStream.writeBounded(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size in 1..512)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBounded(): String {
    val size = readInt().also { require(it in 1..512) }
    val bytes = ByteArray(size)
    readFully(bytes)
    return bytes.toString(Charsets.UTF_8)
}

private fun deterministicId(prefix: String, vararg values: String): String =
    "$prefix-${sha256Hex(values.joinToString("|").toByteArray(Charsets.UTF_8)).take(40)}"

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun absDifference(left: Long, right: Long): Long = try {
    Math.subtractExact(left, right).let { if (it == Long.MIN_VALUE) Long.MAX_VALUE else abs(it) }
} catch (_: ArithmeticException) {
    Long.MAX_VALUE
}

internal const val ZERO_SHA_256 = "0000000000000000000000000000000000000000000000000000000000000000"
internal val SHA_256 = Regex("[a-f0-9]{64}")
internal val SAFE_ID = Regex("[A-Za-z0-9._:-]{1,96}")
internal val SAFE_PROVENANCE_ID = Regex("[A-Za-z0-9._:@/-]{1,160}")
