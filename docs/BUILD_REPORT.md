# Build and verification report

Checkpoint: 2026-08-23 (Australia/Sydney) · source version `0.6.0-research`

## Reproduced in this workspace

| Check | Result | Scope |
|---|---|---|
| Clean pinned Gradle suite under JDK 17 | **610 passed, 0 failed** | `./gradlew clean test lint :phone:assembleDebug :wear:assembleDebug --no-daemon --stacktrace`; 448 core, 91 phone and 71 wear tests |
| Android lint | **Passed** | Phone, Wear and core lint tasks completed; HTML/SARIF reports produced |
| Phone + Wear debug APK assembly | **Passed** | Both identify package `au.com.elied.vitalsignal`, version code 6, version `0.6.0-research`, target/compile SDK 37 |
| APK signer continuity | **Passed** | Both APKs verify with Android Debug signer SHA-256 `06825f8547232d421162e4429502d8e188fd8d4a7f16938bf19748d7470f417f` |
| `python3 tools/validate_project.py` | **Passed** | Tracked-file hygiene, module/version/manifests, claims copy, safety/governance/privacy/transport invariants, backend/assistant contract boundaries, required tests/docs and prototype contract |
| `node --test prototype/prototype.test.mjs` | **16 passed, 0 failed** | Navigation, concern override, engine-aligned forecast explanation, observer states, reduced-motion source contract and simulator/medical boundaries |
| Prototype JavaScript syntax | Passed | Extracted inline program parsed by Node 24 |
| JSON documents | Passed | Research hypothesis and repository JSON documents parsed without error |
| OpenAPI boundary checks | Passed | Observer and assistant gateway declare OpenAPI 3.1, non-routable placeholders, mTLS/idempotency and fail-closed provider controls |
| HTML parse | Passed | Dependency-free strict parser accepted the interactive prototype |
| Secret/private-endpoint scan | Passed | No private keys, provider/GitHub/Tailscale tokens, private tailnet endpoints, proprietary SDKs, personal data or packaged-app outputs were detected |

The clean build used OpenJDK `17.0.19`, Gradle `9.5.1`, Android Gradle Plugin
`9.3.1`, Kotlin `2.4.10` and API 37. Source compilation and local JVM tests do
not establish physical Health Services, Data Layer, Keystore, Samsung SDK,
battery, privacy-command or reference-device behavior.

## Behaviours executed

The 610-test clean JDK 17 Gradle run includes deterministic and adversarial cases for:

- complete qualified-or-explicit-gap daily activity trends and comparable exercise dose/response/recovery;
- non-finite/overflow, future leakage, duplicate/replayed provenance, source/origin forgery, coverage and cross-stream consistency rejection;
- quality hard gates, correlated acquisition families, matched personal baselines, verified persistence and human-concern overrides;
- prospective forecast chronology, hidden commits, locked projections, later outcomes, calibration readiness and future-case exclusion;
- encrypted atomic storage, restart recovery/quarantine, authenticated batch/receipt transport, ACK-before-deletion and crash-safe phone/watch outboxes;
- consent-generation fencing, opaque short-lived pilot/clinical decisions, privacy commands and evidence-based release promotion;
- battery/charging/thermal/off-wrist/process/reboot/clock/permission gaps and exact continuity resumes;
- Samsung contract records, Health Services boundary behaviour and history reconciliation contracts;
- local Ollama plus governed OpenAI/Anthropic request/response contracts, strict reviewed templates, minimized projections, replay/rate/circuit/timeout gates and audit-before-delivery; and
- clinician-observer freshness/coverage/authorization states, atomic acknowledgement/escalation audit and FHIR-shaped draft projections.

See `docs/FAULT_INJECTION_MATRIX.md` for the complete covered/pending matrix.

## Not executable in this cloud workspace

| Check | Result | Reason |
|---|---|---|
| Compose instrumentation/accessibility tests | Not run | No committed `androidTest` suite or reliable managed-device configuration exists |
| Physical phone/watch installation | Not run | The S25 Ultra and Watch Ultra2 are not connected to this cloud VM |
| Proprietary Samsung SDK build | Not run | Licensed Sensor/Data SDK AARs are intentionally absent from the repository |
| Ollama model benchmark | Not run | The configured remote endpoint returned HTTP 502 from this environment; no model inventory, prompt result or quality result was obtained |

These are environment boundaries, not passing Android, Samsung, hardware or model results.

## Release-blocking items not verified

- The exact Ultra2 tracker/capability inventory and proprietary Samsung Health Sensor/Data SDK adapter compatibility.
- Phone/watch APK installation and Compose accessibility/device tests.
- Authenticated physical watch/phone pairing, production key provisioning/rotation and real Data Layer service behaviour.
- Exact Samsung-history query authorization plus current-consent atomic completion fencing.
- Durable privacy export/deletion executors and a phone writer fence that closes the revocation race.
- Storage-full, power-loss, first-unlock, Keystore invalidation/rotation, LTE/Bluetooth loss and long offline-watch backlog behaviour.
- Battery, thermal, charging, off-wrist, process-death, reboot, clock/time-zone and firmware behaviour on the exact devices.
- Deployed Ollama/OpenAI/Anthropic gateways, production authentication/key custody, retention controls and personal-data approval.
- Authenticated clinical samples, critical/extreme workflow ownership, staffed escalation, downtime handling and hospital integration.
- Paired-reference signal agreement, prospective prediction calibration, false-alert burden, subgroup performance, human-factors review, clinical validation or any medical claim.

## Required next gate

Run the pinned GitHub Actions workflow and install only the simulator build it produces. Then complete exact-device public-API capability discovery and the P0 continuity/security protocol before unlocking any personal-data ingestion. Raw Samsung capture remains developer-only until the licensed adapter is supplied and its exact physical behaviour passes reference, battery and failure testing.
