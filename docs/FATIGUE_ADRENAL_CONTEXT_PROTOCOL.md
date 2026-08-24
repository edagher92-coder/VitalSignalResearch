# Fatigue and adrenal-context research protocol

Status: version `0.6.0-research` design and typed context fields only. No personal collection, adrenal detection, symptom triage or medication advice is active.

## Purpose

This lane answers two separate questions:

1. Can qualified wearable physiology plus recorded context forecast the person's future fatigue and functional capacity better than a persistence/no-change baseline?
2. Can it produce a precise, time-aligned history that helps the person and clinician review fatigue in relation to sleep, activity, illness, glucocorticoid exposure and independently obtained clinical results?

It does not ask the watch to diagnose or exclude adrenal insufficiency. Galaxy Watch does not measure cortisol, ACTH, electrolytes, glucose or blood pressure. Fatigue, weakness, lightheadedness, nausea and reduced activity are nonspecific and may have many causes.

## Minimum-burden records

| Record | Source | Timing | Role |
|---|---|---|---|
| Energy, fatigue and functional capacity, each 0–10 | User | Frozen morning and future outcome windows | Primary prospective outcomes |
| Sleep quality and interruptions | User + qualified watch history | Morning | Confounder and recovery context |
| Lightheadedness/standing symptoms | User; optional validated BP/HR reference | Event or morning | Context only; not hypotension/POTS/adrenal diagnosis |
| Nausea, vomiting or diarrhoea burden | User | Event-triggered | Safety/context field; not a sensor inference |
| Acute illness or major physiological stressor | User/clinical record | Event-triggered | Context and stratification |
| Medication dose/time and glucocorticoid taper phase | User/authorized medication record | Only when relevant and consented | Temporal context; never an intervention recommendation |
| Resting/sleeping HR, qualified IBI/HRV, activity, sleep and temperature context | Watch/phone | Passive qualified windows | Nonspecific predictor candidates |
| Morning cortisol, ACTH stimulation, electrolytes, glucose and validated BP | Clinician/reference source when ordered | Clinical schedule | External labels/reference evidence, never requested by the AI |

The ordinary daily check-in should remain under one minute. Additional symptom fields appear only when the participant chooses an event check-in or a medically reviewed deterministic questionnaire requests them.

## Analysis contract

- Commit every forecast before the target outcome is knowable.
- Keep the pre-reveal check-in separate from the future outcome.
- Compare against persistence/no-change and a sleep/activity-only control.
- Use prior-only rolling evaluation; never random train/test splitting of adjacent personal windows.
- Stratify or challenge the model for poor sleep, acute illness, unusual exertion, taper-phase changes and missing data.
- Report calibration, MAE/Brier score, interval coverage, abstention, false pattern episodes and burden—not only correlation.
- Require qualified matched personal data and an untouched prospective period before showing any forecast.
- Treat a repeated association as personal evidence, not proof of cause.

## Adrenal-specific truth boundary

Current glucocorticoid-induced adrenal-insufficiency guidance relies on clinical and biochemical evaluation. Symptoms of glucocorticoid withdrawal can overlap with adrenal insufficiency, so wearable changes cannot separate them reliably ([Endocrine Society/ESE guideline](https://www.endocrine.org/clinical-practice-guidelines/glucocorticoid-induced-adrenal-insufficiency)). The product may say:

> Fatigue/function and recorded context changed from your personal pattern. The watch cannot determine the cause. Review the timeline and follow your clinical plan if symptoms concern you.

It may not say:

- adrenal insufficiency or adrenal crisis detected, predicted or excluded;
- estimated cortisol or HPA-axis recovery;
- take, skip, increase, decrease or change a glucocorticoid dose/taper;
- delay urgent care because sensor values appear normal;
- “adrenal fatigue,” which is not a recognized medical diagnosis ([Healthdirect](https://www.healthdirect.gov.au/adrenal-fatigue)).

## Symptom-first safety route

The safety route is deterministic, medically reviewed for Australia and independent of every model score. It must not wait for adequate watch contact, a forecast, an Ollama response or clinician-observer availability. When its reviewed criteria are met, it displays the participant's clinician-authored emergency plan and urgent-care instructions; the AI cannot change, suppress or downgrade them.

Healthdirect advises calling triple zero or attending an emergency department for symptoms of an adrenal crisis and notes that urgent help is still needed even after following a clinician-provided emergency-dose plan ([Healthdirect](https://www.healthdirect.gov.au/addisons-disease)). Version 0.6 does not implement or claim that questionnaire.

## Promotion gates

This lane remains hidden research until all of the following are met:

1. context/outcome wording and burden are reviewed with the participant and clinician;
2. the symptom route is clinically and human-factors reviewed independently;
3. physical watch signals pass exact-device quality/reference testing;
4. at least 28 effective baseline days and the frozen minimum prospective outcomes exist;
5. prediction improves over persistence without worse calibration, abstention or burden;
6. no adrenal-specific output is derived from wearable data alone;
7. privacy, export, withdrawal and deletion drills pass.

No private result generalizes to another person, and no commercial or clinical claim follows without external prospective validation and the applicable regulatory pathway.
