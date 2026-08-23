package au.com.elied.vitalsignal.wear.samsung

import au.com.elied.vitalsignal.governance.PilotGateDecision
import au.com.elied.vitalsignal.governance.PilotGateReason
import au.com.elied.vitalsignal.governance.PilotCapability
import java.security.MessageDigest

/**
 * Open, app-owned vocabulary for Samsung Health Sensor SDK capabilities.
 *
 * No Samsung SDK class is referenced here. A separately licensed adapter must
 * translate the SDK's runtime capability list into these values. Static entries
 * describe the public Samsung specification; they never prove that a particular
 * watch, firmware, region, permission state, or SDK build supports a tracker.
 */
enum class SamsungTrackerId {
    ACCELEROMETER_CONTINUOUS,
    EDA_CONTINUOUS,
    HEART_RATE_CONTINUOUS,
    PPG_CONTINUOUS,
    SKIN_TEMPERATURE_CONTINUOUS,
    BIA_ON_DEMAND,
    ECG_ON_DEMAND,
    MF_BIA_ON_DEMAND,
    PPG_ON_DEMAND,
    SKIN_TEMPERATURE_ON_DEMAND,
    SPO2_ON_DEMAND,
    SWEAT_LOSS,
}

enum class SamsungTrackerMode {
    CONTINUOUS,
    ON_DEMAND,
    POST_EXERCISE,
}

enum class SamsungSignalForm {
    RAW,
    PROCESSED,
}

enum class SamsungPermissionFamily {
    ACTIVITY_RECOGNITION,
    HEART_RATE_OR_LEGACY_BODY_SENSORS,
    ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
}

data class SamsungSamplingSpecification(
    val nominalHertz: Int? = null,
    val dataPointsPerMeasurement: Int? = null,
    val eventPointCounts: Set<Int> = emptySet(),
    val frequenciesKilohertz: Set<Int> = emptySet(),
) {
    init {
        nominalHertz?.let { require(it > 0) }
        dataPointsPerMeasurement?.let { require(it > 0) }
        require(eventPointCounts.all { it > 0 })
        require(frequenciesKilohertz.all { it > 0 })
    }
}

data class SamsungTrackerRestriction(
    val foregroundOnly: Boolean = false,
    val maximumSessionSeconds: Int? = null,
    val onlyOneOnDemandAtATime: Boolean = false,
    val continuousValuesMayBeInvalidDuringCapture: Boolean = false,
    val minimumGalaxyWatchGeneration: Int? = null,
    val requiresCompletedRunningExercise: Boolean = false,
) {
    init {
        maximumSessionSeconds?.let { require(it > 0) }
        minimumGalaxyWatchGeneration?.let { require(it > 0) }
    }
}

data class SamsungTrackerSpecification(
    val id: SamsungTrackerId,
    val mode: SamsungTrackerMode,
    val signalForm: SamsungSignalForm,
    val outputs: Set<String>,
    val sampling: SamsungSamplingSpecification,
    val permissionFamily: SamsungPermissionFamily,
    val restriction: SamsungTrackerRestriction,
) {
    init {
        require(outputs.isNotEmpty())
        require(outputs.none(String::isBlank))
        if (mode == SamsungTrackerMode.ON_DEMAND) {
            require(restriction.foregroundOnly)
            require(restriction.maximumSessionSeconds == 30)
            require(restriction.onlyOneOnDemandAtATime)
            require(restriction.continuousValuesMayBeInvalidDuringCapture)
        }
    }
}

/** Public SDK specification inventory, reviewed against Samsung documentation on 2026-08-23. */
object OfficialSamsungSensorCatalog {
    const val evidenceRevision =
        "Samsung Health Sensor SDK data specifications and HealthTrackerType API, 2026-08-23"

    private val onDemandRestriction = SamsungTrackerRestriction(
        foregroundOnly = true,
        maximumSessionSeconds = 30,
        onlyOneOnDemandAtATime = true,
        continuousValuesMayBeInvalidDuringCapture = true,
    )

    val specifications: List<SamsungTrackerSpecification> = listOf(
        SamsungTrackerSpecification(
            id = SamsungTrackerId.ACCELEROMETER_CONTINUOUS,
            mode = SamsungTrackerMode.CONTINUOUS,
            signalForm = SamsungSignalForm.RAW,
            outputs = setOf("acceleration_x", "acceleration_y", "acceleration_z"),
            sampling = SamsungSamplingSpecification(nominalHertz = 25),
            permissionFamily = SamsungPermissionFamily.ACTIVITY_RECOGNITION,
            restriction = SamsungTrackerRestriction(),
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.EDA_CONTINUOUS,
            mode = SamsungTrackerMode.CONTINUOUS,
            signalForm = SamsungSignalForm.RAW,
            outputs = setOf("electrodermal_activity", "status"),
            sampling = SamsungSamplingSpecification(nominalHertz = 1),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = SamsungTrackerRestriction(minimumGalaxyWatchGeneration = 8),
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.HEART_RATE_CONTINUOUS,
            mode = SamsungTrackerMode.CONTINUOUS,
            signalForm = SamsungSignalForm.PROCESSED,
            outputs = setOf("heart_rate", "heart_rate_status", "ibi_list", "ibi_status_list"),
            sampling = SamsungSamplingSpecification(nominalHertz = 1),
            permissionFamily = SamsungPermissionFamily.HEART_RATE_OR_LEGACY_BODY_SENSORS,
            restriction = SamsungTrackerRestriction(),
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.PPG_CONTINUOUS,
            mode = SamsungTrackerMode.CONTINUOUS,
            signalForm = SamsungSignalForm.RAW,
            outputs = setOf("ppg_green", "ppg_red", "ppg_infrared", "channel_status"),
            sampling = SamsungSamplingSpecification(nominalHertz = 25),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = SamsungTrackerRestriction(),
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.SKIN_TEMPERATURE_CONTINUOUS,
            mode = SamsungTrackerMode.CONTINUOUS,
            signalForm = SamsungSignalForm.PROCESSED,
            outputs = setOf("skin_temperature", "ambient_temperature", "status"),
            sampling = SamsungSamplingSpecification(),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = SamsungTrackerRestriction(minimumGalaxyWatchGeneration = 5),
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.BIA_ON_DEMAND,
            mode = SamsungTrackerMode.ON_DEMAND,
            signalForm = SamsungSignalForm.PROCESSED,
            outputs = setOf("body_composition", "impedance", "measurement_status"),
            sampling = SamsungSamplingSpecification(dataPointsPerMeasurement = 1),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = onDemandRestriction,
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.ECG_ON_DEMAND,
            mode = SamsungTrackerMode.ON_DEMAND,
            signalForm = SamsungSignalForm.RAW,
            outputs = setOf(
                "ecg_millivolts",
                "embedded_green_ppg",
                "lead_off",
                "sequence",
                "minimum_threshold_millivolts",
                "maximum_threshold_millivolts",
            ),
            sampling = SamsungSamplingSpecification(
                nominalHertz = 500,
                eventPointCounts = setOf(5, 10),
            ),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = onDemandRestriction,
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.MF_BIA_ON_DEMAND,
            mode = SamsungTrackerMode.ON_DEMAND,
            signalForm = SamsungSignalForm.PROCESSED,
            outputs = setOf("impedance_phase", "impedance_magnitude", "measurement_status"),
            sampling = SamsungSamplingSpecification(frequenciesKilohertz = setOf(5, 10, 50, 250)),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = onDemandRestriction.copy(minimumGalaxyWatchGeneration = 8),
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.PPG_ON_DEMAND,
            mode = SamsungTrackerMode.ON_DEMAND,
            signalForm = SamsungSignalForm.RAW,
            outputs = setOf("ppg_green", "ppg_red", "ppg_infrared", "channel_status"),
            sampling = SamsungSamplingSpecification(nominalHertz = 100),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = onDemandRestriction,
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.SKIN_TEMPERATURE_ON_DEMAND,
            mode = SamsungTrackerMode.ON_DEMAND,
            signalForm = SamsungSignalForm.PROCESSED,
            outputs = setOf("skin_temperature", "ambient_temperature", "status"),
            sampling = SamsungSamplingSpecification(dataPointsPerMeasurement = 1),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = onDemandRestriction.copy(minimumGalaxyWatchGeneration = 5),
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.SPO2_ON_DEMAND,
            mode = SamsungTrackerMode.ON_DEMAND,
            signalForm = SamsungSignalForm.PROCESSED,
            outputs = setOf("spo2_percent", "measurement_status"),
            sampling = SamsungSamplingSpecification(),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = onDemandRestriction,
        ),
        SamsungTrackerSpecification(
            id = SamsungTrackerId.SWEAT_LOSS,
            mode = SamsungTrackerMode.POST_EXERCISE,
            signalForm = SamsungSignalForm.PROCESSED,
            outputs = setOf("sweat_loss_millilitres", "exercise_state", "exercise_type"),
            sampling = SamsungSamplingSpecification(),
            permissionFamily = SamsungPermissionFamily.ADDITIONAL_HEALTH_DATA_OR_LEGACY_BODY_SENSORS,
            restriction = SamsungTrackerRestriction(requiresCompletedRunningExercise = true),
        ),
    )

    private val byId = specifications.associateBy(SamsungTrackerSpecification::id)

    init {
        check(byId.size == SamsungTrackerId.entries.size) {
            "Every app-owned tracker identifier must have exactly one public specification"
        }
    }

    fun specification(id: SamsungTrackerId): SamsungTrackerSpecification =
        requireNotNull(byId[id]) { "No specification for $id" }
}

enum class SamsungSdkBridgeState {
    INSTALLED,
    NOT_INSTALLED,
    INCOMPATIBLE,
}

enum class SamsungRuntimeSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class SamsungRuntimePermission {
    GRANTED,
    DENIED,
    NOT_REQUESTED,
}

data class SamsungTrackerRuntimeCapability(
    val trackerId: SamsungTrackerId,
    val support: SamsungRuntimeSupport,
    val permission: SamsungRuntimePermission,
    val detail: String? = null,
)

class SamsungSensorRuntimeInventory(
    val bridgeState: SamsungSdkBridgeState,
    val sdkVersion: String?,
    val watchModel: String,
    val firmwareVersion: String,
    val observedAtEpochMillis: Long,
    trackers: Map<SamsungTrackerId, SamsungTrackerRuntimeCapability>,
) {
    /** Snapshot prevents an SDK adapter from changing a capability result after inspection. */
    val trackers: Map<SamsungTrackerId, SamsungTrackerRuntimeCapability> =
        java.util.Map.copyOf(trackers)

    init {
        require(watchModel.isNotBlank())
        require(firmwareVersion.isNotBlank())
        require(observedAtEpochMillis > 0L)
        require(this.trackers.all { (id, capability) -> id == capability.trackerId })
    }

    fun capability(id: SamsungTrackerId): SamsungTrackerRuntimeCapability =
        trackers[id] ?: SamsungTrackerRuntimeCapability(
            trackerId = id,
            support = SamsungRuntimeSupport.UNKNOWN,
            permission = SamsungRuntimePermission.NOT_REQUESTED,
            detail = "The licensed adapter did not report this tracker",
        )

    fun copy(
        bridgeState: SamsungSdkBridgeState = this.bridgeState,
        sdkVersion: String? = this.sdkVersion,
        watchModel: String = this.watchModel,
        firmwareVersion: String = this.firmwareVersion,
        observedAtEpochMillis: Long = this.observedAtEpochMillis,
        trackers: Map<SamsungTrackerId, SamsungTrackerRuntimeCapability> = this.trackers,
    ) = SamsungSensorRuntimeInventory(
        bridgeState,
        sdkVersion,
        watchModel,
        firmwareVersion,
        observedAtEpochMillis,
        trackers,
    )

    override fun equals(other: Any?): Boolean = other is SamsungSensorRuntimeInventory &&
        bridgeState == other.bridgeState && sdkVersion == other.sdkVersion &&
        watchModel == other.watchModel && firmwareVersion == other.firmwareVersion &&
        observedAtEpochMillis == other.observedAtEpochMillis && trackers == other.trackers

    override fun hashCode(): Int = listOf(
        bridgeState,
        sdkVersion,
        watchModel,
        firmwareVersion,
        observedAtEpochMillis,
        trackers,
    ).hashCode()
}

interface SamsungSensorCapabilityProbe {
    suspend fun inspectRuntimeCapabilities(): SamsungSensorRuntimeInventory
}

class SamsungSensorConsent(
    val consentId: String,
    val generation: Long,
    val participantPseudonym: String,
    val protocolId: String,
    allowedTrackers: Set<SamsungTrackerId>,
    val validFromEpochMillis: Long,
    val validUntilEpochMillis: Long,
    val revokedAtEpochMillis: Long? = null,
) {
    /** Snapshot prevents caller mutation from escalating tracker access after consent creation. */
    val allowedTrackers: Set<SamsungTrackerId> = java.util.Set.copyOf(allowedTrackers)

    init {
        require(consentId.isNotBlank())
        require(generation > 0L)
        require(participantPseudonym.isNotBlank())
        require(protocolId.isNotBlank())
        require(this.allowedTrackers.isNotEmpty())
        require(validFromEpochMillis < validUntilEpochMillis)
        revokedAtEpochMillis?.let { require(it >= validFromEpochMillis) }
    }

    fun permits(trackerId: SamsungTrackerId, atEpochMillis: Long): Boolean =
        trackerId in allowedTrackers &&
            atEpochMillis in validFromEpochMillis until validUntilEpochMillis &&
            (revokedAtEpochMillis == null || atEpochMillis < revokedAtEpochMillis)

    fun copy(
        consentId: String = this.consentId,
        generation: Long = this.generation,
        participantPseudonym: String = this.participantPseudonym,
        protocolId: String = this.protocolId,
        allowedTrackers: Set<SamsungTrackerId> = this.allowedTrackers,
        validFromEpochMillis: Long = this.validFromEpochMillis,
        validUntilEpochMillis: Long = this.validUntilEpochMillis,
        revokedAtEpochMillis: Long? = this.revokedAtEpochMillis,
    ) = SamsungSensorConsent(
        consentId,
        generation,
        participantPseudonym,
        protocolId,
        allowedTrackers,
        validFromEpochMillis,
        validUntilEpochMillis,
        revokedAtEpochMillis,
    )

    override fun equals(other: Any?): Boolean = other is SamsungSensorConsent &&
        consentId == other.consentId && generation == other.generation &&
        participantPseudonym == other.participantPseudonym && protocolId == other.protocolId &&
        allowedTrackers == other.allowedTrackers &&
        validFromEpochMillis == other.validFromEpochMillis &&
        validUntilEpochMillis == other.validUntilEpochMillis &&
        revokedAtEpochMillis == other.revokedAtEpochMillis

    override fun hashCode(): Int = listOf(
        consentId,
        generation,
        participantPseudonym,
        protocolId,
        allowedTrackers,
        validFromEpochMillis,
        validUntilEpochMillis,
        revokedAtEpochMillis,
    ).hashCode()
}

data class SamsungSensorStartRequest(
    val trackerId: SamsungTrackerId,
    val plannedDurationSeconds: Int,
) {
    init {
        require(plannedDurationSeconds > 0)
    }
}

class SamsungSensorPilotGateContext(
    val pilotFeatureEnabled: Boolean,
    /** Result of core:governance for WATCH_RESEARCH_CAPTURE in this exact environment. */
    val governanceDecision: PilotGateDecision,
    val inventory: SamsungSensorRuntimeInventory,
    val consent: SamsungSensorConsent?,
    val nowEpochMillis: Long,
    val appInForeground: Boolean,
    activeOnDemandTrackers: Set<SamsungTrackerId> = emptySet(),
    activeContinuousTrackers: Set<SamsungTrackerId> = emptySet(),
    val completedRunningExerciseAvailable: Boolean = false,
) {
    val activeOnDemandTrackers: Set<SamsungTrackerId> =
        java.util.Set.copyOf(activeOnDemandTrackers)
    val activeContinuousTrackers: Set<SamsungTrackerId> =
        java.util.Set.copyOf(activeContinuousTrackers)

    fun copy(
        pilotFeatureEnabled: Boolean = this.pilotFeatureEnabled,
        governanceDecision: PilotGateDecision = this.governanceDecision,
        inventory: SamsungSensorRuntimeInventory = this.inventory,
        consent: SamsungSensorConsent? = this.consent,
        nowEpochMillis: Long = this.nowEpochMillis,
        appInForeground: Boolean = this.appInForeground,
        activeOnDemandTrackers: Set<SamsungTrackerId> = this.activeOnDemandTrackers,
        activeContinuousTrackers: Set<SamsungTrackerId> = this.activeContinuousTrackers,
        completedRunningExerciseAvailable: Boolean = this.completedRunningExerciseAvailable,
    ) = SamsungSensorPilotGateContext(
        pilotFeatureEnabled,
        governanceDecision,
        inventory,
        consent,
        nowEpochMillis,
        appInForeground,
        activeOnDemandTrackers,
        activeContinuousTrackers,
        completedRunningExerciseAvailable,
    )
}

enum class SamsungSensorBlockReason {
    PILOT_DISABLED,
    CENTRAL_GOVERNANCE_DENIED,
    CENTRAL_CAPABILITY_MISMATCH,
    CENTRAL_SUBJECT_MISMATCH,
    CENTRAL_EVIDENCE_EXPIRED,
    CONSENT_GENERATION_MISMATCH,
    VALIDATION_RECEIPT_MISSING,
    CONSENT_MISSING_OR_OUT_OF_SCOPE,
    SDK_BRIDGE_NOT_READY,
    TRACKER_NOT_SUPPORTED,
    PERMISSION_NOT_GRANTED,
    FOREGROUND_REQUIRED,
    DURATION_EXCEEDS_LIMIT,
    ON_DEMAND_TRACKER_ALREADY_ACTIVE,
    CONTINUOUS_TRACKERS_MUST_BE_PAUSED,
    COMPLETED_RUNNING_EXERCISE_REQUIRED,
}

class SamsungSensorCapturePermit private constructor(
    val trackerId: SamsungTrackerId,
    val participantPseudonym: String,
    val consentId: String,
    val consentGeneration: Long,
    val protocolId: String,
    val validationReceiptId: String,
    val governanceConsentGrantSha256: String,
    val governanceValidationReceiptSha256: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(participantPseudonym.isNotBlank())
        require(consentId.isNotBlank())
        require(consentGeneration > 0L)
        require(protocolId.isNotBlank())
        require(validationReceiptId.isNotBlank())
        require(governanceConsentGrantSha256.matches(Regex("[a-f0-9]{64}")))
        require(governanceValidationReceiptSha256.matches(Regex("[a-f0-9]{64}")))
        require(expiresAtEpochMillis > issuedAtEpochMillis)
    }

    fun isValidAt(epochMillis: Long): Boolean =
        epochMillis in issuedAtEpochMillis until expiresAtEpochMillis

    companion object {
        /** The only construction path; callers cannot bypass these gate checks. */
        fun evaluateGoverned(
            request: SamsungSensorStartRequest,
            context: SamsungSensorPilotGateContext,
        ): SamsungSensorGateDecision {
            val specification = OfficialSamsungSensorCatalog.specification(request.trackerId)
            val runtime = context.inventory.capability(request.trackerId)
            val decision = context.governanceDecision
            val consent = context.consent
            val reasons = buildSet {
                if (!context.pilotFeatureEnabled) add(SamsungSensorBlockReason.PILOT_DISABLED)
                if (!decision.allowed || decision.reason != PilotGateReason.ALLOWED) {
                    add(SamsungSensorBlockReason.CENTRAL_GOVERNANCE_DENIED)
                }
                if (decision.capability != PilotCapability.WATCH_RESEARCH_CAPTURE) {
                    add(SamsungSensorBlockReason.CENTRAL_CAPABILITY_MISMATCH)
                }
                if (consent != null && decision.subjectPseudonym != consent.participantPseudonym) {
                    add(SamsungSensorBlockReason.CENTRAL_SUBJECT_MISMATCH)
                }
                if (consent != null && decision.consentGeneration != consent.generation) {
                    add(SamsungSensorBlockReason.CONSENT_GENERATION_MISMATCH)
                }
                if (decision.validationReceiptId.isNullOrBlank() ||
                    decision.consentGrantSha256.isNullOrBlank() ||
                    decision.validationReceiptSha256.isNullOrBlank()
                ) {
                    add(SamsungSensorBlockReason.VALIDATION_RECEIPT_MISSING)
                }
                if (decision.allowed &&
                    decision.authorizationExpiresAtEpochMillis?.let {
                        context.nowEpochMillis !in decision.evaluatedAtEpochMillis until it
                    } != false
                ) {
                    add(SamsungSensorBlockReason.CENTRAL_EVIDENCE_EXPIRED)
                }
                if (context.inventory.bridgeState != SamsungSdkBridgeState.INSTALLED) {
                    add(SamsungSensorBlockReason.SDK_BRIDGE_NOT_READY)
                }
                if (runtime.support != SamsungRuntimeSupport.SUPPORTED) {
                    add(SamsungSensorBlockReason.TRACKER_NOT_SUPPORTED)
                }
                if (runtime.permission != SamsungRuntimePermission.GRANTED) {
                    add(SamsungSensorBlockReason.PERMISSION_NOT_GRANTED)
                }
                if (consent?.permits(request.trackerId, context.nowEpochMillis) != true) {
                    add(SamsungSensorBlockReason.CONSENT_MISSING_OR_OUT_OF_SCOPE)
                }
                if (consent != null && !decision.authorizes(
                        capability = PilotCapability.WATCH_RESEARCH_CAPTURE,
                        subjectPseudonym = consent.participantPseudonym,
                        consentGeneration = consent.generation,
                        atEpochMillis = context.nowEpochMillis,
                    )
                ) {
                    add(SamsungSensorBlockReason.CENTRAL_GOVERNANCE_DENIED)
                }
                if (specification.restriction.foregroundOnly && !context.appInForeground) {
                    add(SamsungSensorBlockReason.FOREGROUND_REQUIRED)
                }
                specification.restriction.maximumSessionSeconds?.let { maximum ->
                    if (request.plannedDurationSeconds > maximum) {
                        add(SamsungSensorBlockReason.DURATION_EXCEEDS_LIMIT)
                    }
                }
                if (specification.mode == SamsungTrackerMode.ON_DEMAND) {
                    if (context.activeOnDemandTrackers.isNotEmpty()) {
                        add(SamsungSensorBlockReason.ON_DEMAND_TRACKER_ALREADY_ACTIVE)
                    }
                    if (context.activeContinuousTrackers.isNotEmpty()) {
                        add(SamsungSensorBlockReason.CONTINUOUS_TRACKERS_MUST_BE_PAUSED)
                    }
                }
                if (specification.restriction.requiresCompletedRunningExercise &&
                    !context.completedRunningExerciseAvailable
                ) {
                    add(SamsungSensorBlockReason.COMPLETED_RUNNING_EXERCISE_REQUIRED)
                }
            }

            if (reasons.isNotEmpty()) return SamsungSensorGateDecision.Blocked(reasons)
            val exactConsent = requireNotNull(consent)
            return SamsungSensorGateDecision.Allowed(
                SamsungSensorCapturePermit(
                    trackerId = request.trackerId,
                    participantPseudonym = exactConsent.participantPseudonym,
                    consentId = exactConsent.consentId,
                    consentGeneration = exactConsent.generation,
                    protocolId = exactConsent.protocolId,
                    validationReceiptId = requireNotNull(decision.validationReceiptId),
                    governanceConsentGrantSha256 = requireNotNull(decision.consentGrantSha256),
                    governanceValidationReceiptSha256 = requireNotNull(
                        decision.validationReceiptSha256,
                    ),
                    issuedAtEpochMillis = context.nowEpochMillis,
                    expiresAtEpochMillis = minOf(
                        exactConsent.validUntilEpochMillis,
                        requireNotNull(decision.authorizationExpiresAtEpochMillis),
                        context.nowEpochMillis + request.plannedDurationSeconds * 1_000L,
                    ),
                ),
            )
        }
    }
}

sealed interface SamsungSensorGateDecision {
    data class Allowed(val permit: SamsungSensorCapturePermit) : SamsungSensorGateDecision

    class Blocked(reasons: Set<SamsungSensorBlockReason>) : SamsungSensorGateDecision {
        val reasons: Set<SamsungSensorBlockReason> = java.util.Set.copyOf(reasons)

        init {
            require(this.reasons.isNotEmpty())
        }
    }
}

/** Fail-closed gate that must issue a permit before a licensed adapter can start. */
object SamsungSensorPilotGate {
    fun evaluate(
        request: SamsungSensorStartRequest,
        context: SamsungSensorPilotGateContext,
    ): SamsungSensorGateDecision {
        return SamsungSensorCapturePermit.evaluateGoverned(request, context)
    }
}

/**
 * The licensed AAR adapter implements this surface. Requiring a permit makes an
 * accidental ungated real-sensor start impossible through this contract.
 */
interface GovernedSamsungSensorBoundary {
    suspend fun start(
        permit: SamsungSensorCapturePermit,
        onEvent: (SamsungSensorBridgeEvent) -> Unit,
    )

    suspend fun stop(trackerId: SamsungTrackerId)
}

class SamsungSensorBridgeEvent(
    val trackerId: SamsungTrackerId,
    val participantPseudonym: String,
    val consentGeneration: Long,
    val validationReceiptId: String,
    val sequence: Long,
    val sampleCount: Int,
    val sourceTimestampEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    payload: ByteArray,
) {
    private val immutablePayload = payload.copyOf()
    val payloadSizeBytes: Int get() = immutablePayload.size
    val payloadSha256: String = samsungPayloadSha256(immutablePayload)

    init {
        require(participantPseudonym.isNotBlank())
        require(consentGeneration > 0L)
        require(validationReceiptId.isNotBlank())
        require(sequence >= 0L)
        require(sampleCount in 1..maximumSamplesFor(trackerId))
        require(sourceTimestampEpochMillis > 0L)
        require(receivedAtEpochMillis >= sourceTimestampEpochMillis)
        require(receivedAtEpochMillis - sourceTimestampEpochMillis <= MAX_EVENT_LAG_MILLIS)
        require(immutablePayload.size in 1..MAX_EVENT_PAYLOAD_BYTES)
    }

    fun payloadCopy(): ByteArray = immutablePayload.copyOf()

    companion object {
        const val MAX_EVENT_PAYLOAD_BYTES = 64 * 1024
        const val MAX_EVENT_LAG_MILLIS = 5L * 60L * 1_000L

        fun maximumSamplesFor(trackerId: SamsungTrackerId): Int {
            val specification = OfficialSamsungSensorCatalog.specification(trackerId)
            val hertzBound = (specification.sampling.nominalHertz ?: 1) *
                (specification.restriction.maximumSessionSeconds ?: 60)
            val eventPointBound = specification.sampling.eventPointCounts.maxOrNull() ?: 1
            val measurementBound = specification.sampling.dataPointsPerMeasurement ?: 1
            return maxOf(hertzBound, eventPointBound, measurementBound).coerceAtMost(60_000)
        }
    }
}

private fun samsungPayloadSha256(payload: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }
