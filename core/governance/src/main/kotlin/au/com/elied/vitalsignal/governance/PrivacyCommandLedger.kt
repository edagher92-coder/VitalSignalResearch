package au.com.elied.vitalsignal.governance

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

enum class PrivacyCommandType { PAUSE, EXPORT, DELETE }

enum class PrivacyTarget {
    PHONE_ENCRYPTED_STORE,
    PHONE_HISTORY_CACHE,
    PHONE_RECEIPT_DELIVERY_OUTBOX,
    WATCH_OUTBOX,
    WATCH_LOCAL_CACHE,
    WATCH_CONTINUITY_JOURNAL,
    TRANSPORT_REPLAY_STORE,
    FORECAST_AUDIT_JOURNAL,
    HUMAN_CONCERN_JOURNAL,
    MONITORING_SESSION_STORE,
    LOCAL_REASONING_INDEX,
    PROVIDER_REASONING_AUDIT,
    PERSONAL_MODEL_STATE,
    EXPORT_ARCHIVE,
    CLOUD_PROVIDER_STATE,
    OBSERVER_BACKEND,
    WATCH_COLLECTION_RUNTIME,
    PHONE_HISTORY_INGESTION_RUNTIME,
    LOCAL_REASONING_RUNTIME,
    CLOUD_REASONING_RUNTIME,
    CLINICAL_MONITORING_RUNTIME,
}

class PrivacyCommand(
    val commandId: String,
    val type: PrivacyCommandType,
    val subjectPseudonym: String,
    val consentGeneration: Long,
    val requestedAtEpochMillis: Long,
    requiredTargets: Set<PrivacyTarget>,
) {
    /** Immutable snapshot: a caller cannot rewrite command scope after acceptance. */
    val requiredTargets: Set<PrivacyTarget> = java.util.Set.copyOf(requiredTargets)

    init {
        require(commandId.isNotBlank())
        require(subjectPseudonym.isNotBlank())
        require(consentGeneration > 0L)
        require(requestedAtEpochMillis > 0L)
        require(this.requiredTargets.isNotEmpty())
        when (type) {
            PrivacyCommandType.PAUSE -> require(this.requiredTargets == REQUIRED_PAUSE_TARGETS)
            PrivacyCommandType.EXPORT -> require(this.requiredTargets == REQUIRED_EXPORT_TARGETS)
            PrivacyCommandType.DELETE -> require(this.requiredTargets == REQUIRED_DELETE_TARGETS)
        }
    }

    override fun equals(other: Any?): Boolean = other is PrivacyCommand &&
        commandId == other.commandId && type == other.type &&
        subjectPseudonym == other.subjectPseudonym &&
        consentGeneration == other.consentGeneration &&
        requestedAtEpochMillis == other.requestedAtEpochMillis &&
        requiredTargets == other.requiredTargets

    override fun hashCode(): Int = listOf(
        commandId,
        type,
        subjectPseudonym,
        consentGeneration,
        requestedAtEpochMillis,
        requiredTargets,
    ).hashCode()

    fun copy(
        commandId: String = this.commandId,
        type: PrivacyCommandType = this.type,
        subjectPseudonym: String = this.subjectPseudonym,
        consentGeneration: Long = this.consentGeneration,
        requestedAtEpochMillis: Long = this.requestedAtEpochMillis,
        requiredTargets: Set<PrivacyTarget> = this.requiredTargets,
    ) = PrivacyCommand(
        commandId,
        type,
        subjectPseudonym,
        consentGeneration,
        requestedAtEpochMillis,
        requiredTargets,
    )

    override fun toString(): String =
        "PrivacyCommand(commandId=$commandId, type=$type, subjectPseudonym=$subjectPseudonym, " +
            "consentGeneration=$consentGeneration, requestedAtEpochMillis=$requestedAtEpochMillis, " +
            "requiredTargets=$requiredTargets)"

    companion object {
        val REQUIRED_PAUSE_TARGETS: Set<PrivacyTarget> = java.util.Set.copyOf(setOf(
            PrivacyTarget.WATCH_COLLECTION_RUNTIME,
            PrivacyTarget.PHONE_HISTORY_INGESTION_RUNTIME,
            PrivacyTarget.LOCAL_REASONING_RUNTIME,
            PrivacyTarget.CLOUD_REASONING_RUNTIME,
            PrivacyTarget.CLINICAL_MONITORING_RUNTIME,
        ))
        val REQUIRED_EXPORT_TARGETS: Set<PrivacyTarget> = java.util.Set.copyOf(setOf(
            PrivacyTarget.PHONE_ENCRYPTED_STORE,
            PrivacyTarget.PHONE_HISTORY_CACHE,
            PrivacyTarget.FORECAST_AUDIT_JOURNAL,
            PrivacyTarget.HUMAN_CONCERN_JOURNAL,
            PrivacyTarget.LOCAL_REASONING_INDEX,
            PrivacyTarget.PROVIDER_REASONING_AUDIT,
            PrivacyTarget.PERSONAL_MODEL_STATE,
            PrivacyTarget.MONITORING_SESSION_STORE,
            PrivacyTarget.CLOUD_PROVIDER_STATE,
            PrivacyTarget.OBSERVER_BACKEND,
            PrivacyTarget.EXPORT_ARCHIVE,
        ))
        val REQUIRED_DELETE_TARGETS: Set<PrivacyTarget> = java.util.Set.copyOf(setOf(
            PrivacyTarget.PHONE_ENCRYPTED_STORE,
            PrivacyTarget.PHONE_HISTORY_CACHE,
            PrivacyTarget.PHONE_RECEIPT_DELIVERY_OUTBOX,
            PrivacyTarget.WATCH_OUTBOX,
            PrivacyTarget.WATCH_LOCAL_CACHE,
            PrivacyTarget.WATCH_CONTINUITY_JOURNAL,
            PrivacyTarget.TRANSPORT_REPLAY_STORE,
            PrivacyTarget.FORECAST_AUDIT_JOURNAL,
            PrivacyTarget.HUMAN_CONCERN_JOURNAL,
            PrivacyTarget.MONITORING_SESSION_STORE,
            PrivacyTarget.LOCAL_REASONING_INDEX,
            PrivacyTarget.PROVIDER_REASONING_AUDIT,
            PrivacyTarget.PERSONAL_MODEL_STATE,
            PrivacyTarget.EXPORT_ARCHIVE,
            PrivacyTarget.CLOUD_PROVIDER_STATE,
            PrivacyTarget.OBSERVER_BACKEND,
        ))
    }
}

enum class PrivacyTargetDisposition {
    PAUSED,
    EXPORTED,
    DELETED,
    CRYPTO_ERASED,
    ANONYMISED,
    NOT_PRESENT,
}

fun interface PrivacyTargetReceiptSigner {
    fun sign(
        issuerKeyId: String,
        authorizedTarget: PrivacyTarget,
        canonicalPayload: ByteArray,
    ): ByteArray
}

fun interface PrivacyTargetReceiptSignatureVerifier {
    fun verify(
        issuerKeyId: String,
        claimedTarget: PrivacyTarget,
        canonicalPayload: ByteArray,
        signature: ByteArray,
    ): Boolean
}

/**
 * Authenticated evidence that one exact privacy target processed one exact command.
 * Production signers belong in Android Keystore or a server KMS, never in the APK.
 */
class PrivacyTargetReceipt internal constructor(
    val commandId: String,
    val commandSha256: String,
    val target: PrivacyTarget,
    val consentGeneration: Long,
    val completedAtEpochMillis: Long,
    val affectedRecordCount: Long,
    val disposition: PrivacyTargetDisposition,
    val executionSha256: String,
    val issuerKeyId: String,
    signature: ByteArray,
) {
    private val signatureValue = signature.copyOf()

    init {
        require(commandId.isNotBlank())
        require(commandSha256.matches(SHA_256))
        require(consentGeneration > 0L)
        require(completedAtEpochMillis > 0L)
        require(affectedRecordCount >= 0L)
        require(executionSha256.matches(SHA_256))
        require(issuerKeyId.matches(SAFE_KEY_ID))
        require(signatureValue.size in 16..512)
    }

    fun signatureBytes(): ByteArray = signatureValue.copyOf()

    override fun equals(other: Any?): Boolean = other is PrivacyTargetReceipt &&
        commandId == other.commandId && commandSha256 == other.commandSha256 &&
        target == other.target && consentGeneration == other.consentGeneration &&
        completedAtEpochMillis == other.completedAtEpochMillis &&
        affectedRecordCount == other.affectedRecordCount && disposition == other.disposition &&
        executionSha256 == other.executionSha256 && issuerKeyId == other.issuerKeyId &&
        MessageDigest.isEqual(signatureValue, other.signatureValue)

    override fun hashCode(): Int = listOf(
        commandId, commandSha256, target, consentGeneration, completedAtEpochMillis,
        affectedRecordCount, disposition, executionSha256, issuerKeyId,
        signatureValue.contentHashCode(),
    ).hashCode()
}

class PrivacyTargetReceiptIssuer(
    private val issuerKeyId: String,
    private val authorizedTarget: PrivacyTarget,
    private val signer: PrivacyTargetReceiptSigner,
) {
    init { require(issuerKeyId.matches(SAFE_KEY_ID)) }

    fun issue(
        command: PrivacyCommand,
        target: PrivacyTarget,
        completedAtEpochMillis: Long,
        affectedRecordCount: Long,
        disposition: PrivacyTargetDisposition,
        executionSha256: String,
    ): PrivacyTargetReceipt {
        require(target == authorizedTarget) { "Privacy authority may sign only its exact target" }
        require(target in command.requiredTargets)
        val unsigned = PrivacyTargetReceipt(
            command.commandId,
            privacyCommandSha256(command),
            target,
            command.consentGeneration,
            completedAtEpochMillis,
            affectedRecordCount,
            disposition,
            executionSha256,
            issuerKeyId,
            ByteArray(32),
        )
        return PrivacyTargetReceipt(
            unsigned.commandId,
            unsigned.commandSha256,
            unsigned.target,
            unsigned.consentGeneration,
            unsigned.completedAtEpochMillis,
            unsigned.affectedRecordCount,
            unsigned.disposition,
            unsigned.executionSha256,
            unsigned.issuerKeyId,
            signer.sign(
                issuerKeyId,
                authorizedTarget,
                canonicalPrivacyReceipt(unsigned).copyOf(),
            ),
        )
    }
}

enum class PrivacyCommandState { PENDING, PARTIAL, COMPLETE, CONFLICT }

data class PrivacyCommandView(
    val command: PrivacyCommand,
    val state: PrivacyCommandState,
    val completedTargets: Set<PrivacyTarget>,
    val pendingTargets: Set<PrivacyTarget>,
    val receipts: List<PrivacyTargetReceipt>,
)

/** Append-only coordination model; durable persistence belongs to core:storage. */
class PrivacyCommandLedger(
    private val receiptVerifier: PrivacyTargetReceiptSignatureVerifier =
        PrivacyTargetReceiptSignatureVerifier { _, _, _, _ -> false },
) {
    private data class Entry(
        val command: PrivacyCommand,
        val receiptsByTarget: LinkedHashMap<PrivacyTarget, PrivacyTargetReceipt> = linkedMapOf(),
        var conflict: Boolean = false,
    )

    private val entries = linkedMapOf<String, Entry>()

    fun request(command: PrivacyCommand): PrivacyCommandView {
        val existing = entries[command.commandId]
        if (existing != null) {
            if (existing.command != command) existing.conflict = true
            return view(existing)
        }
        return Entry(command).also { entries[command.commandId] = it }.let(::view)
    }

    fun record(receipt: PrivacyTargetReceipt): PrivacyCommandView {
        val entry = requireNotNull(entries[receipt.commandId]) { "Unknown privacy command" }
        val validSignature = try {
            receiptVerifier.verify(
                receipt.issuerKeyId,
                receipt.target,
                canonicalPrivacyReceipt(receipt).copyOf(),
                receipt.signatureBytes(),
            )
        } catch (_: RuntimeException) {
            false
        }
        if (
            receipt.commandSha256 != privacyCommandSha256(entry.command) ||
            !validSignature || receipt.target !in entry.command.requiredTargets ||
            receipt.consentGeneration != entry.command.consentGeneration ||
            receipt.completedAtEpochMillis < entry.command.requestedAtEpochMillis ||
            !dispositionMatches(entry.command.type, receipt.disposition)
        ) {
            entry.conflict = true
            return view(entry)
        }
        val prior = entry.receiptsByTarget[receipt.target]
        if (prior == null) entry.receiptsByTarget[receipt.target] = receipt
        else if (prior != receipt) entry.conflict = true
        return view(entry)
    }

    fun get(commandId: String): PrivacyCommandView? = entries[commandId]?.let(::view)

    private fun view(entry: Entry): PrivacyCommandView {
        val completed = java.util.Set.copyOf(entry.receiptsByTarget.keys)
        val pending = java.util.Set.copyOf(entry.command.requiredTargets - completed)
        val state = when {
            entry.conflict -> PrivacyCommandState.CONFLICT
            pending.isEmpty() -> PrivacyCommandState.COMPLETE
            completed.isEmpty() -> PrivacyCommandState.PENDING
            else -> PrivacyCommandState.PARTIAL
        }
        return PrivacyCommandView(
            entry.command,
            state,
            completed,
            pending,
            java.util.List.copyOf(entry.receiptsByTarget.values),
        )
    }
}

private fun dispositionMatches(type: PrivacyCommandType, disposition: PrivacyTargetDisposition) =
    when (type) {
        PrivacyCommandType.PAUSE -> disposition == PrivacyTargetDisposition.PAUSED ||
            disposition == PrivacyTargetDisposition.NOT_PRESENT
        PrivacyCommandType.EXPORT -> disposition == PrivacyTargetDisposition.EXPORTED ||
            disposition == PrivacyTargetDisposition.NOT_PRESENT
        PrivacyCommandType.DELETE -> disposition in setOf(
            PrivacyTargetDisposition.DELETED,
            PrivacyTargetDisposition.CRYPTO_ERASED,
            PrivacyTargetDisposition.ANONYMISED,
            PrivacyTargetDisposition.NOT_PRESENT,
        )
    }

internal fun privacyCommandSha256(command: PrivacyCommand): String =
    sha256Hex(canonicalPrivacyCommand(command))

private fun canonicalPrivacyCommand(command: PrivacyCommand): ByteArray = canonicalBytes {
    writeField(command.commandId)
    writeField(command.type.name)
    writeField(command.subjectPseudonym)
    writeLong(command.consentGeneration)
    writeLong(command.requestedAtEpochMillis)
    val targets = command.requiredTargets.map(PrivacyTarget::name).sorted()
    writeInt(targets.size)
    targets.forEach(::writeField)
}

internal fun canonicalPrivacyReceipt(receipt: PrivacyTargetReceipt): ByteArray = canonicalBytes {
    writeField(receipt.commandId)
    writeField(receipt.commandSha256)
    writeField(receipt.target.name)
    writeLong(receipt.consentGeneration)
    writeLong(receipt.completedAtEpochMillis)
    writeLong(receipt.affectedRecordCount)
    writeField(receipt.disposition.name)
    writeField(receipt.executionSha256)
    writeField(receipt.issuerKeyId)
}

private fun canonicalBytes(block: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output -> output.block() }
        buffer.toByteArray()
    }

private fun DataOutputStream.writeField(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

private val SHA_256 = Regex("[a-f0-9]{64}")
private val SAFE_KEY_ID = Regex("[A-Za-z0-9._:-]{3,128}")
