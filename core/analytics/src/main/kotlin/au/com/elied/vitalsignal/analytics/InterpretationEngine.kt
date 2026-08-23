package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.BaselineDeviation
import au.com.elied.vitalsignal.model.AcquisitionDependencyProfile
import au.com.elied.vitalsignal.model.AcquisitionOrigin
import au.com.elied.vitalsignal.model.EvidenceItem
import au.com.elied.vitalsignal.model.HealthInsight
import au.com.elied.vitalsignal.model.InsightSeverity
import au.com.elied.vitalsignal.model.InsightState
import au.com.elied.vitalsignal.model.MetricWindow
import au.com.elied.vitalsignal.model.PhysiologicalDomain
import au.com.elied.vitalsignal.model.SensorMetric
import au.com.elied.vitalsignal.model.SignalQuality
import au.com.elied.vitalsignal.model.conservativeAcquisitionProfile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** Independent physiological families used for corroboration and coverage gates. */
enum class IndependentEvidenceFamily {
    CARDIO_AUTONOMIC,
    RESPIRATORY_OXYGENATION,
    THERMAL_EXERTIONAL,
    SLEEP_RESTORATION,
    ACTIVITY_LOAD,
    BODY_COMPOSITION,
    CONTEXT,
}

/** Normalized direction after metric semantics (for example, lower HRV means positive strain). */
enum class NormalizedContributionDirection { POSITIVE, NEGATIVE }

enum class FamilyEvidenceDirection { POSITIVE, NEGATIVE, CONFLICTING, NONE }

data class IndependentFamilyAssessment(
    val family: IndependentEvidenceFamily,
    val direction: FamilyEvidenceDirection,
    /** Zero for a conflicting or directionless family, so it cannot corroborate a pattern. */
    val representativeContribution: Double,
    val evidenceCount: Int,
) {
    init {
        require(representativeContribution in -1.0..1.0)
        require(evidenceCount > 0)
        if (direction == FamilyEvidenceDirection.CONFLICTING || direction == FamilyEvidenceDirection.NONE) {
            require(representativeContribution == 0.0)
        }
    }
}

/** A connected component in the conservative acquisition-dependency graph. */
data class IndependentAcquisitionComponent(
    val families: Set<IndependentEvidenceFamily>,
    val origins: Set<AcquisitionOrigin>,
) {
    init {
        require(families.isNotEmpty())
        require(origins.isNotEmpty())
    }
}

enum class PersistenceEvidenceStatus { CURRENT_ONLY, VERIFIED_PRIOR_CHAIN, REJECTED }

/**
 * A prior interpretation episode offered as persistence evidence. The verifier
 * must authenticate the complete immutable value, including time, directions,
 * acquisition dependencies, quality, provenance and verificationArtifactId;
 * an ID alone is not authority.
 */
data class PersistenceEpisodeEvidence(
    val episodeId: String,
    val observedAtEpochMillis: Long,
    val familyDirections: Map<IndependentEvidenceFamily, NormalizedContributionDirection>,
    /** Exact dependency graph used by that prior assessment. */
    val acquisitionOriginsByFamily: Map<IndependentEvidenceFamily, Set<AcquisitionOrigin>>,
    val quality: SignalQuality,
    val provenanceIds: List<String>,
    val verificationArtifactId: String,
) {
    init {
        require(episodeId.isNotBlank())
        require(observedAtEpochMillis >= 0L)
        require(familyDirections.isNotEmpty())
        require(acquisitionOriginsByFamily.keys == familyDirections.keys)
        require(acquisitionOriginsByFamily.values.all(Set<AcquisitionOrigin>::isNotEmpty))
        require(provenanceIds.isNotEmpty() && provenanceIds.all(String::isNotBlank))
        require(provenanceIds.distinct().size == provenanceIds.size)
        require(verificationArtifactId.isNotBlank())
    }
}

fun interface PersistenceEvidenceVerifier {
    /** Fail closed unless the complete episode is bound to authentic, purpose-scoped evidence. */
    fun verify(evidence: PersistenceEpisodeEvidence): Boolean
}

object RejectAllPersistenceEvidenceVerifier : PersistenceEvidenceVerifier {
    override fun verify(evidence: PersistenceEpisodeEvidence): Boolean = false
}

data class PersistenceAssessment(
    /** Current window plus an accepted, strictly prior chain. Never supplied directly by a caller. */
    val qualifiedWindowCount: Int,
    val status: PersistenceEvidenceStatus,
    val acceptedEpisodeIds: List<String> = emptyList(),
    val acceptedProvenanceIds: List<String> = emptyList(),
    val rejectionReasonCodes: Set<String> = emptySet(),
) {
    init {
        require(qualifiedWindowCount >= 1)
        if (status != PersistenceEvidenceStatus.VERIFIED_PRIOR_CHAIN) {
            require(qualifiedWindowCount == 1)
            require(acceptedEpisodeIds.isEmpty())
            require(acceptedProvenanceIds.isEmpty())
        }
        if (status == PersistenceEvidenceStatus.REJECTED) require(rejectionReasonCodes.isNotEmpty())
    }
}

/**
 * Deterministic persistence gate. Any malformed, future, duplicated,
 * inconsistent or unverifiable episode invalidates the entire proposed chain,
 * leaving only the current window and therefore no persistence boost.
 */
class PersistenceEvidenceEvaluator(
    private val verifier: PersistenceEvidenceVerifier,
    private val maximumGapMillis: Long = 6L * 60L * 60L * 1_000L,
    private val maximumPriorEpisodes: Int = 8,
) {
    init {
        require(maximumGapMillis > 0L)
        require(maximumPriorEpisodes > 0)
    }

    fun evaluate(
        currentObservedAtEpochMillis: Long,
        currentFamilyDirections: Map<IndependentEvidenceFamily, NormalizedContributionDirection>,
        currentAcquisitionOriginsByFamily: Map<IndependentEvidenceFamily, Set<AcquisitionOrigin>>,
        currentProvenanceIds: List<String>,
        priorEpisodes: List<PersistenceEpisodeEvidence>,
    ): PersistenceAssessment {
        require(currentObservedAtEpochMillis >= 0L)
        if (priorEpisodes.isEmpty()) return PersistenceAssessment(1, PersistenceEvidenceStatus.CURRENT_ONLY)

        val reasons = linkedSetOf<String>()
        if (currentFamilyDirections.isEmpty()) reasons += "persistence-current-direction-missing"
        if (currentAcquisitionOriginsByFamily.keys != currentFamilyDirections.keys ||
            currentAcquisitionOriginsByFamily.values.any(Set<AcquisitionOrigin>::isEmpty)
        ) {
            reasons += "persistence-current-acquisition-graph-invalid"
        }
        if (currentProvenanceIds.isEmpty() || currentProvenanceIds.any(String::isBlank)) {
            reasons += "persistence-current-provenance-missing"
        }
        if (priorEpisodes.size > maximumPriorEpisodes) reasons += "persistence-chain-too-long"
        if (priorEpisodes.map { it.episodeId }.distinct().size != priorEpisodes.size) {
            reasons += "persistence-duplicate-episode"
        }
        if (priorEpisodes.map { it.observedAtEpochMillis }.distinct().size != priorEpisodes.size) {
            reasons += "persistence-duplicate-timestamp"
        }
        if (priorEpisodes.map { it.verificationArtifactId }.distinct().size != priorEpisodes.size) {
            reasons += "persistence-duplicate-verification-artifact"
        }
        if (priorEpisodes.zipWithNext().any { (earlier, later) ->
                earlier.observedAtEpochMillis >= later.observedAtEpochMillis
            }
        ) {
            reasons += "persistence-non-chronological"
        }
        if (priorEpisodes.any { it.observedAtEpochMillis >= currentObservedAtEpochMillis }) {
            reasons += "persistence-not-strictly-prior"
        }

        val chronologicalTimes = priorEpisodes.map { it.observedAtEpochMillis } + currentObservedAtEpochMillis
        if (chronologicalTimes.zipWithNext().any { (earlier, later) ->
                later <= earlier || later - earlier > maximumGapMillis
            }
        ) {
            reasons += "persistence-gap-out-of-bounds"
        }
        if (priorEpisodes.any { !it.quality.interpretationGrade }) {
            reasons += "persistence-quality-unqualified"
        }
        if (priorEpisodes.any { it.familyDirections != currentFamilyDirections }) {
            reasons += "persistence-direction-or-family-inconsistent"
        }
        if (priorEpisodes.any { it.acquisitionOriginsByFamily != currentAcquisitionOriginsByFamily }) {
            reasons += "persistence-acquisition-dependency-inconsistent"
        }

        val allProvenance = priorEpisodes.flatMap { it.provenanceIds } + currentProvenanceIds
        if (allProvenance.distinct().size != allProvenance.size) {
            reasons += "persistence-provenance-reused"
        }
        val unverifiable = priorEpisodes.any { episode ->
            try {
                !verifier.verify(episode)
            } catch (_: Exception) {
                true
            }
        }
        if (unverifiable) reasons += "persistence-unverifiable"

        if (reasons.isNotEmpty()) {
            return PersistenceAssessment(
                qualifiedWindowCount = 1,
                status = PersistenceEvidenceStatus.REJECTED,
                rejectionReasonCodes = reasons,
            )
        }

        return PersistenceAssessment(
            qualifiedWindowCount = 1 + priorEpisodes.size,
            status = PersistenceEvidenceStatus.VERIFIED_PRIOR_CHAIN,
            acceptedEpisodeIds = priorEpisodes.map { it.episodeId },
            acceptedProvenanceIds = priorEpisodes.flatMap { it.provenanceIds },
        )
    }
}

enum class InterpretationAssessmentStatus {
    NO_QUALIFIED_DEVIATION,
    CONFLICTING_EVIDENCE,
    ACQUISITION_DEPENDENCY_LIMITED,
    BELOW_DISPLAY_THRESHOLD,
    INTERPRETED,
}

/** Explicit fusion result. Conflict and withholding can no longer masquerade as TYPICAL. */
data class InterpretationAssessment(
    val status: InterpretationAssessmentStatus,
    val availableQualifiedFamilies: Set<IndependentEvidenceFamily>,
    val familyAssessments: List<IndependentFamilyAssessment>,
    val independentCoherentFamilyCount: Int,
    val independentCoherentAcquisitionGroupCount: Int,
    val acquisitionOriginsByFamily: Map<IndependentEvidenceFamily, Set<AcquisitionOrigin>>,
    val acquisitionComponents: List<IndependentAcquisitionComponent>,
    val conflictingFamilies: Set<IndependentEvidenceFamily>,
    val persistence: PersistenceAssessment,
    val evidence: List<EvidenceItem>,
    val insight: HealthInsight?,
) {
    init {
        require(independentCoherentFamilyCount >= 0)
        require(independentCoherentAcquisitionGroupCount >= 0)
        require(independentCoherentAcquisitionGroupCount <= independentCoherentFamilyCount)
        require(independentCoherentAcquisitionGroupCount == acquisitionComponents.size)
        require(acquisitionOriginsByFamily.values.all(Set<AcquisitionOrigin>::isNotEmpty))
        if (status == InterpretationAssessmentStatus.CONFLICTING_EVIDENCE) {
            require(conflictingFamilies.isNotEmpty())
            require(insight == null)
        }
        if (status == InterpretationAssessmentStatus.ACQUISITION_DEPENDENCY_LIMITED) {
            require(independentCoherentFamilyCount >= 2)
            require(independentCoherentAcquisitionGroupCount < 2)
            require(insight == null)
        }
        if (status == InterpretationAssessmentStatus.INTERPRETED) require(insight != null)
    }
}

private data class AcquisitionBoundEvidence(
    val evidence: EvidenceItem,
    val acquisitionProfile: AcquisitionDependencyProfile,
)

private data class AcquisitionGraphResult(
    val originsByFamily: Map<IndependentEvidenceFamily, Set<AcquisitionOrigin>>,
    val independentComponents: List<IndependentAcquisitionComponent>,
)

fun interface AcquisitionDependencyProfileVerifier {
    /**
     * Production verifiers must authenticate the complete window/profile
     * binding. Throwing or returning false excludes the window fail closed.
     */
    fun verify(window: MetricWindow): Boolean
}

/** Default authority: only the documented conservative metric mapping is accepted. */
object ConservativeAcquisitionDependencyProfileVerifier : AcquisitionDependencyProfileVerifier {
    override fun verify(window: MetricWindow): Boolean = window.acquisitionProfile ==
        conservativeAcquisitionProfile(window.metric, window.source, window.provenanceIds)
}

/**
 * Transparent MVP fusion model. It is intentionally conservative and is not a
 * diagnostic classifier. A later validated endpoint model can implement the
 * same interface without changing UI or audit contracts.
 */
class InterpretationEngine(
    private val persistenceEvaluator: PersistenceEvidenceEvaluator = PersistenceEvidenceEvaluator(
        RejectAllPersistenceEvidenceVerifier,
    ),
    private val acquisitionProfileVerifier: AcquisitionDependencyProfileVerifier =
        ConservativeAcquisitionDependencyProfileVerifier,
) {
    /** Physiological-family count only; acquisition independence requires a full assessment. */
    fun coherentIndependentFamilyCount(evidence: List<EvidenceItem>): Int =
        coherentFamilyCount(assessFamilies(evidence))

    fun interpret(
        deviations: List<BaselineDeviation>,
        coverageWindows: List<MetricWindow>,
        nowEpochMillis: Long,
        priorPersistenceEvidence: List<PersistenceEpisodeEvidence> = emptyList(),
    ): InterpretationAssessment {
        val qualifiedCoverageWindows = coverageWindows.filter { window ->
            eligibleForInterpretation(window.metric) &&
                window.quality.interpretationGrade &&
                window.provenanceIds.isNotEmpty() &&
                window.provenanceIds.all(String::isNotBlank) &&
                hasVerifiedAcquisitionProfile(window) &&
                window.endEpochMillis <= nowEpochMillis &&
                nowEpochMillis - window.endEpochMillis <= MAX_QUALIFIED_COVERAGE_AGE_MILLIS
        }.groupBy(MetricWindow::id)
            .filterValues { sameId -> sameId.size == 1 }
            .mapValues { (_, windows) -> windows.single() }
        val availableQualifiedFamilies = qualifiedCoverageWindows.values
            .asSequence()
            .map { independentFamilyFor(domainFor(it.metric)) }
            .toSet()
        val usable = deviations.filter {
            boundToQualifiedCoverage(it, qualifiedCoverageWindows) &&
                it.baselineMaturity >= 1.0 &&
                it.baselineSampleCount >= 20 &&
                abs(it.robustZ) >= 1.5
        }
        if (usable.isEmpty()) {
            return emptyAssessment(
                status = InterpretationAssessmentStatus.NO_QUALIFIED_DEVIATION,
                availableQualifiedFamilies = availableQualifiedFamilies,
                priorPersistenceEvidence = priorPersistenceEvidence,
            )
        }

        val acquisitionBoundEvidence = usable.map { deviation ->
            val domain = domainFor(deviation.metric)
            val coverageWindow = requireNotNull(qualifiedCoverageWindows[deviation.windowId])
            AcquisitionBoundEvidence(
                evidence = EvidenceItem(
                    domain = domain,
                    metric = deviation.metric,
                    statement = evidenceStatement(deviation),
                    contribution = contributionFor(deviation),
                    quality = deviation.quality.score,
                    provenanceIds = deviation.provenanceIds,
                ),
                acquisitionProfile = coverageWindow.acquisitionProfile,
            )
        }
        val evidence = acquisitionBoundEvidence
            .map(AcquisitionBoundEvidence::evidence)
            .sortedByDescending { abs(it.contribution) }

        val familyAssessments = assessFamilies(evidence)
        val conflictingFamilies = familyAssessments
            .filter { it.direction == FamilyEvidenceDirection.CONFLICTING }
            .mapTo(linkedSetOf()) { it.family }
        val coherentFamilyCount = coherentFamilyCount(familyAssessments)
        val acquisitionGraph = assessAcquisitionGraph(
            familyAssessments = familyAssessments,
            boundEvidence = acquisitionBoundEvidence,
        )
        val coherentAcquisitionGroupCount = acquisitionGraph.independentComponents.size
        val currentFamilyDirections = familyAssessments.mapNotNull { assessment ->
            when (assessment.direction) {
                FamilyEvidenceDirection.POSITIVE -> assessment.family to NormalizedContributionDirection.POSITIVE
                FamilyEvidenceDirection.NEGATIVE -> assessment.family to NormalizedContributionDirection.NEGATIVE
                FamilyEvidenceDirection.CONFLICTING, FamilyEvidenceDirection.NONE -> null
            }
        }.toMap()

        if (conflictingFamilies.isNotEmpty()) {
            return InterpretationAssessment(
                status = InterpretationAssessmentStatus.CONFLICTING_EVIDENCE,
                availableQualifiedFamilies = availableQualifiedFamilies,
                familyAssessments = familyAssessments,
                independentCoherentFamilyCount = coherentFamilyCount,
                independentCoherentAcquisitionGroupCount = coherentAcquisitionGroupCount,
                acquisitionOriginsByFamily = acquisitionGraph.originsByFamily,
                acquisitionComponents = acquisitionGraph.independentComponents,
                conflictingFamilies = conflictingFamilies,
                persistence = PersistenceAssessment(1, PersistenceEvidenceStatus.CURRENT_ONLY),
                evidence = evidence,
                insight = null,
            )
        }

        if (coherentFamilyCount >= 2 && coherentAcquisitionGroupCount < 2) {
            return InterpretationAssessment(
                status = InterpretationAssessmentStatus.ACQUISITION_DEPENDENCY_LIMITED,
                availableQualifiedFamilies = availableQualifiedFamilies,
                familyAssessments = familyAssessments,
                independentCoherentFamilyCount = coherentFamilyCount,
                independentCoherentAcquisitionGroupCount = coherentAcquisitionGroupCount,
                acquisitionOriginsByFamily = acquisitionGraph.originsByFamily,
                acquisitionComponents = acquisitionGraph.independentComponents,
                conflictingFamilies = emptySet(),
                persistence = if (priorPersistenceEvidence.isEmpty()) {
                    PersistenceAssessment(1, PersistenceEvidenceStatus.CURRENT_ONLY)
                } else {
                    PersistenceAssessment(
                        qualifiedWindowCount = 1,
                        status = PersistenceEvidenceStatus.REJECTED,
                        rejectionReasonCodes = setOf("persistence-current-acquisition-dependency-limited"),
                    )
                },
                evidence = evidence,
                insight = null,
            )
        }

        val persistence = persistenceEvaluator.evaluate(
            currentObservedAtEpochMillis = nowEpochMillis,
            currentFamilyDirections = currentFamilyDirections,
            currentAcquisitionOriginsByFamily = acquisitionGraph.originsByFamily,
            currentProvenanceIds = evidence.flatMap { it.provenanceIds }.distinct(),
            priorEpisodes = priorPersistenceEvidence,
        )
        val positiveFamilies = familyAssessments.count { it.direction == FamilyEvidenceDirection.POSITIVE }
        val negativeFamilies = familyAssessments.count { it.direction == FamilyEvidenceDirection.NEGATIVE }
        val dominantPositive = positiveFamilies >= negativeFamilies
        val coherentEvidence = familyAssessments.filter {
            if (dominantPositive) {
                it.direction == FamilyEvidenceDirection.POSITIVE
            } else {
                it.direction == FamilyEvidenceDirection.NEGATIVE
            }
        }
        val representedFamilies = familyAssessments.filter { it.direction != FamilyEvidenceDirection.NONE }
        val dataQuality = representedFamilies.map { family ->
            evidence.filter { independentFamilyFor(it.domain) == family.family }
                .maxBy { abs(it.contribution) }
                .quality
        }.averageOrZero()
        val rawStrength = coherentEvidence.map { abs(it.representativeContribution) }.averageOrZero()
        val corroboration = (
            1.0 + (coherentAcquisitionGroupCount - 1) * 0.12
            ).coerceAtMost(1.35)
        val persistenceMultiplier = (
            1.0 + (persistence.qualifiedWindowCount - 1) * 0.08
            ).coerceAtMost(1.32)
        val confidence = (
            rawStrength * corroboration * persistenceMultiplier * dataQuality
            ).coerceIn(0.0, 0.96)

        if (confidence < 0.42) {
            return InterpretationAssessment(
                status = InterpretationAssessmentStatus.BELOW_DISPLAY_THRESHOLD,
                availableQualifiedFamilies = availableQualifiedFamilies,
                familyAssessments = familyAssessments,
                independentCoherentFamilyCount = coherentFamilyCount,
                independentCoherentAcquisitionGroupCount = coherentAcquisitionGroupCount,
                acquisitionOriginsByFamily = acquisitionGraph.originsByFamily,
                acquisitionComponents = acquisitionGraph.independentComponents,
                conflictingFamilies = emptySet(),
                persistence = persistence,
                evidence = evidence,
                insight = null,
            )
        }

        val severity = when {
            confidence >= 0.84 && coherentFamilyCount >= 3 &&
                coherentAcquisitionGroupCount >= 2 && persistence.qualifiedWindowCount >= 3 -> {
                InsightSeverity.CHECK
            }
            confidence >= 0.64 && coherentFamilyCount >= 2 &&
                coherentAcquisitionGroupCount >= 2 -> InsightSeverity.WATCH
            else -> InsightSeverity.INFORMATIONAL
        }
        val state = when {
            persistence.qualifiedWindowCount >= 3 && coherentFamilyCount >= 2 &&
                coherentAcquisitionGroupCount >= 2 -> InsightState.PERSISTENT
            coherentFamilyCount >= 2 && coherentAcquisitionGroupCount >= 2 -> {
                InsightState.CORROBORATED
            }
            else -> InsightState.PRELIMINARY
        }

        val directionSummary = if (coherentFamilyCount < 2) {
            "One qualified measurement differs from its matched baseline. A single signal cannot establish a broader physiological pattern."
        } else if (dominantPositive) {
            "Your recent measurements show a pattern of physiological strain relative to your personal baseline."
        } else {
            "Your recent measurements are below your expected pattern and may reflect reduced physiological activation."
        }

        val insight = HealthInsight(
            id = "insight-$nowEpochMillis",
            createdAtEpochMillis = nowEpochMillis,
            title = when (severity) {
                InsightSeverity.CHECK -> "Persistent change worth checking"
                InsightSeverity.WATCH -> "Several signals have shifted together"
                else -> "One signal needs remeasurement"
            },
            plainLanguageSummary = directionSummary,
            severity = severity,
            state = state,
            confidence = confidence,
            dataQuality = dataQuality,
            evidence = evidence,
            nextStep = when (severity) {
                InsightSeverity.CHECK -> "Pause, record how you feel, and repeat a high-quality resting measurement. Seek clinical advice if symptoms concern you."
                InsightSeverity.WATCH -> "Record relevant context and repeat a high-quality resting measurement. Seek clinical advice if symptoms concern you."
                else -> "Repeat the measurement under good contact and low motion. Do not make a health or treatment decision from this signal alone."
            },
            recheckAtEpochMillis = nowEpochMillis + 2 * 60 * 60 * 1_000L,
        )
        return InterpretationAssessment(
            status = InterpretationAssessmentStatus.INTERPRETED,
            availableQualifiedFamilies = availableQualifiedFamilies,
            familyAssessments = familyAssessments,
            independentCoherentFamilyCount = coherentFamilyCount,
            independentCoherentAcquisitionGroupCount = coherentAcquisitionGroupCount,
            acquisitionOriginsByFamily = acquisitionGraph.originsByFamily,
            acquisitionComponents = acquisitionGraph.independentComponents,
            conflictingFamilies = emptySet(),
            persistence = persistence,
            evidence = evidence,
            insight = insight,
        )
    }

    private fun emptyAssessment(
        status: InterpretationAssessmentStatus,
        availableQualifiedFamilies: Set<IndependentEvidenceFamily>,
        priorPersistenceEvidence: List<PersistenceEpisodeEvidence>,
    ) = InterpretationAssessment(
        status = status,
        availableQualifiedFamilies = availableQualifiedFamilies,
        familyAssessments = emptyList(),
        independentCoherentFamilyCount = 0,
        independentCoherentAcquisitionGroupCount = 0,
        acquisitionOriginsByFamily = emptyMap(),
        acquisitionComponents = emptyList(),
        conflictingFamilies = emptySet(),
        persistence = if (priorPersistenceEvidence.isEmpty()) {
            PersistenceAssessment(1, PersistenceEvidenceStatus.CURRENT_ONLY)
        } else {
            PersistenceAssessment(
                qualifiedWindowCount = 1,
                status = PersistenceEvidenceStatus.REJECTED,
                rejectionReasonCodes = setOf("persistence-current-direction-missing"),
            )
        },
        evidence = emptyList(),
        insight = null,
    )

    private fun assessFamilies(evidence: List<EvidenceItem>): List<IndependentFamilyAssessment> =
        evidence.groupBy { independentFamilyFor(it.domain) }.map { (family, items) ->
            val positive = items.filter { it.contribution > DIRECTION_EPSILON }
            val negative = items.filter { it.contribution < -DIRECTION_EPSILON }
            val direction = when {
                positive.isNotEmpty() && negative.isNotEmpty() -> FamilyEvidenceDirection.CONFLICTING
                positive.isNotEmpty() -> FamilyEvidenceDirection.POSITIVE
                negative.isNotEmpty() -> FamilyEvidenceDirection.NEGATIVE
                else -> FamilyEvidenceDirection.NONE
            }
            val representative = when (direction) {
                FamilyEvidenceDirection.POSITIVE -> positive.maxOf { it.contribution }
                FamilyEvidenceDirection.NEGATIVE -> negative.minOf { it.contribution }
                FamilyEvidenceDirection.CONFLICTING, FamilyEvidenceDirection.NONE -> 0.0
            }
            IndependentFamilyAssessment(
                family = family,
                direction = direction,
                representativeContribution = representative,
                evidenceCount = items.size,
            )
        }

    /**
     * Builds a conservative dependency graph over coherent physiological
     * families. Families that share any acquisition origin belong to the same
     * connected component and therefore contribute only one independent
     * acquisition vote.
     */
    private fun assessAcquisitionGraph(
        familyAssessments: List<IndependentFamilyAssessment>,
        boundEvidence: List<AcquisitionBoundEvidence>,
    ): AcquisitionGraphResult {
        val familyDirection = familyAssessments.associate { it.family to it.direction }
        val originsByFamily = familyAssessments.mapNotNull { assessment ->
            val expectedSign = when (assessment.direction) {
                FamilyEvidenceDirection.POSITIVE -> 1
                FamilyEvidenceDirection.NEGATIVE -> -1
                FamilyEvidenceDirection.CONFLICTING, FamilyEvidenceDirection.NONE -> return@mapNotNull null
            }
            val origins = boundEvidence.asSequence()
                .filter { independentFamilyFor(it.evidence.domain) == assessment.family }
                .filter {
                    if (expectedSign > 0) {
                        it.evidence.contribution > DIRECTION_EPSILON
                    } else {
                        it.evidence.contribution < -DIRECTION_EPSILON
                    }
                }
                .flatMap { it.acquisitionProfile.allOrigins.asSequence() }
                .toSet()
            if (origins.isEmpty()) null else assessment.family to origins
        }.toMap()

        val positiveFamilies = familyAssessments
            .filter { it.direction == FamilyEvidenceDirection.POSITIVE }
            .mapTo(linkedSetOf()) { it.family }
        val negativeFamilies = familyAssessments
            .filter { it.direction == FamilyEvidenceDirection.NEGATIVE }
            .mapTo(linkedSetOf()) { it.family }
        val coherentFamilies = if (positiveFamilies.size >= negativeFamilies.size) {
            positiveFamilies
        } else {
            negativeFamilies
        }

        val remaining = coherentFamilies
            .filter { familyDirection[it] != FamilyEvidenceDirection.CONFLICTING }
            .toMutableSet()
        val components = mutableListOf<IndependentAcquisitionComponent>()
        while (remaining.isNotEmpty()) {
            val pending = ArrayDeque<IndependentEvidenceFamily>()
            pending.addLast(remaining.first())
            val componentFamilies = linkedSetOf<IndependentEvidenceFamily>()
            val componentOrigins = linkedSetOf<AcquisitionOrigin>()
            while (pending.isNotEmpty()) {
                val family = pending.removeFirst()
                if (!remaining.remove(family)) continue
                componentFamilies += family
                val familyOrigins = originsByFamily.getValue(family)
                componentOrigins += familyOrigins
                remaining.filterTo(mutableListOf()) { other ->
                    originsByFamily.getValue(other).any { it in familyOrigins }
                }.forEach(pending::addLast)
            }
            components += IndependentAcquisitionComponent(
                families = componentFamilies,
                origins = componentOrigins,
            )
        }
        return AcquisitionGraphResult(
            originsByFamily = originsByFamily,
            independentComponents = components,
        )
    }

    private fun coherentFamilyCount(families: List<IndependentFamilyAssessment>): Int {
        val positiveFamilies = families.count { it.direction == FamilyEvidenceDirection.POSITIVE }
        val negativeFamilies = families.count { it.direction == FamilyEvidenceDirection.NEGATIVE }
        return max(positiveFamilies, negativeFamilies)
    }

    private fun boundToQualifiedCoverage(
        deviation: BaselineDeviation,
        qualifiedCoverageWindows: Map<String, MetricWindow>,
    ): Boolean {
        val window = qualifiedCoverageWindows[deviation.windowId] ?: return false
        return window.metric == deviation.metric &&
            window.quality == deviation.quality &&
            window.provenanceIds == deviation.provenanceIds
    }

    private fun hasVerifiedAcquisitionProfile(window: MetricWindow): Boolean = try {
        acquisitionProfileVerifier.verify(window)
    } catch (_: Exception) {
        false
    }

    private fun contributionFor(deviation: BaselineDeviation): Double {
        val magnitude = sigmoid(abs(deviation.robustZ) - 1.5)
        val reliability = deviation.quality.score * deviation.baselineMaturity
        val direction = when (deviation.metric) {
            SensorMetric.INTER_BEAT_INTERVAL, SensorMetric.HRV_RMSSD, SensorMetric.HRV_SDNN,
            SensorMetric.OXYGEN_SATURATION, SensorMetric.SLEEP_DURATION,
            SensorMetric.SLEEP_EFFICIENCY, SensorMetric.STEP_COUNT -> -kotlin.math.sign(deviation.robustZ)
            else -> kotlin.math.sign(deviation.robustZ)
        }
        return (direction * magnitude * reliability).coerceIn(-1.0, 1.0)
    }

    private fun sigmoid(value: Double): Double = 1.0 / (1.0 + exp(-value))

    private fun eligibleForInterpretation(metric: SensorMetric): Boolean = when (metric) {
        SensorMetric.HEART_RATE,
        SensorMetric.INTER_BEAT_INTERVAL,
        SensorMetric.HRV_RMSSD,
        SensorMetric.HRV_SDNN,
        SensorMetric.OXYGEN_SATURATION,
        SensorMetric.SKIN_TEMPERATURE,
        SensorMetric.EDA,
        SensorMetric.RESPIRATORY_RATE,
        SensorMetric.SLEEP_DURATION,
        SensorMetric.SLEEP_EFFICIENCY,
        -> true

        SensorMetric.AMBIENT_TEMPERATURE,
        SensorMetric.STEP_COUNT,
        SensorMetric.ACTIVITY_LOAD,
        SensorMetric.BODY_IMPEDANCE,
        SensorMetric.SWEAT_LOSS,
        -> false
    }

    private fun domainFor(metric: SensorMetric): PhysiologicalDomain = when (metric) {
        SensorMetric.HEART_RATE -> PhysiologicalDomain.CARDIOVASCULAR
        SensorMetric.INTER_BEAT_INTERVAL, SensorMetric.HRV_RMSSD, SensorMetric.HRV_SDNN,
        SensorMetric.EDA -> PhysiologicalDomain.AUTONOMIC
        SensorMetric.OXYGEN_SATURATION,
        SensorMetric.RESPIRATORY_RATE -> PhysiologicalDomain.RESPIRATORY
        SensorMetric.SKIN_TEMPERATURE,
        SensorMetric.AMBIENT_TEMPERATURE -> PhysiologicalDomain.THERMAL
        SensorMetric.SLEEP_DURATION,
        SensorMetric.SLEEP_EFFICIENCY -> PhysiologicalDomain.SLEEP
        SensorMetric.STEP_COUNT,
        SensorMetric.ACTIVITY_LOAD -> PhysiologicalDomain.MOVEMENT
        SensorMetric.SWEAT_LOSS -> PhysiologicalDomain.HYDRATION
        SensorMetric.BODY_IMPEDANCE -> PhysiologicalDomain.BODY_COMPOSITION
    }

    private fun independentFamilyFor(domain: PhysiologicalDomain): IndependentEvidenceFamily = when (domain) {
        PhysiologicalDomain.CARDIOVASCULAR,
        PhysiologicalDomain.AUTONOMIC -> IndependentEvidenceFamily.CARDIO_AUTONOMIC
        PhysiologicalDomain.RESPIRATORY -> IndependentEvidenceFamily.RESPIRATORY_OXYGENATION
        PhysiologicalDomain.THERMAL,
        PhysiologicalDomain.HYDRATION -> IndependentEvidenceFamily.THERMAL_EXERTIONAL
        PhysiologicalDomain.SLEEP,
        PhysiologicalDomain.RECOVERY -> IndependentEvidenceFamily.SLEEP_RESTORATION
        PhysiologicalDomain.MOVEMENT -> IndependentEvidenceFamily.ACTIVITY_LOAD
        PhysiologicalDomain.BODY_COMPOSITION -> IndependentEvidenceFamily.BODY_COMPOSITION
        PhysiologicalDomain.CONTEXT -> IndependentEvidenceFamily.CONTEXT
    }

    private fun evidenceStatement(deviation: BaselineDeviation): String {
        val direction = if (deviation.robustZ > 0) "above" else "below"
        return "${displayName(deviation.metric)} was ${format(abs(deviation.robustZ))} robust deviations $direction your matched baseline."
    }

    private fun displayName(metric: SensorMetric): String = metric.name
        .lowercase()
        .replace('_', ' ')
        .replaceFirstChar { it.uppercase() }

    private fun format(value: Double): String = ((value * 10.0).toInt() / 10.0).toString()

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private companion object {
        const val DIRECTION_EPSILON = 0.05
        const val MAX_QUALIFIED_COVERAGE_AGE_MILLIS = 6L * 60L * 60L * 1_000L
    }
}
