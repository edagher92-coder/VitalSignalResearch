# Clinical priority roadmap

Status: version `0.5.0-research`. This is a ranked validation roadmap, not a list of active medical features. No lane is a diagnosis, clinical alarm or treatment recommendation.

## The product shape

VitalSignal should combine three complementary observation modes:

1. **Passive personal radar** — low-burden, quality-qualified trends in heart rate, IBI/HRV, sleep timing, activity and temperature context.
2. **Repeatable response tests** — the same safe, reviewed input (for example a sit-to-stand or fixed walk) compared with the person's own prior valid sessions.
3. **Event and clinical context** — symptoms, medications, infusions and independently obtained reference measurements aligned to the same timeline.

The useful result is not a universal score. It is an evidence bundle that states what changed, over what interval, under which conditions, with what data quality, and whether the change later proved relevant.

## Ranked private-pilot lanes

| Priority | Lane | First useful output | Required truth anchor | Locked boundary |
|---|---|---|---|---|
| 1 | Cardiac/autonomic personal pattern | Qualified resting/sleeping HR, IBI/HRV and recovery changes relative to a matched baseline | Reference ECG/chest strap for feature validation; clinical ECG when ordered | Not arrhythmia diagnosis, ischemia, treatment or reassurance |
| 2 | Sleep/circadian continuity | Change in sleep timing, continuity and cross-rhythm regularity | Sleep diary; polysomnography only when clinically indicated | Not sleep-stage ground truth, insomnia, OSA or “fully recovered” |
| 3 | Standardized function and physiological reserve | Change in valid sit-to-stand/fixed-walk time, cadence, cardiac cost and recovery | Manual/video timing or clinician test; reference ECG where appropriate | Not frailty, VO2max, fall prediction, disability progression or exercise clearance |
| 4 | GI/IBD-associated trajectory | Physiology and symptoms aligned with later clinician-ordered inflammatory markers | Symptoms plus faecal calprotectin/CRP and clinician adjudication when obtained | Not IBD/pouchitis flare detection, inflammation measurement or treatment change |
| 5 | Fatigue and functional-capacity forecast | Prospectively scored future fatigue/function versus persistence | Frozen user outcome; clinical/lab evidence only for separate causal questions | Not adrenal function, cortisol, cause or medication advice |
| 6 | Overnight cardiorespiratory context | Qualified oxygen-stability and pulse-response research burden | Paired polysomnography or validated home sleep study | Not lung function, blood gases, apnoea diagnosis or oxygen-treatment advice |
| 7 | Orthostatic response research | Change in posture-confirmed HR/IBI/PPG response and symptoms | Continuous beat-to-beat BP plus ECG in a reviewed protocol | Not POTS, hypotension, dehydration, dysautonomia or adrenal crisis |
| 8 | Treatment/infusion episode atlas | Repeated before/after physiology, symptom and function patterns | Exact medication/infusion timeline and clinician outcomes | Association only; never change a dose or infer drug efficacy |
| 9 | Cuff-led blood-pressure context | Validated upper-arm cuff readings aligned with posture, rest and symptoms | A suitable validated cuff | The Watch does not estimate BP or authorize treatment changes |
| 10 | Heat/workload response | Change in skin–ambient gradient, cardiac cost and recovery under comparable conditions | Measured environment and, in research, pre/post body mass or clinical temperature | Not core temperature, dehydration, electrolyte loss, heat illness or a fluid dose |

The externally review-gated function/recovery contract is specified in [`FUNCTION_RECOVERY_PROTOCOL.md`](FUNCTION_RECOVERY_PROTOCOL.md). The fatigue/adrenal-context boundary is specified separately in [`FATIGUE_ADRENAL_CONTEXT_PROTOCOL.md`](FATIGUE_ADRENAL_CONTEXT_PROTOCOL.md); neither lane is active personal collection in version 0.5.

If clinician-supported CGM is already present, glucose remains a CGM-led external lane. Watch sleep, meals, activity and medication context may help interpretation, but VitalSignal must not estimate glucose or direct food, insulin or medication.

## Differentiating research technique

A quality-gated, beat-aligned **ECG–PPG timing and morphology fingerprint** may be unusually informative on Galaxy Watch because Samsung documents green PPG values within its on-demand ECG event. The exact Ultra2 cadence, sample order and timestamp relationship must first be measured against simultaneous reference ECG and peripheral pulse waveforms.

The only initial output is:

> The timing or morphology fingerprint in this valid, still recording differs from this person's prior valid recordings; the cause is unknown.

It is not pulse-transit time, blood pressure, arterial stiffness, QT, atrial fibrillation or vascular disease. A caller cannot promote the feature by renaming it; the exact feature version, device, firmware and reference evidence must pass the medical-promotion gate.

## Human concern is a first-class safety signal

The person must always be able to record “I feel unwell” or a symptom event even when sensors look normal. A medically reviewed escalation route operates independently of sensor quality, model scores, Ollama and clinician-observer availability. Normal wearable data must never suppress the person's concern, their clinician-authored plan or urgent-care instructions.

This follows the Australian safety principle that individualised observation trends and worry or concern from patients, carers, families or clinicians belong in deterioration recognition and escalation systems ([ACSQHC deterioration standard](https://www.safetyandquality.gov.au/national-standards/nsqhs-standards/recognising-and-responding-acute-deterioration-standard)). It does not turn a private research app into an attended emergency service.

## Evidence supporting the ranking

- Samsung exposes raw accelerometer, ECG and multi-wavelength PPG plus processed HR/IBI, skin temperature, SpO2, EDA, BIA/MF-BIA and sweat-loss data, subject to device/firmware/permission capability checks ([Samsung Health Sensor SDK](https://developer.samsung.com/health/sensor/overview.html)).
- In 309 people with IBD, longitudinal HR/RHR/HRV, steps and oxygenation were associated with inflammatory or symptomatic flares, sometimes changing weeks beforehand; this supports prospective research, not an individual detector ([Hirten et al., 2025](https://pubmed.ncbi.nlm.nih.gov/39826619/)).
- Sensorised five-times sit-to-stand timing correlated strongly with manual timing in an older cohort, but its sensors and population differ from a wrist Galaxy Watch, so exact-device validation remains necessary ([Park et al., 2021](https://pubmed.ncbi.nlm.nih.gov/33652175/)).
- Consumer single-lead ECG performance varies outside narrow validation populations and other rhythms can be missed; symptom-linked recordings remain supporting evidence for professional review, not a complete rhythm diagnosis ([multicentre study](https://pubmed.ncbi.nlm.nih.gov/40717865/)).
- Samsung's Australian Watch BP information requires cuff calibration and warns against medication changes based on Watch readings; VitalSignal therefore keeps BP reference-led ([Samsung Health Monitor Australia](https://www.samsung.com/au/apps/samsung-health-monitor/)).
- A software product intended to monitor or predict disease can be regulated medical-device software and must be validated with the sensors on which it relies ([TGA wearable medical-device guidance](https://www.tga.gov.au/products/medical-devices/software-and-artificial-intelligence-ai/overview/types-software-based-medical-devices/wearable-medical-devices)).

## Explicit deprioritisation

Do not spend the first pilot on watch-estimated glucose, cuffless blood pressure, BIA-defined oedema, EDA-defined mental stress, passive frailty/fall prediction, microphone cough diagnosis, sweat-defined hydration or autonomous medication interventions. Their present validation and safety burden is higher than their likely incremental value in this N-of-1 pilot.
