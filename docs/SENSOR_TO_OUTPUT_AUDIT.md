# Sensor-to-output research audit

Evidence and platform review date: **23 August 2026**. Target hardware: **Samsung Galaxy Watch Ultra2** paired with an Android phone. This is an engineering and research roadmap, not medical advice, a diagnostic specification or evidence that a feature is clinically validated.

## Bottom line

Exercise distance, steps, pace, elevation and heart-rate summaries are not optional extras. They materially improve the model because they describe the **input or workload** that produced the physiological response. A heart rate of 125 beats/min means something very different during quiet sitting, a level walk, a steep climb and a boxing interval. Activity data can therefore:

- prevent ordinary exertion from being mislabeled as physiological strain;
- separate a change in behaviour (fewer steps or shorter walks) from a change in response (more cardiac cost for the same work);
- create repeatable input-output tests, such as the heart-rate response and recovery after the same route;
- provide exposure variables for next-day fatigue and sleep forecasts; and
- quantify whether a forecast remains useful beyond simple baselines such as yesterday's fatigue, recent sleep and recent activity.

The strongest near-term product is consequently **quality-qualified cardiac/autonomic monitoring conditioned on activity, sleep and context**, not heart monitoring alone and not a generic daily score. The most valuable derived signal is likely to be a person's **response-and-recovery fingerprint under comparable workload**. This is a defensible research hypothesis, not a proven disease marker.

A simple all-day average heart rate is a weak feature because wear time, exercise, missingness and activity mix change it. VitalSignal should instead calculate context-specific summaries—sleeping/resting heart rate, exercise-specific time-weighted heart rate, heart-rate distribution by qualified activity state and matched-workload residuals—and always report coverage.

**Current implementation boundary:** version 0.6 includes platform-neutral daily-activity and same-protocol exercise analytics that are directly unit-tested with synthetic fixtures. They enforce complete qualified-or-explicit-gap accounting and calculate descriptive dose, time-weighted/persistent heart-rate, personal-band time, matched-workload cost, fixed recovery and drift only when quality, provenance and comparability gates pass. The phone/prototype activity card is a reviewed fixture snapshot rather than a live runtime binding to that engine. No physical Watch, Samsung SDK, reference-device, prospective, clinical or medical validation is implied.

## Evidence labels used in this audit

- **Documented:** the vendor or Android API documents the route. It still requires capability discovery on the exact watch, firmware, region and service version.
- **Measured:** a qualified observation from a validated physical adapter. Version 0.6 has no such personal observations yet.
- **Derived:** a deterministic feature computed from measured inputs; it is not itself a direct sensor measurement.
- **Research hypothesis:** scientifically plausible and testable, but not validated for the Ultra2, this person or the proposed use.
- **External context:** imported data or a user/clinician record. Its source is retained and it is not silently treated as a Watch measurement.

## 1. Hardware and access reality

Samsung's Australian Ultra2 specification lists an optical heart-rate sensor, electrical heart sensor, BIA sensor, temperature sensor, accelerometer, gyroscope, barometer, geomagnetic sensor and light sensor. It also lists multi-constellation location and an 800 mAh battery. Samsung's launch specification identifies L1+L5 dual-frequency GPS. Hardware presence is not the same as third-party API access ([Ultra2 Australian specification](https://www.samsung.com/au/watches/galaxy-watch/galaxy-watch-ultra2-titanium-silver-lte-sm-l715fzsaxsa/), [Samsung launch specification](https://news.samsung.com/ph/samsung-galaxy-watch-ultra2-and-watch9-your-health-companion-on-the-wrist)).

The public Samsung Health Sensor SDK is currently v1.4.1 and the Samsung Health Data SDK is v1.1.0. The Sensor SDK works only on a physical Galaxy Watch4-series-or-later Wear OS watch; the Data SDK works through Samsung Health 6.30.2 or later on Android 10 or later. Neither SDK supports an emulator. A documented tracker may still be absent on a particular device, and the app must query runtime capabilities ([Sensor SDK overview](https://developer.samsung.com/health/sensor/overview.html), [Sensor SDK introduction](https://developer.samsung.com/health/sensor/guide/introduction.html), [Data SDK overview](https://developer.samsung.com/health/data/overview.html)).

For a private developer test, Samsung documents a developer mode that temporarily bypasses Sensor SDK package/signature registration and a separate Samsung Health developer mode for Data SDK reads. Public distribution still requires the applicable Samsung partnership and registered package/signature. Data SDK writes require the partnership access code even in the development path ([Sensor SDK developer mode](https://developer.samsung.com/health/sensor/guide/developer-mode.html), [Sensor app verification](https://developer.samsung.com/health/sensor/guide/app-verification.html), [Data SDK developer mode](https://developer.samsung.com/health/data/guide/developer-mode.html)).

### 1.1 Samsung Health Sensor SDK: exact public tracker surface

In the table, `READ_ADDITIONAL_HEALTH_DATA` means Samsung's exact runtime permission string `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA`. For an app targeting Android 16/API 36 or later, Samsung maps trackers to the granular health permissions shown below. At target API 35 or earlier, Samsung documents `BODY_SENSORS` for the health trackers instead; accelerometer uses `ACTIVITY_RECOGNITION`, and sweat loss requires both `ACTIVITY_RECOGNITION` and the applicable health-sensor permission. Permission grant is user consent, not proof that the tracker exists.

| Tracker | Data and documented cadence | Operating boundary | API 36+ permission | Important failure/quality boundary | Power/logging implication |
|---|---|---|---|---|---|
| `HEART_RATE_CONTINUOUS` | Processed HR and IBI, 1 Hz HR events; 0–4 IBI values and matching status values can accompany an update | Continuous until listener removal; screen-off data can be batched | `READ_HEART_RATE` | Preserve HR status: movement, weak PPG, detached, higher-priority sensor and success are distinct. Preserve every IBI status. | Samsung calls continuous trackers low consumption but publishes no tracker-specific current. About 600 HR samples can be delivered in a 10-minute screen-off batch. |
| `PPG_CONTINUOUS` | Raw green/red/IR PPG, 25 Hz; requested wavelength set is selectable | Continuous; multiple continuous trackers may coexist | `READ_ADDITIONAL_HEALTH_DATA` | Public PPG status mainly reports normal/error or higher-priority conflict; it is not a complete signal-quality score. Derive SQI using waveform, saturation, IMU and contact. | Three 32-bit channels alone are about 1.08 MB/hour before timestamps, status, encryption and framing. Actual storage is higher. Duty-cycle until battery is measured. |
| `ACCELEROMETER_CONTINUOUS` | Raw x/y/z, 25 Hz | Continuous | `ACTIVITY_RECOGNITION` | Motion is both a physiological input and a PPG/HR artifact source. Retain timestamps and status. | Three 32-bit axes alone are about 1.08 MB/hour before overhead. Prefer on-watch features plus short consented raw windows. |
| `SKIN_TEMPERATURE_CONTINUOUS` | Processed skin and local ambient temperature; cadence not publicly specified | Continuous; Watch5 series or later | `READ_SKIN_TEMPERATURE` | Skin temperature is not body/core temperature; fit, room, bedding, perfusion and charging/off-wrist periods matter. | Quantify on Ultra2; do not infer cadence or power from the callback interval. |
| `EDA_CONTINUOUS` | Raw electrodermal activity, 1 Hz | Continuous; Samsung documents Watch8 series and later | `READ_ADDITIONAL_HEALTH_DATA` | Ultra2 support must be proven by capability discovery. Contact, motion and temperature can dominate. It cannot identify an emotion or mental state. | Low rate, but electrode/skin contact yield and incremental power remain physical-test questions. |
| `ECG_ON_DEMAND` | Raw ECG, 500 Hz; the `EcgSet` also documents green PPG, lead-off, sequence and saturation thresholds | Foreground, one on-demand tracker at a time, about/at most 30 s | `READ_ADDITIONAL_HEALTH_DATA` | Finger electrode contact is required. Reject lead-off, saturation, gaps or sequence errors. The effective embedded-PPG cadence and alignment are not documented and must be measured. | Short burst; approximately 60 KB for one 32-bit ECG channel over 30 s before PPG and overhead. Not a continuous monitor. |
| `PPG_ON_DEMAND` | Raw green/red/IR PPG, 100 Hz | Foreground, one on-demand tracker, about/at most 30 s | `READ_ADDITIONAL_HEALTH_DATA` | Motion/contact and channel status; continuous trackers may be invalid during the capture. | High-information short burst; pause conflicting continuous interpretation. |
| `SPO2_ON_DEMAND` | Processed SpO2 and HR; measurement usually takes about 30 s | Foreground, one on-demand tracker; Health Sensor Service 1.3.0+ | `READ_OXYGEN_SATURATION` | Only status `2` is complete. Timeout, low signal and motion are explicit failure states. A value is an estimate, not a blood gas. | Use deliberate remeasurement, not continuous polling. |
| `SKIN_TEMPERATURE_ON_DEMAND` | Processed skin and ambient temperature | Foreground, one on-demand tracker, about/at most 30 s | `READ_SKIN_TEMPERATURE` | Same skin-versus-core and environmental boundary. | Spot protocol only. |
| `BIA_ON_DEMAND` | Processed composition point; BIA magnitude/phase fields are also documented | User-initiated posture and finger-key contact | `READ_ADDITIONAL_HEALTH_DATA` | Profile, age, posture, wrist fit, recent food/fluid/exercise, bladder and skin contact affect repeatability. | Infrequent standardized measurement; not a continuous fluid sensor. |
| `MF_BIA_ON_DEMAND` | Impedance magnitude and phase at 5, 10, 50 and 250 kHz | Watch8-series-or-later documentation; runtime check; user initiated | `READ_ADDITIONAL_HEALTH_DATA` | Same standardization boundary. No edema or hydration diagnosis. | Research fingerprint only until repeatability and reference validity are established. |
| `SWEAT_LOSS` | Processed post-run estimate in mL | Running only; requires user profile plus Health Services steps/min input | `ACTIVITY_RECOGNITION` + `READ_ADDITIONAL_HEALTH_DATA` | Samsung requires >5 minutes, >2 km, estimate >=100 mL and >=80% nonzero HR/SPM with SPM >=110. It is model output, not sweat chemistry. | Exercise-only adjunct; validate against body-mass/fluid accounting before interpretation. |

Primary specifications: [Sensor data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html), [`HeartRateSet`](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.HeartRateSet.html), [`PpgSet`](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.PpgSet.html), [`EcgSet`](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.EcgSet.html), [`SpO2Set`](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.SpO2Set.html), [permissions](https://developer.samsung.com/health/sensor/guide/permission-request.html), [measurement guide](https://developer.samsung.com/health/sensor/guide/measurement-guide.html), [sweat-loss requirements](https://developer.samsung.com/health/sensor/sample/sweat-loss-monitor/overview.html).

Continuous sample cadence and event-delivery cadence are different. Samsung warns that consecutive sensor timestamps can vary even when nominal sample frequency is fixed, preserves data-point order, and batches screen-off data. Feature computation must use measurement timestamps, never callback arrival time. `flush()` is a controlled lifecycle operation, not a timer ([Samsung tracking-data guide](https://developer.samsung.com/health/sensor/guide/tracking-data.html)).

The Sensor SDK does **not** read or write Samsung Health. Live Sensor SDK events and phone-side Samsung Health history are different acquisition systems and must retain separate provenance ([Samsung Sensor SDK FAQ](https://developer.samsung.com/health/sensor/faq.html)).

### 1.2 Standard Wear OS sensors and Health Services

The Ultra2 hardware list supports a second, non-Samsung-specific lane. At runtime, Android `SensorManager` may expose the gyroscope, barometer, geomagnetic and ambient-light sensors. GPS/location is available through the normal Android and Health Services exercise routes. Access, rate, batching and background behaviour must be enumerated on the physical build; a product specification does not guarantee an unrestricted Android sensor stream.

If exposed, gyro plus accelerometer can improve turns, transitions, orientation and motion-artifact rejection; barometer can support relative elevation/grade after weather-pressure correction; geomagnetic data can support heading quality; and ambient light can describe local light timing. Wrist lux is not retinal/melanopic exposure, barometric pressure is not blood pressure, and none of these auxiliary channels is an independent confirmation of a health change. Their first role is context and sensor validation.

For `SensorManager`, a requested sampling period is only a hint. Hardware FIFO batching with a positive report latency can reduce application-processor wake-ups, while non-wake-up sensor events can be lost when the processor sleeps and wake-up sensors have significant power implications. Android warns that leaving unnecessary sensors enabled can drain a battery within hours. The physical inventory must therefore record each sensor's wake-up flag, reporting mode, minimum delay, FIFO depth, observed sample/event cadence and screen-off loss before any continuity claim ([Android `SensorManager`](https://developer.android.com/reference/android/hardware/SensorManager)).

Health Services data types are representations, not guarantees of which physical sensor or algorithm generated them. Google explicitly notes, for example, that `DISTANCE` can come from GPS or steps. Every passive and exercise request must be checked against device and exercise-type capabilities ([Health Services `DataType`](https://developer.android.com/reference/androidx/health/services/client/data/DataType), [Health Services compatibility](https://developer.android.com/health-and-fitness/health-services/compatibility)).

| Health Services route | High-value documented types, subject to capability | Cadence/lifecycle reality | Primary use in VitalSignal |
|---|---|---|---|
| `PassiveMonitoringClient` | Wear OS compatibility requires HR, steps/daily steps, distance/daily distance, speed, daily calories, elevation gain/loss and floors; walking/running steps and health events are device dependent. `FALL_DETECTED` is a documented health-event type but must be requested and capability-checked. | Long-lived. HR can be sampled anywhere from every second to every ten minutes and the interval is not exposed; service delivery is batched with no predictable interval. A service registration can survive the app process; a callback registration cannot. Neither persists across **watch reboot**. | Battery-aware baseline and trigger spine, never a live clinical feed. A fall event is an input to a separately validated confirmation/escalation workflow, not proof of injury or continuous attendance. |
| `ExerciseClient` | All devices expose exercise HR, watch-GPS location, steps, distance, speed, pace, elevation gain and calories where appropriate to the exercise; cadence, elevation loss, running dynamics and other metrics are optional | Most metrics are sampled at about one-second intervals; exercise HR is sampled once per second, but unchanged values may not be emitted and screen-off delivery can batch. One app owns the exercise. A missing callback for five minutes may terminate it. Pausing can stop GPS. | User-started workout, route/workload, response and recovery measurement. |
| `MeasureClient` | Device-supported rapid measurements such as HR | Short-lived while the user views a measurement; increased sampling costs power; not a long subscription | Visible spot check or short validation screen. |

Passive registrations must be restored after `BOOT_COMPLETED` through WorkManager; Google warns Health Services may need 10 seconds or more to acknowledge registration during boot. Physical permission loss must be handled explicitly. For an app targeting API 36+, passive background body-sensor access needs the granular health permission plus `READ_HEALTH_DATA_IN_BACKGROUND`; API 33–35 uses the legacy body-sensor/background permissions. The user can decline or revoke background access, and `onPermissionLost()` is a required state, not “normal missing data” ([background monitoring](https://developer.android.com/health-and-fitness/health-services/monitor-background), [current Health Services permissions](https://developer.android.com/health-and-fitness/health-services/permissions), [`HealthEvent.Type.FALL_DETECTED`](https://developer.android.com/reference/androidx/health/services/client/data/HealthEvent.Type)).

Exercise sampling and callback delivery must not be conflated: Health Services documents once-per-second exercise HR sampling and approximately one-second sampling for most exercise types, but a device may emit only changes and can batch high-frequency points while the processor/display is non-interactive. Passive HR cadence is deliberately variable and undisclosed. The special screen-off `HEART_RATE_5_SECONDS` batching mode targets five-second deliveries and explicitly “significantly increases power consumption”; actual behaviour remains device dependent. Cached exercise updates can support short process recovery, but the app still needs its own crash-safe session journal and must re-register within the documented window ([Health Services compatibility](https://developer.android.com/health-and-fitness/health-services/compatibility), [`BatchingMode`](https://developer.android.com/reference/androidx/health/services/client/data/BatchingMode), [`ExerciseClient`](https://developer.android.com/reference/androidx/health/services/client/ExerciseClient)).

### 1.3 Samsung Health Data SDK: processed history on the phone

The Data SDK exposes selected Samsung Health records with per-type consent and source/device information. It is the best current route for Samsung-processed historical context, but it does not expose raw Sensor SDK PPG/ECG/IBI/EDA/MF-BIA waveforms.

| Historical type | Useful available detail | Analysis boundary |
|---|---|---|
| Activity summary | Aggregated active time, active calories, total calories and distance, avoiding overlap among Samsung-connected devices | Aggregates hide within-day pattern and source algorithm details. Calories are a low-confidence context feature. |
| Steps/floors | Steps and floors aggregate operations | The current Data SDK lists steps as aggregate-only; do not assume minute-level step samples. |
| Exercise + location | Sessions with duration, distance, calories, route, altitude gain/loss, incline/decline distance, min/mean/max HR, cadence, speed, power, VO2max and time-stamped logs where present | Fields vary by exercise/source. Preserve null as unavailable. A Samsung VO2max or calorie value remains a vendor estimate. |
| Heart rate | Time-bounded series entries with HR, min and max; SDK aggregate supports min/max | Compute any mean from the qualified series with explicit weighting and coverage; never invent an average from min/max. |
| Sleep | Bedtime, wake time, sessions, actual duration, stages and score | Sleep/wake trend is more defensible than treating stages or score as ground truth. |
| Sleep-associated data | Blood oxygen and skin-temperature series associated with the exact sleep record | Keep association ID and availability. Nightly lowest value is artifact-sensitive. Skin is not core temperature. |
| Body composition, BP, glucose, body temperature | Records from Samsung Health and connected sources | Source may be manual, Watch or external equipment. They are not automatically Watch measurements. BP/glucose require their own validated source and scope. |
| Energy Score | Samsung proprietary score | Context only; do not feed it back as an independent physiological confirmation of the same underlying sensors. |
| Irregular-rhythm notification and sleep-apnoea result | OEM result in supported region/device/workflow | Display only within Samsung's exact regulated meaning and provenance; do not reinterpret raw data into a new diagnosis. |
| Profile, nutrition and water | Date of birth, sex/gender field, height, weight, meals/nutrients and water records | Optional covariates/user context; self-report and demographic data require minimisation and fairness review. |

Primary sources: [Data SDK type/operation matrix](https://developer.samsung.com/health/data/guide/features/data-types.html), [data access and provenance](https://developer.samsung.com/health/data/guide/features/data-access.html), [`ActivitySummaryType`](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-activity-summary-type/index.html), [`ExerciseSession`](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-exercise-session/index.html), [`HeartRate`](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.data.entries/-heart-rate/index.html), [`SleepType`](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-sleep-type/index.html), [sleep-associated data](https://developer.samsung.com/health/data/guide/hello-sdk/associated-data.html).

### 1.4 Health Connect: interoperability, not a new sensor

Health Connect can hold steps, distance, elevation, floors, exercise sessions/routes, speed, power, calories, HR, HRV-RMSSD, respiratory rate, oxygen saturation, sleep, skin temperature, BP, glucose, nutrition, hydration and many other records. A record type's existence does not mean Samsung Health or Ultra2 writes it. The current Samsung mapping includes all steps plus exercise session, exercise distance, exercise HR, speed, power and VO2max. Samsung cautions that the synchronized scope can change by Samsung Health version and that non-exercise activity-tracker distance/calorie/power/speed/VO2max data is not synchronized ([Health Connect types](https://developer.android.com/health-and-fitness/health-connect/data-types), [Samsung-to-Health-Connect mapping](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect)).

Health Connect must retain `DataOrigin`, `Device`, `recordingMethod`, record ID and client ID. Do not deduplicate two records only because their times are close. Aggregate only with an explicit origin policy; otherwise a Samsung Health record and a VitalSignal record can double-count the same episode.

Where the experimental Personal Health Record feature is available and a clinical/source app has written it, Health Connect Medical Records can carry FHIR R4/R4B allergies, conditions, medications, labs, procedures, encounters and observations. These records can anchor a medication/lab/visit timeline, but they do not prove causation and do not turn an experimental Watch feature into a clinical observation. Retain the FHIR resource/version/source and keep VitalSignal research outputs in a separate research namespace ([Android Medical Records format](https://developer.android.com/health-and-fitness/health-connect/medical-records/data-format)).

Android's platform `SymptomRecord`, added in API level 37 and U extension 21, can represent a consented symptom as an instant, interval or local-date record with optional severity, count and notes. Its enumerated types include fatigue, abdominal pain, diarrhoea, dizziness, reduced exercise capacity, shortness of breath and palpitations; every symptom type has a separate read/write permission. This is a promising standards-based route for externally written or user-entered outcome context, **not a Watch sensor** and not evidence of the symptom's cause. VitalSignal must capability-check the installed platform, request only the specific symptoms needed, retain origin, and keep the pilot's frozen outcome instrument stable rather than silently switching labels when a new source appears ([Android `SymptomRecord`](https://developer.android.com/reference/android/health/connect/datatypes/SymptomRecord), [API 37 permission additions](https://developer.android.com/sdk/api_diff/37/changes/android.health.connect.HealthPermissions)).

Health Connect grants read/write access by record type. Default reads cannot reach more than 30 days before the app's first permission grant unless the user grants `PERMISSION_READ_HEALTH_DATA_HISTORY` and the installed Health Connect version reports that feature available. Background reads likewise require the separately available `PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND`. Exercise routes require their own consent path or `PERMISSION_READ_EXERCISE_ROUTES`. Change tokens expire after 30 days of non-use, so the sync must recover by bounded reread and identifier-based deduplication. Rate limits favour change-log sync over repeated full scans ([Health Connect permissions](https://developer.android.com/reference/androidx/health/connect/client/permission/HealthPermission), [sync guidance](https://developer.android.com/health-and-fitness/health-connect/sync-data), [feature availability](https://developer.android.com/health-and-fitness/health-connect/features/availability), [rate limits](https://developer.android.com/health-and-fitness/health-connect/rate-limiting)).

## 2. Required data model before any intelligent output

Every observation or derived feature needs:

- measurement start/end timestamps, UTC and local offset/time zone;
- source system, package, device identifier or pseudonymous device key;
- watch/phone model, OS, firmware, Sensor Service/SDK/app versions;
- sensor/tracker and acquisition dependency (for example, HR and HRV share optical/contact/motion ancestry);
- raw/processed/aggregate/derived status and exact algorithm version;
- unit, sampling cadence, event cadence, coverage, gap mask and time-alignment uncertainty;
- permission, consent generation, on-body/contact, motion, battery, charging and thermal state;
- source status codes, signal-quality features, rejection reason and whether the value was user-entered;
- exercise type, active/paused/recovery state, GPS availability and route privacy state; and
- immutable link from forecast to the prior-only inputs and later outcome used to score it.

Missingness is information but is never normal physiology. Charging, off-wrist time, depleted battery, force-stop, reboot, permission loss, another on-demand sensor, another exercise owner, poor contact and sync delay all need distinct gaps. A smooth resume creates a new continuity segment; it never draws a line through an unobserved interval.

## 3. Candidate derived trends

### 3.1 Activity and exercise—the missing half of heart interpretation

| Feature | Calculation contract | Why it helps | Guardrail |
|---|---|---|---|
| Daily movement volume | Steps, distance, active minutes and elevation by local day; 7-, 28- and 90-day robust medians/quantiles; valid-wear denominator | Describes behaviour/exposure and detects sustained reduction or increase | Never equate a low day with illness; partial wear is unavailable, not low activity. |
| Movement pattern | Active/sedentary bout lengths, step cadence distribution, hour-of-day activity, fragmentation/regularity | A person can keep total steps while becoming more fragmented or delayed | Wrist movement can create false steps; validate by activity and mobility level. |
| Exercise dose | Exercise type, active duration, distance, speed/pace, elevation/grade, cadence, route, laps; optional session-RPE | Quantifies external work and lets different sessions be compared | Calories, power and VO2max are vendor estimates unless independently validated. |
| Qualified average/peak HR | Time-weighted trimmed mean/median, percentiles, peak persistence, min, valid coverage and time in prespecified bands during **active** time | Useful internal response to a known session | Do not use sample-count average when cadence is irregular. One-sample peak is noise. Age-predicted max is not a clinical threshold. |
| Matched-workload cardiac cost | Robustly model HR above personal resting level as a function of speed, grade/elevation, cadence, exercise type, temperature and protocol phase; compare residual only with same device/firmware and comparable sessions | A change in response at the same work may be more informative than absolute HR | Cause remains unknown; heat, hydration, medication, sleep, fitness and sensor error confound it. |
| HR response kinetics | Lag, slope and area from workload onset/step-up to HR response | Tests dynamic reserve rather than a static value | Requires accurate clock alignment and repeatable workload. |
| HR recovery | HR drop at fixed 60/120/300 s, recovery slope/area and optional exponential half-life after an exactly defined active or passive recovery | Captures response after a repeatable input | Recovery method must match. Individual HRR can vary; no diagnostic cutoff or exercise clearance. |
| Cardiac drift/decoupling | Compare HR-to-speed, HR-to-cadence or HR-to-external-power ratio in early versus late steady segments, with grade and environment held/modeled | May reveal a changed cost of maintaining work | Research feature only. Heat, fluid balance, pacing, hills and sensor lag can explain drift. |
| Descriptive training strain | Duration in validated/personal HR bands, HR-duration integral, distance/elevation and session-RPE; rolling totals with uncertainty | Helps explain sleep/fatigue and adaptation | Do not convert an acute:chronic ratio into injury risk or a command to train/rest. |
| Recovery-to-next-day link | Relate prior exercise dose and recovery kinetics to preregistered next-morning fatigue/function and qualified sleep | Tests whether exercise features add forecast value | Association, not proof that exercise caused the outcome. |

Device-measured daily steps are associated with major health outcomes at population level, but relationships are nonlinear and device/age dependent; that evidence supports measuring trends, not issuing a universal target to an individual ([2025 systematic review and dose-response meta-analysis](https://doi.org/10.1016/S2468-2667%2825%2900164-1)). A Galaxy Watch4 daily-life study reported about 10.5% group-level mean absolute percentage error versus a thigh accelerometer, reinforcing exact-device step validation ([step-count validation](https://pubmed.ncbi.nlm.nih.gov/39066055/)).

Heart-rate recovery has prognostic associations in clinical exercise-testing cohorts, but its protocol and individual repeatability matter. One study found poor individual concordance across tests, while a standardized UK Biobank study found moderate-to-strong long-term reproducibility of exercise/recovery dynamics. VitalSignal must learn repeatability under one reviewed protocol before treating a change as meaningful ([Cole et al.](https://pubmed.ncbi.nlm.nih.gov/10536127/), [individual HRR variability](https://pubmed.ncbi.nlm.nih.gov/15055414/), [UK Biobank reproducibility](https://pubmed.ncbi.nlm.nih.gov/28873397/)).

Recent Galaxy Watch6 exercise validation reported small median errors in valid treadmill recordings but wide individual limits of agreement and a 22% device-failure exclusion rate. A 2026 multi-watch study found much weaker energy-expenditure accuracy than HR accuracy, especially in resistance exercise. This is why VitalSignal must expose yield/coverage and keep calorie estimates secondary ([Galaxy Watch6 maximal-exercise validation](https://cardio.jmir.org/2026/1/e81917), [HR and energy-expenditure validation](https://pubmed.ncbi.nlm.nih.gov/42076635/)).

### 3.2 Cardiac and autonomic trends

| Feature | Minimum qualification | Output language |
|---|---|---|
| Sleeping/resting HR level | Still/on-body, adequate coverage, activity- and circadian-matched, no conflicting tracker | “Sleeping heart rate was above your prior matched range.” |
| HR circadian rhythm | Several qualified days; local-time/offset integrity; robust mesor, amplitude, acrophase and day-to-day phase dispersion | “Your daily heart-rate rhythm shifted relative to your own history.” |
| IBI coverage and artifact | Normal IBI status, plausible sequence, ectopic/artifact policy, stillness or prespecified sleep state | A quality result before any HRV result. |
| Rest/sleep RMSSD and SDNN | Fixed window/protocol, enough valid normal-to-normal intervals, log transform and confidence interval | “Qualified RMSSD differed from your matched personal distribution.” |
| Activity-adjusted HR residual | Current HR minus expected HR for movement/posture/time-of-day/protocol | “Heart rate was higher than expected for the measured activity”; never a cause. |
| On-demand ECG morphology/timing | Lead-on, unsaturated, sequence-complete 30-s capture; physical reference validation | “This valid recording differs from prior valid recordings”; clinician can inspect waveform. |

A 92,457-person longitudinal wearable study found much greater between-person resting-HR variation than within-person variation, and age, sex, BMI and sleep explained less than 10% of between-person variation. That strongly supports personal baselines over universal averages ([Quer et al.](https://pubmed.ncbi.nlm.nih.gov/32023264/)). Population HRV differs with age, sex, medication and disease, while wrist PPG/IBI error increases markedly during motion; therefore population HRV bands should be context only and qualified rest/sleep comparisons should dominate ([large HRV cohort](https://www.heartrhythmjournal.com/article/S1547-5271%2818%2930472-7/fulltext), [Galaxy Watch PPG/IBI dataset](https://pmc.ncbi.nlm.nih.gov/articles/PMC12119839/)).

### 3.3 Sleep, circadian and thermal trends

- Sleep opportunity, estimated sleep duration, wake after sleep onset, interruptions, bedtime/wake time and midpoint.
- Sleep regularity, social-jetlag proxy, weekday/weekend shift and rolling sleep debt relative to the person's stable requirement estimate.
- Stage proportions only as low-confidence Samsung algorithm outputs; never ground truth.
- Sleeping HR and qualified HRV trajectories, not one nightly score.
- Skin-temperature nightly median/amplitude and residual after ambient temperature, local clock, bedding/environment and menstrual context where voluntarily relevant.
- Cross-rhythm phase alignment among sleep midpoint, activity rhythm, HR rhythm and temperature rhythm.
- Battery/off-wrist-adjusted availability; a missing night cannot become “poor recovery” or “normal.”

Consumer wrist devices show meaningful disagreement from polysomnography, particularly for stages, so the first output should emphasize timing, continuity and within-device longitudinal change ([2024 meta-analysis](https://pubmed.ncbi.nlm.nih.gov/39484805/)). Wrist-temperature rhythm has population-level associations, not individual diagnostic authority ([Brooks et al.](https://www.nature.com/articles/s41467-023-40977-5)).

### 3.4 Oxygen and respiratory context

The public Sensor SDK provides deliberate on-demand SpO2, while Samsung Health can provide oxygen series associated with sleep. Health Connect may contain respiratory-rate records from another authorized source, but the public Sensor SDK does not expose a processed continuous respiratory-rate tracker. A respiratory waveform or rate derived from PPG/accelerometer modulation is therefore an experimental algorithm requiring simultaneous reference validation.

Candidate research features are qualified nightly median/distribution, repeated desaturation-shape candidates, oxygen availability, pulse/oxygen coupling and change from personal history. Do not promote time-below-threshold, desaturation index, nadir or “hypoxic burden” until exact Ultra2 agreement against polysomnography/reference oximetry and event matching are established.

A Galaxy Watch4/5 sleep-clinic study combined optical pulse and accelerometry to estimate nocturnal respiratory rate and reported an overall overnight RMSE of 1.13 breaths/min, with poorer performance in severe obstructive sleep apnoea. This supports an exact-device paired-reference experiment; it does not supply a public Ultra2 respiratory-rate tracker or validate a lung/ventilation claim ([PPG-plus-accelerometer respiratory-rate study](https://pubmed.ncbi.nlm.nih.gov/37766031/)).

Galaxy Watch studies show promising SpO2 agreement in selected controlled and overnight settings, but results from earlier models do not validate Ultra2 or free-living clinical use ([controlled desaturation study](https://pubmed.ncbi.nlm.nih.gov/38005550/), [Galaxy Watch4 overnight PSG study](https://pubmed.ncbi.nlm.nih.gov/38652502/)). The FDA notes that circulation, skin pigmentation, skin thickness, skin temperature, tobacco use and other factors can affect pulse-oximeter accuracy. VitalSignal must stratify validation across skin pigmentation and perfusion and must never let a Watch SpO2 value overrule symptoms ([FDA pulse-oximeter limitations](https://www.fda.gov/medical-devices/products-and-medical-procedures/pulse-oximeters)).

### 3.5 Movement, function and posture-response research

- IMU-confirmed transitions, cadence, gait regularity, turn signatures and postural sway can support an externally reviewed sit-to-stand or fixed-walk protocol.
- The primary outcome should initially be reference-timed task completion or surveyed distance; Watch features are secondary until validated.
- A posture-change episode can align movement with HR/IBI and PPG response, symptoms and an external continuous beat-to-beat BP reference.
- Where the exact watch reports the capability, a Health Services `FALL_DETECTED` event can trigger a confirmation sequence that checks post-event motion/immobility, altitude change, stream freshness and an explicit user response. This is a research input—not proof of a fall, injury, consciousness or need—and cannot imply that anyone is watching or will respond.
- Free-living changes in steps, walking-bout duration or cadence are useful context but cannot diagnose frailty, falls, orthostatic hypotension, POTS or disability progression.

### 3.6 EDA, PPG morphology, BIA and sweat—the exploratory lanes

- **PPG morphology/perfusion:** pulse amplitude, beat morphology, wavelength ratios/agreement and respiratory modulation can help signal quality and generate research fingerprints. They are not BP, arterial stiffness, blood volume or hydration.
- **ECG–PPG fingerprint:** Samsung's ECG event makes synchronized electrical/peripheral morphology research unusually practical, but actual Ultra2 timing must be characterized with reference ECG and peripheral pulse waveform before any pulse-arrival feature.
- **EDA coupling:** tonic/phasic EDA change paired with stillness, HR/HRV and temperature may characterize physiological activation; it cannot label stress, anxiety or intent.
- **BIA/MF-BIA repeatability:** a standardized spectral impedance fingerprint may detect a within-person measurement shift; it cannot diagnose edema or calculate a treatment fluid target.
- **Sweat estimate:** compare repeatability against pre/post nude body mass corrected for intake/output in a safe sports-science protocol; never infer sodium or prescribe fluid/electrolytes.

## 4. Historical, population and age-aware analysis

VitalSignal should use a hierarchy rather than blend everyone into one “normal” score:

1. **Current quality and context:** Is the measurement real, fresh, on-body and comparable?
2. **Matched personal history:** Same local-time band, sleep/activity/posture state, exercise type/protocol, device/firmware and relevant environment.
3. **Personal rolling history:** 7-day acute context, 28–42-day baseline, 90-day adaptation and 365-day seasonal view where coverage permits.
4. **Event-aligned history:** medication/infusion dates, symptoms, illness, travel, menstrual context if relevant, training blocks and clinician/reference measurements.
5. **Population prior:** age/sex/device/population distribution used only to initialise uncertainty or provide optional educational context.

Use robust medians, quantiles and state-space estimates rather than a mean alone. Display both effect size and uncertainty. A population average should never clear a personal concern, and a personal departure should never be converted into a disease label merely because a cohort association exists.

Age and demographic information can improve calibration and fairness analysis, but they also create risks:

- chronological-age formulas for maximum HR are imprecise for an individual and must not become clinical safety limits;
- HRV changes with age and sex, but medication, health state, recording duration and heart rate itself also matter;
- wearable cohorts may underrepresent older, sicker, darker-skinned or lower-income groups; and
- a model must report subgroup performance and refuse unsupported populations rather than quietly extrapolate.

The safest population-to-person architecture is a versioned hierarchical prior that rapidly yields to adequate personal evidence, followed by calibration on strictly prior data. No live model should update its feature definition or safety threshold autonomously.

## 5. Ranked output lanes

Scores are product-development judgments derived from the cited evidence and public API feasibility. They are not clinical evidence grades.

| Rank | Output lane | Evidence / feasibility | First defensible output | Why it ranks here |
|---|---|---|---|---|
| 1 | Signal integrity and continuity | High / high | “82% qualified coverage; 46 min off-wrist; no conclusion for the gap.” | Every other lane fails without it; it is also a competitive trust feature. |
| 2 | Activity-conditioned cardiac/autonomic pattern | Moderate-high / high | Rest/sleep HR and qualified HRV plus activity-adjusted residual, confidence and drivers | HR/IBI are strong accessible signals; activity removes a major confounder. |
| 3 | Exercise dose, response and recovery | Moderate / high for supervised sessions | Distance/pace/grade/cadence, qualified average/peak HR, matched-workload cost and recovery versus personal sessions | Turns the Watch from passive scorekeeper into a repeatable physiology experiment. |
| 4 | Sleep/circadian continuity | Moderate / high | Timing, continuity and cross-rhythm change | High user value and low burden, while avoiding stage-ground-truth claims. |
| 5 | Prospective fatigue/function forecast | Heterogeneous / moderate | Probability/interval for the person's next recorded fatigue/function outcome versus persistence | Fatigue matters and can be truth-labeled, but the Watch does not directly measure it or its cause. |
| 6 | Overnight oxygen/pulse context | Moderate aggregate evidence / moderate | Qualified personal oxygen-stability change with reference-validation status | Useful secondary cardiorespiratory context; not a lung or apnoea monitor. |
| 7 | Standardized function/reserve | Moderate for reference tests / moderate after review | Change in timed task plus cardiac/movement response under exact protocol | Clinically interpretable input, but safety/protocol review is required. |
| 8 | Symptom/treatment/IBD episode atlas | Promising observational / high for logging | Time-aligned physiology, steps, sleep, symptoms and external labs | May reveal personal associations; cannot prove cause or treatment effect. |
| 9 | Heat/workload response | Emerging / moderate | HR-workload and skin-minus-ambient residual during comparable sessions | Useful sports-safety research; no core temperature or dehydration claim. |
| 10 | ECG–PPG timing/morphology fingerprint | Novel / physically testable | Difference from prior valid 30-s recordings, cause unknown | A potential Galaxy-specific moat, but exact cadence/alignment and clinical meaning are unproven. |

In a 309-person IBD cohort, HR/RHR/HRV, steps and oxygenation differed around inflammatory or symptomatic flares and some signals changed weeks earlier. That supports a preregistered personal research lane with external labs and clinician adjudication; it does not validate an IBD detector ([Hirten et al., 2025](https://pubmed.ncbi.nlm.nih.gov/39826619/)).

## 6. Game-changing but defensible hypotheses

These are ranked experiments. None is an active or proven medical claim.

### A. Personal physiological reserve fingerprint

**Hypothesis:** a vector of workload-normalized HR cost, HR-response lag, HR recovery, cadence stability, PPG quality/amplitude and later fatigue is more repeatable and more sensitive to meaningful within-person change than a static readiness score.

**Why it could matter:** the person supplies a known input and the system observes the response. This creates a repeatable input–response experiment rather than passive score aggregation, while still remaining wellness research and not a medical stress test.

**Falsification:** demonstrate test-retest reliability first; then show prospective outcome value above resting HR, sleep and prior fatigue. If it cannot beat those controls, do not ship the combined score.

### B. Activity-normalized silent-strain residual

**Hypothesis:** persistent HR elevation after accounting for measured movement, sleep state, time of day, route/workload and temperature is a cleaner nonspecific change signal than resting HR alone.

**Why it could matter:** it uses activity both to explain normal HR and to identify an unexpectedly high physiological cost. It may detect changes humans miss when looking at steps and HR separately.

**Boundary:** “higher cardiac cost than expected for the measured context; cause unknown,” never infection, inflammation, adrenal, cardiac or pulmonary disease.

### C. Cross-rhythm integrity map

**Hypothesis:** phase dispersion among sleep timing, activity, HR, HRV and skin-temperature rhythms predicts next-day fatigue/function better than any single nightly value.

**Falsification:** compare precommitted circadian features with sleep-duration-only and prior-fatigue baselines using rolling-origin evaluation.

### D. Sensor mesh that proves its own evidence

**Hypothesis:** motion, contact, optical wavelength agreement, HR/IBI status, temperature context and cross-route provenance can estimate when an apparent change is artifact well enough to materially reduce nuisance alerts.

**Competitive advantage:** the output includes an evidence graph and precise abstention reason instead of hiding missingness in one score.

### E. Adaptive sensing cascade

**Hypothesis:** low-power Health Services and HR/IBI monitoring can trigger short raw PPG/IMU or deliberate on-demand captures, preserving most useful information at much lower battery and storage cost than continuous maximum-rate logging.

**Validation:** randomized schedule blocks comparing yield, signal quality, battery percentage/hour, missed prespecified events and skin comfort. A trigger is not a health alert.

### F. Personal intervention and episode atlas

**Hypothesis:** repeated, time-aligned before/after episodes—exercise blocks, clinician-directed medication/infusion timing, illness, sleep disruption—can reveal stable personal associations useful in a consultation.

**Boundary:** observational data are confounded. The model can show “often followed by” with uncertainty, never causation, drug efficacy, dose changes or a self-directed intervention.

### G. Forecast proof ledger

**Hypothesis:** saving every locked forecast before its outcome, then showing calibration, misses, false alerts and model version, creates more value and trust than an opaque “AI” score.

**Promotion rule:** no output is called predictive until it beats preregistered persistence/simple baselines prospectively and maintains calibration after firmware/model changes.

### H. Clinician evidence capsule

**Hypothesis:** a compact export containing the person's concern, exact timeline, qualified source traces, gaps, matched baseline, symptom/medication context and uncertainty is more clinically useful than a live stream of unfiltered consumer-watch numbers.

**Boundary:** unattended transmission is not clinical monitoring. A separate regulated, staffed workflow would need patient enrollment, service-level expectations, alarm ownership, escalation, downtime, audit and clinical validation.

### I. Posture-linked presyncope/autonomic research

**Hypothesis:** a clinically supervised posture-transition protocol combining PPG-derived HRV dynamics, motion-confirmed posture, HR response, symptoms and an external beat-to-beat blood-pressure reference may identify a repeatable personal presyncope trajectory.

**Why it is worth investigating:** a 2026 prospective study enrolled 132 people with suspected neurally mediated syncope during head-up tilt testing and acquired 25 Hz multiwavelength PPG from Galaxy Watch6. An Extra Trees model using 107 HRV features reported AUROC 0.91 for a five-minute presyncope window; at fixed 90% sensitivity, specificity was 64%, with a five-minute lead-time result in the hold-out split. This is a meaningful Galaxy-specific proof of feasibility, not a released Ultra2 feature ([Lee et al., 2026](https://pubmed.ncbi.nlm.nih.gov/42077384/), [Samsung study summary](https://news.samsung.com/global/samsung-announces-world-first-breakthrough-in-fainting-prediction-with-galaxy-watch)).

**Boundary and falsification:** the study used induced head-up tilt, one clinical cohort, Watch6 and a subject-level 80/20 split; it did not establish external, free-living or Ultra2 performance. VitalSignal must not ship a fainting, POTS, orthostatic-hypotension or adrenal-crisis alert from it. A future lane would require protocol review, simultaneous ECG/beat-to-beat BP, external-site validation, free-living false-alert measurement, an attended escalation design and the appropriate medical-device pathway.

## 7. Analysis methods recommended

1. **Deterministic signal qualification first:** status codes, contact, saturation, motion, timestamp continuity, plausible physiology, frozen-sensor detection and acquisition-dependency graph.
2. **Context segmentation:** sleep/rest/wake/exercise/recovery, exercise type, route/grade, charging/off-wrist and local circadian phase.
3. **Robust personal model:** hierarchical population prior only for cold start; robust state-space/circadian model with firmware/device transitions and explicit missingness.
4. **Derived workload-response models:** interpretable regression or generalized additive/state-space models before deep learning; random effects for repeated sessions.
5. **Change detection:** multiple-window robust residual, Bayesian or other preregistered change-point ensemble with persistence and cooldown.
6. **Multimodal fusion:** count independent acquisition components, not metric names. HR and HRV from the same optical tracker are not two confirmations.
7. **Forecasting:** persistence and simple-feature baselines first; then regularized tree/time-series models; missing-aware encoders only in shadow mode until they add prospective value.
8. **Uncertainty:** calibrated probability or prediction interval, conformal coverage where assumptions are checked, and mandatory abstention outside validated support.
9. **Explanation:** deterministic driver attribution from verified features. An LLM may turn approved facts into plain language but cannot calculate the physiology, invent a diagnosis or recommend treatment.
10. **Continuous validation, not live self-modification:** candidates train offline, run frozen evaluations and safety challenges, receive human promotion, and retain rollback.

Large wearable studies support individual baselines and multimodal change detection, but retrospective infection and disease associations are not portable diagnoses ([Mishra et al.](https://pubmed.ncbi.nlm.nih.gov/33208926/), [Alavi et al.](https://pubmed.ncbi.nlm.nih.gov/34845389/)). Google's SensorFM illustrates missing-aware multimodal representation learning; it is a research architecture direction, not evidence for an Ultra2 medical claim ([Google Research](https://research.google/blog/sensorfm-towards-a-general-intelligence-and-interface-for-wearable-health-data/), [SensorFM preprint](https://arxiv.org/html/2605.22759v3)).

## 8. Validation plan and reference equipment

Follow the V3 framework—verification, analytical validation and clinical validation—and the INTERLIVE wearable-validation principles. A software test pass is not sensor validation ([V3 framework](https://pubmed.ncbi.nlm.nih.gov/32337371/), [INTERLIVE framework](https://pubmed.ncbi.nlm.nih.gov/33397674/)).

### 8.1 Signal and feature validation

| Target | Reference | Required conditions | Minimum metrics |
|---|---|---|---|
| HR | Simultaneous research/clinical ECG; validated RR chest strap as practical secondary reference | Supine/seated/standing, household motion, walking/running, boxing/resistance, cold/warm skin; both wrists/fit levels | Availability, failure rate, bias, MAE/RMSE/MAPE, concordance/ICC, Bland–Altman limits and lag by activity/intensity |
| IBI/HRV | Raw ECG R–R intervals with predeclared ectopic/artifact editing | Five-minute stable rest/sleep-like stillness; paced/spontaneous breathing; movement negatives | Beat detection sensitivity/PPV, interval error, qualified coverage; RMSSD/SDNN bias and limits; exact known-sequence unit tests |
| Steps/cadence | Manual/video count and a validated thigh/research accelerometer | 100/500-step walks, slow/normal/fast, stairs, household tasks, mobility variation | Count error/MAPE, false steps during nonwalking, cadence bias and coverage |
| Distance/pace/route | Surveyed track/treadmill; high-quality GNSS reference for outdoor route | Urban canyon/open sky, turns, trees, hills, walk/run, phone present/absent | Total and segment distance error, pace bias, route point error, time-to-fix, missing route and battery/hour |
| Elevation/grade | Surveyed elevation/known stairs plus calibrated barometric/environment reference | Weather/pressure change, stairs/hill, pause/resume | Gain/loss bias, drift, false floors, repeatability |
| Activity state/transitions | Synchronized video/manual labels; research IMU where needed | Rest, typing, vehicle, walk/run, sit/stand/turn | Epoch confusion matrix, event timing error, false transition rate |
| Skin/ambient temperature | Calibrated contact skin thermistor and ambient logger near prespecified sites | Stable room, sleep, controlled ambient shifts, different fit | Bias/limits, lag, availability; never compare wrist skin directly with core as interchangeable |
| SpO2 | Suitable medical/reference oximeter for stable pairing; PSG/reference oximetry for overnight work | Still warm hand, controlled/reference-supervised range, perfusion/skin-pigmentation strata, sleep | Availability/failure, Arms/RMSE, bias/limits, paired-event sensitivity/PPV and dropout; prespecified subgroup performance |
| Sleep | Diary and actigraphy first; PSG only for the exact claim/study | Home and clinical nights, adequate sample across sleep disorders if intended | TST/WASO bias/limits; epoch sleep/wake sensitivity/specificity; stage confusion only as secondary |
| ECG–PPG | Simultaneous multi-lead/reference ECG and peripheral pulse waveform | Lead-on/off, saturation, repeat sessions, posture/temperature | ECG waveform agreement, sequence loss, actual PPG cadence/alignment, beat timing repeatability |
| BIA/MF-BIA | Repeated standardized condition; DXA/BIS or other appropriate reference only for a promoted claim | Same time, food/fluid/exercise/bladder/posture/contact | Completion/yield, within-person CV/ICC, between-day repeatability; agreement only for the exact claimed quantity |
| Sweat estimate | Pre/post nude body mass corrected for intake/output under supervised exercise-science protocol | Safe controlled runs across heat/load; meet SDK requirements | Yield, bias/limits, repeatability, error versus sweat rate; no electrolyte claim |
| Battery/continuity | USB power instrumentation where feasible plus OS battery/thermal traces | Passive, each continuous tracker alone/combined, GPS exercise, adaptive bursts, LTE/Bluetooth/Wi-Fi, charging, low battery, reboot | Percentage points/hour, energy/session, thermal events, sample yield, dropped/duplicate records, resume latency and 24/48-hour survivability |

Reference devices must have calibration/status records. Test the exact Ultra2, wrist, strap fit, firmware, Samsung service/SDK and app version; a Watch6 validation paper cannot transfer its limits of agreement to Ultra2.

### 8.2 Forecast and user-output validation

- Lock every forecast before the outcome is entered and prohibit future leakage. Version 0.6 implements only the frozen +72h-to-+73h point-assessment contract; a next-day/24-hour endpoint would require its own frozen schema, outcome instrument and validation.
- Compare with persistence, rolling personal mean, sleep-only and activity-only baselines.
- For continuous fatigue/function outcomes report MAE, RMSE, rank correlation, prediction-interval coverage and missing-outcome rate.
- For predeclared binary outcomes report prevalence, sensitivity/specificity, PPV/NPV, AUROC, AUPRC, Brier score, calibration intercept/slope and expected calibration error.
- Report abstention/coverage, false alerts per person-week, duplicated alerts, lead time, time under concern hold and whether the result changed an appropriate user action.
- Stratify by activity, motion, skin pigmentation, age, sex, perfusion, wrist, watch fit, battery state, firmware, medication classes where consented and relevant, and missingness.
- Use rolling-origin or blocked prospective evaluation. Never random-split adjacent windows from the same episode across train and test.
- Freeze outcome scales and evaluation before viewing the answer. Human concern and clinician-confirmed events remain independent truth anchors.
- Require external clinical, biostatistical, exercise-science, accessibility and human-factors review before promoting any medical or physical protocol.

## 9. Claims that are not currently allowed

| Requested concept | Defensible current product language | Prohibited implication before an appropriate regulated validation pathway |
|---|---|---|
| “Heart monitoring” | Qualified HR/IBI/HRV, exercise response/recovery, on-demand ECG evidence and exact Samsung regulated results | Continuous ECG, arrhythmia exclusion/diagnosis, ischemia, heart failure, cardiovascular safety or “your heart is healthy” |
| “Lung monitoring” | Spot/sleep-associated oxygen and pulse context; experimental respiratory proxy only after reference validation | Lung function, respiratory airflow, ventilation/CO2, blood gas, pneumonia/COPD/asthma detection, OSA diagnosis or oxygen-treatment advice |
| Fatigue | Prospectively forecast the person's later self-recorded fatigue/function and show contributing associations | Direct fatigue measurement, cause, “fully recovered,” “safe to train/work/drive” or treatment advice |
| Adrenal insufficiency | Show symptom, glucocorticoid, illness and clinician/lab timeline alongside nonspecific physiology | Cortisol estimate, adrenal-insufficiency/crisis detection or exclusion, HPA-axis recovery, stress dose or taper change |
| Infection/inflammation/IBD | “Several qualified systems changed from your own pattern; cause unknown,” with external symptom/lab context | Infection, sepsis, inflammation or IBD/pouchitis flare detection/prediction or reassurance |
| Oxygen/sleep | “Qualified overnight oxygen stability differed; reference validation status X” | Hypoxaemia/apnoea diagnosis or severity from an unvalidated Watch stream |
| Hydration/heat | Sweat estimate and skin/ambient/workload context | Dehydration amount, electrolyte status, core temperature, heat illness or fluid dose |
| Exercise | Descriptive dose, response, recovery and personal association | VO2max/CPET equivalence, injury prediction, exercise clearance, compulsory target or autonomous training prescription |
| Medication/intervention | Time-aligned repeated personal association | Drug efficacy, causation, dose selection, starting/stopping/changing treatment |
| Clinical observer | Freshness, quality and transmission state in a separately enrolled service | Claiming a clinician is watching, replacing hospital observations, an emergency response guarantee or autonomous triage |

The Watch does not measure cortisol, ACTH, sodium, potassium, glucose without an external glucose source, CRP/calprotectin, cytokines, lactate, respiratory airflow, carbon dioxide, core temperature or continuous cuffless BP through the public research sensor surface. Adrenal evaluation depends on clinical and biochemical assessment; concerning symptoms in an at-risk person need a separately medically reviewed route that cannot be downgraded by sensors or AI ([ESE/Endocrine Society guideline](https://www.endocrine.org/clinical-practice-guidelines/glucocorticoid-induced-adrenal-insufficiency)). Wearable fatigue research remains heterogeneous and does not turn physiology into a direct fatigue measurement ([fatigue review](https://pubmed.ncbi.nlm.nih.gov/34975541/)).

In Australia, software intended to diagnose, monitor or predict disease, or support treatment decisions, can be regulated as a medical device. TGA explicitly contrasts a general workout-intensity tracker with a heart-rate warning for people with chronic heart disease; the latter is regulated. A clinician-facing monitoring function is therefore a separate intended-purpose and evidence pathway, not a UI toggle ([TGA wearable guidance](https://www.tga.gov.au/products/medical-devices/software-and-artificial-intelligence-ai/overview/types-software-based-medical-devices/wearable-medical-devices), [TGA wellness-software examples](https://www.tga.gov.au/resources/guidance/understanding-general-health-or-wellness-software-exclusion)).

## 10. Recommended implementation order

1. Physically inventory every Ultra2 Sensor SDK/Health Services/Android sensor capability and record exact service/firmware versions.
2. Complete crash-safe watch collection, boot/process/charging/off-wrist recovery and acknowledged phone transfer before collecting personal data.
3. Start with passive HR/steps/distance plus Samsung Health sleep/exercise history and prospective fatigue/function check-ins.
4. Validate HR, IBI, steps, distance, pace, elevation, timestamps and battery on the exact devices.
5. Ship research-only activity-conditioned cardiac and sleep/circadian views with visible quality and no disease claims.
6. Add user-started exercise response/recovery and same-route comparisons after protocol and exercise-science review.
7. Add short raw PPG/IMU bursts and ECG–PPG experiments only after the lower-power backbone is stable.
8. Add sleep oxygen and experimental respiratory features only with paired reference validation.
9. Prospectively score fatigue/function forecasts against simple baselines; publish misses and calibration.
10. Treat clinician observation, disease prediction and treatment support as separate regulated products with their own clinical service, risk management and trials.

This path offers the most credible competitive advantage: not more unexplained scores, but a personal, multimodal, activity-aware physiology model that can show exactly what was measured, what changed, why the system believes it, when it abstained and whether its prior forecasts were actually right.

## 11. Final output contract

The final output must be an **evidence packet**, not a single readiness number. The phone can summarise the packet in one glance, while every sentence remains traceable to qualified measurements, a frozen feature definition and prior-only history. Cards are withheld rather than estimated when their required evidence is missing.

| Output block | Required input families | Time horizon | Defensible result | Mandatory withholding or warning |
|---|---|---|---|---|
| Evidence state | Contact/on-body status, motion, timestamps, permissions, battery/charging, continuity segments, source/firmware/algorithm provenance | Now and selected analysis window | Qualified coverage, gaps by cause, freshness, time-alignment uncertainty and independent acquisition-family count | “No conclusion” for insufficient coverage, stale data, unresolved clock shift, unverified firmware transition or a gap bridged only by interpolation |
| Current matched state | Sleeping/resting HR, qualified IBI/HRV, movement state, skin/ambient temperature and recent workload | Current qualified window versus matched personal history | Effect size and interval for each validated feature; correlated-domain summary with cause unknown | Learning state, unmatched context, motion-contaminated IBI, insufficient baseline or out-of-support population/context |
| Workload and response | Steps, active minutes, distance, speed/pace, elevation/grade, cadence, exercise type, qualified HR and environment | Session, 7/28/90 days | External dose, cardiac cost for measured work, response lag, recovery and drift versus comparable sessions | Partial wear, mixed exercise ownership, low GPS/step yield, different protocol/device/firmware or no repeatability evidence |
| Sleep/circadian continuity | Sleep/wake timing, interruptions, activity rhythm, qualified sleeping HR/HRV, skin-minus-ambient temperature | Night, 7/28/90 days | Timing, continuity, rhythm phase/regularity and personal change | Missing night, charging/off-wrist interval, travel/clock shift without re-alignment or stage-only evidence |
| Oxygen/pulse context | Complete on-demand SpO2 or sleep-associated oxygen with pulse, contact, motion and coverage | Spot capture or night | Qualified personal oxygen-stability change and reference-validation status | Any symptom concern; incomplete status; poor perfusion/motion; unsupported attempt to infer airflow, lung function, apnoea or treatment need |
| Fatigue/function trajectory | Frozen prospective outcome scale, prior fatigue/function, sleep, activity, workload-response and qualified physiology | Version 0.6 research endpoint: point assessment at +72h to +73h; any next-day endpoint is separate and not implemented | After prospective validation, a calibrated estimate/interval that beats preregistered persistence and simple-feature baselines | Missing outcome history, model drift, uncalibrated subgroup, concern hold, weak coverage or failure to outperform baselines |
| Context and episode atlas | Symptoms, medications/infusions, illness, travel, stress, meals/hydration, exercise and external clinician/lab facts | Event aligned, with predeclared before/after windows | Repeated temporal association with uncertainty and explicit confounders | Single episode, post-hoc window selection, unknown source or any causal/treatment claim |
| Next-best measurement | Current uncertainty, missing modality, battery budget, user burden and approved capture protocols | Immediate or scheduled | One optional measurement likely to reduce uncertainty, or “no further Watch measurement is useful now” | Must never delay care, override concern, trigger an unreviewed invasive/exertional protocol or masquerade as treatment |
| Forecast proof | Immutable forecast commitment, later independent outcome, model/firmware version and abstentions | Rolling prospective history | Calibration, interval coverage, MAE/Brier score, misses, false alerts, lead time and coverage | No cherry-picked periods, no future leakage, no hidden abstentions and no retrospective result labelled predictive |

### 11.1 Independence-aware signal mesh

The final summary counts **independent acquisition families**, not the number of displayed metrics:

1. Optical: PPG, processed HR/IBI and optical SpO2 may share contact, perfusion and motion failure.
2. Electrical: on-demand ECG has a different electrical path, but still shares user posture and watch contact.
3. Motion/workload: accelerometer, gyro, steps and cadence may share algorithms; GPS and barometer add partly independent workload context.
4. Thermal/electrodermal: skin/ambient temperature and EDA have contact/environment dependencies and do not independently name a cause.
5. Impedance: BIA/MF-BIA is a standardized spot experiment, not continuous confirmation.
6. Human/external: symptoms, validated reference devices, clinician observations and laboratory results are independent context or outcomes when their provenance is retained.

For example, HR, resting HR, RMSSD and a proprietary energy score derived from the same optical stream do not constitute four confirmations. A meaningful cross-family pattern could be qualified optical/autonomic change **plus** a motion-normalized workload change **plus** a prospectively recorded symptom, but it still has no disease name until the exact intended claim is validated.

### 11.2 Highest-value cross-signal composites to test

| Research composite | Constituent evidence | Why it may add information | Required falsification test |
|---|---|---|---|
| Personal reserve vector | Matched-workload HR cost, response lag, fixed-method HR recovery, cadence/pace stability, PPG SQI and later fatigue/function | Measures response to a repeatable input rather than passive state alone | Test-retest reliability; prospective incremental value above rest HR, sleep, prior fatigue and activity-only baselines |
| Silent-strain residual | Activity/posture/circadian/temperature-adjusted HR plus qualified HRV and recent workload | Removes common reasons for an elevated HR before flagging a persistent unexplained cost | Compare false-alert rate and lead time against resting-HR-only change detection |
| Cross-rhythm integrity | Sleep midpoint, activity phase, HR rhythm, qualified HRV and skin-temperature rhythm | Phase dispersion may capture disruption that daily means miss | Rolling-origin next-day outcome evaluation versus duration-only and prior-outcome baselines |
| Optical-electrical pulse fingerprint | Synchronized valid ECG and embedded PPG morphology/timing plus lead/contact/sequence status | Galaxy-specific research lane that may reveal repeatable beat-to-periphery changes | Measure exact Ultra2 alignment and repeatability against reference ECG and peripheral waveform; do not infer BP or arterial disease |
| Posture-linked autonomic trajectory | Motion-confirmed posture transition, qualified PPG/IBI/HRV dynamics, symptoms and external beat-to-beat BP | Could test whether a person's pre-symptom autonomic response is repeatable under a reviewed clinical protocol | External clinical validation with free-living false-alert burden; no fainting/POTS/adrenal alert from the research result |
| Recovery debt trajectory | Recent exercise dose, HR recovery, sleep continuity, activity fragmentation and prospective fatigue | Separates behavioural load from physiological response and later lived outcome | Prospective calibration across training, illness, medication and low-activity weeks; show subgroup/condition failure |
| Evidence yield optimizer | Passive HR/activity triggers, battery/thermal state, prior raw-burst information gain and false triggers | Could preserve battery while capturing more informative raw windows | Randomized schedule blocks measuring battery/hour, qualified yield, missed prespecified events and burden |

### 11.3 One-glance message grammar

The top-level interface should always render in this order:

1. **Evidence:** “81% of the selected window was qualified; 42 minutes were off-wrist.”
2. **Observation:** “Heart rate was higher than your matched range for the measured activity.”
3. **Trajectory:** “Your next recorded fatigue/function outcome is uncertain” or a validated calibrated estimate.
4. **Drivers and contradictions:** ranked verified features, including what remained unchanged.
5. **Uncertainty:** data limitations, model support and whether the pattern has repeated.
6. **Action:** a reviewed wellness action, optional measurement or symptom-first safety instruction; never an AI-created treatment.
7. **Proof:** link to source traces, model version and the app's historical accuracy for this exact output.

Concern mode supersedes this sequence. A person's stated concern cannot be cleared by a normal-looking or missing Watch signal.

## 12. Pre-approval testing and data-acquisition ladder

Useful engineering and validation work can begin before Samsung distribution approval without pretending that simulated data validate sensors:

1. Run the app's deterministic scenario simulator for quality, gaps, concern, forecasts and UI states.
2. On a **Wear OS 4+ emulator**, use the Health Services sensor panel and synthetic exercise/events for HR, steps, GPS, duration, elevation, floors, sleep state and fall-event plumbing. Google explicitly says this modern route is emulator-only and cannot validate a physical Watch ([Health Services simulated data](https://developer.android.com/health-and-fitness/health-services/simulated-data)).
3. Install on the real Watch and enumerate Health Services and standard Android sensor capabilities; validate timestamp, batching, reboot and battery behaviour without Samsung raw trackers.
4. Enable Samsung Health Sensor Service developer mode for private raw/processed tracker testing and Samsung Health developer mode for consented Data SDK reads. This bypasses registration only for developer testing, not public distribution or clinical claims.
5. Pair each promoted feature with a predeclared reference protocol and preserve the raw evidence, firmware, fit, environment and failure cases.
6. Keep all forecasts in shadow mode until the prospectively committed outcome ledger proves incremental value and calibration.

Synthetic data verifies software paths. Developer mode verifies that an exact device can expose a tracker. Only physical paired-reference and prospective outcome studies can validate the final signal or claim.
