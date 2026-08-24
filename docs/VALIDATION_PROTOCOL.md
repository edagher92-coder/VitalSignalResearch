# Prospective N-of-1 validation protocol

Protocol status: engineering draft for clinical/statistical review before visible alerts. Version `0.6.0-research` adds simulator explainability and contract alignment to the consent/environment/promotion gates, crash-safe outbox and bridge controls, adaptive sensing, empirical context, history reconciliation and audit-before-delivery local reasoning in the tested platform-neutral core. It does not begin Phase A or ingest personal data.

Release decision: GO only for simulator engineering evaluation. Phase A, personal-data ingestion, physical-device claims, visible forecasts, Ollama results and clinician/clinical use remain NO-GO.

## Primary question

Can a quality-aware, personal circadian model produce useful and calibrated 24-hour and 72-hour fatigue/recovery forecasts, and identify sustained multisystem divergence, without excessive nuisance alerts or unsafe interpretation?

The pilot does not validate a diagnosis. Person 1 tests feasibility and personal usefulness. Person 2 replicates the locked software and tuning procedure with a new baseline; no person-1 threshold is copied.

## Preregistered outcomes

The protocol uses two distinct records: blind context captured after hidden commitment but before reveal, and a point assessment captured inside the later target window then recorded after that window closes. The pre-reveal check-in cannot be an input feature or label for the forecast that was already committed before it was captured. The chronology control introduced in version 0.3 remains enforced in the current `0.6.0-research` restart-safe encrypted fixture journal; the visible browser simulator interaction remains memory-only.

- morning energy and fatigue, 0–10;
- evening fatigue, 0–10;
- perceived sleep quality, 0–10;
- felt unwell enough to change normal activity, yes/no;
- planned versus completed activity;
- optional GI/pouch symptom burden, 0–10, with separate frequency/urgency/pain fields.
- functional capacity, 0–10, plus optional lightheadedness, nausea/vomiting/diarrhoea and acute-illness context;
- independent user-concern event, which a sensor or model score cannot downgrade.

Context is explanatory, not an outcome: exercise/load, stress, travel, caffeine/alcohol, fluid intake/output estimate, sleep interruptions, illness, infusion, antibiotic timing, prednisolone dose/time and taper phase. Clinician-ordered CRP, ESR, calprotectin, cortisol/Synacthen and clinician-confirmed events are external labels only.

Missing labels remain unknown and are never converted to a negative outcome.

## Phases

| Phase | Duration | User visibility | Exit criterion |
|---|---:|---|---|
| A. Verification | 1–2 weeks | Raw trends and quality | Units/timestamps/idempotency pass; loss and battery characterized |
| B. Personal baseline | 28–42 days | Learning state only | Effective sample size and context coverage mature |
| C. Hidden shadow | 28–42 days | Predictions/alerts hidden | Thresholds, feature set and update policy frozen |
| D. Visible prospective | 8–12 weeks | Qualified messages visible | Metrics and burden scored without retrospective edits |
| E. Person-2 replication | Same locked sequence | New personal model | Same acceptance suite; independent baseline |

## Reference verification

Where feasible, compare:

- HR and IBI with a validated chest strap or ECG during rest, posture change, walking and exercise;
- SpO2 with a validated fingertip/reference oximeter at stable rest and overnight;
- skin temperature with a calibrated skin thermistor, explicitly not core temperature;
- activity with manual protocol/reference accelerometer;
- sleep only against polysomnography if a future release claims stage accuracy.

Report availability, bias, MAE/RMSE, concordance/ICC and Bland–Altman limits, stratified by motion, fit/contact, wrist, temperature and perfusion. Follow the V3 framework—verification, analytical validation and clinical validation ([Goldsack et al.](https://pubmed.ncbi.nlm.nih.gov/32337371/))—and INTERLIVE principles for wearable validation ([Johnston et al.](https://pubmed.ncbi.nlm.nih.gov/33397674/)).

## Locked model behavior

The following is the protocol target unless explicitly marked as implemented.

### Quality

- retention-grade feature estimation requires score at least 0.60 plus minimum coverage, contact, validity, clipping, continuity and motion component gates;
- user-visible interpretation requires score at least 0.80, coverage/contact at least 0.80, validity at least 0.90, clipping at most 0.05, timestamp continuity at least 0.90 and motion contamination at most 0.25;
- the upstream window evaluator hard-fails at clipping above 0.05 or motion above 0.50; watch tracker warnings hard-fail;
- missing sensors are omitted, never imputed as normal;
- motion/contact failure is a data message, not a health message.

These gates are implemented engineering priors and must still be analytically calibrated by modality and exact firmware.

### Baseline

- days 0–7: raw trends only;
- days 8–27: baseline learning;
- mature after at least 28 days and effective sample size of at least 20 in the relevant context;
- current implementation: one robust time/activity-matched median/MAD reference;
- target: fast approximately 7-day state plus slow reference, with slow learning frozen during reviewed acute events, travel/device/firmware transitions and major treatment-context changes.

### Alert episode

Only the independent-family/coherent-direction gate and one-family remeasurement behavior are implemented. The complete episode policy remains a preregistered release gate:

- at least two quasi-independent domains;
- persistence of two of three nights or three of five relevant observations;
- calibrated posterior threshold;
- start/clear hysteresis and 24–48 hour cooldown;
- no more than one bundled pattern alert per day;
- preregistered nuisance budget: no more than one unexplained episode per 30 stable person-days.

One extreme sensor requests a remeasurement; it cannot create a multisystem alert.

## Evaluation

Use rolling-origin evaluation only—never a random train/test split.

| Output | Metrics |
|---|---|
| Continuous outcomes | MAE, median AE, MASE, pinball loss and CRPS |
| Prediction intervals | Empirical 80/90/95% coverage and interval width |
| Events | Brier score, calibration intercept/slope, reliability and AUPRC |
| Alert episodes | PPV, sensitivity, false alerts/person-week, alert-days, lead time and missed-event rate |
| Product safety | Anxiety, unwanted actions, symptoms overriding the app, and clinician escalations |
| Operations | Wear time, quality yield, missingness, battery and transfer loss |

Use seven-day moving-block bootstrap intervals. A forecast challenger is promotable only after preregistered improvement over persistence—initially at least 10% with block-bootstrap support—while preserving calibration and alert burden. If it does not beat persistence, suppress the forecast.

## Calibration and abstention

The binary Bayesian control is now a frozen **72-hour point-assessment endpoint**: the assessment window runs from +72 hours inclusive to +73 hours exclusive from the committed feature cutoff. It is not an event “during the next 72 hours.” Endpoint ID/version/definition digest, exact target timestamps, feature-schema ID/version/digest, typed feature IDs/versions, source windows and provenance bind each commitment. An observed label must preserve an assessment timestamp inside `[targetStart, targetEnd)` even though the outcome record is finalized after the window; late retrospective labels are invalid. Future-source features, future or unresolved outcomes, endpoint/schema/key drift, conflicting duplicate cases, replayed outcome IDs/digests, unauthenticated outcome receipts and duplicate target-window claims fail closed; exact duplicate cases count once. At least 30 prior distinct resolved cases and sufficient effective weight are required, and low-quality targets abstain. Its interval is an engineering Bayesian interval, not a calibrated product claim. A locked public projection contains no probability or bounds until the sequence commit hidden → blind pre-reveal context → reveal has been durably recorded. The blind check-in cannot be a feature or outcome of that already-committed forecast.

The target pilot adds direct 24/72-hour preregistered endpoints, persistence comparisons, rolling-origin evaluation and time-series conformal intervals fitted only from prior timestamped residuals. Until enough prospective residuals exist per horizon, the UI must withhold calibration language.

Abstain when quality/coverage is inadequate, required domains are absent, uncertainty is too broad to be useful, context is out of distribution, firmware/device changed, or rolling interval coverage falls below tolerance.

## Automated-test status

The following platform-neutral behaviors have deterministic test coverage. The exact merged execution command, result and excluded Android/device surfaces belong in `BUILD_REPORT.md`; this list is not an Android, hardware or clinical result.

- future or late-resolved outcomes cannot enter a forecast;
- quality/contact/motion/clipping failures are rejected;
- outliers and repeated same-day samples cannot mature the baseline;
- correlated families cannot double-vote; opposing normalized directions within one family explicitly abstain and contribute zero corroboration, including when another positive family is present;
- `TYPICAL` requires complete qualified expected-family coverage; partial sensor-family loss is unavailable, never reassuring;
- persistence severity uses only verifier-authenticated, provenance-bound, chronological prior episodes; future, duplicate, inconsistent, poor-quality, wide-gap and unverifiable chains receive no boost;
- lower target quality cannot narrow forecast uncertainty;
- missing labels never become negative outcomes;
- duplicate/conflicting, out-of-order, bad-unit and firmware/schema-transition packets are quarantined;
- forecast commitments, context, reveal and outcomes survive encrypted-journal restart; locked views cannot expose probability/bounds and missing outcomes remain indeterminate;
- transport envelopes are bounded, application-encrypted with AES-GCM metadata authentication, and reject checksum/tag/metadata tampering, unknown keys, oversize and trailing bytes;
- phone receipts are issued only after an encrypted atomic commit; lost ACKs are idempotently reissued after restart;
- delayed non-overlapping batches are accepted while reused ordinals/conflicts are rejected;
- corrupt/wrong-key encrypted records quarantine and force unavailable/abstention states;
- exact watch deletion decisions require batch/session/sequence/digest matching and a durable replay claim;
- signed health-state packets are immutable, exact-canonical, signature-verified, internally rehashed, short-lived and reverified after model generation; model candidates have no free clinical-prose field;
- research observer/FHIR-shaped drafts require a purpose-bound composite permit; alert mutations require signed actor/action/version permits and atomic state-plus-audit commits;
- function/recovery comparison requires exact physical-configuration digests and holds human concern before sensor scoring;
- unsafe simulator states withhold interpretation/forecast as designed;
- authenticated human-concern report/resolve events survive encrypted-journal restart, and an unavailable concern store fails safe;
- incomplete fatigue/function self-reports remain missing and cannot unlock or label a forecast.

Still required before the pilot:

- known 24/12-hour synthetic signals recover amplitude and phase;
- known RR sequences produce exact RMSSD;
- daylight-saving, timezone and overnight-sleep cases remain correct;
- frozen-sensor detection, persistence episodes, hysteresis/cooldown and nuisance budgets;
- physical-process crash injection across watch queue/phone commit/ACK/purge, real Keystore rotation/invalidation and complete outbox deletion;
- instrumented accessibility, notification, export, analytics and log-sink checks extend the existing static unsafe-language lint.

For later clinical development use TRIPOD+AI, PROBAST+AI and DECIDE-AI reporting/evaluation standards before any clinical performance claim.
