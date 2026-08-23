# Build and verification report

Checkpoint: 2026-08-23 (Australia/Sydney) · source version `0.5.0-research`

## Reproduced in this workspace

| Check | Result | Scope |
|---|---|---|
| Merged direct Kotlin/JVM suite | **582 passed, 0 failed** | 79 platform-neutral production sources and 69 test classes across core, phone and watch domains |
| `python3 tools/validate_project.py` | Passed after this report was updated | Module/version/manifests, claims copy, quality/baseline/safety/governance/privacy/transport invariants, required tests/docs, endpoint/secret exclusions and prototype contract |
| `node --test prototype/prototype.test.mjs` | **10 passed, 0 failed** | Navigation, top-down/bottom-up traceability, concern override, forecast reveal, activity states, observer states and simulator/medical boundaries |
| Prototype JavaScript syntax | Passed | Extracted inline program parsed by Node 24 |
| JSON documents | Passed | Research hypothesis and repository JSON documents parsed without error |
| OpenAPI contracts | Passed | Assistant-gateway and observer contracts parsed as OpenAPI 3.1 YAML |
| HTML parse | Passed | Dependency-free strict parser accepted the interactive prototype |
| Secret/private-endpoint scan | Passed | No private keys, provider/GitHub/Tailscale tokens, private tailnet endpoints, proprietary SDKs, personal data or packaged-app outputs were detected |

The merged suite used the locally available Kotlin `2.3.20` compiler on JRE 17 with Kotlin standard library/reflect `2.3.20` and coroutines `1.10.2`. The project itself pins Kotlin `2.4.10`; the direct compiler run is therefore a strong platform-neutral source/test check, not a substitute for the pinned Gradle build.

There are 93 production Kotlin files. Fourteen import Android, AndroidX or Google Play APIs and were excluded from the direct compiler. The runner supplied temporary test-boundary definitions matching the repository's `DataLayerBatchTransport` and `BoundedReceiptPublishEngine` platform-independent shells so the crash-safe outbox and bounded receipt tests could execute without compiling their Android/Google Play implementations. The actual Android Keystore, Compose, services, Health Services and Wearable Data Layer source was not compiled by this local run.

## Behaviours executed

The 582-test merged run includes deterministic and adversarial cases for:

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

## Attempted but blocked locally

| Check | Result | Reason |
|---|---|---|
| Gradle 9.5.1 `test --offline --no-daemon` | Failed before source compilation | Android Gradle Plugin `9.3.1` is not present in the isolated cache and offline mode cannot resolve it |
| Android lint and phone/wear debug APK assembly | Not run locally | Android SDK/Maven dependencies are unavailable in this container; the pinned GitHub Actions job is the next reproducible build surface |
| Compose UI/instrumentation/accessibility tests | Not run | No Android emulator/toolchain is available in this container |
| Proprietary Samsung SDK build | Not run | Licensed Sensor/Data SDK AARs are intentionally absent from the repository |
| Ollama model benchmark | Not run | The configured remote endpoint returned HTTP 502 from this environment; no model inventory, prompt result or quality result was obtained |

These are environment boundaries, not passing Android, Samsung, hardware or model results.

## Release-blocking items not verified

- The exact Ultra2 tracker/capability inventory and proprietary Samsung Health Sensor/Data SDK adapter compatibility.
- Pinned full Android Gradle compilation, lint, phone/watch APK installation and Compose/accessibility/device tests.
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
