# Privacy and security model

## Current `0.5.0-research` status

This checkpoint does not yet ingest real watch, Samsung Health or Health Connect data. Public Health Services and Data Layer Android services are manifest-declared in source, but app-startup composition, signed consent/key provisioning and exact-device execution are absent. The visible phone/watch simulator still holds its fixture interactions only in process memory. No application-operated cloud or clinician service is configured.

The platform-neutral core now implements AES-GCM encrypted atomic records, restart recovery/quarantine, application-authenticated batch transport, receipt-before-ACK, durable replay claims, an encrypted prospective forecast journal and a crash-safe encrypted watch outbox. Consent generation is carried from the watch envelope through phone receipt handling; stale generations, keys, nodes, paths and device identities fail closed. An explicit Android Keystore provider separates loading from first-time key creation so a missing key cannot silently reset the vault.

Signed private-pilot consent and validation receipts, privacy-command target tracking, pause/recovery blocking and evidence-based feature promotion are also implemented as platform-neutral components. These are test evidence, not a completed security claim. Physical key provisioning, real Android Keystore invalidation/rotation, durable export/deletion executors, offline-watch deletion confirmation, log/crash leakage tests and penetration testing remain incomplete. Real personal or health data must not enter the application until those controls pass on exact hardware.

## Requirements before real-data collection

- Explicit consent per data source, purpose, retention period and optional research use.
- Separate, revocable consent for clinician/research live sharing; ordinary collection consent never implies observer access.
- Local-first processing and collection of only protocol-required signals.
- Purpose-separated Android Keystore-backed keys and authenticated encryption for database, files and queued watch batches; missing keys fail closed.
- Integrity-checked, replay-protected transfer; the watch retains data until the phone durably stores and acknowledges the matching batch/checksum.
- No health values in ordinary logs, crash reports, analytics events, notifications or lock-screen previews.
- Least-privilege runtime permissions with a clear source/permission dashboard.
- Export, pause, revoke and deletion controls with verified end-to-end behavior.
- Versioned provenance and audit events without duplicating sensitive payloads.
- No advertising, sale, brokerage or unrelated secondary use.
- No cross-user model training without separate plain-language opt-in and governance review.
- Secrets never embedded in source or APKs.
- No direct internet exposure of Ollama or a clinician feed; use an authenticated private gateway, purpose-bound credentials, replay protection and access audit.

## Required user controls

Before the private pilot, the user must be able to see:

- which sources are connected and what each permission provides;
- last successful receipt, missing intervals and storage state;
- every active purpose and retention period;
- export, pause, revoke and delete controls;
- which model, feature and safety-policy versions produced each result.

Deletion must pause collection, fence concurrent writers and remove raw, derived, baseline, forecast, outcome, audit, export and cache records plus application keys. The watch backlog must be confirmed separately if offline. Source records already held by Samsung Health or Health Connect are outside this app's deletion scope and must be described accurately.

## Cloud expansion

Any later research backend must separate identity, consent, sensor data and analytics; use scoped service identities, encryption/key rotation, regional retention controls, access auditing and a documented incident-response plan. Australian privacy, health-record, medical-device and cross-border requirements need professional review before collection begins.

An optional observer lane must bind every access permit to the exact subject pseudonym, consent generation, scheduled session, observer principal, metric, data class, destination and purpose. It must display whether a current named-observer heartbeat exists, revoke the viewer surface immediately on withdrawal, and retain measurement/receipt/view time plus acknowledgement and escalation audit. Regulated mode additionally binds the exact authorized medical feature/version/environment evidence. Every alert mutation requires a short-lived signed actor/role/action/alert/version permit, and the state plus audit record commit atomically. Acknowledgement means only that an item was accepted by the workflow; it never proves that a clinician assessed or treated the person. Regulated clinical monitoring requires a separately approved clinical service and cannot be enabled by research consent or a software toggle.
