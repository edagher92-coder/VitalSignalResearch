# Samsung and Wear OS setup

This repository keeps proprietary Samsung SDK binaries out of source control. Use only downloads and licences obtained from Samsung's developer portal.

## Current checkpoint

Version `0.6.0-research` keeps every visible health surface in simulator mode. The source now contains tested encrypted storage, authenticated batch/ACK handling, a crash-safe watch outbox, consent-fenced watch/phone bridge and Health Services boundaries, Samsung tracker/history contracts, standardized-response/adaptive-sensing research logic, history reconciliation and governed local-AI orchestration. Proprietary Samsung Sensor/Health Data implementations, physical key provisioning and exact-device validation remain locked.

## Target platform

- Wear OS 7 / Android API 37 for the watch target.
- Android API 37 target and Android 10+ phone baseline.
- JDK 17.
- Same package ID and signing certificate on phone and watch: `au.com.elied.vitalsignal`.
- Physical Samsung phone/watch for Samsung SDK testing; emulator demo mode is not sensor validation.

## Target SDK roles

- **Samsung Health Sensor SDK:** watch-side raw/live and on-demand research measurements.
- **Samsung Health Data SDK:** phone-side processed Samsung Health history.
- **Health Services:** low-power passive watch monitoring.
- **Wearable Data Layer:** acknowledged transfer between the two apps.
- **Health Connect:** optional interoperability later.

The complete verified capability/constraint table is in `docs/SENSOR_SIGNAL_MATRIX.md`. One high-priority physical test is the public `ECG_ON_DEMAND` event's documented embedded green-PPG values: confirm effective PPG cadence, sample order and timestamp coherence before deriving any ECG-to-pulse feature. Never relabel such a feature as blood pressure, arterial stiffness, QT or a rhythm diagnosis.

## Binary placement

After accepting Samsung's terms:

1. Place the compatible Health Sensor SDK AAR in `wear/libs/`.
2. Place the compatible Health Data SDK AAR in `phone/libs/`.
3. Enable the AAR dependency lines documented in each module's `libs/README.md`.
4. Implement the supplied adapter interfaces, keeping Samsung types inside the infrastructure boundary; never leak SDK types into core analytics.
5. Re-run capability discovery on every watch/firmware combination.

Never assume that a tracker documented for one Galaxy Watch generation is available on another. Unsupported, permission-denied and temporarily unavailable are separate states in the UI and audit log.

## Watch permissions for API 36+

The current Wear manifest is a future-facing research superset. It declares heart rate, oxygen saturation, skin temperature, background health and Samsung additional-data capabilities even though the current default research capture does not exercise every one. Before any real pilot build, split passive/default/on-demand capabilities into explicit modes or product flavours and request only the permissions required for the user-selected, installed adapter. Runtime permission screens must explain why each active capability is used:

- activity recognition;
- read heart rate;
- read oxygen saturation;
- read skin temperature;
- Samsung additional health data for raw PPG/ECG/EDA/BIA where enabled;
- foreground service and foreground-service health;
- background health data for the passive baseline where supported;
- notifications for an active foreground session.

Legacy body-sensor permissions, if used for older compatibility, must be capped at API 35.

The phone manifest likewise contains future Health Connect read declarations, but no concrete Health Connect reader or runtime permission flow is installed. A pilot build must either defer those declarations or present a source-by-source, least-privilege connection flow before use.

## Capture modes

### Passive baseline

Target behavior: use `PassiveMonitoringClient` for low-power all-day metrics and restore registrations after reboot. Persist only protocol-required fields. The platform-neutral consent/capability/clock state machine is implemented; its physical listener/service and reboot lifecycle remain an Android/device validation gate.

### Research session

Start from a visible user action, show live contact/motion/quality, and keep the foreground notification visible. Candidate research trackers include:

- continuous accelerometer (nominal 25 Hz);
- continuous green/red/infrared PPG (nominal 25 Hz);
- continuous processed heart rate plus IBI (nominal 1 Hz outputs);
- continuous skin and ambient temperature;
- supported on-demand ECG, PPG, SpO2, BIA/MF-BIA or skin temperature.

Only one on-demand tracker may run at once. Pause conflicting continuous trackers, observe the SDK's session-duration constraints, then restore passive monitoring.

## Reliable transfer

Target physical behavior:

- `DataClient`: persistent batch envelopes.
- `MessageClient`: commands and acknowledgements.
- `ChannelClient`: large streams or files.

Each envelope carries a stable batch ID, sequence, schema, application-encrypted payload, consent generation, watch measurement context, device/firmware and quality provenance. The encrypted outbox retains a batch until a purpose-separated authenticated receipt matches the batch/session/sequence/wire digest and a durable phone commit. The platform-neutral behavior is tested; the real radio/service and Keystore lifecycle still need the exact-hardware suite.

## Distribution boundary

Samsung developer modes can support a private sideloaded pilot before partnership approval. Sensor SDK developer mode temporarily bypasses signature registration for local testing; Samsung Health Data SDK developer mode can allow local reads. They are not intended for users or public distribution. Public/commercial distribution and restricted/write scopes require the applicable Samsung partnership, registered package/signature, review, policies and country/device eligibility. See `docs/INSTALL_AND_PILOT_RUNBOOK.md` and confirm the conditions again immediately before release.
