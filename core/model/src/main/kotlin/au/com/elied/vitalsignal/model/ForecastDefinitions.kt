package au.com.elied.vitalsignal.model

import java.security.MessageDigest

enum class ForecastWindowSemantics {
    /** One prespecified assessment collected inside a bounded clock window. */
    POINT_ASSESSMENT,

    /** A prespecified summary over the complete bounded interval. */
    WINDOW_AGGREGATE,
}

/**
 * Immutable, content-addressed definition of one forecast endpoint.
 *
 * The digest covers the identity, human label, positive-class definition,
 * anchor, window semantics, and both offsets. A changed definition therefore
 * becomes a different endpoint even if a caller accidentally reuses its ID.
 */
data class ForecastEndpointDefinition(
    val id: String,
    val version: String,
    val displayLabel: String,
    val positiveClassDefinition: String,
    val anchor: String,
    val windowSemantics: ForecastWindowSemantics,
    val targetStartOffsetMillis: Long,
    val targetEndOffsetMillis: Long,
    val definitionSha256: String,
) {
    init {
        require(id.matches(SAFE_ID))
        require(version.matches(SAFE_VERSION))
        require(displayLabel.isNotBlank() && displayLabel.length <= 200)
        require(positiveClassDefinition.isNotBlank() && positiveClassDefinition.length <= 1_000)
        require(anchor == FEATURE_CUTOFF_ANCHOR) {
            "Only the frozen feature-cutoff target anchor is supported"
        }
        require(targetStartOffsetMillis > 0L)
        require(targetEndOffsetMillis > targetStartOffsetMillis)
        require(definitionSha256 == calculatedDefinitionSha256()) {
            "Endpoint definition SHA-256 does not match its canonical definition"
        }
    }

    fun targetStart(cutoffEpochMillis: Long): Long = Math.addExact(
        cutoffEpochMillis,
        targetStartOffsetMillis,
    )

    fun targetEnd(cutoffEpochMillis: Long): Long = Math.addExact(
        cutoffEpochMillis,
        targetEndOffsetMillis,
    )

    private fun calculatedDefinitionSha256(): String = forecastDefinitionSha256(
        id,
        version,
        displayLabel,
        positiveClassDefinition,
        anchor,
        windowSemantics.name,
        targetStartOffsetMillis.toString(),
        targetEndOffsetMillis.toString(),
    )

    companion object {
        const val FEATURE_CUTOFF_ANCHOR: String = "FEATURE_CUTOFF_EPOCH_MILLIS"

        fun freeze(
            id: String,
            version: String,
            displayLabel: String,
            positiveClassDefinition: String,
            windowSemantics: ForecastWindowSemantics,
            targetStartOffsetMillis: Long,
            targetEndOffsetMillis: Long,
            anchor: String = FEATURE_CUTOFF_ANCHOR,
        ): ForecastEndpointDefinition {
            val digest = forecastDefinitionSha256(
                id,
                version,
                displayLabel,
                positiveClassDefinition,
                anchor,
                windowSemantics.name,
                targetStartOffsetMillis.toString(),
                targetEndOffsetMillis.toString(),
            )
            return ForecastEndpointDefinition(
                id = id,
                version = version,
                displayLabel = displayLabel,
                positiveClassDefinition = positiveClassDefinition,
                anchor = anchor,
                windowSemantics = windowSemantics,
                targetStartOffsetMillis = targetStartOffsetMillis,
                targetEndOffsetMillis = targetEndOffsetMillis,
                definitionSha256 = digest,
            )
        }
    }
}

/** A content-addressed schema; snapshots must contain this exact feature-key set. */
data class ForecastFeatureSchemaDefinition(
    val id: String,
    val version: String,
    val featureVersions: Map<String, String>,
    val standardizationProtocol: String,
    val definitionSha256: String,
) {
    val featureKeys: Set<String>
        get() = featureVersions.keys

    init {
        require(id.matches(SAFE_ID))
        require(version.matches(SAFE_VERSION))
        require(featureVersions.isNotEmpty() && featureVersions.size <= 256)
        require(featureVersions.keys.all { it.matches(SAFE_FEATURE_KEY) })
        require(featureVersions.values.all { it.matches(SAFE_VERSION) })
        require(standardizationProtocol.isNotBlank() && standardizationProtocol.length <= 1_000)
        require(definitionSha256 == calculatedDefinitionSha256()) {
            "Feature schema SHA-256 does not match its canonical definition"
        }
    }

    /**
     * Re-snapshot the feature map so a caller-owned or `copy()`-supplied map
     * cannot change the sealed key set after the digest has been checked.
     */
    fun sealedCopy(): ForecastFeatureSchemaDefinition =
        copy(featureVersions = java.util.Map.copyOf(featureVersions.toSortedMap()))

    private fun calculatedDefinitionSha256(): String = forecastDefinitionSha256(
        id,
        version,
        featureVersions.toSortedMap().entries.joinToString("\u001f") { (id, version) ->
            "$id@$version"
        },
        standardizationProtocol,
    )

    companion object {
        fun freeze(
            id: String,
            version: String,
            featureVersions: Map<String, String>,
            standardizationProtocol: String,
        ): ForecastFeatureSchemaDefinition {
            // Digest and validation must agree on order, so both sort before hashing.
            val stableVersions = featureVersions.toSortedMap()
            val digest = forecastDefinitionSha256(
                id,
                version,
                stableVersions.entries.joinToString("\u001f") { (featureId, featureVersion) ->
                    "$featureId@$featureVersion"
                },
                standardizationProtocol,
            )
            return ForecastFeatureSchemaDefinition(
                id = id,
                version = version,
                featureVersions = java.util.Map.copyOf(stableVersions),
                standardizationProtocol = standardizationProtocol,
                definitionSha256 = digest,
            )
        }
    }
}

private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,96}")
private val SAFE_VERSION = Regex("[A-Za-z0-9._-]{1,64}")
private val SAFE_FEATURE_KEY = Regex("[A-Za-z0-9._-]{1,96}")

/** Length-prefixing prevents delimiter ambiguity in definition digests. */
private fun forecastDefinitionSha256(vararg fields: String): String {
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
