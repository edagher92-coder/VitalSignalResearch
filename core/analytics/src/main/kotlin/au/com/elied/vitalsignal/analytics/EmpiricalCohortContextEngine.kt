package au.com.elied.vitalsignal.analytics

data class EmpiricalQuantiles(
    val p10: Double,
    val p25: Double,
    val p50: Double,
    val p75: Double,
    val p90: Double,
) {
    init {
        require(listOf(p10, p25, p50, p75, p90).all { it.isFinite() })
        require(p10 <= p25 && p25 <= p50 && p50 <= p75 && p75 <= p90)
    }
}

/** A curated descriptive cohort, never a diagnostic or safety threshold. */
data class EmpiricalCohortReference(
    val referenceId: String,
    val featureId: String,
    val unit: String,
    val deviceGeneration: String,
    val protocolVersion: String,
    val minimumAgeYears: Int,
    val maximumAgeYears: Int,
    /** Explicit study stratum, or ALL when the source pooled participants. */
    val sexStratum: String,
    val geographyAndSeason: String,
    val sampleSize: Int,
    val quantiles: EmpiricalQuantiles,
    val sourceId: String,
    val sourceContentSha256: String,
    val validationReceiptId: String,
    val verifiedAtEpochMillis: Long,
) {
    init {
        require(referenceId.isNotBlank())
        require(featureId.isNotBlank())
        require(unit.isNotBlank())
        require(deviceGeneration.isNotBlank())
        require(protocolVersion.isNotBlank())
        require(minimumAgeYears in 0..130)
        require(maximumAgeYears in minimumAgeYears..130)
        require(sexStratum.isNotBlank())
        require(geographyAndSeason.isNotBlank())
        require(sampleSize > 0)
        require(sourceId.isNotBlank())
        require(sourceContentSha256.matches(Regex("[a-f0-9]{64}")))
        require(validationReceiptId.isNotBlank())
        require(verifiedAtEpochMillis > 0L)
    }
}

data class EmpiricalContextRequest(
    val featureId: String,
    val observedValue: Double,
    val unit: String,
    val deviceGeneration: String,
    val protocolVersion: String,
    val ageYears: Int,
    val sexStratum: String,
    val evaluatedAtEpochMillis: Long,
) {
    init {
        require(featureId.isNotBlank())
        require(observedValue.isFinite())
        require(unit.isNotBlank())
        require(deviceGeneration.isNotBlank())
        require(protocolVersion.isNotBlank())
        require(ageYears in 0..130)
        require(sexStratum.isNotBlank())
        require(evaluatedAtEpochMillis > 0L)
    }
}

enum class EmpiricalPosition {
    BELOW_P10,
    P10_TO_P25,
    P25_TO_P50,
    P50_TO_P75,
    P75_TO_P90,
    ABOVE_P90,
}

enum class EmpiricalContextState { UNAVAILABLE, CONTEXT_ONLY }

data class EmpiricalContextResult(
    val state: EmpiricalContextState,
    val referenceId: String?,
    val position: EmpiricalPosition?,
    val sampleSize: Int?,
    val sourceId: String?,
    val validationReceiptId: String?,
    val reason: String,
    /** This field is fixed so downstream code cannot treat a cohort as an alert. */
    val advisoryOnly: Boolean = true,
) {
    init {
        require(advisoryOnly)
        require((state == EmpiricalContextState.CONTEXT_ONLY) == (position != null))
    }
}

/**
 * Verifies signed review authority over the exact cohort content/digest. A
 * syntactically valid receipt identifier or hash is never trusted by itself.
 */
fun interface EmpiricalCohortReferenceVerifier {
    fun verify(reference: EmpiricalCohortReference): Boolean
}

/**
 * Adds a provenance-rich empirical comparison beside the personal baseline.
 * It never changes a personal anomaly, safety state, prediction or action.
 */
class EmpiricalCohortContextEngine(
    private val referenceVerifier: EmpiricalCohortReferenceVerifier,
    private val minimumSampleSize: Int = 100,
    private val maximumReferenceAgeMillis: Long = 2L * 365 * 24 * 60 * 60 * 1_000L,
) {
    init {
        require(minimumSampleSize >= 30)
        require(maximumReferenceAgeMillis > 0L)
    }

    fun describe(
        request: EmpiricalContextRequest,
        references: List<EmpiricalCohortReference>,
    ): EmpiricalContextResult {
        val candidates = references.filter { reference ->
            verified(reference) &&
                reference.featureId == request.featureId &&
                reference.unit == request.unit &&
                reference.deviceGeneration == request.deviceGeneration &&
                reference.protocolVersion == request.protocolVersion &&
                request.ageYears in reference.minimumAgeYears..reference.maximumAgeYears &&
                (reference.sexStratum == request.sexStratum || reference.sexStratum == ALL_STRATA) &&
                reference.sampleSize >= minimumSampleSize &&
                reference.verifiedAtEpochMillis <= request.evaluatedAtEpochMillis &&
                request.evaluatedAtEpochMillis - reference.verifiedAtEpochMillis <= maximumReferenceAgeMillis
        }
        val selected = candidates.sortedWith(
            compareBy<EmpiricalCohortReference> {
                if (it.sexStratum == request.sexStratum) 0 else 1
            }.thenBy { it.maximumAgeYears - it.minimumAgeYears }
                .thenByDescending { it.sampleSize }
                .thenByDescending { it.verifiedAtEpochMillis },
        ).firstOrNull() ?: return unavailable(
            "No current validated cohort matches the feature, unit, protocol, device and demographic frame",
        )

        return EmpiricalContextResult(
            state = EmpiricalContextState.CONTEXT_ONLY,
            referenceId = selected.referenceId,
            position = position(request.observedValue, selected.quantiles),
            sampleSize = selected.sampleSize,
            sourceId = selected.sourceId,
            validationReceiptId = selected.validationReceiptId,
            reason = "Descriptive cohort context only; the matched personal baseline remains authoritative",
        )
    }

    private fun position(value: Double, quantiles: EmpiricalQuantiles): EmpiricalPosition = when {
        value < quantiles.p10 -> EmpiricalPosition.BELOW_P10
        value < quantiles.p25 -> EmpiricalPosition.P10_TO_P25
        value < quantiles.p50 -> EmpiricalPosition.P25_TO_P50
        value < quantiles.p75 -> EmpiricalPosition.P50_TO_P75
        value <= quantiles.p90 -> EmpiricalPosition.P75_TO_P90
        else -> EmpiricalPosition.ABOVE_P90
    }

    private fun unavailable(reason: String) = EmpiricalContextResult(
        state = EmpiricalContextState.UNAVAILABLE,
        referenceId = null,
        position = null,
        sampleSize = null,
        sourceId = null,
        validationReceiptId = null,
        reason = reason,
    )

    private fun verified(reference: EmpiricalCohortReference): Boolean = try {
        referenceVerifier.verify(reference)
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val ALL_STRATA = "ALL"
    }
}
