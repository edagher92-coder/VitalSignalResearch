package au.com.elied.vitalsignal.reasoning

data class LocalReasoningBenchmarkExpectation(
    val deliveryState: ReasoningDeliveryState,
    val orchestrationFailure: ReasoningOrchestrationFailure = ReasoningOrchestrationFailure.NONE,
    val policyFailureCodes: Set<ReasoningFailureCode> = emptySet(),
    val candidateSha256: String? = null,
) {
    init {
        require(candidateSha256 == null || candidateSha256.matches(Regex("[a-f0-9]{64}")))
        require((deliveryState == ReasoningDeliveryState.DELIVERABLE) || candidateSha256 == null)
    }
}

data class LocalReasoningBenchmarkCase(
    val id: String,
    val packet: SignedHealthStatePacket,
    val expectation: LocalReasoningBenchmarkExpectation,
) {
    init {
        require(id.isNotBlank() && id.length <= 200)
    }
}

data class LocalReasoningBenchmarkPlan(
    val cases: List<LocalReasoningBenchmarkCase>,
    val repetitions: Int,
) {
    init {
        require(cases.isNotEmpty())
        require(cases.size <= 1_000)
        require(cases.map { it.id }.distinct().size == cases.size)
        require(repetitions in 1..100)
        require(cases.size.toLong() * repetitions <= 10_000L)
    }
}

/** Receipt subset contains reproducibility metadata, never prompt or candidate text. */
data class OllamaBenchmarkRunIdentity(
    val ollamaVersion: String,
    val modelName: String,
    val modelDigest: String,
    val quantization: String,
    val promptSha256: String,
    val jsonSchemaSha256: String,
    val seed: Long,
    val temperature: Double,
    val contextTokens: Int,
)

data class LocalReasoningBenchmarkObservation(
    val caseId: String,
    val repetition: Int,
    val elapsedNanos: Long,
    val deliveryState: ReasoningDeliveryState,
    val orchestrationFailure: ReasoningOrchestrationFailure,
    val policyFailureCodes: Set<ReasoningFailureCode>,
    val candidateSha256: String?,
    val runIdentity: OllamaBenchmarkRunIdentity?,
    val expectationMet: Boolean,
) {
    init {
        require(caseId.isNotBlank())
        require(repetition >= 0)
        require(elapsedNanos >= 0L)
        require(candidateSha256 == null || candidateSha256.matches(Regex("[a-f0-9]{64}")))
    }

    internal fun deterministicSignature(): String = buildString {
        append(deliveryState.name)
        append('|').append(orchestrationFailure.name)
        append('|').append(policyFailureCodes.map { it.name }.sorted().joinToString(","))
        append('|').append(candidateSha256 ?: "")
        append('|').append(runIdentity?.ollamaVersion ?: "")
        append('|').append(runIdentity?.modelName ?: "")
        append('|').append(runIdentity?.modelDigest ?: "")
        append('|').append(runIdentity?.quantization ?: "")
        append('|').append(runIdentity?.promptSha256 ?: "")
        append('|').append(runIdentity?.jsonSchemaSha256 ?: "")
        append('|').append(runIdentity?.seed ?: "")
        append('|').append(runIdentity?.temperature ?: "")
        append('|').append(runIdentity?.contextTokens ?: "")
    }
}

data class LocalReasoningBenchmarkReport(
    val observations: List<LocalReasoningBenchmarkObservation>,
) {
    init {
        require(observations.isNotEmpty())
    }

    val expectationPassCount: Int get() = observations.count { it.expectationMet }

    val expectationPassRate: Double get() = expectationPassCount.toDouble() / observations.size

    val nonDeterministicCaseIds: Set<String>
        get() = observations
            .groupBy { it.caseId }
            .filterValues { runs -> runs.map { it.deterministicSignature() }.distinct().size > 1 }
            .keys
}

/**
 * Benchmarks only the verified, audit-gated path. It deliberately accepts no
 * raw gateway, database, health reader, tool executor, or network client, and
 * the report retains hashes and dispositions rather than health content.
 */
class VerifiedLocalReasoningBenchmarkHarness(
    private val orchestrator: VerifiedLocalReasoningOrchestrator,
    private val monotonicNanos: () -> Long,
) {
    fun run(plan: LocalReasoningBenchmarkPlan): LocalReasoningBenchmarkReport {
        val observations = ArrayList<LocalReasoningBenchmarkObservation>(plan.cases.size * plan.repetitions)
        plan.cases.forEach { benchmarkCase ->
            repeat(plan.repetitions) { repetition ->
                val started = monotonicNanos()
                val outcome = orchestrator.run(benchmarkCase.packet)
                val completed = monotonicNanos()
                require(completed >= started) { "Benchmark monotonic clock moved backwards" }
                val candidateSha256 = outcome.candidate?.let(CanonicalReasoningCandidate::sha256)
                val identity = outcome.runReceipt?.let { receipt ->
                    OllamaBenchmarkRunIdentity(
                        ollamaVersion = receipt.ollamaVersion,
                        modelName = receipt.modelName,
                        modelDigest = receipt.modelDigest,
                        quantization = receipt.quantization,
                        promptSha256 = receipt.promptSha256,
                        jsonSchemaSha256 = receipt.jsonSchemaSha256,
                        seed = receipt.seed,
                        temperature = receipt.temperature,
                        contextTokens = receipt.contextTokens,
                    )
                }
                val expectation = benchmarkCase.expectation
                val met = outcome.state == expectation.deliveryState &&
                    outcome.orchestrationFailure == expectation.orchestrationFailure &&
                    outcome.policyFailureCodes == expectation.policyFailureCodes &&
                    (expectation.candidateSha256 == null || expectation.candidateSha256 == candidateSha256)
                observations += LocalReasoningBenchmarkObservation(
                    caseId = benchmarkCase.id,
                    repetition = repetition,
                    elapsedNanos = completed - started,
                    deliveryState = outcome.state,
                    orchestrationFailure = outcome.orchestrationFailure,
                    policyFailureCodes = outcome.policyFailureCodes,
                    candidateSha256 = candidateSha256,
                    runIdentity = identity,
                    expectationMet = met,
                )
            }
        }
        return LocalReasoningBenchmarkReport(observations)
    }

}
