# Contributing to VitalSignal Research

This private project accepts engineering work only under the simulator-first release boundary in `README.md` and `docs/SAFETY_CASE.md`. A code change must not unlock personal health-data collection, clinical monitoring, diagnosis, treatment advice or a user-facing prediction without the exact governance and validation evidence required by the promotion gates.

## Before a change

1. Read `docs/SESSION_HANDOFF.md`, `docs/STATUS_MATRIX.md` and the relevant protocol.
2. State the narrow hypothesis or defect being addressed.
3. Preserve generated-data fixtures and explicit missingness.
4. Keep Samsung SDK types, Android services, Ollama transport and clinical integrations behind their existing boundaries.

## Required checks

```bash
python3 tools/validate_project.py
node --test prototype/prototype.test.mjs
./gradlew test lint :phone:assembleDebug :wear:assembleDebug
```

The first two checks are necessary but not sufficient. Android, physical-device, battery, reference-device, privacy and prospective clinical evidence are separate gates.

## Non-negotiable invariants

- Missing, stale, conflicting, implausible or low-quality data must abstain rather than look normal.
- A user's concern or reviewed care plan cannot be cleared by a model or reassuring sensor value.
- Forecasts are committed before outcomes and remain hidden until the pre-reveal sequence is durable.
- Every derived result retains device, firmware, protocol, time, unit, quality and provenance bindings.
- Ollama may select reviewed explanation structures; it may not calculate physiology, invent facts, diagnose, alert or change treatment.
- No clinical or commercial claim may exceed the evidence recorded for the exact version and environment.

## Pull requests

Keep changes reviewable, add failure-path tests, update status/build evidence truthfully and identify every untested platform boundary. Do not describe test presence as test execution or simulator behavior as hardware evidence.
