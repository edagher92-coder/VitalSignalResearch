# VitalSignal discovery blueprint

Research checkpoint: 23 August 2026. This is a falsifiable product-development programme, not medical advice, a diagnostic specification or evidence that any proposed signal works on Galaxy Watch Ultra2.

## The strongest thesis

The most promising advance is a **personal physiological response model**. Ordinary wearable products compare isolated values with broad averages. VitalSignal should learn how one person normally responds to a known context or repeatable input, then detect when the relationship changes.

Examples include:

- the heart-rate cost of the same walking pace, grade, temperature and time of day;
- how quickly heart rate, IBI/HRV, optical pulse amplitude and movement return toward baseline;
- whether sleep of similar duration produces the same next-day restoration;
- whether skin temperature changes are explained by ambient temperature and circadian phase;
- whether the usual relationship among activity, heart rate, EDA, temperature and symptoms has changed.

This is more informative than another composite score because the target is a **response residual**:

`observed response − expected personal response for the matched context`

It is still nonspecific. A changed response can reflect poor sleep, medication, illness, heat, stress, training, measurement error or other causes. VitalSignal must report the observed change and uncertainty, not invent the cause.

## Five falsifiable candidate product primitives

### 1. Co-timed ECG–PPG hemodynamic fingerprint

Samsung's public `ECG_ON_DEMAND` payload documents raw ECG together with green PPG values. This may allow one 30-second, user-initiated capture to preserve electrical activation, peripheral pulse timing and pulse morphology without trying to run two mutually exclusive on-demand trackers. ECG is documented at 500 Hz; the effective PPG cadence and timestamp relationship are an inference that must be measured on the physical Ultra2 ([Samsung Sensor data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html), [`EcgSet` API](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.EcgSet.html)).

Candidate research features include R-to-PPG-foot/peak timing, beat-to-beat timing variability, amplitude, upstroke, width/area, wavelength-independent morphology stability, lead-off/saturation quality and response to posture or recovery. The output is “timing/morphology changed from your qualified personal baseline.” Pulse-arrival time includes pre-ejection time and contact/vascular effects; it is not a universal blood-pressure surrogate and must never be labelled blood pressure, arterial stiffness, QT or rhythm diagnosis ([PAT limitations](https://pmc.ncbi.nlm.nih.gov/articles/PMC8611024/)).

### 2. Standardized response signatures

Short, repeatable research protocols turn uncontrolled passive data into more comparable experiments. Candidate protocols are:

| Protocol | Candidate features | Reference required before any clinical interpretation | Safety boundary |
|---|---|---|---|
| Fixed walk and recovery | HR cost after pace/grade/heat adjustment; peak delta; recovery half-life; PPG amplitude; gait regularity; oxygen stability | CPET or validated walk test; ECG/oximetry where relevant | Never state VO₂max, ischemia, heart failure or exercise clearance |
| Seated/supine-to-stand | IMU-confirmed transition; HR/IBI overshoot and recovery; PPG perfusion; symptoms; postural sway | ECG plus continuous beat-to-beat BP during stand/tilt | Not POTS, orthostatic hypotension, dehydration or adrenal-crisis detection |
| Paced breathing/rest | Respiratory-linked IBI variation; EDA recovery; PPG morphology stability; movement quality | ECG-derived respiration/IBI and research respiratory reference | Not psychological-stress, vagal-tone or disease diagnosis |
| Standardized morning MF-BIA | Frequency-specific impedance and phase trend under fixed posture/time/context | Validated multi-frequency reference BIA and fluid-balance measurements | Not hydration quantity, edema, congestion or body-fluid diagnosis |

These protocols require a separately reviewed eligibility and stop-rule pathway. Symptoms and clinician advice override the app. Medication or treatment is never randomized.

The platform-free `StandardizedResponseEngine` now enforces a research gate: an explicit human-concern hold before sensor scoring; same protocol, version, device, firmware and physical-configuration fingerprint; at least 12 qualified reference episodes across 28 days; strong current quality; compatible units and provenance; and change in at least two independent signal families. Its output is deliberately `POSSIBLE_RESPONSE_CHANGE`, with cause unknown. The separate function/recovery capture contract is documented in [FUNCTION_RECOVERY_PROTOCOL.md](FUNCTION_RECOVERY_PROTOCOL.md).

### 3. Cross-sensor coherence and sensor disagreement

Sensor disagreement is valuable information. It can reveal motion, loose contact, cold skin, a device/firmware transition, a timing fault or a real physiological decoupling. The system should preserve two distinct outputs:

- **measurement disagreement:** evidence is unreliable, so abstain or remeasure;
- **qualified physiological decoupling:** several clean channels no longer relate as expected, so record a research signal.

The model must explicitly carry missingness. It may learn patterns of missing data, but an imputed value can never be presented as a measurement.

### 4. Personal analogue search

Encode each qualified 24-hour episode with a specialized time-series model, then retrieve the most similar prior episodes from the same person. A governed local language model may explain:

- what is similar;
- what is different;
- what was recorded afterward;
- what confounders weaken the comparison;
- whether there are too few good analogues to say anything.

Prospective testing asks whether analogue features improve Brier score, calibration and lead time over the transparent circadian baseline. “Five similar days were followed by low energy” can be evaluated; “this means disease X” is prohibited.

### 5. Low-risk N-of-1 intervention learning

The application can learn what helps the individual by preregistering safe wellness actions and outcomes. Examples for clinician/user review include a consistent wake time, morning light exposure, training-load choices or the completeness of hydration logging. Use randomized timing or AB/BA blocks, washout where relevant and locked outcome definitions.

The target is causal evidence for this person, not engagement. Never randomize or recommend medication doses, steroid timing/taper, biologics, supplements with treatment implications, or delay of care.

## Fatigue and adrenal-insufficiency safety lane

Fatigue is a strong initial outcome because it can be recorded prospectively, tied to function and sleep, and scored against a precommitted future window. Version 0.6 retains daily energy/fatigue and adds optional functional-capacity, lightheadedness, nausea/vomiting/diarrhoea, acute-illness/stressor and glucocorticoid-taper context. These are user/clinical context—not watch measurements.

Adrenal insufficiency is a different and much narrower question. Galaxy Watch does not measure cortisol, ACTH, sodium, potassium, glucose or blood pressure, and its nonspecific heart-rate, HRV, sleep and activity changes cannot diagnose or exclude adrenal insufficiency. Glucocorticoid withdrawal, poor sleep, infection, inflammatory disease, deconditioning and many other conditions can produce overlapping fatigue. The Endocrine Society/ESE guideline relies on clinical and biochemical evaluation and treats haemodynamic instability or prolonged vomiting/diarrhoea in an at-risk person as a potential emergency, not as a wearable prediction problem ([guideline](https://www.endocrine.org/clinical-practice-guidelines/glucocorticoid-induced-adrenal-insufficiency)).

The permitted research question is therefore:

> Does a qualified personal physiology-and-context trajectory improve prediction of prospectively recorded fatigue or functional decline, and does it help the person assemble a clearer timeline for clinical review?

The truth anchors are clinician assessment, morning cortisol/ACTH stimulation when ordered, validated blood pressure, electrolytes/glucose when ordered and the exact medication/illness timeline. A separate, medically reviewed Australian symptom route must tell a person with concerning symptoms to follow their clinician's emergency plan and seek urgent help without waiting for the watch or AI. Healthdirect advises emergency care for symptoms of adrenal crisis and notes that it can be fatal if not treated quickly ([Healthdirect](https://www.healthdirect.gov.au/addisons-disease)). The app cannot invent a stress dose, change a taper or label the non-medical concept “adrenal fatigue.”

## Ranked discovery lanes

| Priority | Research output | Why it may matter | Evidence maturity | Falsifiable first test |
|---|---|---|---|---|
| 1 | Co-timed ECG–PPG fingerprint | May reveal subtle electrical-to-pulse timing and morphology change in a clean spot capture | Official payload access; derived Ultra2 signal unvalidated | Verify cadence/timestamps, repeatability and cuff/finger-PPG/reference agreement before interpretation |
| 2 | Physiological reserve and recovery signature | Detects a change in response before static values become obviously unusual | Moderate association evidence; novel Galaxy implementation | Weekly same-route walk; test repeatability, reference-device agreement and prospective relation to fatigue |
| 3 | Multisystem early-warning trajectory | Requires persistent corroboration rather than one noisy signal | Promising translational evidence; condition-dependent | Precommit thresholds; evaluate lead time at a fixed false-alert budget against adjudicated outcomes |
| 4 | IBD-associated personal trajectory | Wearable changes have preceded inflammatory/symptomatic IBD flares in a 309-person cohort | Promising; independent validation required | Anchor forecasts to fecal calprotectin/CRP and adjudicated symptoms; measure lead time and false alerts/week |
| 5 | Fatigue and glucocorticoid-context trajectory | May forecast the person's fatigue/function and create a clearer medication/illness/symptom timeline | Fatigue is measurable; adrenal specificity is unproven and cannot come from the Watch alone | Compare against persistence using prospective outcomes; separately anchor any adrenal question to clinician/lab/reference evidence |
| 6 | Overnight cardiorespiratory stress dose | Burden/area and pulse response may contain more information than mean or minimum SpO₂ | Established with reference sleep signals; wrist transfer unproven | Paired Watch and polysomnography/home-study nights before displaying a screening interpretation |
| 7 | Circadian integrity and phase dispersion | Misalignment among temperature, activity, sleep and cardiac rhythms may precede subjective decline | Strong observational associations; not an individual disease predictor | Compare a preregistered consistent-schedule block with usual routine |
| 8 | Orthostatic response signature | Standardized posture change exposes response kinetics that passive averages miss | Physiology established; Watch implementation unvalidated | Paired active stand with continuous BP and ECG |
| 9 | Mobility reserve trajectory | Gait cost, bout fragmentation, stairs and recovery may track functional resilience | Emerging-to-moderate | Instrumented walkway/clinical assessment plus prospective follow-up |
| 10 | Heat-strain response | Fuses workload-adjusted HR, skin–ambient gradient, sweat estimate, GPS/environment and recovery | Promising; sweat estimate too imprecise for dosing | Safe paired sessions with core temperature and mass/fluid references |
| 11 | MF-BIA fluid-distribution signature | Frequency-specific impedance may reveal repeatable within-person fluid-distribution change | Exploratory on this device | Standardized morning series against validated reference measurements |

## Population history without losing the person

VitalSignal should use a dual reference system:

1. **Personal reference:** matched by time, sleep/wake state, activity, environment, device/firmware and recorded context. This drives personal interpretation.
2. **Population context:** age, sex where relevant, device, geography/season, study protocol and sample size. This provides a clearly labelled percentile or prior—not a red/green diagnosis.

A hierarchical Bayesian model can shrink an immature personal estimate toward a transparent, matched population prior, then give the person’s own data increasing weight. Clinical reference thresholds may be used only inside their validated measurement and intended-use scope.

## Cross-person and cross-clinic collaboration

The future public system should learn across people without turning a private pilot into a central raw-data warehouse.

- Keep raw waveforms local by default; share consented, versioned features or gradient/statistic updates only when a study protocol requires them.
- Use secure aggregation and privacy review for federated experiments. Federated learning alone is not anonymity; update leakage, small cohorts and membership inference still require threat testing.
- Maintain device/firmware, country, age band, sex where scientifically relevant, skin/contact context, health-state inclusion criteria and missingness with every cohort estimate.
- Split evaluation by person, site and chronological future time. Adjacent windows from the same person must never appear in both train and test.
- Publish a model card for each subgroup and device generation: calibration, coverage/abstention, false alerts, lead time and uncertainty—not only overall AUROC.
- Let clinical/research partners contribute reference outcomes through a governed FHIR mapping and a separate adjudication layer; never treat an imported code as automatically correct.
- Promote a population model only if personal adaptation improves or preserves performance without hiding subgroup failures.

Every population reference is a versioned object containing source, cohort definition, device/protocol, sample size, summary statistic, uncertainty, inclusion/exclusion criteria and verification date. The UI must never show an anonymous “people your age” average with no provenance.

## Cross-data clinical bridge

The most useful clinician view is not another untraceable AI summary. It is a time-aligned evidence bundle:

- qualified watch observations and derived features;
- device, firmware, clock, unit and algorithm provenance;
- symptoms and functional outcomes;
- medications, dose changes, infusions and interventions as context;
- clinician-ordered labs and medical history through user-authorized FHIR records;
- the original prediction, model/policy version and later outcome;
- explicit missingness, contradictory evidence and reasons for abstention.

Android Health Connect Medical Records uses FHIR R4/R4B resources, providing a standards-based path for conditions, medications, labs, procedures, visits and vitals. Unvalidated VitalSignal features should remain in the research record and must not be written into a clinical chart as if they were measured diagnoses ([Android Medical Records data format](https://developer.android.com/health-and-fitness/health-connect/medical-records/data-format)).

## Evidence anchors

- Personal physiology supports matched baselines rather than one universal normal: [Quer et al., 2020](https://pubmed.ncbi.nlm.nih.gov/32023264/).
- Wearable physiology preceded some IBD inflammatory and symptomatic flares in a 309-person longitudinal cohort: [Hirten et al., 2025](https://doi.org/10.1053/j.gastro.2024.12.024).
- HR relative to activity is associated with cardiometabolic status, but remains confounded and non-diagnostic: [Chen et al., 2025](https://pubmed.ncbi.nlm.nih.gov/40156587/).
- Orthostatic HR recovery has established physiological/prognostic associations, but Watch-only implementation lacks BP evidence: [McCrory et al., 2016](https://pubmed.ncbi.nlm.nih.gov/27330018/).
- Hypoxic burden can outperform simple event counts in reference sleep cohorts; wrist transfer must be validated: [Azarbarzin et al.](https://pubmed.ncbi.nlm.nih.gov/30376054/).
- Wrist-temperature rhythm amplitude has population health associations, not individual diagnostic authority: [Brooks et al., 2023](https://www.nature.com/articles/s41467-023-40977-5).
- Large wrist-actigraphy studies support sleep regularity as a meaningful longitudinal feature: [Windred et al., 2024](https://pubmed.ncbi.nlm.nih.gov/37738616/).
- Samsung researchers reported promising pre-syncope HRV features under tilt testing, but this is not free-living external validation: [European Heart Journal – Digital Health, 2026](https://academic.oup.com/ehjdh/article/7/4/ztag053/8586837).

## Promotion rule

Every discovery remains a shadow feature until it has:

1. a frozen definition and claim boundary;
2. signal-quality and missingness tests;
3. reference-device agreement where applicable;
4. chronological, person-level validation with an untouched holdout;
5. prospective calibration, lead time and false-alert results;
6. subgroup/device/firmware robustness checks;
7. human-factors and clinical review;
8. a measured benefit that exceeds burden and anxiety.

Nothing is promoted because a language model found a correlation or because two models agree.
