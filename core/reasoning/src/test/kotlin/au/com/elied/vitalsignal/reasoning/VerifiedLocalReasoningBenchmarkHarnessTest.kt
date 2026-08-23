package au.com.elied.vitalsignal.reasoning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedLocalReasoningBenchmarkHarnessTest {
    @Test
    fun repeatedVerifiedRunsProduceMetadataOnlyDeterministicReport() {
        val packet = packet()
        val audit = RecordingAuditSink()
        val harness = harness(
            gateway = LocalReasoningGateway { ReasoningTestFixtures.candidate(packet) to ReasoningTestFixtures.receipt() },
            audit = audit,
        )
        val plan = LocalReasoningBenchmarkPlan(
            cases = listOf(
                LocalReasoningBenchmarkCase(
                    id = "grounded-observation",
                    packet = packet,
                    expectation = LocalReasoningBenchmarkExpectation(ReasoningDeliveryState.DELIVERABLE),
                ),
            ),
            repetitions = 3,
        )

        val report = harness.run(plan)

        assertEquals(3, report.observations.size)
        assertEquals(3, report.expectationPassCount)
        assertEquals(1.0, report.expectationPassRate, 0.0)
        assertTrue(report.nonDeterministicCaseIds.isEmpty())
        assertEquals(3, audit.records.size)
        assertTrue(report.observations.all { it.elapsedNanos == 10L })
        assertTrue(report.observations.all { it.candidateSha256!!.matches(Regex("[a-f0-9]{64}")) })
        assertEquals(audit.records.first().candidateSha256, report.observations.first().candidateSha256)
        assertTrue(report.observations.all { it.runIdentity?.modelDigest == "b".repeat(64) })
        assertNotNull(report.observations.single { it.repetition == 0 }.runIdentity)
        assertFalse(
            LocalReasoningBenchmarkObservation::class.java.declaredFields
                .any { it.type == LocalReasoningCandidate::class.java },
        )
    }

    @Test
    fun benchmarkCannotBypassTemplatePolicyOrAudit() {
        val packet = packet()
        val unsafe = ReasoningTestFixtures.candidate(
            packet,
            ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
            NarrativeClaimKind.TREND,
        )
        val audit = RecordingAuditSink()
        val report = harness(
            gateway = LocalReasoningGateway { unsafe to ReasoningTestFixtures.receipt() },
            audit = audit,
        ).run(
            LocalReasoningBenchmarkPlan(
                cases = listOf(
                    LocalReasoningBenchmarkCase(
                        id = "unapproved-template",
                        packet = packet,
                        expectation = LocalReasoningBenchmarkExpectation(
                            deliveryState = ReasoningDeliveryState.SAFE_FALLBACK,
                            orchestrationFailure = ReasoningOrchestrationFailure.POLICY_REJECTED,
                            policyFailureCodes = setOf(ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE),
                        ),
                    ),
                ),
                repetitions = 1,
            ),
        )

        val observation = report.observations.single()
        assertTrue(observation.expectationMet)
        assertEquals(ReasoningDeliveryState.SAFE_FALLBACK, observation.deliveryState)
        assertEquals(setOf(ReasoningFailureCode.UNAPPROVED_NARRATIVE_TEMPLATE), observation.policyFailureCodes)
        assertEquals(null, observation.candidateSha256)
        assertEquals(1, audit.records.size)
    }

    @Test
    fun changedVerifiedSemanticOutputIsReportedAsNondeterministic() {
        val packet = packet(
            approvedTemplates = setOf(
                ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1,
                ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
            ),
        )
        var invocation = 0
        val alternatingGateway = LocalReasoningGateway {
            invocation += 1
            val candidate = if (invocation % 2 == 0) {
                ReasoningTestFixtures.candidate(
                    packet,
                    ReviewedNarrativeTemplates.DIRECTIONAL_TREND_V1,
                    NarrativeClaimKind.TREND,
                )
            } else {
                ReasoningTestFixtures.candidate(packet)
            }
            candidate to ReasoningTestFixtures.receipt()
        }
        val report = harness(alternatingGateway).run(
            LocalReasoningBenchmarkPlan(
                cases = listOf(
                    LocalReasoningBenchmarkCase(
                        id = "stability-case",
                        packet = packet,
                        expectation = LocalReasoningBenchmarkExpectation(ReasoningDeliveryState.DELIVERABLE),
                    ),
                ),
                repetitions = 2,
            ),
        )

        assertEquals(setOf("stability-case"), report.nonDeterministicCaseIds)
        assertEquals(2, report.observations.mapNotNull { it.candidateSha256 }.distinct().size)
    }

    @Test
    fun exactGoldenCandidateHashCanBeRequired() {
        val packet = packet()
        val gateway = LocalReasoningGateway { ReasoningTestFixtures.candidate(packet) to ReasoningTestFixtures.receipt() }
        val first = harness(gateway).run(
            plan(packet, LocalReasoningBenchmarkExpectation(ReasoningDeliveryState.DELIVERABLE)),
        )
        val golden = first.observations.single().candidateSha256!!

        val matching = harness(gateway).run(
            plan(
                packet,
                LocalReasoningBenchmarkExpectation(
                    deliveryState = ReasoningDeliveryState.DELIVERABLE,
                    candidateSha256 = golden,
                ),
            ),
        )
        val wrong = harness(gateway).run(
            plan(
                packet,
                LocalReasoningBenchmarkExpectation(
                    deliveryState = ReasoningDeliveryState.DELIVERABLE,
                    candidateSha256 = "f".repeat(64),
                ),
            ),
        )

        assertTrue(matching.observations.single().expectationMet)
        assertFalse(wrong.observations.single().expectationMet)
    }

    @Test
    fun unavailableModelAndAuditFailureRemainVisibleInBenchmark() {
        val packet = packet()
        val unavailable = harness(LocalReasoningGateway { throw IllegalStateException("offline") }).run(
            plan(
                packet,
                LocalReasoningBenchmarkExpectation(
                    deliveryState = ReasoningDeliveryState.SAFE_FALLBACK,
                    orchestrationFailure = ReasoningOrchestrationFailure.MODEL_UNAVAILABLE,
                ),
            ),
        ).observations.single()
        assertTrue(unavailable.expectationMet)
        assertEquals(null, unavailable.runIdentity)

        val auditFailure = harness(
            gateway = LocalReasoningGateway { ReasoningTestFixtures.candidate(packet) to ReasoningTestFixtures.receipt() },
            audit = RecordingAuditSink(commitSucceeds = false),
        ).run(
            plan(
                packet,
                LocalReasoningBenchmarkExpectation(
                    deliveryState = ReasoningDeliveryState.SAFE_FALLBACK,
                    orchestrationFailure = ReasoningOrchestrationFailure.AUDIT_COMMIT_FAILED,
                ),
            ),
        ).observations.single()
        assertTrue(auditFailure.expectationMet)
        assertEquals(null, auditFailure.candidateSha256)
    }

    @Test
    fun planBoundsAndMonotonicClockAreEnforced() {
        val packet = packet()
        val duplicate = LocalReasoningBenchmarkCase(
            id = "duplicate",
            packet = packet,
            expectation = LocalReasoningBenchmarkExpectation(ReasoningDeliveryState.DELIVERABLE),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalReasoningBenchmarkPlan(listOf(duplicate, duplicate), repetitions = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalReasoningBenchmarkPlan(listOf(duplicate), repetitions = 101)
        }

        var first = true
        val backwardsClock = VerifiedLocalReasoningBenchmarkHarness(
            orchestrator = orchestrator(
                LocalReasoningGateway { ReasoningTestFixtures.candidate(packet) to ReasoningTestFixtures.receipt() },
            ),
            monotonicNanos = {
                if (first) {
                    first = false
                    20L
                } else {
                    10L
                }
            },
        )
        assertThrows(IllegalArgumentException::class.java) {
            backwardsClock.run(
                plan(packet, LocalReasoningBenchmarkExpectation(ReasoningDeliveryState.DELIVERABLE)),
            )
        }
    }

    private fun plan(
        packet: SignedHealthStatePacket,
        expectation: LocalReasoningBenchmarkExpectation,
    ) = LocalReasoningBenchmarkPlan(
        cases = listOf(LocalReasoningBenchmarkCase("case", packet, expectation)),
        repetitions = 1,
    )

    private fun harness(
        gateway: LocalReasoningGateway,
        audit: RecordingAuditSink = RecordingAuditSink(),
    ): VerifiedLocalReasoningBenchmarkHarness {
        var nanos = 0L
        return VerifiedLocalReasoningBenchmarkHarness(
            orchestrator = orchestrator(gateway, audit),
            monotonicNanos = {
                nanos += 10L
                nanos
            },
        )
    }

    private fun orchestrator(
        gateway: LocalReasoningGateway,
        audit: ReasoningAuditSink = RecordingAuditSink(),
    ) = VerifiedLocalReasoningOrchestrator(
        gateway = gateway,
        authority = ReasoningTestFixtures.authority(),
        policy = LocalReasoningPolicy(),
        auditSink = audit,
        nowEpochMillis = { ReasoningTestFixtures.NOW },
    )

    private class RecordingAuditSink(
        private val commitSucceeds: Boolean = true,
    ) : ReasoningAuditSink {
        val records = mutableListOf<ReasoningAuditRecord>()

        override fun commit(record: ReasoningAuditRecord): Boolean {
            records += record
            return commitSucceeds
        }
    }

    private fun packet(
        approvedTemplates: Set<String> = setOf(ReviewedNarrativeTemplates.PERSONAL_BASELINE_OBSERVATION_V1),
    ) = ReasoningTestFixtures.packet(approvedTemplateIds = approvedTemplates)
}
