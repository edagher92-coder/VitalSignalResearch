package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.HealthForecast
import au.com.elied.vitalsignal.model.ForecastEndpointDefinition
import au.com.elied.vitalsignal.model.ForecastFeatureSchemaDefinition
import au.com.elied.vitalsignal.model.ForecastWindowSemantics
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ForecastModelState { LEARNING, READY, ABSTAINED }

data class ForecastFeatureValue(
    val featureId: String,
    val featureVersion: String,
    val standardizedValue: Double,
    val sourceWindowStartEpochMillis: Long,
    val sourceWindowEndEpochMillis: Long,
    val provenanceIds: List<String>,
) {
    init {
        require(featureId.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(featureVersion.matches(Regex("[A-Za-z0-9._-]{1,64}")))
        require(standardizedValue.isFinite())
        require(sourceWindowStartEpochMillis >= 0L)
        require(sourceWindowEndEpochMillis >= sourceWindowStartEpochMillis)
        require(provenanceIds.isNotEmpty() && provenanceIds.size <= 256)
        require(provenanceIds.all { it.matches(Regex("[A-Za-z0-9._:-]{1,160}")) })
        require(provenanceIds.distinct().size == provenanceIds.size)
    }
}

data class ForecastFeatureSnapshot(
    val id: String,
    val cutoffEpochMillis: Long,
    val featureSchema: ForecastFeatureSchemaDefinition,
    val featureValues: Map<String, ForecastFeatureValue>,
    val quality: Double,
) {
    init {
        require(id.isNotBlank())
        require(featureValues.isNotEmpty())
        require(featureValues.keys == featureSchema.featureKeys) {
            "Feature snapshot keys must exactly match the frozen feature schema"
        }
        featureValues.forEach { (key, feature) ->
            require(feature.featureId == key) {
                "Feature map key must equal its typed feature ID"
            }
            require(feature.featureVersion == featureSchema.featureVersions.getValue(key)) {
                "Feature version must exactly match the frozen feature schema"
            }
            require(feature.sourceWindowEndEpochMillis <= cutoffEpochMillis) {
                "A forecast feature cannot use data after the committed cutoff"
            }
        }
        require(quality in 0.0..1.0)
    }
}

data class ForecastTrainingCase(
    val caseId: String,
    val endpoint: ForecastEndpointDefinition,
    val features: ForecastFeatureSnapshot,
    /** Binary endpoint only. Missing outcomes remain null. */
    val observedOutcome: Double?,
    /** When the outcome became knowable; required to enforce prospective cutoffs. */
    val resolvedAtEpochMillis: Long?,
    val outcomeObservationId: String?,
    val outcomeRecordSha256: String?,
    val verificationReceiptId: String?,
    val caseBindingSha256: String = trainingCaseBindingSha256(
        caseId = caseId,
        endpoint = endpoint,
        features = features,
        observedOutcome = observedOutcome,
        resolvedAtEpochMillis = resolvedAtEpochMillis,
        outcomeObservationId = outcomeObservationId,
        outcomeRecordSha256 = outcomeRecordSha256,
    ),
) {
    init {
        require(caseId.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(observedOutcome == null || observedOutcome in setOf(0.0, 1.0))
        require((observedOutcome == null) == (resolvedAtEpochMillis == null))
        require((observedOutcome == null) == (outcomeObservationId == null))
        require((observedOutcome == null) == (outcomeRecordSha256 == null))
        require((observedOutcome == null) == (verificationReceiptId == null))
        outcomeObservationId?.let {
            require(it.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        }
        outcomeRecordSha256?.let {
            require(it.matches(Regex("[a-f0-9]{64}")))
        }
        verificationReceiptId?.let {
            require(it.matches(Regex("[A-Za-z0-9._:-]{1,160}")))
        }
        require(caseBindingSha256 == trainingCaseBindingSha256(
            caseId,
            endpoint,
            features,
            observedOutcome,
            resolvedAtEpochMillis,
            outcomeObservationId,
            outcomeRecordSha256,
        )) { "Training case binding SHA-256 does not match its immutable content" }
        if (resolvedAtEpochMillis != null) {
            require(resolvedAtEpochMillis >= endpoint.targetEnd(features.cutoffEpochMillis)) {
                "Training outcome cannot resolve before its frozen endpoint window ends"
            }
        }
    }
}

fun interface ForecastTrainingCaseReceiptVerifier {
    /** Production implementations must authenticate a receipt bound to caseBindingSha256. */
    fun isAuthentic(trainingCase: ForecastTrainingCase): Boolean
}

data class ForecastEstimate(
    val state: ForecastModelState,
    val forecast: HealthForecast?,
    val validCaseCount: Int,
    val effectiveCaseWeight: Double,
    val reason: String,
)

/**
 * Transparent N-of-1 baseline forecast. Similar prior states are weighted,
 * regularized with a Beta prior, and uncertainty grows as quality/case support
 * falls. It is the control model; future challengers must beat it prospectively.
 */
class PersonalForecastEngine(
    private val minimumReadyCases: Int = 30,
    private val trainingCaseReceiptVerifier: ForecastTrainingCaseReceiptVerifier =
        ForecastTrainingCaseReceiptVerifier { false },
) {
    init {
        require(minimumReadyCases >= 30)
    }

    fun forecast(
        history: List<ForecastTrainingCase>,
        target: ForecastFeatureSnapshot,
        createdAtEpochMillis: Long,
        endpoint: ForecastEndpointDefinition,
        modelVersion: String = "personal-bayes-v1",
    ): ForecastEstimate {
        require(target.cutoffEpochMillis <= createdAtEpochMillis)
        require(modelVersion.isNotBlank())
        require(createdAtEpochMillis - target.cutoffEpochMillis <= MAX_CREATION_LAG_MILLIS) {
            "Forecast creation is too far after its frozen feature cutoff"
        }

        val targetStartEpochMillis = endpoint.targetStart(target.cutoffEpochMillis)
        val targetEndEpochMillis = endpoint.targetEnd(target.cutoffEpochMillis)
        require(createdAtEpochMillis < targetStartEpochMillis) {
            "Forecast must be created before the frozen endpoint window starts"
        }

        if (target.quality < MIN_TARGET_QUALITY) {
            return ForecastEstimate(
                state = ForecastModelState.ABSTAINED,
                forecast = null,
                validCaseCount = 0,
                effectiveCaseWeight = 0.0,
                reason = "Target feature quality is below the forecast gate",
            )
        }

        val casesById = history.groupBy(ForecastTrainingCase::caseId)
        if (casesById.values.any { duplicates -> duplicates.distinct().size > 1 }) {
            return integrityAbstention("Conflicting training cases reuse the same case ID")
        }
        val identityDeduplicated = casesById.values.map(List<ForecastTrainingCase>::first)
        val resolvedCases = identityDeduplicated.filter { it.observedOutcome != null }
        if (resolvedCases.groupBy { it.outcomeObservationId }.values.any { it.size > 1 }) {
            return integrityAbstention("A resolved outcome observation ID was replayed")
        }
        if (resolvedCases.groupBy { it.outcomeRecordSha256 }.values.any { it.size > 1 }) {
            return integrityAbstention("A resolved outcome record digest was replayed")
        }
        if (resolvedCases.any { !trainingCaseReceiptVerifier.isAuthentic(it) }) {
            return integrityAbstention("Training outcome verification receipt is not authentic")
        }
        val casesByTargetWindow = identityDeduplicated.groupBy { case ->
            listOf(
                case.endpoint.id,
                case.endpoint.version,
                case.endpoint.definitionSha256,
                case.endpoint.targetStart(case.features.cutoffEpochMillis).toString(),
                case.endpoint.targetEnd(case.features.cutoffEpochMillis).toString(),
            ).joinToString("|")
        }
        if (casesByTargetWindow.values.any { cases -> cases.size > 1 }) {
            return integrityAbstention(
                "Multiple training case IDs claim the same endpoint target window",
            )
        }

        val eligible = identityDeduplicated.filter { case ->
            case.observedOutcome != null &&
                case.resolvedAtEpochMillis != null &&
                case.resolvedAtEpochMillis <= target.cutoffEpochMillis &&
                case.features.cutoffEpochMillis < target.cutoffEpochMillis &&
                case.features.quality >= MIN_HISTORY_QUALITY &&
                case.endpoint == endpoint &&
                case.features.featureSchema == target.featureSchema &&
                case.features.featureValues.keys == target.featureValues.keys
        }

        var positiveWeight = 0.0
        var totalWeight = 0.0
        eligible.forEach { case ->
            val exactKeys = target.featureValues.keys.sorted()
            val meanSquaredDistance = exactKeys
                .map { key ->
                    val delta = case.features.featureValues.getValue(key).standardizedValue -
                        target.featureValues.getValue(key).standardizedValue
                    delta * delta
                }
                .average()
            val similarity = exp(-0.5 * meanSquaredDistance)
            val weight = similarity * case.features.quality * target.quality
            totalWeight += weight
            positiveWeight += weight * case.observedOutcome!!
        }

        val posteriorAlpha = PRIOR_ALPHA + positiveWeight
        val posteriorBeta = PRIOR_BETA + totalWeight - positiveWeight
        val probability = posteriorAlpha / (posteriorAlpha + posteriorBeta)
        val posteriorVariance =
            (posteriorAlpha * posteriorBeta) /
                ((posteriorAlpha + posteriorBeta).let { it * it * (it + 1.0) })
        val qualityPenalty = (1.0 - target.quality) * 0.20
        val margin = Z_80 * sqrt(max(0.0, posteriorVariance)) + qualityPenalty
        val lower = max(0.0, probability - margin)
        val upper = min(1.0, probability + margin)
        val intervalWidth = upper - lower
        val confidence = (
            min(1.0, totalWeight / minimumReadyCases.toDouble()) *
                target.quality *
                (1.0 - intervalWidth)
            ).coerceIn(0.0, 0.85)
        val state = if (eligible.size >= minimumReadyCases && totalWeight >= MIN_READY_WEIGHT) {
            ForecastModelState.READY
        } else {
            ForecastModelState.LEARNING
        }

        val snapshotDigest = snapshotHash(target)
        val forecast = HealthForecast(
            id = forecastId(
                createdAtEpochMillis = createdAtEpochMillis,
                targetStartEpochMillis = targetStartEpochMillis,
                targetEndEpochMillis = targetEndEpochMillis,
                endpoint = endpoint,
                featureSchema = target.featureSchema,
                featureKeys = target.featureValues.keys,
                modelVersion = modelVersion,
                snapshotDigest = snapshotDigest,
            ),
            createdAtEpochMillis = createdAtEpochMillis,
            endpoint = endpoint,
            probability = probability,
            lowerBound = lower,
            upperBound = upper,
            confidence = confidence,
            modelVersion = modelVersion,
            featureSnapshotIds = listOf(target.id),
            featureSchema = target.featureSchema,
            cutoffEpochMillis = target.cutoffEpochMillis,
            targetStartEpochMillis = targetStartEpochMillis,
            targetEndEpochMillis = targetEndEpochMillis,
            policyVersion = "prospective-forecast-v2",
            intervalCoverage = 0.80,
            featureSnapshotHash = snapshotDigest,
        )

        return ForecastEstimate(
            state = state,
            forecast = forecast,
            validCaseCount = eligible.size,
            effectiveCaseWeight = totalWeight,
            reason = if (state == ForecastModelState.READY) {
                "Minimum prospective case support met"
            } else {
                "Learning: ${eligible.size} of $minimumReadyCases resolved cases"
            },
        )
    }

    private fun integrityAbstention(reason: String) = ForecastEstimate(
        state = ForecastModelState.ABSTAINED,
        forecast = null,
        validCaseCount = 0,
        effectiveCaseWeight = 0.0,
        reason = reason,
    )

    private fun snapshotHash(snapshot: ForecastFeatureSnapshot): String {
        val canonical = buildString {
            append(snapshot.id)
            append('|')
            append(snapshot.cutoffEpochMillis)
            append('|')
            append(snapshot.featureSchema.id)
            append('|')
            append(snapshot.featureSchema.version)
            append('|')
            append(snapshot.featureSchema.definitionSha256)
            append('|')
            append(snapshot.quality)
            append('|')
            snapshot.featureValues.toSortedMap().forEach { (key, feature) ->
                append(key)
                append('=')
                append(feature.featureVersion)
                append('@')
                append(feature.standardizedValue)
                append('@')
                append(feature.sourceWindowStartEpochMillis)
                append('-')
                append(feature.sourceWindowEndEpochMillis)
                append('@')
                feature.provenanceIds.sorted().forEach { provenanceId ->
                    append(provenanceId.length)
                    append(':')
                    append(provenanceId)
                }
                append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun forecastId(
        createdAtEpochMillis: Long,
        targetStartEpochMillis: Long,
        targetEndEpochMillis: Long,
        endpoint: ForecastEndpointDefinition,
        featureSchema: ForecastFeatureSchemaDefinition,
        featureKeys: Set<String>,
        modelVersion: String,
        snapshotDigest: String,
    ): String {
        val canonical = listOf(
            createdAtEpochMillis.toString(),
            targetStartEpochMillis.toString(),
            targetEndEpochMillis.toString(),
            endpoint.id,
            endpoint.version,
            endpoint.definitionSha256,
            endpoint.windowSemantics.name,
            endpoint.targetStartOffsetMillis.toString(),
            endpoint.targetEndOffsetMillis.toString(),
            featureSchema.id,
            featureSchema.version,
            featureSchema.definitionSha256,
            featureKeys.sorted().joinToString(","),
            modelVersion,
            snapshotDigest,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "forecast-${digest.take(32)}"
    }

    companion object {
        private const val HOUR_MILLIS = 60L * 60L * 1_000L
        private const val MAX_CREATION_LAG_MILLIS = 15L * 60L * 1_000L

        /**
         * Simulator control endpoint: one check-in between +72h inclusive and
         * +73h exclusive, anchored to the committed feature cutoff.
         */
        val SIMULATOR_72_HOUR_POINT_ENDPOINT: ForecastEndpointDefinition =
            ForecastEndpointDefinition.freeze(
                id = "energy-function-72h-point",
                version = "1.0.0",
                displayLabel = "72-hour point assessment (+72h to +73h)",
                positiveClassDefinition =
                    "The preregistered target-window check-in meets the frozen binary " +
                        "lower-than-personal-usual energy/function rule; missing or ambiguous " +
                        "check-ins are not negative outcomes.",
                windowSemantics = ForecastWindowSemantics.POINT_ASSESSMENT,
                targetStartOffsetMillis = 72L * HOUR_MILLIS,
                targetEndOffsetMillis = 73L * HOUR_MILLIS,
            )

        val SIMULATOR_FEATURE_SCHEMA: ForecastFeatureSchemaDefinition =
            ForecastFeatureSchemaDefinition.freeze(
                id = "sim-recovery-features",
                version = "1.0.0",
                featureVersions = mapOf(
                    "cardio-autonomic" to "sim-v1",
                    "sleep" to "sim-v1",
                ),
                standardizationProtocol =
                    "Deterministic simulator-only baseline-adjusted fixture values; " +
                        "not fitted to or validated on personal health data.",
            )

        private const val MIN_TARGET_QUALITY = 0.80
        private const val MIN_HISTORY_QUALITY = 0.60
        private const val MIN_READY_WEIGHT = 15.0
        private const val PRIOR_ALPHA = 2.0
        private const val PRIOR_BETA = 2.0
        private const val Z_80 = 1.2816
    }
}

private fun trainingCaseBindingSha256(
    caseId: String,
    endpoint: ForecastEndpointDefinition,
    features: ForecastFeatureSnapshot,
    observedOutcome: Double?,
    resolvedAtEpochMillis: Long?,
    outcomeObservationId: String?,
    outcomeRecordSha256: String?,
): String {
    val fields = listOf(
        caseId,
        endpoint.id,
        endpoint.version,
        endpoint.definitionSha256,
        endpoint.targetStart(features.cutoffEpochMillis).toString(),
        endpoint.targetEnd(features.cutoffEpochMillis).toString(),
        features.id,
        features.cutoffEpochMillis.toString(),
        features.featureSchema.id,
        features.featureSchema.version,
        features.featureSchema.definitionSha256,
        observedOutcome?.toString() ?: "missing",
        resolvedAtEpochMillis?.toString() ?: "missing",
        outcomeObservationId ?: "missing",
        outcomeRecordSha256 ?: "missing",
    )
    val canonical = buildString {
        fields.forEach { value ->
            append(value.toByteArray(Charsets.UTF_8).size)
            append(':')
            append(value)
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
