# VitalSignal private pilot evidence plan

Audience: Elie and technical/clinical reviewers of the S25 Ultra + Galaxy Watch Ultra2 N-of-1 pilot.

Protocol status: draft to freeze before personal collection.

Last reviewed: 2026-08-23.

## Purpose and claim boundary

This plan determines whether VitalSignal can reliably collect, transport, quality-grade and prospectively interpret Elie's wearable and historical data as a private wellness/research system.

It does **not** validate diagnosis, screening, treatment, medication changes, emergency triage, or generalisation to other people. No result may override symptoms or established medical advice. An apparent association is a hypothesis until reproduced prospectively and, where relevant, compared with a suitable reference and clinician-confirmed outcome.

Use the V3 sequence—verification, analytical validation and clinical validation—as the evidence hierarchy: [Goldsack et al.](https://pubmed.ncbi.nlm.nih.gov/32337371/). Apply the INTERLIVE wearable validation principles where applicable: [Johnston et al.](https://pubmed.ncbi.nlm.nih.gov/33397674/). Any later clinical prediction study should follow [TRIPOD+AI](https://www.bmj.com/content/385/bmj-2023-078378).

## Promotion rule

Implementation and evidence are separate. A coded feature remains hidden unless signed evidence matches its exact:

- feature and feature version;
- app commit/version and data schema;
- phone/watch model, OS and firmware;
- SDK/Health Sensor Service/Samsung Health versions;
- protocol and dataset freeze;
- intended surface: shadow research, private visible wellness, public wellness, or medical intended use.

Old evidence is historical evidence. It cannot automatically validate a new firmware, sensor adapter, feature definition, model, population or claim.

## Questions to answer

1. Can the watch produce timestamped, provenance-complete data with usable quality on Elie's wrist?
2. Does every retained watch batch reach durable phone storage exactly once at the analytic layer despite disconnects, retries, process death and reboot?
3. What is the incremental battery and thermal cost of each collection mode?
4. How closely do selected signals agree with an appropriate external reference under prespecified conditions?
5. After a minimum 28-day baseline, can locked models forecast prespecified point-assessment outcomes better than a simple persistence/no-change benchmark?
6. Can the system abstain when data or evidence is inadequate and keep nuisance alerts within budget?
7. Can Elie pause, export and delete the data predictably across an offline watch and phone?
8. Can a physiology-plus-context model forecast prospectively recorded fatigue/function better than persistence without implying that it measures cortisol or detects adrenal insufficiency?

## Evidence phases

| Phase | Minimum duration | Data/result visibility | Exit requirement |
|---|---:|---|---|
| 0. Build and simulator verification | Until all gates pass | Simulator only | Reproducible signed build, automated tests, safe UI and no personal ingestion |
| 1. Physical engineering verification | 7–14 days | Raw/quality engineering views only | Units, clocks, provenance, persistence, battery, transfer and privacy operations pass |
| 2. Personal baseline | At least 28 days; extend to 42 if coverage is weak | Learning/quality state only | At least 28 calendar days and 20 qualified context-matched days for a primary baseline |
| 3. Hidden shadow prediction | 28–42 days | Forecasts and pattern episodes hidden | Model/features/thresholds frozen; minimum outcome and calibration evidence met |
| 4. Limited visible wellness pilot | 8–12 weeks | Only evidence-qualified, non-medical messages | Forecast benefit, calibration, burden, human factors and safety pass prospectively |
| 5. Independent replication | New participant; same locked sequence | Same gates | New baseline and independent evidence; no copied personal thresholds |

Person 1 evidence may show personal usefulness. It cannot establish population clinical performance.

## Freeze before the first personal record

Create and sign a protocol manifest containing:

- protocol ID and version;
- source commit, dependency lock and APK signer SHA-256;
- feature definitions, units and sampling requests;
- primary and secondary endpoints;
- quality gates and missing-data rules;
- analysis code/version;
- reference devices and synchronisation procedure;
- baseline maturity criteria;
- forecast horizons, benchmark and promotion metrics;
- endpoint ID/version/definition SHA-256, cutoff anchor, window semantics and exact start/end offsets;
- feature-schema ID/version/definition SHA-256, exact typed feature IDs/versions, source-window and provenance rules;
- nuisance-alert and battery budgets;
- allowed wording and prohibited claims;
- stopping rules;
- data retention, export and deletion process.

Changes after the freeze must create a new version and state whether prior data remain compatible. Never silently retune a threshold after seeing an outcome.

## Minimum provenance record

Every retained observation or derived feature must be traceable to:

| Field group | Required content |
|---|---|
| Identity | Pseudonymous participant, device alias, watch/phone model, app install ID |
| Software | App/commit, schema, feature/model version, OS/firmware, SDK/service versions |
| Time | Measurement start/end instant, timezone offset, monotonic/sequence evidence where available, phone receipt instant |
| Source | API/source package, tracker type, runtime capability state, reference-device ID if paired |
| Consent | Consent generation, allowed purpose/channel, pause/revocation status |
| Quality | Contact/status flags, validity, clipping/saturation, motion, continuity, coverage and rejection reason |
| Transport | Session, batch, sequence range, payload digest, key ID, retry count, durable receipt and purge state |
| Transformation | Input record IDs, algorithm/version, parameters, output unit and evidence digest |

Ordinary operational logs contain IDs, counts, states and digests—not raw physiology, medication details, free-text symptoms, secrets or tokens.

## Engineering verification on the exact hardware pair

### Transport and packet-loss protocol

Use synthetic numbered batches before real sensor data.

1. Generate 100 bounded batches with known session/batch IDs, non-overlapping sequence ranges and checksums.
2. Transfer 20 with normal connectivity.
3. During the remaining batches, separately inject:
   - 30 minutes with Bluetooth and Wi-Fi unavailable;
   - a phone process kill before durable commit;
   - a phone process kill after commit but before ACK;
   - a watch process kill before enqueue and after enqueue;
   - a duplicated Data Item/event;
   - an out-of-order delayed batch;
   - one altered payload byte, one altered metadata field and one forged/stale ACK;
   - low storage or an injected storage-write failure.
4. Reconnect and wait for the backlog to settle.
5. Compare the generation manifest, watch outbox, phone durable journal, receipt journal, quarantine ledger and analytic record count.

Acceptance:

- zero unexplained missing generated batches after recovery;
- zero duplicate records at the analytic layer;
- every accepted record has the expected authenticated digest and sequence;
- all tampered/conflicting records are rejected or quarantined and never interpreted;
- lost ACK causes safe idempotent retry, not duplicate commit;
- watch deletion occurs only after an exact authenticated durable receipt;
- stale consent generations and wrong watch nodes are rejected;
- every failure is visible as unavailable/backlogged/quarantined—not silently reported as healthy.

Android describes the Data Layer as synchronization rather than primary storage, which is why both the durable outbox and phone store are mandatory: [Data Layer guidance](https://developer.android.com/training/wearables/data/sync).

### Process-kill and reboot protocol

Run each condition three times with pending data:

- swipe the watch app away;
- force-stop the watch app, then explicitly relaunch it;
- kill the phone process;
- reboot the phone;
- reboot the watch;
- reboot both while disconnected, then reconnect.

Distinguish process death, force-stop and reboot: Android does not promise that an app restarts itself after a user force-stop. The app must, however, recover safely when it is next opened. Passive Health Services registrations do not persist through reboot and must be restored through the intended boot/WorkManager path: [Android background monitoring](https://developer.android.com/health-and-fitness/health-services/monitor-background).

Acceptance:

- no acknowledged data reappears and no unacknowledged data disappears;
- consent, pause and key-generation fences survive restart;
- passive registration returns within 15 minutes of a normal reboot when permissions and platform services are available;
- unavailable services produce a visible retry state rather than fabricated zeros;
- model/baseline state either restores with verified integrity or fails closed.

### Clock and timezone protocol

Measurement time and receipt time must never be interchangeable.

1. Record NTP-synchronised reference time at the start and end of each reference session.
2. Disconnect the watch for two hours, generate known synthetic events, then reconnect.
3. Cross a local midnight during one run.
4. Use synthetic fixtures for daylight-saving start/end, travel timezone changes and repeated local clock times.
5. If safe and supported, briefly test manual-clock offset, then restore automatic time before physiological comparisons.

Acceptance:

- event ordering uses measurement/sequence evidence, not callback arrival order;
- phone delay does not move an event into a different physiological window;
- UTC instants and contemporaneous timezone offsets are retained;
- ambiguous/repeated local times are represented without collision;
- clock discontinuity triggers a data-quality/stratum event, not a physiological alert.

### Battery and thermal protocol

Use paired A/B days with similar wear, settings and activity. Do not charge during a measurement window.

| Condition | Repeats | Measure |
|---|---:|---|
| VitalSignal paused/control | 3 × 24 h | Start/end battery, screen/AOD, radios, wear time, activity, thermal events |
| Public passive lane | 3 × 24 h | Same fields plus quality yield and batches |
| Public passive + transfer backlog | 3 × 24 h | Drain while disconnected and during catch-up |
| Each Samsung continuous tracker | 3 supervised sessions | Percentage points/hour, event yield, skin comfort, thermal status |
| Each on-demand tracker | At least 10 attempts across conditions | Drain/attempt, completion, quality and failure reason |

Calculate incremental drain as active-condition drain minus matched paused/control drain, with the raw values retained. Initial private-pilot acceptance is:

- the passive lane completes a 20-hour day with at least 15% watch battery remaining on every verification day;
- median incremental passive drain is no more than 15 percentage points per 24 hours;
- no thermal warning, shutdown, charging anomaly or material UI lag;
- disconnect/catch-up does not create uncontrolled retry drain;
- each raw tracker has a measured session budget before baseline use.

These are product engineering budgets, not medical thresholds. If they fail, reduce sampling/session duration or remain in supervised sessions; do not hide the failure by excluding bad days.

## Sensor and reference-device verification

Use the best feasible reference for the specific measurand. A second consumer watch is a comparator, not ground truth.

| Signal / proposed use | Minimum home/pilot reference | Conditions | Required analysis / boundary |
|---|---|---|---|
| Heart rate | Validated chest strap with RR export or clinician/research ECG | Supine rest, seated, standing, walking and graded exercise | Availability, bias, MAE/RMSE, coverage and Bland–Altman; stratify motion/contact |
| IBI/RR-derived HRV | ECG or validated RR chest strap with raw interval export | Five-minute stable rest, paced and spontaneous breathing; no ectopic editing hidden | Verify interval units/order; exact RMSSD on known sequences; no autonomic diagnosis |
| Raw ECG | Clinical/research single-lead ECG where available | Supervised stillness, simultaneous clock marker, documented electrode placement | Waveform/timing agreement and quality only; no rhythm/QT claim without a separate clinical protocol |
| ECG plus embedded PPG | Simultaneous ECG/PPG reference or research acquisition system | Repeated 30-second still sessions across posture/contact | First prove sample order, cadence and clock coherence; timing fingerprint remains exploratory |
| SpO2 | Clinician-approved or suitable medical-grade fingertip oximeter used per instructions | Warm, still hand at stable rest; repeated paired readings | Agreement/availability by perfusion and motion; never use either device in isolation for illness severity |
| Skin temperature | Calibrated contact skin thermistor/logger near a prespecified site | Stable room, rest, sleep and controlled ambient changes | Skin-to-skin comparison; never relabel as core temperature |
| Accelerometer/steps | Timed/manual video count; research accelerometer if available | Fixed 100/500-step walks, different speeds, household motion | Step error, missingness and false steps; gait biomarkers need a separate protocol |
| Sleep duration/timing | Contemporaneous sleep diary for feasibility | Bed/wake times and awakenings | Diary agreement only; stage/apnoea claims require polysomnography and clinical protocol |
| BIA/MF-BIA | Repeated standardised condition; formal body-composition reference only if pursuing that claim | Same time, hydration/meal/exercise/bladder protocol | Repeatability first; no day-to-day hydration or composition diagnosis |
| EDA | Research EDA electrodes/system if pursuing a claim | Controlled rest and prespecified stimuli with motion logging | Signal agreement/response detection only; no emotion or mental-health inference |

Record reference model, serial/alias, firmware, calibration status, sample rate, placement, start/end clock offsets and operator notes. Do not compare unmatched summaries—for example, a watch five-minute mean against one fingertip spot value.

The TGA cautions that pulse oximeters have limitations, including effects associated with skin pigmentation, and should not be used alone to judge illness severity: [TGA safety update](https://www.tga.gov.au/news/safety-updates/limitations-pulse-oximeters-and-effect-skin-pigmentation).

## 28-day baseline protocol

The baseline begins only after engineering verification. Firmware, watch wrist, major sampling logic and feature definitions remain fixed.

### Daily minimum

- wear the watch on the prespecified wrist and fit, targeting at least 16 hours/day;
- target at least four hours of qualified overnight coverage;
- complete a morning check-in before viewing any forecast-like output;
- complete the prespecified evening/future outcome after its target window;
- log only concise context needed by the protocol: unusual exercise/load, sleep interruption, travel, illness/stressor, hydration context, stress, medication/infusion timing, glucocorticoid taper phase when relevant, lightheadedness/standing symptoms and relevant GI symptom burden;
- record charging, watch-off periods, reference measurements and deviations.

Context is not automatically cause. Medication timing and clinician-ordered labs are labels/context; the watch cannot infer drug dose, adrenal function, inflammation, infection or an IBD flare.

### Baseline maturity

Minimum baseline maturity requires all of:

- at least 28 calendar days;
- at least 20 qualified time/activity-matched days for each primary reference window;
- at least 24 days with 16 or more hours of wear and at least 24 nights with four or more qualified hours, unless the preregistered feature uses a different justified window;
- documented coverage across ordinary weekdays/weekends and relevant activity states;
- no unresolved device/firmware/schema transition within the stratum;
- quality yield and missingness reported, not imputed as normal.

Days 0–7 show quality/raw engineering trends only. Days 8–27 remain learning. If maturity fails at day 28, extend to 42 days; do not weaken the rule retrospectively.

### Personal, historical and population context

Keep three references visually and analytically distinct:

1. **Personal contemporaneous baseline:** primary for N-of-1 change detection, matched by time/activity/context.
2. **Personal historical baseline:** allowed only when source, units, device and change/delete history are compatible; show device/firmware transitions.
3. **Population/age reference:** secondary context only, using a licensed, versioned, relevant source with cohort, age range, sex definition, geography, measurement method and uncertainty disclosed.

Do not blend an age/sex mean into “your normal,” treat a mean as a healthy threshold, infer ancestry from appearance, or import unsupported internet averages. Evaluate whether quality/error differs by skin tone, fit, wrist characteristics, age and other relevant subgroups before any public claim.

## Prospective forecast and outcome protocol

### Preregistered outcomes

Primary candidate endpoints:

- next-day morning energy/fatigue, 0–10;
- next-evening fatigue, 0–10;
- 24-hour lower-than-personal-usual energy, yes/no using a frozen definition;
- a **72-hour point assessment**, collected from +72 hours inclusive to +73 hours exclusive relative to the frozen feature cutoff, using a frozen binary energy/function definition.

The current simulator implements only that final 72-hour point-assessment control endpoint. It must never be described as an outcome occurring “during the next 72 hours.” The endpoint ID, version, positive-class rule, cutoff anchor, point-window semantics, offsets and canonical definition SHA-256 are immutable inputs to the forecast. A changed field creates a different endpoint.

Each committed feature snapshot binds one exact schema ID/version/definition SHA-256 and the complete feature-key/version set. Each typed feature also carries its source-window start/end and provenance IDs; a source end after the forecast cutoff is invalid. Prior cases may be pooled only when endpoint, schema and feature-key set match exactly. Repeated case identities are counted once, while conflicting duplicate identities, replayed outcome IDs/digests, unauthenticated outcome receipts or multiple case IDs for one endpoint target window force abstention. Missing outcomes never become negative examples.

Secondary outcomes may include perceived sleep quality, planned versus completed activity, and felt unwell enough to change normal activity. Optional GI/pouch symptom burden stays a separate outcome family.

### Fatigue and adrenal-context substudy

Fatigue is measured directly by prospective check-in, not inferred from the watch. Record energy, fatigue and functional capacity on the frozen scale; lightheadedness/standing symptoms, nausea/vomiting/diarrhoea and acute illness/stressors are optional contextual fields. Exact medication dose/time and taper phase are recorded only with consent and never treated as a randomized intervention.

The Watch supplies nonspecific physiology: qualified resting/sleeping HR, IBI/HRV, sleep, activity, temperature context and—where separately validated—posture-response or oxygen stability. It does not measure cortisol, ACTH, electrolytes, glucose or blood pressure. Any adrenal-insufficiency research label must come from clinician assessment and clinician-ordered morning cortisol/ACTH stimulation or other reference evidence; a validated cuff is required for blood-pressure/orthostatic comparison.

Evaluate two different questions and never merge them:

1. Does the frozen model improve future fatigue/function prediction over persistence?
2. Do physiology/context trajectories show reproducible associations with independently obtained clinical/laboratory episodes?

The second is exploratory and cannot produce “adrenal insufficiency detected/excluded,” a cortisol estimate, a stress-dose recommendation or a taper change. Glucocorticoid withdrawal and adrenal insufficiency can have overlapping symptoms, and current guidance relies on clinical/biochemical evaluation ([Endocrine Society/ESE guideline](https://www.endocrine.org/clinical-practice-guidelines/glucocorticoid-induced-adrenal-insufficiency)). A separately medically reviewed Australian symptom route must bypass every model and direct the person to their clinician-authored emergency plan/urgent care when indicated; the model cannot downgrade it. Healthdirect advises urgent emergency care for an adrenal crisis ([Healthdirect](https://www.healthdirect.gov.au/addisons-disease)).

### Standardized function/recovery substudy

This sub-study remains inactive until its exact physical protocol and per-session workflow receive external clinical, exercise-physiology, accessibility and human-factors review. The first candidate is a reference-timed five-times sit-to-stand; a fixed-route walk is a later, separately reviewed protocol rather than an automatic progression.

Every comparable episode binds an immutable protocol/version, physical-configuration SHA-256 digest, external protocol/session review evidence, completion state, human-concern state, timing/equipment/setup/rest/recovery markers, exact device/firmware and sensor provenance. A caller-supplied receipt identifier is not authority; an injected verifier must validate the external review evidence. Human concern is checked before sensor quality. Stopped, declined, incomplete, deviated or concern-held episodes remain visible in the feasibility/safety denominator but cannot enter the comparison reference.

The primary observation is reference-measured task time. Watch-derived movement boundaries and cardiac/recovery features remain secondary until exact-device agreement is established. Compare task time and physiological response separately with prospectively recorded fatigue/function, using the same prior-only/persistence controls. Never output frailty, fall risk, disability progression, VO₂max, cardiovascular/autonomic diagnosis, exercise clearance or advice to start/repeat/continue a task. See `docs/FUNCTION_RECOVERY_PROTOCOL.md`.

### Chronology

1. Freeze the endpoint and feature schema; materialize only features whose source windows end on or before the cutoff.
2. Persist the hidden forecast commitment and canonical feature-snapshot digest within the preregistered cutoff-to-commit latency bound.
3. After commitment, capture and durably store a **blind pre-reveal context check-in** while the probability and interval remain absent.
4. Persist the reveal event. In a visible phase the forecast may now be displayed; a hidden-shadow study may suppress participant display while retaining the same audit chronology.
5. Capture the distinct point assessment only inside the endpoint's frozen window `[targetStart, targetEnd)`, preserving its source assessment timestamp; record the resulting observed/missing/ambiguous outcome after that window closes. A late retrospective label is invalid.
6. Resolve as observed, missing or indeterminate, then score against the exact committed endpoint and target timestamps.

The chronology is therefore **commit hidden → blind pre-reveal context → reveal → target-window outcome**. `PreForecastCheckIn` cannot be a feature of the forecast that was already committed before it was captured, and it cannot serve as that forecast's future outcome. It may be eligible only for a separately preregistered later forecast. Never backfill missing labels as negative.

### Evaluation

Use rolling-origin evaluation and prior-only training. Never use a random train/test split for this time series. Compare every candidate with a persistence/no-change benchmark.

| Output | Minimum reporting |
|---|---|
| Continuous forecast | MAE, median absolute error, MASE, interval score/pinball loss |
| Binary event | Brier score, reliability/calibration, AUPRC where meaningful |
| Interval | Empirical 80/90/95% coverage and width |
| Pattern episode | PPV, sensitivity, false episodes/person-time, lead time and missed events |
| Operations | Wear time, qualified yield, missingness, transfer delay/loss and battery |
| Human factors | Anxiety, misunderstanding, unwanted action, symptom override and clinician escalation |

Use seven-day moving-block bootstrap intervals. A challenger forecast remains hidden unless it improves on persistence by at least 10% with block-bootstrap support, preserves calibration and safety, and has at least 30 prior resolved outcomes/effective cases for the horizon. If the evidence is insufficient, the correct result is learning or abstention.

## Alert and abstention policy

A multisystem pattern episode requires at least two quasi-independent signal families changing coherently, adequate quality, prespecified persistence, and hysteresis/cooldown. HR and HRV are related, not two independent confirmations. One extreme value requests remeasurement.

Initial nuisance budget: no more than one unexplained pattern episode per 30 stable person-days and no more than one bundled pattern message per day. Exceeding the budget returns the feature to hidden shadow.

Abstain when:

- contact, motion, clipping, continuity or coverage fails;
- required independent domains are missing;
- uncertainty is too broad;
- context is outside the trained range;
- a firmware/device/schema/model transition lacks matching evidence;
- calibration degrades;
- a deterministic safety gate or audit commit fails.

Ollama or another language model may explain only signed, computed evidence. It cannot invent measurements, probabilities, diagnoses, causes or actions. Model unavailability must produce a static safe fallback or no narrative, never alter the underlying result.

## Pause, export and deletion verification

Run before personal collection, at day 7 and at the end of every phase.

### Pause

- initiate pause on the phone;
- verify a new consent generation or revocation fence is installed;
- confirm watch collection stops or clearly reports pending stop;
- inject a delayed old-generation callback and confirm rejection;
- verify no new analytic feature or model update occurs after the pause time.

### Export

- export observations, provenance, quality, context/outcome records, commitments, receipts, quarantine summaries and version manifest;
- verify record counts against the durable store;
- compute and record an export SHA-256 digest;
- open the export independently and confirm units/timestamps/source fields;
- confirm secrets, encryption keys and raw operational logs are absent.

### Delete

- request deletion while the watch is online; verify phone encrypted store, history cache, local reasoning index, personal model state, watch outbox and watch cache receipts;
- repeat with the watch offline; the UI must say deletion is partial/pending rather than complete;
- reconnect the watch and require its durable completion receipt;
- verify a replayed/conflicting receipt cannot falsely complete deletion;
- confirm the deleted pilot cannot reappear through a stale Data Item, backup or history reconciliation.

Acceptance is 100% completion of every applicable target, or an explicit pending/failed state naming the target. “Delete requested” is not “deleted.”

## Research-only clinician observer substudy

This optional substudy may begin only after the private capture pipeline passes and a named observer, purpose, consent/withdrawal process, data-retention rule and independent contact plan are approved for the study. It is not continuously attended clinical monitoring and must state that it **does not replace hospital telemetry, medical alarms, usual care or emergency services**.

The clinician/observer view must present freshness and failure evidence with the physiology:

| Domain | Required measure / behavior |
|---|---|
| Freshness | Measurement time, phone receipt time, view time and p50/p95/p99 measurement-to-view latency |
| Staleness | Prominent no-current-data state after two missed expected updates or five minutes, whichever occurs first |
| Signal quality | Contact, motion, clipping/saturation, coverage and tracker warning; low quality suppresses interpretation |
| Missingness | Expected versus received windows, disconnect duration and cause where known; missing is never normal |
| Acknowledgement | Separate timestamps for delivered, opened, acknowledged and any independently recorded action |
| Escalation | Named person/channel/hours and tabletop-tested fallback; no response is explicitly unresolved |
| Observer burden | Review minutes/day, interruptions, notifications/day, acknowledgement delay and abandonment |
| Performance | False research alerts, missed known events, lead time and time spent stale/offline |
| Autonomy | Participant opt-in scope/version, access log, withdrawal time, projection-stop time and deletion receipts |

Initial engineering targets for a connected low-rate summary feed are p95 latency below two minutes and p99 below five minutes, with one scheduled observer review/day, no more than one bundled research notification/day, and no more than one unexplained pattern episode per 30 stable person-days. These are pilot engineering/burden targets, not a hospital-grade service level.

Run at least three drills each for disconnection, stale data, low contact quality, delayed delivery, observer non-response, duplicate alert, participant withdrawal while online, withdrawal while the watch is offline, and observer-access revocation. Acceptance requires that stale/low-quality/missing data cannot appear normal; withdrawal revokes the observer surface immediately and stops new projection; acknowledgements never imply an unrecorded clinical action; false-alert and observer-burden budgets pass; and all failures are auditable.

No real-time clinical claim may be promoted from this substudy without a separate validated intended purpose, appropriate reference/clinical performance evidence, alarm human-factors/usability work, staffed operational response model, cybersecurity/privacy assurance, quality system and applicable regulatory and Samsung approvals.

## Stopping rules

Immediately pause personal collection and open an incident record if:

- unencrypted personal data, secrets or identifiers appear in ordinary logs/exports;
- timestamps, units, device/source or consent generation cannot be reconstructed;
- corruption is accepted, data are silently lost, or ACK/purge authority is ambiguous;
- battery/thermal limits fail;
- deletion/export cannot be reconciled;
- an interpretation appears without matching evidence;
- a message causes medication/treatment change, delays care, creates dangerous reassurance or creates unacceptable anxiety;
- the participant asks to stop.

## Go/no-go acceptance matrix

| Promotion | Mandatory evidence | No-go examples |
|---|---|---|
| Synthetic to public personal capture | Transport, key/store, permission, pause/export/delete, reboot, clock and battery tests all pass | Fixture key, memory-only sink, unregistered service, sequence loss, duplicate analytics, incomplete deletion |
| One sensor to retained feature | Exact-device capability plus quality yield and reference/repeatability evidence | Unsupported/unknown tracker, ambiguous units/time, poor agreement or systematic missingness |
| Baseline learning to hidden shadow | 28 days and effective context-matched sample criteria | Firmware change, weak coverage, retrospective threshold change |
| Hidden forecast to private visible wellness | Locked prospective improvement over persistence, calibration, false-alert budget, human-factors and safety evidence | Fewer than 30 resolved cases/horizon, uncalibrated probability, unsafe wording, excess burden |
| Private pilot to another participant | Frozen install/protocol and independent new baseline | Copying Elie's personal thresholds or claiming population validity |
| Any public/medical surface | External cohorts, subgroup analysis, security/privacy, clinical safety, quality system, human factors, regulatory classification and required authorisations | N-of-1 evidence alone or developer-mode dependency |

## Evidence bundle for each gate

Each decision package contains:

- frozen protocol and analysis manifest;
- source commit and reproducible build/test report;
- APK hashes and signer digests;
- device/software/capability inventory;
- raw-to-feature provenance manifest;
- engineering test results and fault-injection ledger;
- battery/thermal report;
- reference-comparison dataset and analysis;
- missingness/quality report;
- prospective commitments, outcomes and scores;
- false-alert and human-factors log;
- privacy operation receipts;
- deviations/incidents;
- signed promotion decision, scope and expiry.

The most valuable early outcome is not an impressive score. It is a trustworthy record of what the hardware can measure, when it fails, how data travel, what changes precede Elie's outcomes, and which hypotheses survive prospective testing.
