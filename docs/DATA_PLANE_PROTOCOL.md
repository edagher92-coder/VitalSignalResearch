# VitalSignal v0.5 data-plane protocol

Status: implementation contract for the simulator-first research checkpoint. This document describes the source that exists in `core/transport`, `core/storage`, `core/audit`, the Android Keystore provider, and the phone/watch Data Layer adapters. It is not deployment evidence, a security proof, or a medical-readiness claim.

## Activation rule

Real personal ingestion remains **locked**. The current phone and watch experiences run synthetic fixtures and memory-only capture. None of the production-shaped data-plane components changes that gate by merely existing in source.

The real-data gate may open only after the physical watch/phone path, key lifecycle, durable outbox, deletion controls, and target-device failure tests listed at the end of this document have passed. Until then:

- no screen may label simulator values as live or personal;
- no probability may be presented as calibrated;
- a missing, unreadable, unauthenticated, or low-quality input must become `LOCKED`, `LEARNING`, `ABSTAINED`, `DATA UNAVAILABLE`, `NACK`, or `RECOVERY_REQUIRED` as applicable;
- no failure may be converted into a normal or reassuring health result.

## Threat boundary

The implemented contracts are intended to detect bounded classes of corruption, tampering, mismatch, replay, and incomplete persistence. They do not make a compromised endpoint trustworthy.

| In scope for the source contract | Outside the demonstrated boundary |
|---|---|
| Bounded wire decoding; truncation, trailing-byte, version, and checksum rejection | A compromised watch, phone, OS, app process, accessibility service, debugger, or rooted device |
| AES-GCM confidentiality and authentication of a batch payload plus canonical routing/provenance metadata | Physical device pairing, authenticated key agreement, secure key rotation, revocation, or recovery |
| Per-record AES-GCM at rest with bounded plaintext decoding | Anti-rollback hardware counters, a cross-record hash chain, or proof that files were not deleted |
| Restart reconstruction of accepted/quarantined batches and exact pending receipt-delivery state in JVM tests | Verified Android filesystem durability, Keystore lifecycle, backup/restore, reinstall, migration, or device-transfer behavior |
| HMAC-authenticated ACK wrapper, exact batch/session/sequence/digest checks and a durable receipt replay claim before deletion authorization | Physical pairing/provisioning of the purpose-separated ACK key, process-lifecycle composition and exact-device sender/listener behavior |
| Prospective forecast event ordering and hidden pre-reveal views | Clinical validity, diagnostic performance, emergency detection, or treatment guidance |

Visible metadata is not fully concealed. The transport envelope exposes protocol version, batch/session/device identifiers, sequence, time, schema, content type, and encrypted-payload size. Local storage exposes approximate record count, order, file size, key ID, and filesystem metadata; record ID is represented in the filename only as a truncated SHA-256 value. Payload content remains inside AES-GCM ciphertext, assuming the key and endpoint are not compromised.

The contracts do not prevent denial of service. Invalid files can intentionally force recovery-required behavior, which is preferable to silently accepting questionable health data.

## Data-plane sequence

The intended protocol is:

1. The watch forms a bounded plaintext batch and seals it with `AuthenticatedBatchPayloadCipher`.
2. The encrypted payload is placed inside a version-1 `BatchEnvelope`, then `BatchEnvelopeCodec` adds a SHA-256 corruption checksum over the canonical wire bytes.
3. Before durable watch enqueue, `WearDataItemPayloadPolicy` rejects a canonical envelope above 64 KiB. This conservative application budget leaves room for DataMap framing; larger raw captures require a separately authenticated and tested Asset/chunk protocol, which is not implemented.
4. `GooglePlayDataLayerBatchTransport` places an eligible envelope in a Data Item at `/v1/research/batches/{batchId}` with `setUrgent()`. A successful `putDataItem` callback means only that Google Play services accepted the Data Item; it is not a phone receipt.
5. `VitalSignalPhoneDataLayerListenerService` copies the exact canonical envelope and consent generation from the DataMap, then dispatches only when an explicitly installed governed runtime exists. The coordinator decodes the envelope, authenticates AES-GCM, and sends the authenticated plaintext plus canonical wire bytes to a `DurableBatchSink`.
6. The sink atomically commits the canonical wire record or confirms an already-durable byte-identical record. Only then may `BatchReceiverCoordinator` construct an `ACK` with a durable commit token.
7. Before any radio attempt, `EncryptedAppendOnlyReceiptDeliveryOutbox` AES-GCM-encrypts a strict event that binds the complete canonical acknowledgement to the consent generation, paired node, receipt path, batch and acknowledgement-key ID. Missing keys and publisher failures remain recoverable after restart; stale consent is terminal and cannot transmit.
8. `GooglePlayMessageReceiptPublisher` HMAC-wraps the exact recovered acknowledgement and sends it to the exact paired node/path with a bounded background wait. Delivery uses finite exponential retries; radio success followed by an uncertain terminal write is retried at least once rather than declared complete.
9. Decode, authentication, identity, ordering, or store failure produces a `NACK`. The receiver attempts to record bounded rejection metadata in a separate encrypted quarantine journal.
10. `VitalSignalWatchReceiptListenerService` copies the receipt message. The installed handler checks exact path, batch, source node, consent generation and HMAC before the outbox coordinator independently re-authenticates and evaluates deletion.
11. On the watch, an ACK must pass every deletion gate and be durably claimed in the replay store before deletion can be authorized.
12. `removeAuthorized` rechecks the exact batch ID and VitalSignal URI path before requesting Data Item deletion.

Steps 1–12 are represented by source contracts, Android adapter source and isolated JVM/API-stub tests. The process runtimes deliberately have no default consent, keys or stores, and no signed APK or paired-device execution has run. Therefore this sequence is not a claim of working physical or offline transport.

## Checksum is not authentication

VitalSignal uses SHA-256 and AES-GCM for different purposes. They must not be described interchangeably.

| Mechanism | Current use | What it establishes | What it does not establish |
|---|---|---|---|
| Envelope SHA-256 | Final 32 bytes of `BatchEnvelopeCodec` output | Accidental corruption detection and a canonical byte identity | Sender authenticity; anyone who can change the bytes can recompute an unkeyed hash |
| Inner ACK SHA-256 | Final 32 bytes of `BatchAcknowledgementCodec` output | ACK corruption detection and canonical inner bytes | Phone authenticity by itself |
| ACK HMAC-SHA-256 | `AuthenticatedAcknowledgementCodec` over key ID + complete inner ACK | Application-level ACK authenticity under a purpose-separated shared key | Physical pairing, endpoint trust, anti-rollback or availability |
| `wireSha256Hex` | Duplicate comparison and exact queued-batch/ACK binding | Equality with the locally queued canonical bytes | A MAC, signature, or proof of who generated the receipt |
| AES-GCM tag | Encrypted batch payload and each local encrypted record | Confidentiality and integrity under the secret key, including supplied AAD | Trust in a compromised endpoint, key exchange, anti-rollback, or availability |

The ACK contract is cryptographically authenticated at application level in the platform-free core, but the shared HMAC key is injected in tests. Real deletion remains disabled until the physical pairing/key-provisioning boundary, runtime composition and paired sender/listener lifecycle are implemented and tested.

## Application-level batch encryption

`AuthenticatedBatchPayloadCipher` uses `AES/GCM/NoPadding`, a 128-bit tag, and a 12-byte nonce supplied by an injected `SecureRandom`. The encrypted payload codec carries a bounded key ID, nonce, and ciphertext. A `TransportKeyResolver` selects the receiving key by key ID; unknown, invalid, malformed, or authentication-failed inputs do not expose plaintext to the durable sink.

Canonical AAD binds all of the following to the ciphertext:

- protocol version;
- batch ID;
- session ID;
- device ID;
- sequence;
- creation time;
- content schema version;
- content type;
- transport key ID;
- nonce.

Changing any bound field while retaining the ciphertext causes AES-GCM opening to fail. The outer envelope fields remain visible for routing, but cannot be changed undetectably under the application key.

The general core wire limits are protocol-versioned: payloads are capped at 512 KiB, total envelopes at 520 KiB, acknowledgements at 16 KiB, and identifier/string fields have explicit UTF-8 bounds. The current ordinary DataItem route is deliberately narrower: the complete canonical envelope must be no more than 64 KiB and is rejected before durable watch enqueue otherwise. Decoders reject malformed UTF-8, invalid lengths, unsupported versions, truncation, and trailing bytes.

Nonce uniqueness and shared transport-key provisioning depend on the runtime integration. Injecting `SecureRandom` is a testable cryptographic boundary, not evidence that watch and phone have completed secure pairing.

## Encrypted append-only persistence

`EncryptedAppendOnlyRecordStore` is a pure JVM per-record store used as the lower layer for received batches, receiver quarantine metadata, phone receipt-delivery events, forecast-audit events, and watch ACK replay claims.

### Commit path

Each `LocalEncryptedRecord` contains a safe record ID, a sequence beginning at 1, a non-negative timestamp, a bounded content type, and an opaque payload capped at 1 MiB. The store:

1. reconstructs and verifies existing state before every append;
2. rejects appends if any committed file is quarantined;
3. treats the same record ID and same content type/payload as an idempotent duplicate;
4. treats reuse of that ID with different content as a replay conflict;
5. requires the next exact local sequence with no gaps;
6. encrypts the complete record with AES-GCM;
7. authenticates envelope magic, version, key ID, nonce, and ciphertext length as AAD;
8. writes and `force(true)`-flushes a `.pending-*.tmp` file;
9. requires `ATOMIC_MOVE` to the deterministic final name `record-{20-digit sequence}-{record-id hash}.vsr` and fails closed if the provider cannot guarantee it;
10. attempts a best-effort directory metadata flush, then immediately re-runs authenticated recovery before returning accepted status.

There is no non-atomic fallback. Directory forcing remains best effort. Atomic rename and power-loss behavior must still be verified on the exact Android storage location; source shape alone is not a durability proof.

### Recovery path

Recovery ignores only store-shaped `.pending-*.tmp` files. It bounds file size, parses the envelope strictly, checks the key ID, verifies the GCM tag, decodes bounded plaintext, verifies the deterministic filename, rejects duplicate IDs, and accepts only a consecutive sequence beginning at 1.

Each suspect final file appears in `StorageRecoveryReport.quarantined` with a non-payload reason such as wrong key, authentication failure, corrupt/truncated format, filename mismatch, duplicate ID, or out-of-sequence. It is not silently included in `accepted`, and any quarantine makes `canAppend` false. Recovery classification does not physically relocate the suspect file.

This is per-record authenticated storage, not an anti-rollback ledger. There is no external monotonic anchor, multi-process file lock, or cross-record hash chain. In particular, deletion of a trailing committed record may be undetectable. Those controls require separate design and fault testing before personal ingestion.

### Receiver quarantine journal

Transport rejection quarantine is a separate concept from storage recovery quarantine. `EncryptedBatchJournalSink.quarantine` writes bounded rejection metadata—reason, available batch/session/sequence, wire digest and size, receive time, and detail code—to an independently configurable encrypted store. It does not store arbitrary malformed wire bytes. A successful write yields `RECORDED`; a failed quarantine write yields `RECORDING_FAILED`. Both remain `NACK`, and neither can authorize watch deletion.

## Durable receipt before ACK

`BatchReceiverCoordinator` has no branch that emits ACK merely because bytes decoded or arrived through Data Layer. Its order is:

1. decode and verify the bounded envelope/checksum;
2. authenticate and decrypt the payload;
3. ask the durable sink to commit the canonical wire bytes;
4. emit `DURABLY_COMMITTED` only for `Committed` with a valid commit token;
5. emit `DURABLE_DUPLICATE` only when the durable sink supplies the previously committed canonical digest and that digest equals the received wire digest in constant time;
6. otherwise NACK and attempt quarantine recording.

`EncryptedBatchJournalSink` persists canonical received wire bytes in the encrypted store. During restart recovery it decodes and re-authenticates every accepted envelope, checks the record ID/content type, rejects reused device/session/sequence ordinals, and reconstructs deterministic commit tokens. A lost ACK can therefore be reissued as a durable duplicate in JVM tests without creating a second accepted batch.

The generated response is staged before radio delivery in `EncryptedAppendOnlyReceiptDeliveryOutbox`. Its strict append-only state machine retains the canonical inner acknowledgement and binds it to consent generation, paired node, exact `/v1/research/receipts/{batchId}` path, acknowledgement-key ID and creation time. It records bounded failed attempts, deterministic backoff, delivered state, stale-consent discard or retry exhaustion. Recovery rejects mutation, orphan transitions, terminal replay, impossible attempt numbers and configured pending/journal overflow. The queue contains receipt metadata only—not decrypted sensor payload—and its injected AES key has no plaintext or hard-coded fallback.

`CrashSafeReceiptDeliveryCoordinator` rechecks active consent before every attempt, resolves the exact generation-specific HMAC key, reconstructs the authenticated wrapper and publishes at least once. A missing key or radio failure remains pending across restart. Consent withdrawal/rotation makes the queued generation terminal without sending. A process-shaped failure after radio acceptance but before the delivered marker leaves the entry pending, so a later worker may send a duplicate; the watch replay/deletion gate must make that duplicate harmless. Attempts, pending entries, recovery batch size and total journal records are finite. The journal currently has no reviewed compaction/retention implementation, so reaching its finite event bound fails closed.

`PhoneDataLayerAndroidRuntime` now requires an explicit `PhoneReceiptRecoveryRequestor`. Runtime installation requests recovery of restart-surviving entries, and a `ReceiptDeliveryPending` dispatch requests another run instead of being silently ignored. `PhoneDataLayerBridgeCoordinator.retryPendingReceipts` exposes the bounded recovery entrypoint and reports the next eligible retry time. A concrete WorkManager/JobScheduler worker, boot/connectivity hooks, app-startup construction, Keystore alias provisioning and device execution are still absent. The source therefore closes the platform-neutral liveness gap but does not establish an operating Android response channel.

## Exact watch deletion gate and replay store

`OutboxAcknowledgementValidator` authorizes deletion only if all checks pass:

1. the outer ACK HMAC validates under a known purpose-separated key, then the inner ACK codec/checksum parses;
2. disposition is `ACK`, never `NACK`;
3. batch ID exactly matches the locally queued batch;
4. session ID exactly matches;
5. sequence exactly matches;
6. `wireSha256Hex` equals the queued canonical wire digest using constant-time comparison;
7. the acknowledgement receipt ID is durably claimed for that batch in the replay store;
8. the replay claim returns first-use `Claimed`, not `AlreadyClaimed` or `StoreFailure`.

Only then is `DeletionAuthorized` returned with the durable commit token. `GooglePlayDataLayerBatchTransport.removeAuthorized` performs two further checks: the queued batch ID must equal the authorization batch ID, and the stored URI path must be exactly `/v1/research/batches/{batchId}`. Only that URI is passed to `deleteDataItems`.

`EncryptedAcknowledgementReplayStore` stores a receipt-ID-to-batch-ID claim in its own encrypted append-only store. Its restart test verifies that the same claim becomes `AlreadyClaimed` and that moving a claimed receipt to another batch fails closed.

The source contains an encrypted atomic watch snapshot, deterministic retry state, queued URI/digest reconstruction, a receipt listener, exact staged deletion and restart recovery after an uncertain remote delete. Those seams pass controlled JVM/API-stub tests. They are not composed at app startup with Keystore keys and signed consent, scheduled after reboot, or verified on paired hardware. A Data Item enqueue callback is not sufficient deployment evidence.

## Power, contact and restart continuity

`WatchCollectionContinuityEngine` is the platform-neutral gate between Android runtime facts and a collection lane. A verified runtime signal names the exact device/firmware, consent generation, boot session, wall and elapsed clocks, battery/charging state, thermal state, on-wrist state, permission/registration state, encrypted-storage readiness and recovery-material result. The model does not claim that Android has supplied those facts until a concrete adapter is composed and tested.

Low battery, charging, elevated thermal state and confirmed off-wrist status pause collection and open an explicit `EXPLICIT_MISSING_NEVER_IMPUTE_NORMAL` gap. Unknown wrist/thermal state, unavailable storage/key/permission/registration, clock discontinuity, unverified reboot, device change or consent mismatch fail closed. Process death and a verified same-generation reboot can produce an exact resume permit; collection cannot resume until that permit is confirmed against the immutable checkpoint. The next measurement sequence never resets, and each commit extends a SHA-256 provenance chain.

`EncryptedWatchContinuityJournal` stores each checkpoint in a per-stream authenticated append-only local record store. Recovery verifies consecutive revisions, predecessor hashes, stable stream/device/firmware/consent identity, non-decreasing measurement sequence, provenance changes only on an exact next commit, explicit reboot gaps and legal resume transitions. `DurableWatchContinuityCoordinator` applies audit-before-action ordering, so it returns a resume permit only after that exact pending checkpoint is durable. Corruption, a wrong key, a forged successor or the finite journal limit makes continuity unavailable rather than returning an empty/normal state.

This closes a platform-neutral design gap only. The state machine and journal are not yet composed with Android battery/thermal/on-body callbacks, Keystore provisioning, a boot receiver, WorkManager, Health Services registration or the Samsung tracker lifecycle. `PassiveBootRestoreContract.automaticRestoreEnabled` therefore remains `false`; physical reboot and battery-drain behavior is still release-blocking.

## Forecast chronology and non-leakage

Forecast evidence uses a separate append-only event journal and state machine:

1. **Commit hidden:** a forecast and canonical feature-snapshot SHA-256 are committed before the target window starts. The pre-reveal `LockedForecastView` intentionally has no probability or interval fields.
2. **Store pre-reveal context:** the forecast must already exist; the context event is stored before the target starts and before reveal or outcome.
3. **Reveal:** requires a persisted pre-reveal check-in, cannot predate that check-in, and must occur before the target starts.
4. **Observe:** requires a persisted reveal and cannot occur before the target window ends.
5. **Resolve:** an observed binary `0` or `1` becomes resolved. A missing observation stays `null` and becomes `INDETERMINATE`; it is never converted to a negative outcome.

Event IDs are idempotent only for exact duplicates. Conflicting reuse is rejected. Revisions must be consecutive. Recovery replays and validates the chronology; any unreadable storage, revision gap, conflicting replay, or impossible chronology makes the ledger unavailable instead of reconstructing a plausible forecast.

`EncryptedForecastAuditJournal` stores the explicit bounded binary event encoding in a dedicated `EncryptedAppendOnlyRecordStore`. It fails closed on a storage quarantine, unexpected content type, invalid metadata, duplicate event ID, or unreadable event.

The current dashboard does **not** use this durable ledger. Its check-in and forecast reveal remain memory-only simulator behavior, truthfully labelled “Simulator commitment only.” The durable audit core must be integrated and Android-tested before a real forecast can be shown.

## Key-purpose separation

The source defines distinct roles, and these roles must remain separate in runtime provisioning:

| Purpose | Source boundary | Current status |
|---|---|---|
| Watch-to-phone payload authentication | Transport key ID + injected AES `SecretKey` resolved by `TransportKeyResolver` | Pure-JVM contract/tests only; no physical pairing or exchange |
| Phone records at rest | `VitalSignalKeyAliases.PHONE_STORAGE` and injected store keys | Alias/provider exists; not wired to the dashboard/receiver runtime |
| Pending phone receipt delivery | Injected AES key + `EncryptedAppendOnlyReceiptDeliveryOutbox` | Crash-safe bounded source/tests exist; dedicated Keystore alias, retention/compaction and Android worker composition are absent |
| Phone transport receiver role | `VitalSignalKeyAliases.PHONE_TRANSPORT_RECEIVER` | Alias exists; it does not itself establish a shared watch key |
| Watch outbox/replay at rest | `VitalSignalKeyAliases.WATCH_OUTBOX_STORAGE` | Encrypted outbox/replay source and tests exist; no watch-side Keystore/runtime composition is wired |
| Phone-receipt authenticity | HMAC-SHA-256 + `VitalSignalKeyAliases.WATCH_ACK_AUTHENTICATION` role | Core contract/tests only; physical shared-key provisioning is absent |
| Forecast audit, received batches, and rejection quarantine | Independently injected `EncryptedAppendOnlyRecordStore` instances | Separation is possible; dedicated production aliases and lifecycle policy are not finalised |

`AndroidKeystoreAesKeyProvider` separates `loadExisting` from `initialiseFresh`. A missing or invalidated expected key returns `RecoveryRequired`; it does not silently generate a replacement and make old encrypted records look empty. Fresh generation is intended only for an explicit empty-vault initialization. AES-GCM keys are non-exportable when created by Android Keystore, do not require a biometric prompt for background ingestion, and request StrongBox opportunistically with a fallback when unavailable.

These are source policies, not verified device behavior. Key alias creation, StrongBox result, invalidation, lock-screen change, OS upgrade, restore, uninstall/reinstall, backup exclusion, rotation, retirement, compromise recovery, and secure wipe are not yet exercised on the target devices. Tests also reuse keys in places where the deployed design should use purpose-specific keys.

## Truthful UI states

The present UI deliberately exposes the gate rather than pretending the data plane is active:

| UI state/copy | Required meaning |
|---|---|
| `SIMULATED DATA · NOT YOUR HEALTH DATA` | Values are fixtures, not measurements from Elz or any user |
| `Galaxy Watch simulator`, `Fixture loaded`, `MEMORY ONLY` | No physical sensor or durable watch capture is represented |
| `REAL DATA LOCKED` | Production-shaped components exist, but personal ingestion is disabled |
| `No watch batch received` | Source adapters exist, but no governed personal-data runtime is installed or active |
| `Simulator commitment only` | Dashboard check-ins and reveal are not yet using the encrypted audit journal |
| `LOCKED` | Probability and interval are absent before the required pre-reveal event |
| `LEARNING` | Baseline/outcome history is insufficient; no physiological interpretation or probability |
| `ABSTAINED` / `DATA UNAVAILABLE` | Quality, recovery, or policy gates failed; missing data is not treated as normal |
| `UNVALIDATED` | A simulator probability may be visible after check-in, but no calibration claim is made |

The eventual real UI must derive receipt, audit, key, and recovery states from the actual durable components. Copy alone is not an operational control.

## Verification boundary and locked acceptance gates

The core source has deterministic JVM tests for bounded codecs, AES-GCM metadata tampering, unknown keys, commit-before-ACK, byte-identical duplicate recovery, encrypted-store restart, tamper/wrong-key quarantine, ACK mismatch/replay denial, encrypted replay claims, and forecast chronology/restart reconstruction. Those tests validate code paths under controlled conditions only.

The following are explicitly **NOT verified**, and real personal ingestion stays locked until they are resolved:

- physical Galaxy Watch/phone pairing and authenticated transport-key exchange;
- Android runtime composition and the full Android Keystore lifecycle on the exact devices;
- end-to-end process composition of the source-wired phone listener, receipt sender and watch receipt listener with real consent, keys and stores;
- a composed Android receipt-delivery worker with boot/connectivity triggers, Keystore key, retention/compaction and exact-device process-death evidence (the encrypted platform-neutral outbox and scheduling seam now exist);
- physical watch outbox crash/reboot recovery, retry, duplicate delivery, lost-ACK handling and uncertain-delete reconciliation;
- physical provisioning/rotation of the ACK-authentication key and end-to-end authenticated ACK sender/listener behavior;
- offline physical transport behavior;
- privacy deletion, retention, export, consent withdrawal, secure wipe, and backup/restore behavior;
- filesystem atomic-move and power-loss durability on target Android storage;
- rollback/trailing-deletion detection and multi-process locking;
- full fault injection across process death, reboot, low storage, file corruption, clock/timezone change, packet loss, disconnection, OS upgrade, and key invalidation;
- Samsung Health Sensor SDK, Samsung Health Data SDK, Health Services, and Health Connect behavior on the exact watch/phone/firmware combination;
- battery, thermal, radio, and background-execution behavior;
- security review, penetration testing, privacy impact assessment, clinical validation, human-factors validation, or regulatory assessment.

This checkpoint must not be described as medically ready, production secure, clinically validated, diagnostic, life-saving, or proven to operate offline between physical devices. Its correct description is: **a simulator-first research codebase with tested platform-free integrity, storage, authenticated receipt, replay, and forecast-audit contracts, while real personal ingestion remains locked pending Android and physical-device validation.**
