# Research evidence and claim boundaries

Evidence review date: 23 August 2026. This file is a product-development evidence map, not medical advice or a substitute for a systematic clinical review. Version `0.5.0-research` still uses synthetic fixtures for user-visible results; none of the research associations below is an implemented detection claim.

## Why the first product is personal pattern intelligence

Between-person physiology varies widely while many wearable measures are relatively stable within a person. A 92,457-person analysis found large between-person resting-heart-rate differences, supporting a personal baseline instead of a universal “normal” score ([Quer et al., 2020](https://pubmed.ncbi.nlm.nih.gov/32023264/)).

Once the acquisition and reference gates pass, the first defensible product should therefore identify **qualified, persistent departures from Elz's expected time- and context-matched pattern**. Version 0.5 currently demonstrates that logic only with generated fixtures and does not identify a personal or medical cause.

## Evidence-to-product map

The “may say” column is a future claim ceiling after the corresponding physical-device, reference, prospective and promotion gates pass. It does not authorize those words for the current simulator build.

| Target | Current evidence | What VitalSignal may say | What it must not say |
|---|---|---|---|
| Nonspecific physiological strain | Wearable deviations have preceded some acute infections in retrospective and prospective cohorts ([Mishra et al.](https://pubmed.ncbi.nlm.nih.gov/33208926/), [Alavi et al.](https://pubmed.ncbi.nlm.nih.gov/34845389/)) | “Several systems are more unusual than your personal pattern.” | “Infection detected/predicted” or “no infection” |
| Fatigue/recovery | Wearable-fatigue literature is heterogeneous; wearables do not directly measure subjective fatigue ([systematic review](https://pubmed.ncbi.nlm.nih.gov/34975541/)) | After prospective calibration, “For the frozen +72h-to-+73h check-in, the fatigue/function-associated forecast is X, learned from your own prior check-ins.” Until then it is withheld | “Your body is recovered” or “safe to train” |
| IBD/GI context | In 309 people with IBD, HR/RHR/HRV, steps and oxygenation changed in association with inflammatory or symptomatic flares, sometimes weeks earlier ([Hirten et al., 2025](https://pubmed.ncbi.nlm.nih.gov/39826619/)) | “This pattern coincides with your recorded GI symptoms/context.” | “Pouchitis/IBD flare detected” |
| Rhythm | Large smartwatch studies support regulated irregular-rhythm pathways, but confirmation and prevalence matter ([Apple Heart Study](https://pubmed.ncbi.nlm.nih.gov/31722151/)) | Refer to Samsung's supported rhythm result and clinician confirmation | New AF diagnosis/forecast or “no arrhythmia” |
| Sleep | Consumer wrist devices differ meaningfully from polysomnography for several sleep measures ([2024 meta-analysis](https://pubmed.ncbi.nlm.nih.gov/39484805/)) | Longitudinal sleep/wake and restoration trend | Exact sleep stage as ground truth or sleep-apnoea diagnosis |
| SpO2/respiratory rate | Galaxy Watch studies show useful aggregate agreement in studied settings, with limitations and device dependence ([SpO2](https://pubmed.ncbi.nlm.nih.gov/35817700/), [respiratory rate](https://pubmed.ncbi.nlm.nih.gov/37766031/)) | Qualified personal trend with quality and remeasurement | Hypoxaemia/apnoea diagnosis or reassurance |
| Real-world PPG/IBI quality | A 2025 Samsung Galaxy Watch PPG dataset found low baseline error but substantial HR/HRV discrepancies during motion, reinforcing activity- and quality-stratified use ([Scientific Data](https://www.nature.com/articles/s41597-025-05152-z)) | Rest/stillness-qualified personal HR/IBI trend with visible rejection reasons | Motion-contaminated HRV as clinical truth or a “normal” result when data are missing |
| Fluid balance | Wearable hydration sensing remains promising but insufficiently generalizable ([review](https://pubmed.ncbi.nlm.nih.gov/40513095/)) | Exercise sweat-loss estimate or fluid-balance context | “You are dehydrated” |
| Heat | Skin temperature is not core temperature; wearable core-temperature algorithms have important limits ([review](https://pubmed.ncbi.nlm.nih.gov/36236737/)) | Skin-temperature residual and heat-exposure context | Heat-illness diagnosis or clearance |
| Steroid/adrenal context | Endocrine assessment relies on clinical and biochemical evaluation, not a consumer-wearable proxy ([ESE/Endocrine Society guideline](https://doi.org/10.1210/clinem/dgae250)) | Correlate dose/time and symptoms with clinician-ordered results | HPA recovery, adrenal diagnosis, dose/taper instruction |
| Standardized function/response/recovery | 5xSTS has good test–retest reliability but substantial protocol/population heterogeneity; HR-recovery classification can be individually variable ([5xSTS review](https://pmc.ncbi.nlm.nih.gov/articles/PMC8228261/), [HRR reproducibility](https://pubmed.ncbi.nlm.nih.gov/15055414/)) | “Your measured time or qualified response to the same reviewed research protocol changed from your personal history; cause is unknown.” | Frailty, fall risk, VO₂max, cardiovascular disease, POTS, hypotension, exercise clearance or cause |
| Activity-conditioned exercise response | Long-term repeated submaximal tests show moderate-to-strong within-person reproducibility of HR dynamics, while recent Galaxy validation still shows individual error, dropouts and weaker calorie accuracy ([UK Biobank reproducibility](https://pubmed.ncbi.nlm.nih.gov/28873397/), [Galaxy Watch6 exercise validation](https://cardio.jmir.org/2026/1/e81917), [multi-watch HR/energy study](https://pubmed.ncbi.nlm.nih.gov/42076635/)) | “For this comparable session, qualified cardiac cost or recovery differed from your prior sessions,” with steps/km/pace/grade, coverage and method shown | Disease cause, calorie truth, injury prediction, exercise clearance or an autonomous training prescription |
| ECG–PPG timing/morphology | Samsung documents green PPG values inside the ECG on-demand payload; pulse-arrival timing has important pre-ejection/contact limitations ([Samsung `EcgSet`](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.EcgSet.html), [PAT review](https://pmc.ncbi.nlm.nih.gov/articles/PMC8611024/)) | Qualified timing/morphology deviation after physical cadence/timestamp validation | Blood pressure, arterial stiffness, QT, AF or cardiac diagnosis |
| Overnight oxygen burden | Desaturation area/burden is associated with cardiovascular outcomes in reference sleep cohorts ([Azarbarzin et al.](https://pubmed.ncbi.nlm.nih.gov/30376054/)) | Paired-reference research trend after Watch agreement testing | OSA diagnosis/severity, event-specific hypoxic burden or reassurance |
| Circadian integrity | Wrist-temperature amplitude and sleep/activity regularity have population-level health associations ([temperature rhythm](https://www.nature.com/articles/s41467-023-40977-5), [sleep regularity](https://pubmed.ncbi.nlm.nih.gov/37738616/)) | Personal phase, amplitude and cross-rhythm alignment trend | Individual disease risk or a watch-derived melatonin phase |
| Remote heart/lung follow-up | A multicentre randomized trial reported benefit when home telemonitoring was added to coordinated clinical care for advanced chronic heart/lung disease ([trial](https://pubmed.ncbi.nlm.nih.gov/34851202/)); the intervention was a care model, not a consumer watch algorithm | A separately approved observer workflow may present qualified data, freshness and follow-up state | Claiming the Watch alone reduces admissions, replaces observations or guarantees a clinician response |

## Current AI and wearable direction

- Google's 2026 SensorFM work illustrates a missing-aware multimodal foundation-model direction for minute-level PPG, accelerometer, EDA, temperature and altimetry features across a very large corpus ([Google Research](https://research.google/blog/sensorfm-towards-a-general-intelligence-and-interface-for-wearable-health-data/)). It is inspiration for a shadow encoder, not evidence for a Galaxy-specific medical claim.
- PHIA demonstrates an agent that can reason and code over longitudinal wearable data ([Nature Communications, 2026](https://www.nature.com/articles/s41467-025-67922-y)).
- PH-LLM showed strong coaching/reasoning results, while specialized baselines remained competitive for many prediction endpoints ([Nature Medicine, 2025](https://www.nature.com/articles/s41591-025-03888-0)). That supports a split architecture: specialized calibrated models predict; a constrained language model explains.
- Samsung's 2026 vasovagal-syncope research reported promising short-horizon fainting prediction in a small suspected-VVS cohort, but lacked the external validation required for this pilot to claim the capability ([European Heart Journal – Digital Health](https://academic.oup.com/ehjdh/article/7/4/ztag053/8586837)).

## Hardware facts that shape the product

The Samsung Health Sensor SDK exposes device-dependent continuous and on-demand trackers, while Samsung Health Data SDK exposes processed Samsung Health history. Capability discovery, permissions, physical-device testing and SDK partnership terms are mandatory; a documented sensor is not automatically supported by every device/firmware combination ([Sensor SDK introduction](https://developer.samsung.com/health/sensor/guide/introduction.html), [data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html), [Data SDK types](https://developer.samsung.com/health/data/guide/features/data-types.html)).

## Evidence grade required for a promoted UI

The current simulator uses explicit fixture/unvalidated labels; it does not yet render this complete taxonomy. Before any personal feature is promoted, every feature must be assigned one of four labels:

- **Measured:** a qualified observation from a physically verified acquisition path, with no causal interpretation. A simulator fixture is never labelled measured.
- **Personal association:** prospectively observed relationship within this pilot.
- **Research association:** published observational evidence that may not generalize to this person/device.
- **Validated indication:** reserved for a regulated OEM/clinical feature used exactly within its validated scope.

Research associations never silently become personal predictions. A personal association never becomes a disease claim.
