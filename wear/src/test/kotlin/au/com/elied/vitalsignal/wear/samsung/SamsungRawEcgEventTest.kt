package au.com.elied.vitalsignal.wear.samsung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungRawEcgEventTest {
    @Test
    fun fivePointCallbackPreservesPpgSequenceLeadThresholdsAndTimestamps() {
        val event = event(sequence = 18, startEpochMillis = 10_000L)

        assertEquals(18, event.sequence)
        assertEquals(listOf(10_000L to 900), event.embeddedGreenPpgSamples)
        assertFalse(event.hasLeadOff)
        assertFalse(event.hasSaturation)
        assertEquals(7L, event.provenance.consentGeneration)
        assertEquals("validation-watch-1", event.provenance.validationReceiptId)
    }

    @Test
    fun tenPointCallbackRequiresAndPreservesSecondEmbeddedPpgSample() {
        val event = event(sequence = 19, startEpochMillis = 10_010L, pointCount = 10)

        assertEquals(
            listOf(10_010L to 900, 10_020L to 905),
            event.embeddedGreenPpgSamples,
        )
    }

    @Test
    fun thresholdExcursionIsRetainedAsSaturationWithoutDiscardingRawValue() {
        val base = event(sequence = 20, startEpochMillis = 10_100L)
        val saturated = base.copy(
            points = base.points.toMutableList().also { points ->
                points[0] = points[0].copy(ecgMillivolts = 1.5)
            },
        )

        assertTrue(saturated.hasSaturation)
        assertEquals(1.5, saturated.points.first().ecgMillivolts, 0.0)
    }

    @Test
    fun sequenceInspectionAcceptsDocumentedByteRolloverAndFindsGaps() {
        val rollover = listOf(
            event(255, 10_000L),
            event(0, 10_010L),
        )
        val gap = listOf(
            event(5, 20_000L),
            event(8, 20_010L),
        )

        assertTrue(SamsungEcgSequenceInspector.inspect(rollover).continuous)
        assertEquals(1, SamsungEcgSequenceInspector.inspect(gap).discontinuities)
    }

    @Test
    fun crossModalTimingStaysLockedUntilPhysicalReferenceAlignment() {
        val unvalidated = listOf(
            event(1, 10_000L),
            event(2, 10_010L),
        )
        val validated = unvalidated.map { event -> event.copy(
            timingValidationState = EcgTimingValidationState.REFERENCE_ALIGNED,
            referenceSessionId = "reference-ecg-1",
            clockAlignmentResidualMillis = 2.0,
        ) }

        assertFalse(SamsungEcgTimingUsePolicy.canUseForExperimentalCrossModalTiming(unvalidated))
        assertTrue(SamsungEcgTimingUsePolicy.canUseForExperimentalCrossModalTiming(validated))
    }

    @Test
    fun malformedCallbackLayoutIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            event(1, 10_000L).copy(points = event(1, 10_000L).points.take(4))
        }
    }

    @Test
    fun callbackPointsAreSnapshottedAgainstAdapterMutation() {
        val original = event(23, 30_000L)
        val mutablePoints = original.points.toMutableList()
        val snapshotted = original.copy(points = mutablePoints)

        mutablePoints[0] = mutablePoints[0].copy(rawSequence = 99, ecgMillivolts = 0.75)

        assertEquals(23, snapshotted.sequence)
        assertEquals(0.0, snapshotted.points.first().ecgMillivolts, 0.0)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshotted.points as MutableList<SamsungRawEcgPoint>).clear()
        }
    }

    private fun event(
        sequence: Int,
        startEpochMillis: Long,
        pointCount: Int = 5,
    ): SamsungRawEcgEvent {
        val points = (0 until pointCount).map { index ->
            SamsungRawEcgPoint(
                sourceTimestampEpochMillis = startEpochMillis + index * 2L,
                ecgMillivolts = index / 100.0,
                embeddedGreenPpgRaw = when (index) {
                    0 -> 900
                    5 -> 905
                    else -> null
                },
                rawSequence = if (index == 0) sequence else null,
                rawLeadOff = if (index == 0) 0 else null,
                minimumThresholdMillivolts = if (index == 0) -1.0 else null,
                maximumThresholdMillivolts = if (index == 0) 1.0 else null,
            )
        }
        return SamsungRawEcgEvent(
            captureSessionId = "ecg-session-1",
            callbackOrdinal = sequence.toLong(),
            receivedAtEpochMillis = startEpochMillis + 100L,
            receivedAtElapsedRealtimeNanos = startEpochMillis * 1_000_000L,
            source = SamsungEcgSourceIdentity(
                watchModel = "Galaxy Watch Ultra2 fixture",
                firmwareVersion = "fixture-fw-1",
                sensorSdkVersion = "1.4.1",
                appVersion = "0.5.0-research",
            ),
            provenance = SamsungEcgCollectionProvenance(
                participantPseudonym = "participant-1",
                protocolId = "pilot-protocol-1",
                consentGeneration = 7L,
                validationReceiptId = "validation-watch-1",
            ),
            points = points,
        )
    }
}
