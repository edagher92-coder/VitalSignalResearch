# Evidessa Research

**Your pattern, made clear.**

Evidessa Research is the working user-facing brand for version `0.5.0-research`, a fail-closed private N-of-1 health-intelligence foundation for a Galaxy Watch Ultra2 and Android phone. It combines a truthful simulator with platform-neutral collection, transport, history, analysis, validation and governance components. Real personal-data surfaces remain locked until the exact phone, watch, firmware and reference-device tests produce matching evidence receipts.

The repository, Android package/namespace, cryptographic aliases, protocol routes, signed schemas and versioned audit identifiers intentionally retain their existing `VitalSignal` internal names. That separation makes the brand reversible without an unsafe protocol migration or a break in validation traceability.

`HumanCurrent` is the internal R&D programme codename. It does not appear as a consumer product, clinical service or protocol identity.

This is a wellness/research system under development. It is not a diagnosis, medical clearance, emergency monitor or validated health prediction. Symptoms and professional medical advice override the application.

**Release posture:** GO for simulator-only engineering evaluation. NO-GO for personal health-data collection, visible health forecasting, hardware claims, clinical monitoring, public release, or any Ollama/OpenAI/Anthropic-generated user result. Those lanes remain locked behind the gates below.

## What has been built

- `core/model/` — versioned health, quality, provenance, context and prospective-outcome contracts.
- `core/analytics/` — strict signal quality, 28-day matched personal baselines, correlation-aware interpretation, daily activity trends with explicit gap accounting, same-protocol exercise dose/heart-rate/recovery descriptors, standardized response signatures, adaptive sensing, descriptive cohort context, safety policy and calibration-ready N-of-1 forecast controls.
- `core/transport/` — bounded codecs, AES-GCM application encryption, authenticated metadata, HMAC receipts, durable-commit-before-ACK and exact deletion authorization.
- `core/storage/` — encrypted append-only records, atomic publication, corruption quarantine, receipt recovery and replay protection.
- `core/audit/` — hidden prospective forecast commitment, pre-reveal context, later outcome resolution, an authority-verified human-concern ledger and encrypted restart-safe persistence.
- `core/governance/` — contracts and gates for signed consent generations, exact device/firmware validation receipts, pause/recovery, privacy-command completion evidence and cumulative research-to-medical promotion evidence. Production signing/key services and physical privacy-command executors are not present.
- `core/monitoring/` — contracts for separately gated research-observer and regulated-clinical states, freshness/dropout, exact purpose/identity/destination authorization, short-lived observer authority, atomic signed alert actions and provenance-preserving FHIR-shaped drafts. No monitoring service is deployed.
- `core/reasoning/` — short-lived signed health-state packets, a privacy-minimized provider projection, signed provider-policy receipts, OpenAI/Anthropic/Ollama advisory contracts, reviewed semantic-template selection, deterministic verification, audit-before-delivery, UI state and human-governed offline release promotion. The model has no free clinical-prose field and cannot create a number, diagnosis, intervention or probability.
- `wear/` — simulator UX, runtime sensor inventory, manifest-declared public Health Services passive listener, Samsung tracker contracts, lossless canonical ECG-with-embedded-PPG research events, encrypted crash-safe outbox, deterministic retries and a consent-fenced Data Layer receipt-listener adapter. App-startup composition and hardware execution remain absent.
- `phone/` — simulator UX, manifest-declared Data Layer listener, consent-fenced receipt coordinator, exact encrypted commit-before-ACK behavior, crash-safe receipt-delivery outbox, Samsung Health/Health Connect/FHIR canonical records and deterministic history reconciliation. App-startup composition, worker scheduling and hardware execution remain absent.
- `prototype/` — browser-viewable product walkthrough, explicitly labelled simulated.
- `backend/` — non-deployed OpenAPI contract for a future scheduled research-observer gateway; it is not a running server or attended clinical service.

The [interactive simulator prototype](prototype/index.html) is the functional UI reference. The [dashboard concept render](docs/assets/vitalsignal-dashboard-concept.jpg) is a high-fidelity visual direction, not a screenshot of an Android build or evidence that real health data were processed.

## The central product idea

Evidessa does not treat one reading as a conclusion. It learns the person's expected pattern for the same time, activity, sleep state, environment, protocol, device and firmware. It then asks:

1. Is the measurement trustworthy?
2. What changed relative to this person's matched reference?
3. Did independent signal families change together?
4. Would a short, clean, user-initiated remeasurement add information?
5. What context or history could explain or contradict the pattern?
6. Was the original forecast later correct?

The strongest research output is a **physiological response and recovery signature**: the difference between an observed response and the response expected for that person in a matched context. A change can have many causes, so the system reports the observation, quality and uncertainty rather than naming a disease.

## High-value shadow research lanes

| Lane | Implemented foundation | Locked claim boundary |
|---|---|---|
| Activity-conditioned reserve and recovery | A platform-neutral engine enforces complete daily gap accounting and same-protocol/device/firmware exercise dose, time-weighted/persistent HR, personal bands, matched-workload cardiac cost, fixed recovery, drift and prior-session comparison | Synthetic fixtures and direct unit tests only; the UI is a reviewed fixture snapshot, not a live engine binding, and the output is not VO₂max, ischemia, heart failure or exercise clearance |
| Sleep/circadian continuity | Versioned sleep metric, matched-baseline and evidence-family contracts; the dedicated timing/cross-rhythm feature engine remains planned | Not yet implemented as a personal sleep engine; not sleep-stage ground truth, insomnia, OSA or “fully recovered” |
| Standardized function | Contracts for an externally review-gated sit-to-stand candidate and later fixed-route movement/recovery comparison | No physical protocol is approved or active; not frailty, disability progression, fall prediction or exercise clearance |
| Adaptive early-change radar | A platform-neutral planner can request—not launch—an optional foreground capture after persistent change in at least two independent qualified families | No Android runtime trigger is active; not infection, sepsis, IBD flare or medical deterioration |
| GI/IBD-associated trajectory | Typed symptom, medication/infusion and imported-history context contracts; the event-alignment study/model remains planned | No personal episode analysis is active; not inflammation measurement, IBD/pouchitis flare detection or treatment change |
| Fatigue and adrenal context | Typed prospective fatigue/function outcomes and symptom/illness/glucocorticoid context plus a memory-only simulator check-in | No durable personal capture or symptom-triage route is active; not cortisol, adrenal insufficiency/crisis detection or dose/taper advice |
| ECG–PPG timing/morphology fingerprint | A canonical event contract preserves every supplied field in Samsung's documented ECG payload, including embedded green PPG, sequence, contact and saturation metadata | No physical ECG/PPG record has been captured; not blood pressure, arterial stiffness, QT or rhythm diagnosis |
| Empirical cohort context | An advisory engine for externally curated age/device/protocol quantiles with source, sample size and validation receipt; no production cohort dataset is bundled | Context only; never overrides the personal baseline or safety state |
| Health-history mesh | Canonical Samsung Health, Health Connect and FHIR R4/R4B provenance, change, deduplication and tombstone contracts tested with generated records | Real reads remain locked; imported history is context, not automatic causal truth |
| Research clinician observer | Contracts for consented low-rate summaries, freshness/quality/dropout states, short-lived named-observer authority and atomic signed alert/audit transitions | No backend, participants or observers are connected; not continuously attended hospital telemetry or a clinical alarm service |
| Governed local explanation | Signed short-lived packet, reviewed template IDs, deterministic verifier and durable audit | Ollama cannot author free clinical prose, calculate physiology, alert, diagnose or change treatment |
| Governed cloud research copilot | Credential-free phone/watch contract, minimized typed packet, signed purpose/consent/retention receipt, strict schema, deterministic verifier and human promotion/rollback | No API call is enabled; not a nurse/doctor, direct provider browsing, diagnosis, treatment, emergency clearance or live self-modification |

The person's own concern is a first-class context signal. Normal or missing wearable data can never suppress “I feel unwell,” a clinician-authored plan or reviewed urgent-care instructions. Version 0.5 implements the audited simulator-session hold and explicit human resolution; it does not notify a clinician or emergency service and is not an attended channel.

## Current installation truth

The source is not yet a ready-to-sideload personal-data APK. Platform-neutral code and tests are working, but these Android/runtime gates still have to pass before collection is enabled:

- compile both apps with Android Studio/JDK 17/API 37;
- compose, compile and verify the manifest-declared Health Services passive listener and Wear Data Layer services against the real Android/GMS libraries;
- provision Android Keystore-backed storage, transport and receipt keys without hard-coded secrets;
- download and privately integrate Samsung's licensed Sensor and Health Data SDK AARs;
- enable Samsung developer mode for private testing or obtain partner registration for distribution;
- grant consent and platform permissions on the exact devices;
- execute reboot, offline, process-kill, storage-full, clock, packet-loss, battery and privacy tests;
- validate every experimental signal against an appropriate reference device before display.

The ordinary Wear DataItem route rejects canonical payloads above 64 KiB before durable enqueue. “Lossless canonical ECG record” means the event contract preserves every supplied field; it is not evidence that a physical capture or transfer has occurred. Larger waveform captures still need a separately authenticated Asset/chunk transport and physical loss/recovery tests.

Samsung documents that Sensor SDK and Samsung Health Data SDK developer modes can be used for local testing before distribution approval. A Wear OS 4+ emulator also has a documented Health Services synthetic-data route for testing HR, steps, GPS, duration, elevation, floors, sleep and fall-event plumbing before a physical signal is available. Synthetic data do not validate a sensor, and developer mode does not remove the need for applicable partner registration before public release. See [the private pilot runbook](docs/INSTALL_AND_PILOT_RUNBOOK.md) and [the sensor-to-output audit](docs/SENSOR_TO_OUTPUT_AUDIT.md#12-pre-approval-testing-and-data-acquisition-ladder).

## Verification checkpoint

The exact final merged platform-neutral result and its exclusions are recorded in `docs/BUILD_REPORT.md`. The suite covers the core analytics/data plane, signed reasoning authority, governance/monitoring, phone bridge, history reconciliation, watch outbox, Samsung contracts and simulator domain logic. A passing result supports only the simulator engineering checkpoint.

This is not an Android build result. Android Gradle Plugin `9.3.1`, the Android SDK, proprietary Samsung AARs, Ollama and physical devices were unavailable here. No APK or real health-data ingestion is claimed. The dependency-free structural/safety validator is included, and CI/Android Studio remains the next Android compilation gate.

On every push, GitHub Actions runs the structural/safety checks, prototype tests, Gradle tests, lint and both debug assemblies. A successful Android job publishes a 14-day `vitalsignal-0.5.0-research-simulator-debug` artifact containing the phone and watch simulator APKs. That artifact is for hardware/UI smoke testing only; it does not unlock personal collection or establish Samsung SDK behavior.

```bash
python3 tools/validate_project.py
./gradlew test
./gradlew :phone:assembleDebug :wear:assembleDebug
```

## Private pilot sequence

1. Build and install signed debug apps in simulator mode on the S25 Ultra and Ultra2; keep all personal-data lanes disabled.
2. Validate public Health Services timestamps and the encrypted watch-to-phone data path with synthetic/test records.
3. Enable Samsung developer modes and integrate the licensed AARs for private raw-sensor and Samsung Health history testing.
4. Complete reference-device, battery, loss/recovery, permission and privacy acceptance tests.
5. Collect at least 28 effective days before interpreting a matched personal baseline.
6. Run shadow forecasts first; commit every prediction before its outcome and measure calibration, lead time and false alerts.
7. Promote only a frozen feature whose exact version and environment has the required signed evidence.

See [brand and experience system](docs/BRAND_AND_EXPERIENCE_SYSTEM.md), [implementation status](docs/STATUS_MATRIX.md), [build evidence](docs/BUILD_REPORT.md), [validation protocol](docs/VALIDATION_PROTOCOL.md), [competitive moat](docs/COMPETITIVE_MOAT.md), [clinical priority roadmap](docs/CLINICAL_PRIORITY_ROADMAP.md), [fatigue/adrenal-context protocol](docs/FATIGUE_ADRENAL_CONTEXT_PROTOCOL.md), [backend/clinician contract](docs/BACKEND_CLINICIAN_PLATFORM.md), [discovery blueprint](docs/DISCOVERY_BLUEPRINT.md), [sensor-to-output audit](docs/SENSOR_TO_OUTPUT_AUDIT.md), [sensor map](docs/SENSOR_SIGNAL_MATRIX.md), [local-AI boundary](docs/LOCAL_AI_OLLAMA.md), [cloud-AI provider boundary](docs/CLOUD_AI_PROVIDER_BOUNDARY.md) and [pilot evidence plan](docs/PILOT_EVIDENCE_PLAN.md).

For continued development, read [the session handoff](docs/SESSION_HANDOFF.md), [threat model](docs/THREAT_MODEL.md), [contribution gates](CONTRIBUTING.md) and [security policy](SECURITY.md) first.

`Evidessa Research` is a working brand candidate, not a cleared commercial name or trademark. Formal legal, trademark, domain and store-listing clearance is required before public use. Samsung and Apple are independent reference ecosystems; this project is not affiliated with, endorsed by or presented as a product of either company.
