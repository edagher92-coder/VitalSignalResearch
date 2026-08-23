package au.com.elied.vitalsignal.storage

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small, encrypted record suitable for the private-pilot persistence boundary.
 *
 * The payload is deliberately opaque to this module. It is never logged or placed in a filename.
 * Callers are responsible for using a versioned content type and for avoiding direct identifiers.
 */
class LocalEncryptedRecord(
    val recordId: String,
    val sequence: Long,
    val createdEpochMillis: Long,
    val contentType: String,
    payload: ByteArray,
) {
    private val payloadBytes = payload.copyOf()

    init {
        require(recordId.matches(RECORD_ID_PATTERN)) {
            "recordId must contain 1..$MAX_RECORD_ID_BYTES safe ASCII characters"
        }
        require(sequence >= FIRST_SEQUENCE) { "sequence must be at least $FIRST_SEQUENCE" }
        require(createdEpochMillis >= 0L) { "createdEpochMillis cannot be negative" }
        require(contentType.isNotBlank()) { "contentType cannot be blank" }
        require(contentType.toByteArray(StandardCharsets.UTF_8).size <= MAX_CONTENT_TYPE_BYTES) {
            "contentType exceeds $MAX_CONTENT_TYPE_BYTES UTF-8 bytes"
        }
        require(payloadBytes.size <= ABSOLUTE_MAX_PAYLOAD_BYTES) {
            "payload exceeds the absolute $ABSOLUTE_MAX_PAYLOAD_BYTES-byte limit"
        }
    }

    fun payloadCopy(): ByteArray = payloadBytes.copyOf()

    internal fun safeCopy(): LocalEncryptedRecord = LocalEncryptedRecord(
        recordId = recordId,
        sequence = sequence,
        createdEpochMillis = createdEpochMillis,
        contentType = contentType,
        payload = payloadBytes,
    )

    internal fun payloadUnsafe(): ByteArray = payloadBytes

    companion object {
        const val FIRST_SEQUENCE = 1L
        const val MAX_RECORD_ID_BYTES = 96
        const val MAX_CONTENT_TYPE_BYTES = 128
        const val ABSOLUTE_MAX_PAYLOAD_BYTES = 1_048_576

        private val RECORD_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,$MAX_RECORD_ID_BYTES}")
    }
}

data class AcceptedRecord(
    val record: LocalEncryptedRecord,
    val fileName: String,
)

enum class RecoveryQuarantineReason {
    INVALID_FILENAME,
    FILE_TOO_LARGE,
    TRUNCATED_OR_CORRUPT,
    WRONG_KEY,
    AUTHENTICATION_FAILED,
    INVALID_PLAINTEXT,
    FILENAME_MISMATCH,
    OUT_OF_SEQUENCE,
    DUPLICATE_RECORD_ID,
}

data class QuarantinedStoredFile(
    val fileName: String,
    val reason: RecoveryQuarantineReason,
    /** Deliberately generic: never contains decrypted record content. */
    val detail: String,
)

data class StorageRecoveryReport(
    val accepted: List<AcceptedRecord>,
    val quarantined: List<QuarantinedStoredFile>,
    val ignoredTemporaryFiles: List<String>,
) {
    val canAppend: Boolean get() = quarantined.isEmpty()
}

enum class AppendQuarantineReason {
    REPLAY_CONFLICT,
    OUT_OF_SEQUENCE,
    PAYLOAD_TOO_LARGE,
    RECOVERY_BLOCKED,
}

sealed interface StorageAppendResult {
    data class Accepted(val acceptedRecord: AcceptedRecord) : StorageAppendResult
    data class Duplicate(
        val canonicalRecordId: String,
        val canonicalSequence: Long,
        val canonicalFileName: String,
    ) : StorageAppendResult

    data class Quarantined(
        val recordId: String,
        val sequence: Long,
        val reason: AppendQuarantineReason,
        /** Deliberately generic: never contains record payload or content type. */
        val detail: String,
    ) : StorageAppendResult
}

/**
 * Pure JVM, append-only AES-GCM record store.
 *
 * Security and durability properties:
 * - every committed record is authenticated with AES-GCM;
 * - the key ID and full envelope header are authenticated as additional data;
 * - filenames are deterministic but contain only a sequence and a record-ID hash;
 * - a record is written and fsynced to a temporary file, then atomically renamed;
 * - recovery accepts only a gap-free sequence beginning at one;
 * - corrupt, tampered, or wrong-key files are reported and block future appends;
 * - incomplete `.tmp` files are ignored during recovery and never treated as records.
 *
 * This class synchronizes access within one instance and refreshes from disk before every append.
 * A production multi-process integration must additionally provide a process-wide file lock.
 */
class EncryptedAppendOnlyRecordStore(
    private val rootDirectory: Path,
    private val secretKey: SecretKey,
    private val keyId: String,
    private val secureRandom: SecureRandom,
    private val maxPayloadBytes: Int = LocalEncryptedRecord.ABSOLUTE_MAX_PAYLOAD_BYTES,
) {
    private val recordsDirectory = rootDirectory.resolve(RECORDS_DIRECTORY_NAME)
    private var latestReport: StorageRecoveryReport

    init {
        require(secretKey.algorithm.equals("AES", ignoreCase = true)) { "secretKey must use AES" }
        require(keyId.matches(KEY_ID_PATTERN)) {
            "keyId must contain 1..$MAX_KEY_ID_BYTES safe ASCII characters"
        }
        require(maxPayloadBytes in 1..LocalEncryptedRecord.ABSOLUTE_MAX_PAYLOAD_BYTES) {
            "maxPayloadBytes must be within the absolute payload limit"
        }
        Files.createDirectories(recordsDirectory)
        latestReport = scanDisk()
    }

    @Synchronized
    fun recoveryReport(): StorageRecoveryReport = copyReport(latestReport)

    @Synchronized
    fun recover(): StorageRecoveryReport {
        latestReport = scanDisk()
        return copyReport(latestReport)
    }

    @Synchronized
    fun append(record: LocalEncryptedRecord): StorageAppendResult {
        latestReport = scanDisk()

        if (!latestReport.canAppend) {
            return record.quarantined(
                AppendQuarantineReason.RECOVERY_BLOCKED,
                "Stored record verification failed; recovery must be resolved before appending",
            )
        }

        val existing = latestReport.accepted.firstOrNull { it.record.recordId == record.recordId }
        if (existing != null) {
            return if (MessageDigest.isEqual(fingerprint(existing.record), fingerprint(record))) {
                StorageAppendResult.Duplicate(
                    canonicalRecordId = existing.record.recordId,
                    canonicalSequence = existing.record.sequence,
                    canonicalFileName = existing.fileName,
                )
            } else {
                record.quarantined(
                    AppendQuarantineReason.REPLAY_CONFLICT,
                    "Record ID was reused with different authenticated content",
                )
            }
        }

        if (record.payloadUnsafe().size > maxPayloadBytes) {
            return record.quarantined(
                AppendQuarantineReason.PAYLOAD_TOO_LARGE,
                "Payload exceeds this store's configured limit",
            )
        }

        val expectedSequence = latestReport.accepted.lastOrNull()
            ?.record
            ?.sequence
            ?.plus(1L)
            ?: LocalEncryptedRecord.FIRST_SEQUENCE
        if (record.sequence != expectedSequence) {
            return record.quarantined(
                AppendQuarantineReason.OUT_OF_SEQUENCE,
                "Expected the next consecutive sequence",
            )
        }

        val finalFileName = deterministicFileName(record.sequence, record.recordId)
        val finalPath = recordsDirectory.resolve(finalFileName)
        val envelope = encrypt(record)
        var temporaryPath: Path? = null

        try {
            temporaryPath = Files.createTempFile(recordsDirectory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX)
            FileChannel.open(temporaryPath, WRITE).use { channel ->
                val remaining = ByteBuffer.wrap(envelope)
                while (remaining.hasRemaining()) channel.write(remaining)
                channel.force(true)
            }
            moveAtomicallyWithoutReplacement(temporaryPath, finalPath)
            temporaryPath = null
            forceDirectoryBestEffort(recordsDirectory)
        } finally {
            temporaryPath?.let { Files.deleteIfExists(it) }
        }

        latestReport = scanDisk()
        val accepted = latestReport.accepted.firstOrNull { it.fileName == finalFileName }
            ?: error("Committed record did not pass immediate authenticated recovery")
        return StorageAppendResult.Accepted(copyAccepted(accepted))
    }

    private fun scanDisk(): StorageRecoveryReport {
        Files.createDirectories(recordsDirectory)
        val accepted = mutableListOf<AcceptedRecord>()
        val quarantined = mutableListOf<QuarantinedStoredFile>()
        val ignoredTemporary = mutableListOf<String>()
        val candidates = mutableListOf<CandidateFile>()

        Files.list(recordsDirectory).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val name = path.fileName.toString()
                if (name.startsWith(TEMPORARY_PREFIX) && name.endsWith(TEMPORARY_SUFFIX)) {
                    ignoredTemporary += name
                } else {
                    val match = FINAL_FILENAME_PATTERN.matchEntire(name)
                    if (match == null) {
                        quarantined += QuarantinedStoredFile(
                            name,
                            RecoveryQuarantineReason.INVALID_FILENAME,
                            "Unrecognized committed filename",
                        )
                    } else {
                        val sequenceHint = match.groupValues[1].toLongOrNull()
                        if (sequenceHint == null) {
                            quarantined += QuarantinedStoredFile(
                                name,
                                RecoveryQuarantineReason.INVALID_FILENAME,
                                "Filename sequence is invalid",
                            )
                        } else {
                            candidates += CandidateFile(path, name, sequenceHint)
                        }
                    }
                }
            }
        }

        candidates.sortedWith(compareBy<CandidateFile> { it.sequenceHint }.thenBy { it.fileName })
            .forEach { candidate ->
                val decoded = decodeCandidate(candidate)
                if (decoded is DecodedCandidate.Failure) {
                    quarantined += QuarantinedStoredFile(
                        candidate.fileName,
                        decoded.reason,
                        decoded.detail,
                    )
                    return@forEach
                }

                decoded as DecodedCandidate.Success
                val record = decoded.record
                val expectedFileName = deterministicFileName(record.sequence, record.recordId)
                if (candidate.fileName != expectedFileName || candidate.sequenceHint != record.sequence) {
                    quarantined += QuarantinedStoredFile(
                        candidate.fileName,
                        RecoveryQuarantineReason.FILENAME_MISMATCH,
                        "Authenticated record metadata does not match its filename",
                    )
                    return@forEach
                }

                if (accepted.any { it.record.recordId == record.recordId }) {
                    quarantined += QuarantinedStoredFile(
                        candidate.fileName,
                        RecoveryQuarantineReason.DUPLICATE_RECORD_ID,
                        "Authenticated record ID is already committed",
                    )
                    return@forEach
                }

                val expectedSequence = accepted.lastOrNull()
                    ?.record
                    ?.sequence
                    ?.plus(1L)
                    ?: LocalEncryptedRecord.FIRST_SEQUENCE
                if (record.sequence != expectedSequence) {
                    quarantined += QuarantinedStoredFile(
                        candidate.fileName,
                        RecoveryQuarantineReason.OUT_OF_SEQUENCE,
                        "Authenticated sequence is not the next consecutive value",
                    )
                    return@forEach
                }

                accepted += AcceptedRecord(
                    record = record,
                    fileName = candidate.fileName,
                )
            }

        return StorageRecoveryReport(
            accepted = accepted.map(::copyAccepted),
            quarantined = quarantined.sortedBy { it.fileName },
            ignoredTemporaryFiles = ignoredTemporary.sorted(),
        )
    }

    private fun decodeCandidate(candidate: CandidateFile): DecodedCandidate {
        val size = runCatching { Files.size(candidate.path) }.getOrElse {
            return DecodedCandidate.Failure(
                RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                "Committed file could not be read",
            )
        }
        if (size > maximumEnvelopeBytes()) {
            return DecodedCandidate.Failure(
                RecoveryQuarantineReason.FILE_TOO_LARGE,
                "Committed file exceeds configured bounds",
            )
        }
        if (size < MINIMUM_ENVELOPE_BYTES) {
            return DecodedCandidate.Failure(
                RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                "Committed file is truncated",
            )
        }

        val bytes = runCatching { Files.readAllBytes(candidate.path) }.getOrElse {
            return DecodedCandidate.Failure(
                RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                "Committed file could not be read",
            )
        }
        val parsed = parseEnvelope(bytes)
        if (parsed is ParsedEnvelope.Failure) return DecodedCandidate.Failure(parsed.reason, parsed.detail)
        parsed as ParsedEnvelope.Success

        if (parsed.keyId != keyId) {
            return DecodedCandidate.Failure(
                RecoveryQuarantineReason.WRONG_KEY,
                "Committed record belongs to a different key ID",
            )
        }

        val plaintext = try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, parsed.nonce))
            cipher.updateAAD(parsed.authenticatedHeader)
            cipher.doFinal(parsed.ciphertext)
        } catch (_: AEADBadTagException) {
            return DecodedCandidate.Failure(
                RecoveryQuarantineReason.AUTHENTICATION_FAILED,
                "Ciphertext authentication failed",
            )
        } catch (_: Exception) {
            return DecodedCandidate.Failure(
                RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                "Committed envelope could not be decrypted",
            )
        }

        return when (val decoded = decodePlaintext(plaintext)) {
            is PlaintextDecode.Failure -> DecodedCandidate.Failure(
                RecoveryQuarantineReason.INVALID_PLAINTEXT,
                decoded.detail,
            )
            is PlaintextDecode.Success -> DecodedCandidate.Success(decoded.record)
        }
    }

    private fun encrypt(record: LocalEncryptedRecord): ByteArray {
        val plaintext = encodePlaintext(record)
        val nonce = ByteArray(GCM_NONCE_BYTES).also(secureRandom::nextBytes)
        val keyIdBytes = keyId.toByteArray(StandardCharsets.US_ASCII)
        val ciphertextLength = plaintext.size + GCM_TAG_BYTES
        val header = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.write(ENVELOPE_MAGIC)
                data.writeByte(ENVELOPE_VERSION)
                data.writeByte(keyIdBytes.size)
                data.write(keyIdBytes)
                data.writeByte(nonce.size)
                data.write(nonce)
                data.writeInt(ciphertextLength)
            }
            output.toByteArray()
        }

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce), secureRandom)
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(plaintext)
        check(ciphertext.size == ciphertextLength)
        return header + ciphertext
    }

    private fun parseEnvelope(bytes: ByteArray): ParsedEnvelope {
        try {
            val buffer = ByteBuffer.wrap(bytes)
            val magic = ByteArray(ENVELOPE_MAGIC.size).also(buffer::get)
            if (!magic.contentEquals(ENVELOPE_MAGIC)) {
                return ParsedEnvelope.Failure(
                    RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                    "Envelope magic is invalid",
                )
            }
            val version = buffer.get().toInt() and 0xff
            if (version != ENVELOPE_VERSION) {
                return ParsedEnvelope.Failure(
                    RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                    "Envelope version is unsupported",
                )
            }
            val keyIdLength = buffer.get().toInt() and 0xff
            if (keyIdLength !in 1..MAX_KEY_ID_BYTES || buffer.remaining() < keyIdLength + 1) {
                return ParsedEnvelope.Failure(
                    RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                    "Envelope key ID length is invalid",
                )
            }
            val storedKeyIdBytes = ByteArray(keyIdLength).also(buffer::get)
            val storedKeyId = decodeUtf8Strict(storedKeyIdBytes)
                ?: return ParsedEnvelope.Failure(
                    RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                    "Envelope key ID encoding is invalid",
                )
            val nonceLength = buffer.get().toInt() and 0xff
            if (nonceLength != GCM_NONCE_BYTES || buffer.remaining() < nonceLength + Int.SIZE_BYTES) {
                return ParsedEnvelope.Failure(
                    RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                    "Envelope nonce length is invalid",
                )
            }
            val nonce = ByteArray(nonceLength).also(buffer::get)
            val ciphertextLength = buffer.getInt()
            if (ciphertextLength < GCM_TAG_BYTES || ciphertextLength != buffer.remaining()) {
                return ParsedEnvelope.Failure(
                    RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                    "Envelope ciphertext length is invalid",
                )
            }
            val headerLength = bytes.size - ciphertextLength
            val ciphertext = ByteArray(ciphertextLength).also(buffer::get)
            return ParsedEnvelope.Success(
                keyId = storedKeyId,
                nonce = nonce,
                authenticatedHeader = bytes.copyOfRange(0, headerLength),
                ciphertext = ciphertext,
            )
        } catch (_: Exception) {
            return ParsedEnvelope.Failure(
                RecoveryQuarantineReason.TRUNCATED_OR_CORRUPT,
                "Envelope structure is invalid",
            )
        }
    }

    private fun encodePlaintext(record: LocalEncryptedRecord): ByteArray {
        val id = record.recordId.toByteArray(StandardCharsets.US_ASCII)
        val contentType = record.contentType.toByteArray(StandardCharsets.UTF_8)
        val payload = record.payloadUnsafe()
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.write(PLAINTEXT_MAGIC)
                data.writeByte(PLAINTEXT_VERSION)
                data.writeByte(id.size)
                data.write(id)
                data.writeLong(record.sequence)
                data.writeLong(record.createdEpochMillis)
                data.writeShort(contentType.size)
                data.write(contentType)
                data.writeInt(payload.size)
                data.write(payload)
            }
            output.toByteArray()
        }
    }

    private fun decodePlaintext(bytes: ByteArray): PlaintextDecode {
        try {
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.remaining() < MINIMUM_PLAINTEXT_BYTES) {
                return PlaintextDecode.Failure("Authenticated plaintext is truncated")
            }
            val magic = ByteArray(PLAINTEXT_MAGIC.size).also(buffer::get)
            if (!magic.contentEquals(PLAINTEXT_MAGIC)) {
                return PlaintextDecode.Failure("Authenticated plaintext magic is invalid")
            }
            if ((buffer.get().toInt() and 0xff) != PLAINTEXT_VERSION) {
                return PlaintextDecode.Failure("Authenticated plaintext version is unsupported")
            }
            val idLength = buffer.get().toInt() and 0xff
            if (idLength !in 1..LocalEncryptedRecord.MAX_RECORD_ID_BYTES || buffer.remaining() < idLength) {
                return PlaintextDecode.Failure("Authenticated record ID length is invalid")
            }
            val id = String(ByteArray(idLength).also(buffer::get), StandardCharsets.US_ASCII)
            if (!id.matches(RECORD_ID_PATTERN)) {
                return PlaintextDecode.Failure("Authenticated record ID is invalid")
            }
            if (buffer.remaining() < Long.SIZE_BYTES * 2 + Short.SIZE_BYTES) {
                return PlaintextDecode.Failure("Authenticated record metadata is truncated")
            }
            val sequence = buffer.getLong()
            val createdEpochMillis = buffer.getLong()
            val contentTypeLength = buffer.getShort().toInt() and 0xffff
            if (contentTypeLength !in 1..LocalEncryptedRecord.MAX_CONTENT_TYPE_BYTES ||
                buffer.remaining() < contentTypeLength + Int.SIZE_BYTES
            ) {
                return PlaintextDecode.Failure("Authenticated content type length is invalid")
            }
            val contentType = decodeUtf8Strict(ByteArray(contentTypeLength).also(buffer::get))
                ?: return PlaintextDecode.Failure("Authenticated content type encoding is invalid")
            val payloadLength = buffer.getInt()
            if (payloadLength !in 0..maxPayloadBytes || payloadLength != buffer.remaining()) {
                return PlaintextDecode.Failure("Authenticated payload length is invalid")
            }
            val payload = ByteArray(payloadLength).also(buffer::get)
            val record = runCatching {
                LocalEncryptedRecord(id, sequence, createdEpochMillis, contentType, payload)
            }.getOrElse {
                return PlaintextDecode.Failure("Authenticated record fields are invalid")
            }
            return PlaintextDecode.Success(record)
        } catch (_: Exception) {
            return PlaintextDecode.Failure("Authenticated plaintext structure is invalid")
        }
    }

    private fun fingerprint(record: LocalEncryptedRecord): ByteArray {
        val contentType = record.contentType.toByteArray(StandardCharsets.UTF_8)
        val payload = record.payloadUnsafe()
        return MessageDigest.getInstance("SHA-256").run {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(contentType.size).array())
            update(contentType)
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(payload.size).array())
            digest(payload)
        }
    }

    private fun deterministicFileName(sequence: Long, recordId: String): String {
        val idHash = MessageDigest.getInstance("SHA-256")
            .digest(recordId.toByteArray(StandardCharsets.US_ASCII))
            .toHex()
            .take(FILE_ID_HASH_HEX_CHARS)
        return "record-${sequence.toString().padStart(SEQUENCE_DIGITS, '0')}-$idHash$FINAL_SUFFIX"
    }

    private fun maximumEnvelopeBytes(): Long =
        (MAX_ENVELOPE_FIXED_OVERHEAD + MAX_KEY_ID_BYTES + maxPayloadBytes).toLong()

    private fun moveAtomicallyWithoutReplacement(source: Path, target: Path) {
        // Fail closed if the provider cannot guarantee atomic publication. A non-atomic fallback
        // could expose a partial file as committed after process or power loss.
        Files.move(source, target, ATOMIC_MOVE)
    }

    private fun forceDirectoryBestEffort(directory: Path) {
        runCatching { FileChannel.open(directory, READ).use { it.force(true) } }
    }

    private fun copyReport(report: StorageRecoveryReport): StorageRecoveryReport = StorageRecoveryReport(
        accepted = report.accepted.map(::copyAccepted),
        quarantined = report.quarantined.toList(),
        ignoredTemporaryFiles = report.ignoredTemporaryFiles.toList(),
    )

    private fun copyAccepted(accepted: AcceptedRecord): AcceptedRecord = accepted.copy(
        record = accepted.record.safeCopy(),
    )

    private fun LocalEncryptedRecord.quarantined(
        reason: AppendQuarantineReason,
        detail: String,
    ): StorageAppendResult.Quarantined = StorageAppendResult.Quarantined(
        recordId = recordId,
        sequence = sequence,
        reason = reason,
        detail = detail,
    )

    private data class CandidateFile(val path: Path, val fileName: String, val sequenceHint: Long)

    private sealed interface DecodedCandidate {
        data class Success(val record: LocalEncryptedRecord) : DecodedCandidate
        data class Failure(val reason: RecoveryQuarantineReason, val detail: String) : DecodedCandidate
    }

    private sealed interface ParsedEnvelope {
        data class Success(
            val keyId: String,
            val nonce: ByteArray,
            val authenticatedHeader: ByteArray,
            val ciphertext: ByteArray,
        ) : ParsedEnvelope

        data class Failure(val reason: RecoveryQuarantineReason, val detail: String) : ParsedEnvelope
    }

    private sealed interface PlaintextDecode {
        data class Success(val record: LocalEncryptedRecord) : PlaintextDecode
        data class Failure(val detail: String) : PlaintextDecode
    }

    companion object {
        private const val RECORDS_DIRECTORY_NAME = "records"
        private const val FINAL_SUFFIX = ".vsr"
        private const val TEMPORARY_PREFIX = ".pending-"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val SEQUENCE_DIGITS = 20
        private const val FILE_ID_HASH_HEX_CHARS = 24
        private const val MAX_KEY_ID_BYTES = 64
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENVELOPE_VERSION = 1
        private const val PLAINTEXT_VERSION = 1
        private const val MAX_ENVELOPE_FIXED_OVERHEAD = 512
        private const val MINIMUM_ENVELOPE_BYTES = 4 + 1 + 1 + 1 + GCM_NONCE_BYTES + 4 + GCM_TAG_BYTES
        private const val MINIMUM_PLAINTEXT_BYTES = 4 + 1 + 1 + 1 + 8 + 8 + 2 + 1 + 4
        private val ENVELOPE_MAGIC = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), '3'.code.toByte())
        private val PLAINTEXT_MAGIC = byteArrayOf('V'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), '3'.code.toByte())
        private val KEY_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,$MAX_KEY_ID_BYTES}")
        private val RECORD_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,${LocalEncryptedRecord.MAX_RECORD_ID_BYTES}}")
        private val FINAL_FILENAME_PATTERN = Regex(
            "record-([0-9]{$SEQUENCE_DIGITS})-([a-f0-9]{$FILE_ID_HASH_HEX_CHARS})\\${FINAL_SUFFIX}",
        )

        private fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()

        private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
