package au.com.elied.vitalsignal.analytics

import au.com.elied.vitalsignal.model.ForecastEndpointDefinition
import au.com.elied.vitalsignal.model.ForecastFeatureSchemaDefinition
import au.com.elied.vitalsignal.model.ForecastWindowSemantics
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalForecastEngineTest {
    private val engine = PersonalForecastEngine(
        trainingCaseReceiptVerifier = ForecastTrainingCaseReceiptVerifier { trainingCase ->
            trainingCase.verificationReceiptId?.startsWith("test-receipt:") == true
        },
    )
    private val endpoint = PersonalForecastEngine.SIMULATOR_72_HOUR_POINT_ENDPOINT
    private val schema = PersonalForecastEngine.SIMULATOR_FEATURE_SCHEMA

    @Test
    fun lowQualityTargetForcesAbstention() {
        val result = engine.forecast(
            history = history(35),
            target = snapshot("target", TARGET_CUTOFF, 1.0, quality = 0.79),
            createdAtEpochMillis = TARGET_CUTOFF,
            endpoint = endpoint,
        )

        assertEquals(ForecastModelState.ABSTAINED, result.state)
        assertNull(result.forecast)
    }

    @Test
    fun futureMissingAndPrematureOutcomesCannotEnterForecast() {
        val target = snapshot("target", TARGET_CUTOFF, 1.0)
        val base = history(10)
        val ignored = base + listOf(
            case("future", TARGET_CUTOFF - 60L * DAY, 1.0, TARGET_CUTOFF + 1L),
            ForecastTrainingCase(
                caseId = "case-missing",
                endpoint = endpoint,
                features = snapshot("missing", TARGET_CUTOFF - 61L * DAY, 1.0),
                observedOutcome = null,
                resolvedAtEpochMillis = null,
                outcomeObservationId = null,
                outcomeRecordSha256 = null,
                verificationReceiptId = null,
            ),
        )

        val premature = assertThrows(IllegalArgumentException::class.java) {
            case(
                "premature",
                TARGET_CUTOFF - 5L * DAY,
                1.0,
                endpoint.targetEnd(TARGET_CUTOFF - 5L * DAY) - 1L,
            )
        }
        assertTrue(premature.message!!.contains("window ends"))

        val first = run(base, target)
        val second = run(ignored, target)
        assertEquals(first.validCaseCount, second.validCaseCount)
        assertEquals(first.forecast!!.probability, second.forecast!!.probability, 0.0)
    }

    @Test
    fun futureFeatureSourceIsRejectedAtSnapshotConstruction() {
        val values = exactValues(TARGET_CUTOFF, 1.0).toMutableMap()
        values["sleep"] = values.getValue("sleep").copy(
            sourceWindowEndEpochMillis = TARGET_CUTOFF + 1L,
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ForecastFeatureSnapshot(
                id = "future-feature",
                cutoffEpochMillis = TARGET_CUTOFF,
                featureSchema = schema,
                featureValues = values,
                quality = 0.95,
            )
        }
        assertTrue(error.message!!.contains("after the committed cutoff"))
    }

    @Test
    fun lowerQualityCannotNarrowUncertainty() {
        val cases = history(35)
        val high = run(cases, snapshot("high", TARGET_CUTOFF, 1.0, quality = 1.0)).forecast!!
        val lower = run(cases, snapshot("lower", TARGET_CUTOFF, 1.0, quality = 0.81)).forecast!!

        assertTrue((lower.upperBound - lower.lowerBound) >= (high.upperBound - high.lowerBound))
    }

    @Test
    fun thirtyResolvedCasesCanReachReadyStateAndBindExactTarget() {
        val result = run(history(40), snapshot("target", TARGET_CUTOFF, 1.0))
        val forecast = result.forecast!!

        assertEquals(ForecastModelState.READY, result.state)
        assertNotNull(forecast)
        assertEquals(40, result.validCaseCount)
        assertEquals(endpoint, forecast.endpoint)
        assertEquals(schema, forecast.featureSchema)
        assertEquals(endpoint.targetStart(TARGET_CUTOFF), forecast.targetStartEpochMillis)
        assertEquals(endpoint.targetEnd(TARGET_CUTOFF), forecast.targetEndEpochMillis)
    }

    @Test
    fun repeatedExactCaseCannotManufactureMinimumSupport() {
        val one = history(1).single()

        val result = run(List(40) { one }, snapshot("target", TARGET_CUTOFF, 1.0))

        assertEquals(ForecastModelState.LEARNING, result.state)
        assertEquals(1, result.validCaseCount)
    }

    @Test
    fun conflictingDuplicateIdentityOrTargetWindowForcesAbstention() {
        val cutoff = TARGET_CUTOFF - 10L * DAY
        val resolvedAt = endpoint.targetEnd(cutoff)
        val one = case("duplicate", cutoff, 1.0, resolvedAt)
        val conflictingIdentity = case("duplicate", cutoff, 2.0, resolvedAt)
        val identityResult = run(
            listOf(one, conflictingIdentity),
            snapshot("target", TARGET_CUTOFF, 1.0),
        )
        val duplicateWindow = case("different", cutoff, 1.0, resolvedAt)
        val windowResult = run(
            listOf(one, duplicateWindow),
            snapshot("target", TARGET_CUTOFF, 1.0),
        )

        assertEquals(ForecastModelState.ABSTAINED, identityResult.state)
        assertTrue(identityResult.reason.contains("same case ID"))
        assertEquals(ForecastModelState.ABSTAINED, windowResult.state)
        assertTrue(windowResult.reason.contains("same endpoint target window"))
    }

    @Test
    fun replayedOutcomeIdentityCannotManufactureResolvedSupport() {
        val replayed = history(40).map { original ->
            ForecastTrainingCase(
                caseId = original.caseId,
                endpoint = original.endpoint,
                features = original.features,
                observedOutcome = original.observedOutcome,
                resolvedAtEpochMillis = original.resolvedAtEpochMillis,
                outcomeObservationId = "replayed-outcome",
                outcomeRecordSha256 = "f".repeat(64),
                verificationReceiptId = "test-receipt:replayed:${original.caseId}",
            )
        }

        val result = run(replayed, snapshot("target", TARGET_CUTOFF, 1.0))

        assertEquals(ForecastModelState.ABSTAINED, result.state)
        assertTrue(result.reason.contains("observation ID was replayed"))
        assertEquals(0, result.validCaseCount)
    }

    @Test
    fun historyPoolingRejectsEndpointSchemaAndFeatureKeyDrift() {
        val exact = history(10)
        val driftedEndpoint = endpointWithWindow(48, 49)
        val driftedSchema = schemaWithFeatures(
            mapOf("cardio-autonomic" to "sim-v1", "sleep" to "sim-v1", "thermal" to "sim-v1"),
        )
        val extras = listOf(
            ForecastTrainingCase(
                caseId = "case-endpoint-drift",
                endpoint = driftedEndpoint,
                features = snapshot("endpoint-drift", TARGET_CUTOFF - 50L * DAY, 1.0),
                observedOutcome = 1.0,
                resolvedAtEpochMillis = driftedEndpoint.targetEnd(TARGET_CUTOFF - 50L * DAY),
                outcomeObservationId = "outcome-endpoint-drift",
                outcomeRecordSha256 = "b".repeat(64),
                verificationReceiptId = "test-receipt:endpoint-drift",
            ),
            ForecastTrainingCase(
                caseId = "case-schema-key-drift",
                endpoint = endpoint,
                features = snapshot(
                    id = "schema-key-drift",
                    cutoff = TARGET_CUTOFF - 51L * DAY,
                    value = 1.0,
                    selectedSchema = driftedSchema,
                ),
                observedOutcome = 1.0,
                resolvedAtEpochMillis = endpoint.targetEnd(TARGET_CUTOFF - 51L * DAY),
                outcomeObservationId = "outcome-schema-key-drift",
                outcomeRecordSha256 = "c".repeat(64),
                verificationReceiptId = "test-receipt:schema-drift",
            ),
        )

        val result = run(exact + extras, snapshot("target", TARGET_CUTOFF, 1.0))
        assertEquals(exact.size, result.validCaseCount)
    }

    @Test
    fun endpointHorizonSchemaAndKeyDriftChangeIdentityOrFailClosed() {
        val target = snapshot("target", TARGET_CUTOFF, 1.0)
        val original = run(history(10), target).forecast!!
        val changedEndpoint = endpointWithWindow(48, 49)
        val horizonDrift = run(
            history(10, selectedEndpoint = changedEndpoint),
            target,
            changedEndpoint,
        ).forecast!!
        val driftedSchema = schemaWithFeatures(
            mapOf("cardio-autonomic" to "sim-v2", "sleep" to "sim-v1"),
        )
        val schemaTarget = snapshot("target", TARGET_CUTOFF, 1.0, selectedSchema = driftedSchema)
        val schemaDrift = run(history(10, selectedSchema = driftedSchema), schemaTarget).forecast!!

        assertNotEquals(original.id, horizonDrift.id)
        assertNotEquals(original.id, schemaDrift.id)
        assertThrows(IllegalArgumentException::class.java) {
            ForecastFeatureSnapshot(
                id = "key-drift",
                cutoffEpochMillis = TARGET_CUTOFF,
                featureSchema = schema,
                featureValues = mapOf("cardio-autonomic" to feature("cardio-autonomic", "sim-v1", TARGET_CUTOFF, 1.0)),
                quality = 0.95,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            endpoint.copy(targetEndOffsetMillis = endpoint.targetEndOffsetMillis + HOUR)
        }
    }

    private fun run(
        cases: List<ForecastTrainingCase>,
        target: ForecastFeatureSnapshot,
        selectedEndpoint: ForecastEndpointDefinition = endpoint,
    ) = engine.forecast(
        history = cases,
        target = target,
        createdAtEpochMillis = TARGET_CUTOFF,
        endpoint = selectedEndpoint,
    )

    private fun history(
        count: Int,
        selectedEndpoint: ForecastEndpointDefinition = endpoint,
        selectedSchema: ForecastFeatureSchemaDefinition = schema,
    ): List<ForecastTrainingCase> = (0 until count).map { index ->
        val cutoff = TARGET_CUTOFF - (index + 4L) * DAY
        ForecastTrainingCase(
            caseId = "training-case-$index-${selectedEndpoint.definitionSha256.take(8)}-${selectedSchema.definitionSha256.take(8)}",
            endpoint = selectedEndpoint,
            features = snapshot("case-$index", cutoff, 0.8 + (index % 3) * 0.1, selectedSchema = selectedSchema),
            observedOutcome = if (index % 4 == 0) 0.0 else 1.0,
            resolvedAtEpochMillis = selectedEndpoint.targetEnd(cutoff),
            outcomeObservationId = "outcome-$index-${selectedEndpoint.definitionSha256.take(8)}-${selectedSchema.definitionSha256.take(8)}",
            outcomeRecordSha256 = "%064x".format(index + 1),
            verificationReceiptId = "test-receipt:$index:${selectedEndpoint.definitionSha256.take(8)}:${selectedSchema.definitionSha256.take(8)}",
        )
    }

    private fun case(id: String, cutoff: Long, value: Double, resolvedAt: Long) =
        ForecastTrainingCase(
            caseId = "training-$id",
            endpoint = endpoint,
            features = snapshot(id, cutoff, value),
            observedOutcome = 1.0,
            resolvedAtEpochMillis = resolvedAt,
            outcomeObservationId = "outcome-$id",
            outcomeRecordSha256 = sha256("outcome-$id"),
            verificationReceiptId = "test-receipt:$id",
        )

    private fun snapshot(
        id: String,
        cutoff: Long,
        value: Double,
        quality: Double = 0.95,
        selectedSchema: ForecastFeatureSchemaDefinition = schema,
    ) = ForecastFeatureSnapshot(
        id = id,
        cutoffEpochMillis = cutoff,
        featureSchema = selectedSchema,
        featureValues = selectedSchema.featureVersions.mapValues { (featureId, version) ->
            feature(
                id = featureId,
                version = version,
                cutoff = cutoff,
                value = when (featureId) {
                    "cardio-autonomic" -> value
                    "sleep" -> 0.2
                    "thermal" -> 0.1
                    else -> error("Unexpected fixture feature $featureId")
                },
            )
        },
        quality = quality,
    )

    private fun exactValues(cutoff: Long, value: Double) = schema.featureVersions.mapValues { (id, version) ->
        feature(id, version, cutoff, if (id == "cardio-autonomic") value else 0.2)
    }

    private fun feature(id: String, version: String, cutoff: Long, value: Double) =
        ForecastFeatureValue(
            featureId = id,
            featureVersion = version,
            standardizedValue = value,
            sourceWindowStartEpochMillis = cutoff - DAY,
            sourceWindowEndEpochMillis = cutoff,
            provenanceIds = listOf("fixture:$id:$cutoff"),
        )

    private fun endpointWithWindow(startHours: Long, endHours: Long) =
        ForecastEndpointDefinition.freeze(
            id = endpoint.id,
            version = endpoint.version,
            displayLabel = "$startHours-hour point assessment (+${startHours}h to +${endHours}h)",
            positiveClassDefinition = endpoint.positiveClassDefinition,
            windowSemantics = ForecastWindowSemantics.POINT_ASSESSMENT,
            targetStartOffsetMillis = startHours * HOUR,
            targetEndOffsetMillis = endHours * HOUR,
        )

    private fun schemaWithFeatures(features: Map<String, String>) =
        ForecastFeatureSchemaDefinition.freeze(
            id = schema.id,
            version = schema.version,
            featureVersions = features,
            standardizationProtocol = schema.standardizationProtocol,
        )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
        const val DAY = 24L * HOUR
        const val TARGET_CUTOFF = 100L * DAY
    }
}
