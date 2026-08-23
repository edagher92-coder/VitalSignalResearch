# Galaxy Watch Ultra2 sensor and data mesh

Verified against public documentation on 23 August 2026. “Documented” means an API or hardware capability is described; it does not prove support on a particular Ultra2/firmware/region. Every tracker must pass runtime capability discovery and exact-device testing. Relative power tiers are engineering estimates because Samsung does not publish tracker-level power measurements. The detailed feature, evidence, failure-mode and validation analysis is in [`SENSOR_TO_OUTPUT_AUDIT.md`](SENSOR_TO_OUTPUT_AUDIT.md).

## Live and spot signals

| Signal | Official route/rate | Mode | Key restriction | High-value derived research signal |
|---|---|---|---|---|
| HR + IBI/status | Sensor SDK processed at 1 Hz; up to four IBI values per update | Continuous | Permission/API-level dependent; weak/motion/detached/conflict status must be retained | Qualified RMSSD/SDNN, recovery kinetics, circadian autonomic profile; status-driven quality |
| Green/red/IR PPG | Raw 25 Hz | Continuous | High battery if uninterrupted; physical capability check | Pulse amplitude/perfusion, morphology, respiratory modulation, wavelength agreement, contact/motion SQI |
| Green/red/IR PPG | Raw 100 Hz | On demand, ≤30 s | Foreground; one on-demand tracker at a time; continuous streams may be invalid | Higher-resolution foot, slope, area, morphology and perfusion response |
| ECG + embedded green PPG | ECG raw 500 Hz; PPG values documented inside `EcgSet` | On demand, ≤30 s | Finger electrode; verify real PPG cadence/timestamp coherence on Ultra2 | **ECG-to-pulse timing and morphology fingerprint**, beat consistency and quality—not BP/QT/AF diagnosis |
| SpO₂ | Processed spot | On demand, ≤30 s | Foreground and permission/service version; overnight history is separate | Qualified oxygen stability; paired-reference desaturation burden research |
| Skin + ambient temperature | Processed | Continuous/spot | Continuous cadence not publicly specified; skin is not core temperature | Skin-minus-ambient gradient, overnight thermal recovery, circadian phase/context |
| EDA | Raw 1 Hz on Watch8 generation and later | Continuous | Samsung documents the generation boundary, but Ultra2 support must still be confirmed through runtime capability discovery; motion/temp/contact sensitive | Tonic/phasic conductance and recovery; EDA–HRV coupling—not mental-state inference |
| Accelerometer | Raw xyz 25 Hz through Sensor SDK | Continuous | Preserve timestamp/batching metadata | Gait/turn/transition features, activity fragmentation, micro-movement, motion SQI, respiration proxy |
| Gyro/barometer/geomagnetic/light | Standard Android sensor APIs if vendor exposes at runtime | App-defined | Enumerate each sensor; background/rate restrictions | Turns, stairs/grade, orientation, pressure/elevation and light timing; lux is not melanopic spectrum |
| BIA | One processed composition point | On demand | Standardized posture/profile/contact required | Within-person direction and repeatability—not daily absolute truth |
| Multi-frequency BIA | Magnitude/phase at 5, 10, 50 and 250 kHz | On demand | Watch8+ documentation; Ultra2 runtime check; strict standardization | Spectral impedance/phase fingerprint for fluid-distribution research—not edema/hydration diagnosis |
| Sweat loss | Processed estimate after qualifying run | Post-exercise | Model-based, not sweat chemistry; duration/distance/quality rules | Calibrate within person against mass/intake; not sodium/electrolyte measurement |
| GPS + exercise | L1+L5 hardware; Health Services exposes exercise HR, watch-GPS location, steps, distance, speed, pace, elevation gain and calories on all compatible devices where appropriate to the exercise; cadence and advanced metrics remain capability-dependent | Workout | Exercise HR is sampled once/second and most metrics at about one-second intervals, but unchanged points may not emit and screen-off delivery batches; GPS and frequent delivery increase battery use | Grade-adjusted cardiac cost, HR–speed decoupling, exertional drift and terrain load |
| Health Services passive | Required Wear OS surface includes HR, steps/daily steps, distance/daily distance, speed, daily calories, elevation gain/loss and floors; optional walking/running steps and capability-gated health events such as `FALL_DETECTED` | Passive/batched | HR can be sampled from every second to every ten minutes and the interval is not exposed; delivery interval is unpredictable; registration does not survive watch reboot; a health event is not proof of injury or an attended response | Low-power trigger spine for adaptive sensing; a fall event may enter a separately validated user-confirmation/long-lie research workflow |

Canonical documentation: [Samsung Sensor data specifications](https://developer.samsung.com/health/sensor/guide/data-specifications.html), [`HeartRateSet`](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.HeartRateSet.html), [`EcgSet`](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.EcgSet.html), [`MfBiaSet`](https://developer.samsung.com/health/sensor/api-reference/com/samsung/android/service/health/tracking/data/ValueKey.MfBiaSet.html), [Wear Health Services compatibility](https://developer.android.com/health-and-fitness/health-services/compatibility).

The Samsung Sensor SDK has no emulator. Public release requires Samsung partnership/verification and package/signing registration. An on-demand tracker is foreground-only, one at a time and limited to 30 seconds; continuous trackers can return invalid values during it. [Samsung app verification](https://developer.samsung.com/health/sensor/guide/app-verification.html), [Sensor SDK FAQ](https://developer.samsung.com/health/sensor/faq.html).

## Historical and clinical-context mesh

| Source | Useful data | Provenance/duplication boundary |
|---|---|---|
| Samsung Health Data SDK | Sleep with associated oxygen/skin temperature, HR, exercise/location/VO₂max, body composition, BP, glucose, temperature, Energy Score, steps/floors, nutrition/water, irregular-rhythm notification, sleep-apnoea result and demographics. Exercise sessions may include distance, altitude gain/loss, min/mean/max HR, cadence, speed, power and time-stamped logs where the source recorded them. | Processed phone history only; no raw PPG/ECG/IBI, EDA or MF-BIA spectra. Fields vary by source/exercise. Per-type consent, device provenance and Samsung distribution approval apply. |
| Health Connect | User-authorized HR/HRV-RMSSD, respiration, sleep, exercise, BP, glucose, temperature, nutrition and other records written by apps | It does not create Ultra2 raw data. Preserve origin and deduplicate; most health types are not automatically source-priority deduplicated. |
| Health Connect symptoms | API 37/U extension 21 `SymptomRecord` can hold separately permissioned user/source records such as fatigue, abdominal pain, diarrhoea, dizziness, reduced exercise capacity, shortness of breath and palpitations | It is an outcome/context interoperability route, not a Watch measurement or diagnosis. Capability-check it, preserve origin and keep the research outcome instrument version frozen. |
| Health Connect Medical Records | FHIR R4/R4B allergies, conditions, labs, medications, procedures, visits and vitals when a source writes them | Experimental API; individual/source provenance is essential. Keep research-derived signals separate from clinician-measured facts. |
| VitalSignal encrypted store | Raw qualified bursts, features, context, missingness, model receipts, forecasts and later outcomes | App-owned longitudinal evidence; immutable IDs, exact timestamp/unit/device/firmware/algorithm provenance required. |
| Reference devices/labs | Upper-arm cuff, ECG, oximeter/PSG, thermometer, scale, CGM if clinically present, CPET/walk tests and clinician-ordered labs | These supply validation labels; they are not silently interchangeable with the Watch. |

Sources: [Samsung Health Data types](https://developer.samsung.com/health/data/guide/features/data-types.html), [Health Connect types](https://developer.android.com/health-and-fitness/health-connect/data-types), [Health Connect aggregation](https://developer.android.com/health-and-fitness/health-connect/aggregate-data), [`SymptomRecord`](https://developer.android.com/reference/android/health/connect/datatypes/SymptomRecord), [Medical Records](https://developer.android.com/health-and-fitness/health-connect/medical-records).

## Transmission and logging rules

- Capture source measurement timestamps; never align streams by callback time because Sensor SDK and Health Services can batch asynchronously.
- Store UTC, local offset/time zone, model/firmware/SDK/service version, permission state, battery, on-body/status code, unit, quality, missingness and source device with each record.
- Keep an encrypted, crash-safe watch outbox; Data Layer is transport/synchronization, not durable storage.
- Batch qualified features for routine transfer; preserve explicitly consented raw bursts as Assets/batches with hashes and exact ACK/retry semantics.
- Treat the Bluetooth/cloud relay path as an external transport boundary even though Wear Data Layer states cloud relay is end-to-end encrypted.
- Record firmware/schema transitions, re-run sensor capability discovery and rewarm baselines before comparison.
- Use passive data as the low-power spine, duty-cycle high-cost raw PPG/EDA, and request high-information bursts only after a preregistered trigger or deliberate research protocol.

Wear Data Layer guidance: [overview](https://developer.android.com/training/wearables/data/overview), [synchronization](https://developer.android.com/training/wearables/data/sync).

## Samsung-owned features that are not public raw inputs

Ultra2 marketing describes Vitals, Heart Health Score, BP Trend, AGEs, Vascular Load, Antioxidant Index, ectopic-beat, hearing and diving functions. Their presence confirms hardware/product potential; it does not grant third-party access. The public Data SDK list currently exposes Energy Score, irregular-rhythm notification and sleep-apnoea result, but not those other proprietary scores/signals. Do not scrape, reverse-engineer or imply access ([Ultra2 specification](https://news.samsung.com/global/samsung-galaxy-watch-ultra2-and-watch9-your-health-companion-on-the-wrist)).

## Explicitly unavailable from standard public Watch sensors

Direct cortisol, glucose, lactate, sodium/potassium, cytokines, CRP/calprotectin, haemoglobin/ferritin, core temperature, respiratory airflow, continuous cuffless BP and sweat chemistry are not exposed measurements. The same is true of IBD flare, adrenal crisis, sepsis, heart failure, dehydration quantity and mental state. Models may study nonspecific patterns anchored to external labels; they may not relabel those patterns as direct measurements or diagnoses.
