# VitalSignal session handoff

Use this file when the project is opened in another ChatGPT account, workspace or development environment. It preserves the product decisions without depending on access to the original chat.

## Mission

Build an exceptionally useful Galaxy Watch Ultra-class personal health-pattern research application that can surface subtle, qualified, multisystem changes that a person may not notice directly—without presenting unvalidated correlations as diagnoses.

## Decisions already locked

- Start with **person 1 as a private N-of-1 pilot**, then replicate the locked protocol on person 2 after validation.
- The product claim is personal physiological interpretation, not disease detection.
- Use one top-level message with bidirectional traceability: message → domains → features → qualified samples, and samples → exact message contribution.
- Separate **low-power daily baseline collection** from **short raw research sessions**.
- Combine Samsung Health Sensor SDK, Samsung Health Data SDK, Wear OS Health Services, Wearable Data Layer and optional Health Connect according to their actual roles.
- Build confidence, uncertainty, sensor quality and abstention into the result—not as small-print disclaimers.
- Group correlated features before fusion; HR and HRV are one cardio-autonomic family for corroboration.
- The baseline learns for at least 28 days and uses time, sleep/activity state and context.
- Forecast only prospectively recorded outcomes. The implemented binary control uses an exact +72h-to-+73h point-assessment window; 24-hour and other horizons remain separately preregistered targets. Record the pre-forecast check-in before revealing the prediction; finalize only a point assessment captured inside its target window and only after that window closes.
- Commit predictions and canonical feature provenance before outcomes are knowable; store pre-reveal context separately; reveal only after that context is durable; resolve only after the target window.
- A specialized statistical/temporal model predicts. An optional constrained language model explains a structured result; it never independently diagnoses raw waveforms.
- No public release or medical claim follows from one or two people.

## Personal-pilot context categories

The pilot must support voluntary logging of fatigue/energy, functional capacity, standing/lightheadedness context, GI symptoms, sleep, stress, exercise, medication dose/time, steroid-taper phase, antibiotic timing, infusion timing, travel, acute illness/stressors, user concern and clinician-ordered labs. These are contextual associations only. The app may not infer adrenal recovery, inflammation, infection or a treatment change. User concern remains independently actionable even if sensor data are normal or unavailable.

Keep exact personal medical details outside source control and out of demo data. Store them only in the consented encrypted pilot record.

## Current source status

- Source version `0.5.0-research`; visible phone/watch and browser experiences remain explicitly simulated and personal-data locked.
- Release decision is GO only for simulator engineering evaluation. Personal collection, visible forecasts, hardware-performance claims, Ollama output, clinician monitoring and public/clinical use remain NO-GO.
- Strict quality, 28-day matched personal baseline, correlation-aware interpretation, deterministic safety policy and prospective binary forecast controls.
- Standardized-response engine matched by protocol/device/firmware/physical-configuration digest, requiring 12 qualified episodes across 28 days and change in two independent families; human concern holds before sensor scoring. A separate function-capture gate requires externally reviewed protocol/session receipts.
- Battery-aware adaptive-sensing planner that can request, but never silently start, a short validated foreground remeasurement.
- Provenance-rich empirical cohort context matched by feature/unit/device/protocol/age/study stratum; structurally advisory and unable to alter alerts.
- AES-GCM append-only phone records and encrypted, atomic, restart-safe watch outbox with bounded retry and exact deletion staging.
- Consent-fenced watch-to-phone Data Layer contract and phone bridge coordinator: exact node/path/device/key/generation, durable commit before purpose-separated HMAC receipt and safe duplicate recovery.
- Signed consent and exact environment-validation receipts, pause/recovery access gate, privacy-command ledger and cumulative shadow/private/public/medical promotion gate.
- Official Samsung Sensor tracker catalog and gate-issued permits; lossless ECG record preserves embedded green PPG, sequence, lead/contact, saturation thresholds and source/receipt clocks.
- Samsung Health, Health Connect and FHIR R4/R4B canonical history records with source revisions, provenance, deterministic deduplication, conflict rejection, deletion tombstones and resurrection rules.
- Typed Ollama boundary using a short-lived signature-verified health-state packet and reviewed template IDs only; candidates contain no free clinical-prose field and are reverified/audited before delivery. No Ollama runtime/model benchmark was available.
- Prospective forecast state machine and encrypted journal: committed hidden → pre-reveal context → revealed → resolution due → resolved/indeterminate.
- Authority-verified human-concern ledger and encrypted journal: a concern hold survives restart and can be cleared only by an authorised explicit resolution. The visible demo is not attended and notifies nobody.
- Crash-safe phone receipt-delivery outbox: accepted receipts can be retried across a process-shaped gap; Android scheduling/startup composition is still absent.
- Android Keystore provider boundary that never silently regenerates a missing expected key.
- The exact final merged platform-neutral suite result is recorded in `docs/BUILD_REPORT.md`. This is not an Android/physical-device result.
- Non-deployed backend OpenAPI and clinician-platform contract; no server, database, IAM, portal or attended service exists.

Missing or externally gated: proprietary Samsung Sensor/Data SDK adapters and AARs; real Samsung Health/Health Connect reads; secure physical key provisioning/rotation; app-startup composition of manifest-declared Health Services/Data Layer adapters; Android scheduling for receipt redelivery; completed export/deletion executors; concrete authenticated Ollama HTTPS transport/decoders and real benchmark; backend implementation/IAM/portal; Android APK build in the current workspace; battery/clock/reboot/radio/device tests; ECG–PPG timestamp/reference verification; function-protocol external approval; reference-device agreement and prospective calibration.

## Next build checkpoint

1. Complete Android compilation and install the matched-signature debug phone/watch apps.
2. Provision purpose-separated Keystore keys and signed consent/validation receipts; test invalidation, rotation and recovery.
3. Verify physical Health Services and Data Layer service lifecycle, radio retry, reboot restoration and consent rotation.
4. Install licensed Samsung SDK AARs, enable developer modes and implement the concrete adapters behind the tested contracts.
5. Execute every P0 fault, permission, battery, clock, privacy/export/delete and acknowledged-transfer test on the exact S25 Ultra/Ultra2 firmware.
6. Verify ECG-plus-green-PPG payload cadence/timestamps and reference agreement before deriving any timing feature.
7. Begin Phase A data-quality collection, then the 28-day baseline only after every collection gate passes.
8. Implement the authenticated Ollama gateway transport/decoders and adversarially benchmark the pinned model only behind the signed packet, verifier, audit and promotion gates.
9. Run hidden prospective forecasts and promote nothing until calibration, lead-time and false-alert evidence passes.

## Continuation prompt

After attaching the project bundle in another session, use:

> Continue building VitalSignal Research from `docs/SESSION_HANDOFF.md`. Inspect the existing source first, preserve the locked safety and validation decisions, report the next smallest testable checkpoint, then implement and verify it. Do not make diagnostic claims or add cloud health-data transfer without explicit approval.
