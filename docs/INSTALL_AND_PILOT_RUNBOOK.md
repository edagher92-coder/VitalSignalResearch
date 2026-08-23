# VitalSignal private install and hardware pilot runbook

Audience: Elie, privately testing on a Samsung Galaxy S25 Ultra and Galaxy Watch Ultra2.

Status: engineering runbook; not a clinical-use instruction.

Last reviewed: 2026-08-23.

## Read this first

VitalSignal is a research/wellness prototype. It is not a medical device, has not been validated on Elie's hardware, and must not diagnose, reassure, direct treatment, change medication, or replace professional care. If symptoms are severe, new, or concerning, act on the symptoms and established medical advice—not on the app.

There is **no prebuilt APK in this repository**. The source must first build successfully on a development computer. No proprietary Samsung AAR is present, no Samsung adapter has been run, and no end-to-end personal-data capture has been validated on the S25 Ultra/Ultra2 pair.

The safe order is:

1. Build and install the simulator on both devices.
2. Verify lifecycle, permissions, pause/export/delete and encrypted persistence without personal data.
3. Activate the public Wear OS lane and validate transport with synthetic records.
4. Collect a minimal public-sensor baseline only after the public lane passes its gates.
5. Add Samsung developer-mode lanes one sensor at a time.
6. Keep all interpretations hidden until the evidence plan permits promotion.

## What can be tested before partnership approval

| Lane | What it can provide | Partnership position | Current repository truth |
|---|---|---|---|
| 0. Hardware smoke test | Phone/watch UI, simulator, permissions, lifecycle, accessibility and thermal behavior | No Samsung partnership | Source exists; an APK still has to be built and installed |
| A. Public Wear OS | Low-power supported metrics through Health Services, watch-to-phone transfer through Data Layer, optional phone history through Health Connect | No Samsung partnership | Domain gates, outbox/receipt logic and bridge components exist; active platform registration, production key/store wiring and exact-device behavior still require build and physical verification |
| B1. Samsung Health Sensor SDK | Runtime-supported raw/live or on-demand watch trackers | Samsung's developer mode is for private testing/debugging only | Licensed AAR and concrete adapter are absent; capabilities must be probed on the exact watch/firmware |
| B2. Samsung Health Data SDK | Read-only Samsung Health history on the phone | Samsung documents read testing in developer mode without a partner request; writing and distribution require partnership/access approval | AAR and concrete reader are absent; history contracts exist but no real read has run |
| R. Research clinician observer | Consented, quality-labelled summaries for a named research observer | Requires a separate protocol, privacy/security review and clinician agreement; it is not clinical monitoring | No clinician portal, network backend, identity/access service or live-alert workflow exists |
| C. Approved release | Restricted scopes, writing where approved, and public distribution | Applicable Samsung, store, privacy, regulatory and clinical approvals | Out of scope for this private pilot |

Samsung explicitly limits both developer modes to development/testing, not ordinary app users: [Health Sensor Service developer mode](https://developer.samsung.com/health/sensor/guide/developer-mode.html) and [Samsung Health Data SDK developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html). Samsung also states that read-only Health Data SDK development can proceed in developer mode without a partnership, while writing or distribution requires partnership: [migration guidance](https://developer.samsung.com/health/data/migration-guide/overview.html).

## Gate 0.5 — Health Services synthetic-path test

Before using personal signals, install the Wear app on a Wear OS 4+ emulator and use Android Studio's **Wear Health Services** sensor panel. It can enable/disable capabilities, override exercise values and trigger auto-pause/resume, sleep and fall events. Start and stop the exercise through the app; modern Health Services synthetic generation follows the normal API lifecycle.

Use event broadcasts only for the specific event cases documented by Android, for example:

```bash
adb shell am broadcast -a "whs.AUTO_PAUSE_DETECTED" com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.AUTO_RESUME_DETECTED" com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.START_SLEEPING" com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.STOP_SLEEPING" com.google.android.wearable.healthservices
```

Pass when unsupported capabilities, missing samples, pause/resume, sleep, falls, transport retries and UI withholding all behave safely. Google states the Wear OS 4+ method is emulator-only; it does not run on a physical Ultra2 and proves no sensor accuracy ([Health Services simulated data](https://developer.android.com/health-and-fitness/health-services/simulated-data)). The subsequent physical public Health Services lane uses real device measurements and separate reference validation.

## Gate 0 — workstation and project setup

Install:

- Android Studio capable of the versions pinned by the project;
- Android SDK Platform 37.0 (SDK Manager package `platforms;android-37.0`) and current Platform Tools;
- JDK 17 selected as the Gradle JDK;
- a USB data cable for the S25 Ultra;
- the S25 Ultra and Ultra2 paired normally in the Galaxy Wearable app and signed into their normal services;
- a private, encrypted location for exports and test evidence.

The project currently pins Gradle 9.5.1, Android Gradle Plugin 9.3.1, Kotlin 2.4.10, `compileSdk`/`targetSdk` 37, and Java 17. Do not casually upgrade these during a measurement phase; a dependency or firmware change creates a new validation stratum.

Phone and watch modules use the same application ID, `au.com.elied.vitalsignal`. Build both from this one project and signing configuration. For the Data Layer pilot, require the same signing certificate on both APKs as a project invariant. Android also recommends the same package name for companion phone/watch applications: [Wear OS packaging](https://developer.android.com/training/wearables/packaging).

From the repository root:

```bash
java -version
./gradlew --version
./gradlew clean testDebugUnitTest :phone:assembleDebug :wear:assembleDebug
```

A successful build is expected to create:

```text
phone/build/outputs/apk/debug/phone-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

Those paths are expected outputs, not a claim that they exist now. Before installing, inspect both certificates with the Android Build Tools `apksigner` utility and confirm the SHA-256 signer digest matches:

The repository workflow runs the same tests, lint and two debug assemblies on GitHub Actions. If that job passes, its `vitalsignal-0.6.0-research-simulator-debug` artifact provides both simulator-only APKs for 14 days. A downloaded artifact is a build convenience, not evidence of Samsung sensor access, personal-data readiness or hardware validation; still perform the signer check and every gate below.

```bash
apksigner verify --print-certs phone/build/outputs/apk/debug/phone-debug.apk
apksigner verify --print-certs wear/build/outputs/apk/debug/wear-debug.apk
```

**Stop** if compilation, tests, APK signing, or the signer comparison fails. Do not bypass a failing test to begin personal collection.

## Gate 1 — prepare and connect the S25 Ultra

1. On the phone, open **Settings > About phone > Software information**.
2. Tap **Build number** seven times and authenticate if requested.
3. Open **Settings > Developer options** and enable **USB debugging**.
4. Connect the phone by USB, unlock it, and accept the computer's debugging fingerprint.
5. Confirm the phone serial:

```bash
adb devices -l
```

Samsung's phone instructions are documented at [Turn on the phone's developer options](https://developer.samsung.com/health/data/guide/phone-developer-options.html).

Assign the displayed serial to `PHONE_SERIAL` when using the commands below; do not literally type the placeholder.

## Gate 2 — prepare and connect the Galaxy Watch Ultra2

1. Connect the watch and development computer to the same trusted Wi-Fi network.
2. On the watch, open **Settings > About watch > Software information**.
3. Tap **Software version** five times to reveal Developer options.
4. Open **Settings > Developer options > Wireless debugging** and turn it on.
5. Choose **Pair new device**. Note the pairing IP/port and code.
6. Pair, then use the separate connection port displayed on the Wireless debugging page:

```bash
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb devices -l
```

Pairing and connection ports are commonly different. A connection must usually be re-established after wireless debugging is restarted or the Wi-Fi network changes. Follow Android's current [Wear OS Wi-Fi debugging guide](https://developer.android.com/training/wearables/get-started/debug-wifi) or Samsung's [Galaxy Watch connection guide](https://developer.samsung.com/health/sensor/guide/connect-watch.html).

Assign `WATCH_SERIAL` to the exact `IP:CONNECTION_PORT` shown by `adb devices`.

## Gate 3 — sideload both debug APKs

Only run this after Gate 0 passes:

```bash
adb -s PHONE_SERIAL install -r phone/build/outputs/apk/debug/phone-debug.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Verify the installed packages and inspect their version information:

```bash
adb -s PHONE_SERIAL shell dumpsys package au.com.elied.vitalsignal
adb -s WATCH_SERIAL shell dumpsys package au.com.elied.vitalsignal
```

Launch explicitly if needed:

```bash
adb -s PHONE_SERIAL shell am start -n au.com.elied.vitalsignal/au.com.elied.vitalsignal.phone.MainActivity
adb -s WATCH_SERIAL shell am start -n au.com.elied.vitalsignal/au.com.elied.vitalsignal.wear.MainActivity
```

An `INSTALL_FAILED_UPDATE_INCOMPATIBLE` error usually means a previously installed build used a different certificate. Do not uninstall reflexively: uninstalling erases that app's local data. Export and verify any needed test data first; only then remove an obsolete smoke-test install deliberately.

## Gate 4 — permission and consent checklist

Grant only the permission needed for the test being run. Permission is not proof that the corresponding tracker exists or produces valid data.

| Device / surface | Permission or control | Required when | Pass condition |
|---|---|---|---|
| Watch | Activity recognition | Steps/activity context | Plain-language rationale shown; denial keeps the app safe |
| Watch | Heart-rate/legacy body sensor permission | Public or Samsung HR/IBI | Collection remains off until explicitly granted |
| Watch | Background health data | Passive Health Services lane | Separate contextual request; revocation stops/blocks collection |
| Watch | Notifications and foreground health service | User-started research session | Persistent notification while the session is active |
| Watch | Oxygen and skin-temperature read permissions | Only if the active public API exposes them | Unsupported and denied remain separate audited states |
| Watch | Samsung additional health data | Raw PPG/ECG/EDA/BIA trackers in developer mode | Requested only after the AAR/adapter and runtime capability probe pass |
| Phone | Health Connect per-type read permissions | Optional historical lane | User selects each type; revoked types are omitted, never treated as normal |
| Phone | Samsung Health Data SDK read consent | Samsung read-only history lane | Granted in Samsung Health's own permission screen; permission can be changed later |
| Both | VitalSignal consent generation | Every personal collection lane | Signed/current generation matches watch, phone, keys and feature receipt |
| Both | Pause, export and delete | Before first personal record | Each action has a visible result and durable receipt; offline watch deletion remains incomplete until the watch confirms |

Samsung Health Data SDK requires user consent for every data type and recommends an in-app path for changing allowance: [data permission guidance](https://developer.samsung.com/health/data/guide/features/data-permission.html). Public Health Connect must likewise be treated as a user-controlled, per-type source—not an automatic Samsung Health mirror.

## Lane A — public Wear OS collection while waiting

This is the preferred first real-data lane because it does not depend on a Samsung partnership.

### A1. Activation requirements

Do not activate personal collection until all are true:

- the concrete `PassiveMonitoringClient`/listener adapter is wired and registered;
- passive registrations are restored after reboot through a boot receiver and WorkManager;
- the phone's Data Layer listener is registered in the manifest and connected to durable encrypted storage;
- app keys are generated through the Android Keystore path, not fixture keys;
- Android battery, charging, thermal, on-wrist, elapsed-clock and boot facts are wired into the continuity gate, and its encrypted journal is recovered before registration;
- the watch outbox survives process death and deletes a batch only after an authenticated durable phone receipt;
- consent, pause, export and cross-device deletion are operable through the UI;
- synthetic disconnect/replay/corruption tests pass on the physical pair.

The source contains a tested platform-neutral continuity state machine: unsafe power/contact/runtime states become explicit gaps, while an exact same-generation resume permit retains the next sequence and provenance chain. It is not yet wired to Android lifecycle callbacks. This list is therefore **not yet proven complete on hardware**. Android notes that passive Health Services registrations do not survive reboot and should be restored via a boot receiver plus WorkManager: [background monitoring](https://developer.android.com/health-and-fitness/health-services/monitor-background).

### A2. Minimal first protocol

Start with the smallest capability-probed set—typically passive heart-rate and steps if the exact device reports them. Do not request every available data type.

1. Record app version, watch model, Wear OS build, firmware, Health Services version, phone build and timezone.
2. Install a fresh signed consent generation.
3. Start with a two-hour, awake, normal-activity session.
4. Verify measurement timestamps are retained separately from phone receipt timestamps.
5. Disconnect Bluetooth/Wi-Fi for 30 minutes, then reconnect.
6. Confirm all retained batches arrive once, duplicates do not reach analytics, and the watch purges only acknowledged batches.
7. Pause collection; confirm no later callback from the old consent generation is accepted.
8. Remove the watch, charge it and drain/reboot it in separate controlled runs; verify each interval is an explicit missing gap and never a normal value.
9. For process restart and reboot, verify the recovered checkpoint, exact consent generation, boot identity, resume permit, next batch sequence and provenance-chain digest before accepting a post-gap sample.
10. Move wall time/timezone while elapsed time remains monotonic; verify the clock discontinuity blocks automatic resume until reviewed recovery evidence is supplied.
11. Export, hash and inspect the manifest—not raw values in ordinary logs.
12. Delete the pilot; verify phone targets immediately and the watch target after it reconnects.
13. Only after this passes, extend to 24 hours and run the evidence plan.

The Wear OS Data Layer synchronizes data but is not primary storage; VitalSignal therefore needs its own durable watch outbox and phone store: [Android Data Layer sync guidance](https://developer.android.com/training/wearables/data/sync).

### A3. Optional historical context through Health Connect

Health Connect is a separate phone-side permissioned source. It may contain records written by Samsung Health or other apps, depending on the user's settings and source support. Probe availability, source package, record IDs, units and change/delete behavior. Never assume a missing record means a normal measurement, and never merge duplicates solely because timestamps are close.

Until the concrete read/reconciliation adapter and deletion semantics pass tests on the S25 Ultra, use Health Connect only as a planned lane, not as claimed historical ingestion.

## Lane B1 — Samsung Health Sensor SDK developer mode

This lane can begin only if Elie, as the developer/tester, can lawfully download the current SDK package and accept Samsung's terms.

1. Download the compatible Samsung Health Sensor SDK from Samsung.
2. Place its AAR in `wear/libs/`; never commit the licensed binary to a public repository.
3. Enable the documented AAR dependency and implement the app-owned adapter. Keep Samsung SDK types at the infrastructure boundary.
4. On the watch, open **Settings > Apps > Health Sensor Service**.
5. Tap the **Health Sensor Service** title area about ten times until **Developer mode** appears, then enable it.
6. Start the app and run capability discovery. Record every tracker as supported, unsupported, permission denied, temporarily unavailable, or unknown.
7. Activate one tracker at a time, starting with a short supervised session and an external reference.

Official sources: [Sensor SDK introduction](https://developer.samsung.com/health/sensor/guide/introduction.html), [developer mode](https://developer.samsung.com/health/sensor/guide/developer-mode.html), [data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html), and [getting started](https://developer.samsung.com/health/sensor/guide/getting-started.html).

Do not infer support from the Ultra2 product name. Probe the runtime after every SDK, Health Sensor Service or firmware update. On-demand measurements must remain visible, user-started research sessions. Confirm the documented ECG event's embedded green-PPG ordering, cadence, lead-off/saturation status and timestamp coherence before deriving an ECG-to-pulse timing feature. That feature must not be called blood pressure, arterial stiffness, QT, atrial fibrillation, or a diagnosis.

## Lane B2 — Samsung Health Data SDK read-only developer mode

Samsung currently documents a read-only development path without partnership approval.

1. Confirm Samsung Health is installed and current. Samsung's current overview states version 6.30.2 or later, Android 10/API 29 or later, Java 17+, and no emulator support: [Health Data SDK overview](https://developer.samsung.com/health/data/overview.html).
2. Download the SDK and place its AAR in `phone/libs/`; do not commit it publicly.
3. Before any real read, replace the current broad source/scope permit with an authenticated short-lived authorization bound to the exact data types, query time range, purpose and destination. At completion, atomically recheck current consent/revocation before the durable writer can commit. Then implement the concrete read adapter and change/delete reconciliation; keep records out of analytics until all of these fences pass.
4. In Samsung Health, open **Settings > About Samsung Health**.
5. Tap the version-line region quickly ten or more times.
6. Open **Developer mode (Samsung Health Data SDK)**, accept its notice, and turn **Developer Mode for Data Read** on.
7. Request only selected read types in Samsung Health's permission UI.
8. Run a seven-day read first. Record source identity, device, units, time bounds, IDs, update tokens and deletions; keep the data hidden from interpretations.

Do not enable or test writes without Samsung's required partnership and access code. Do not distribute a developer-mode build to other users.

## Optional Lane O — Ollama on the private server PC

Ollama is an explanation/retrieval experiment, not the sensor processor, predictor, alert engine or treatment authority. Keep Ollama bound to `127.0.0.1:11434`; use Tailscale Serve for a tailnet-only HTTPS connectivity check and never use Tailscale Funnel. On the current Windows pilot server, `tools/windows/Setup-VitalSignal-Ollama.cmd` checks the local version endpoint, sets `OLLAMA_NO_CLOUD=1`, configures Tailscale Serve and prints the health-free test URL.

The direct Ollama proxy is suitable only for version/model-inventory checks with no health data. Before a signed research packet is transmitted, replace it with the authenticated VitalSignal gateway and require exact endpoint, model digest, schema/prompt/policy hash, bounded body/time, replay protection, request audit and a synthetic/adversarial benchmark receipt. Do not put a bearer secret in source, the APK, chat or an ordinary log. Ollama documents that its localhost API has no local authentication; Tailscale transport alone does not provide the app-level purpose and model controls required here: [Ollama authentication](https://docs.ollama.com/api/authentication), [Tailscale Serve](https://tailscale.com/docs/reference/tailscale-cli/serve).

## Optional Lane F — fatigue, function and adrenal context

This lane starts with user-recorded outcomes, not an AI diagnosis. Once encrypted personal storage, consent, export and deletion pass on the phone, the pilot may prospectively record energy, fatigue, functional capacity and optional standing/GI/acute-illness context before a forecast is revealed and again only after its target window. Glucocorticoid dose/time and taper phase are timeline context only.

The watch does not measure cortisol, ACTH, electrolytes, glucose or blood pressure and cannot detect or exclude adrenal insufficiency/crisis. The app cannot change a steroid dose/taper. A separately reviewed symptom route must operate independently of every sensor/model/Ollama result; it is not active in this version. See `docs/FATIGUE_ADRENAL_CONTEXT_PROTOCOL.md`.

A standardized function/recovery observation is a later research sublane. The source contains a gate and candidate protocol, but the app must not instruct Elie to perform a sit-to-stand or walk until the exact physical protocol, eligibility/stop/response plan, environment and observer role receive external clinical, exercise-physiology, accessibility and human-factors approval. A stopped or concern-held session is never converted into a score. See `docs/FUNCTION_RECOVERY_PROTOCOL.md`.

## Optional Lane R — research-only clinician observer

This is a future supervised study lane, not a shortcut to telehealth or hospital monitoring. VitalSignal must explicitly state that the observer view is research-only, may be delayed or unavailable, is not continuously attended, has no guaranteed emergency response, and **does not replace hospital telemetry, a medical alarm, usual care or emergency services**. It stays disabled until the relevant monitoring purpose is validated and appropriately approved.

Before a named clinician/research observer can see anything:

- Elie explicitly opts in to the observer, purpose, data types, duration and contact/escalation plan;
- withdrawal immediately revokes the observer's access and stops new projection, with any offline/pending cross-device action shown honestly;
- authenticated user/observer identity, least-privilege access, encryption, audit and retention/deletion controls pass security/privacy review;
- every value shows measurement time, phone receipt time, current age/freshness, device state, contact/quality and missing-data state;
- a stale or disconnected feed becomes a prominent **no current data** state, never a reassuring normal state;
- acknowledgements distinguish “observer saw message” from “clinical action occurred,” and unacknowledged research alerts cannot silently imply escalation;
- the protocol defines who, if anyone, is contacted, during what hours, by which independent channel, and what happens if nobody responds;
- observer time, interruptions, acknowledgement delay, false alerts and missed known events are measured as outcomes.

For the first engineering study, transmit derived low-rate summaries rather than continuous raw waveforms. Preregister expected cadence and mark the feed stale after two missed expected updates or five minutes, whichever occurs first. Measure measurement-to-view latency at p50/p95/p99; an initial connected-mode engineering target is p95 below two minutes and p99 below five minutes. Failure does not create an emergency alert—it returns the lane to engineering/shadow state. The Wear OS Data Layer is not a network backend or hospital-grade transport, so a clinician lane requires a separately designed store-and-forward service with its own failure model.

Initial observer-burden limits are one scheduled dashboard review per day and no more than one bundled research notification per day, with the existing nuisance budget of no more than one unexplained pattern episode per 30 stable person-days. Any safety-critical intended use requires a new clinical, human-factors, operational, cybersecurity and regulatory validation programme.

## Physical validation sequence

Run each numbered stage and retain a signed evidence result before moving on:

1. Simulator install and UI/accessibility smoke test.
2. Synthetic Data Layer transfer, duplicate, corruption and lost-ACK tests.
3. Public passive two-hour test.
4. Public passive 24-hour battery/disconnect/reboot test.
5. One Samsung tracker for a short supervised reference session.
6. Read-only historical import/reconciliation test.
7. Seven-day engineering pilot with interpretations hidden.
8. Minimum 28-day personal baseline.
9. Hidden prospective forecasts/outcomes.
10. Only then consider a limited visible wellness result.

The detailed measurements and acceptance thresholds are in `docs/PILOT_EVIDENCE_PLAN.md`.

## Hardware evidence collection

For every run, preserve:

- protocol/run ID and preregistered purpose;
- app commit/version, schema and model version;
- phone/watch model, OS, firmware, service and SDK versions;
- consent generation and pseudonymous participant ID;
- exact start/end instants, timezone offsets and receipt-time delay;
- capabilities and permissions at start/end;
- battery percentage and thermal/charging state;
- batch counts, accepted/quarantined/duplicate counts, gaps and ACK/purge state;
- reference-device model, firmware, placement and clock alignment;
- deviations, interruptions and adverse/user-anxiety events;
- export digest and deletion receipts.

Ordinary `logcat` output must not contain raw health values, medication names, free-text symptoms, keys or tokens. Store private evidence encrypted and restrict access.

## Hard go/no-go criteria

### Go: simulator on personal hardware

- both APKs build from the same reviewed commit and certificate;
- tests pass;
- both apps launch without a crash;
- no real sensors/history are enabled.

### Go: limited public personal collection

- all Lane A activation requirements pass on the exact S25 Ultra/Ultra2 pair;
- 100 synthetic batches survive disconnect/retry with zero unexplained loss and zero duplicate analytics records;
- corruption/tampering is rejected, never interpreted;
- pause blocks new and late-generation data;
- export is complete and verifiable;
- delete completes on phone and, after reconnection, watch;
- process death and reboot recover the authenticated continuity journal, preserve the exact next sequence/provenance chain, expose the whole gap and restore passive registration only after an exact resume permit;
- charging, low battery, thermal limits and off-wrist time are never interpreted as normal physiology;
- battery acceptance in the evidence plan passes.

### No-go

Stop personal ingestion or revert to simulator if any of these occur:

- fixture/debug keys, in-memory-only storage, or an unregistered listener are active;
- a batch can be deleted before authenticated durable receipt;
- consent revocation, pause or deletion is incomplete without an explicit visible status;
- device, firmware, app, schema or SDK changes without a new evidence stratum;
- timestamps/units/source identity are ambiguous;
- unexplained sequence gaps, duplicate analytic records, corrupt accepted records, excessive drain or thermal warnings occur;
- an unvalidated result reaches a personal-facing insight;
- the app influences medication, treatment or urgent-care decisions.

Passing this runbook proves only that the private engineering pipeline is fit to begin evidence collection. It does not prove a sensor is clinically accurate, a forecast is useful, or the product is safe for the public.
