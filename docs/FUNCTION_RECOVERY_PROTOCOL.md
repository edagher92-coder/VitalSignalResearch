# Standardized function and recovery research protocol

Status: candidate `function-recovery-v1`, 23 August 2026. This is a protocol contract for external clinical, exercise-physiology and human-factors review. It is not participant-facing instruction, exercise prescription, medical clearance, a frailty/fall/VO2 assessment, or a diagnostic pathway.

## Research question

For the same person, can a fully qualified, repeated low-volume movement observation produce a stable **measured task-time and physiological-response history** that is useful to compare with their separately reported fatigue and function? The first input is a reviewed five-times sit-to-stand (5xSTS). A fixed-route walk is a later, separately reviewed phase; it is not activated merely because 5xSTS data exist.

The purpose is repeatability, not to make a person work harder, reach a target heart rate, or determine whether they should exercise. The app does not decide whether someone can stand or walk.

## Non-negotiable safety and claim boundary

- A study-team clinical/exercise-physiology review defines whether a protocol may be offered, the environment, observer role, and the site-specific stop/response plan. Its receipt is evidence of review for **research capture only**, never app-generated participant clearance.
- A participant or observer may stop or decline for any reason. A stopped, incomplete or deviated session is retained as a non-comparable record and produces no function/recovery score.
- A human concern is an independent input. `CONCERN_REPORTED` or `NOT_CAPTURED` holds the episode for human review even when every sensor is high quality. The product must not ask or infer a red-flag questionnaire until that questionnaire and its response route have separately received external clinical review.
- The app must not initiate, coach, progress, repeat, compensate for, or prescribe the task. It must not use a pass/failed result to offer reassurance, clearance, training advice or an emergency disposition.
- No result may be labelled frailty, fall risk, disability progression, VO2max, aerobic fitness, cardiovascular disease, POTS, hypotension or recovery/safety-to-train. A task-time change and a heart-rate/recovery feature are observations with an unknown cause.

The 5xSTS has good group-level test–retest reliability, but methods and populations vary ([Muñoz-Bermejo et al., 2021](https://pmc.ncbi.nlm.nih.gov/articles/PMC8228261/)). That evidence supports a controlled research measure, not a consumer-wearable diagnosis or threshold. Heart-rate-recovery classifications can vary materially within an individual ([Yawn et al., 2003](https://pubmed.ncbi.nlm.nih.gov/15055414/)); no single recovery cutoff is used.

## Contract shared by every capture

Only an externally reviewed contract can create a candidate `StandardizedResponseEpisode`. The contract stores opaque identifiers, not an in-app procedure:

| Required record | Why it is required | Comparison rule |
|---|---|---|
| Protocol ID and immutable version | Prevents accidental mixing of 5xSTS and walk designs | Exact match |
| External protocol-review and per-session review receipts | Shows that the research workflow, not the app, authorised capture | Both non-blank |
| Human concern state | Keeps human judgement independent of sensor scores | Must be `NO_CONCERN_REPORTED`; otherwise hold |
| Completion state | Separates a complete reviewed observation from decline/stop/deviation | Only `COMPLETED_AS_REVIEWED` is comparable |
| Script, timing, equipment and chair/route markers | Preserves the physical input and timing definition | All required markers present |
| Pre-capture rest and post-capture recovery-window markers | Makes response features time-aligned | All required markers present |
| Device, firmware, sensor provenance and quality | Avoids treating a hardware transition or poor trace as a physiological change | Exact device/firmware match; quality gate |
| Standardization fingerprint | Hash of the reviewed physical setup and marker values | Exact match for history and current episode |

The capture gate has no clinical screen. It can only return: qualified for research comparison, hold for human review, or abstained. It cannot manufacture an external-review receipt or determine eligibility. Caller-supplied receipt identifiers are not authority: an injected verifier must validate the external protocol and per-session review evidence before a completed capture may enter research comparison.

## Candidate phase 1: reviewed 5xSTS

This is the proposed version to take to external review. It must not be shipped as autonomous instructions.

1. The reviewed protocol defines a stable chair/setup identifier, environment, observer support arrangement and a verbatim instruction-script identifier. The source-aligned candidate uses a straight-backed, hard, armless chair fixed against a wall; the source literature commonly describes a 43–46 cm chair and arms folded across the chest. Any departure is a new configuration, not a comparable session.
2. The externally reviewed workflow may include an observer-administered demonstration and any screening/one-rise step required by its approved source protocol. The application neither performs nor interprets that screen.
3. The only primary function outcome is **observed 5xSTS completion time in seconds**, with its timing convention frozen: start on the reviewed start command; finish at fully upright posture after the fifth stand. Timer source (observer/manual/video/reference) is recorded. The product displays it only as a measured protocol time, never a pass/fail score.
4. The task endpoint timestamp is the fifth-upright event. All recovery feature windows are anchored to that exact endpoint: `0–30 s`, `30–60 s`, and `60–120 s`. The exact pre-capture quiet-rest duration and clock source are frozen in the reviewed contract and included in the fingerprint; the v1 candidate uses a 3-minute seated rest only if the reviewer accepts it.
5. A distinct manual or consented-video timer is the reference for task time during validation. A watch-only detection may be explored but cannot replace that reference before its exact-device error and failure modes are characterised.

The NIH LIFE/SPPB procedure documents arms folded, five rapid rises, timing from the command through the fifth full stand, a chair against a wall, and observer safety positioning ([LIFE physical-measurements manual](https://agingresearchbiobank.nia.nih.gov/studies/life/documents/download/Manual_of_Procedures_Pilot/16.pLIFE-MOP%20Chapter%2016-Physical%20Measurements.pdf/)). It also shows why setup and observer behaviour are part of the protocol rather than sensor metadata.

## Candidate phase 2: later fixed-route walk

Activation requires a separate external review, a new protocol ID/version and a fresh reference period. It is not a continuation of the chair-rise protocol.

The candidate is a **10 m timed core at the reviewed, self-selected usual pace**, on a flat, marked, obstacle-free route, with 2 m approach and 2 m deceleration zones that are not timed. The route direction, surface, footwear/assistive-device policy, timing line definition, pace wording, observer arrangement, turn policy and manual/reference timer are frozen in the route configuration marker. A different route, pace wording, timing method or assistive-device policy changes the standardization fingerprint and begins a new reference.

The primary observation is timed-core duration (and derived speed only as a unit conversion); it is not a gait diagnosis or mobility classification. The endpoint timestamp is the finish line crossing. Pre-capture rest and recovery windows use the same fixed windows as phase 1 only if the review accepts them. Different test procedures produce meaningfully different walking speeds, so they must not be pooled ([Cleland et al., 2020](https://pmc.ncbi.nlm.nih.gov/articles/PMC7749042/)).

## Features and outcomes

| Type | Capture | Allowed statement | Prohibited statement |
|---|---|---|---|
| Function | Manual/reference 5xSTS or timed-route duration | “Your measured time in this reviewed protocol changed from your qualified personal history.” | “You are frail,” “your fall risk changed,” or “you lost function.” |
| Movement | Cadence, step count or task-boundary agreement, after exact-device validation | “This movement feature was/was not usable for this protocol.” | Gait disorder, balance disorder or disability inference. |
| Physiological response | Task HR delta and qualified recovery-window features | “This qualified response changed across independent signal families; cause is unknown.” | VO2max, cardiovascular fitness, autonomic diagnosis or exercise clearance. |
| Human outcome | Prospectively recorded fatigue and functional-capacity check-ins, separate from the capture | “This protocol history can be compared with your own later reports.” | “The watch measured your fatigue/recovery.” |

All sensor-derived comparisons use the existing `StandardizedResponseEngine`: same protocol ID/version, device, firmware **and standardization fingerprint**; at least 12 qualified prior episodes spanning 28 effective days; high current quality; unit/provenance matching; and at least two independent feature families for a possible-response-change research signal. A human concern holds before these computations and is never converted into a sensor feature.

## Reference and repeatability plan

1. Freeze one reviewed contract before collection. Assign a new ID/version/fingerprint for every physical or software/timing change.
2. Run an exact-device feasibility set with manual/reference timing and documented observer agreement before analysing physiological associations. Report task-boundary sensitivity/specificity, timing bias/limits of agreement, missingness, sensor quality failures, and adverse/stop/decline burden separately.
3. Collect at least 12 complete qualified episodes across at least 28 distinct local days for each protocol/fingerprint before personal comparison. Do not backfill across a firmware, device, route, chair, script or timing change.
4. Estimate within-person repeatability separately for task time and each feature (median/MAD, ICC with confidence interval where justified, SEM and minimum detectable change). Report first-session/familiarisation effects rather than silently treating them as improvement; the 5xSTS literature notes faster second sessions in many studies.
5. Analyse protocol time and recovery features as different endpoints. Evaluate their association with *future* frozen fatigue/function outcomes against persistence and context-only controls; use prior-only rolling evaluation, calibration/MAE or Brier score as appropriate, abstention, missingness and participant burden.
6. Keep stopped/deviated/held episodes visible in a safety and feasibility denominator, but exclude them from the comparable-response reference. Never recode them as normal, zero impairment or a negative symptom result.

## Release gates

The lane remains research-only until all gates pass:

1. external clinical, exercise-physiology, accessibility and human-factors approval of each physical contract and its stop/response plan;
2. physical-site and observer training/competency check, including timer and setup agreement;
3. exact watch/device/firmware validation against the declared task-time reference and a documented quality-failure analysis;
4. protocol/fingerprint-specific reference maturity and prospective outcome separation;
5. evidence that any personalised association is stable, calibrated, useful beyond persistence and not achieved by excluding burdensome failures; and
6. independent review of all wording, including the human-concern route, before any participant-facing workflow or red-flag question is implemented.

Until then, the only defensible output is a qualified research observation with its provenance and uncertainty. No user should be encouraged to start, repeat or continue a task by the application.
