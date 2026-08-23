# Backend and clinician platform

Status: `0.5.0-research` domain logic and API contract only. No service is deployed, no clinician portal is connected, and nobody is continuously watching.

## Purpose

The future backend supports scheduled research observation first and a separately regulated clinical service only after its exact intended use, sensor set, workflow and escalation pathway are authorized. It is not a generic health-data lake and is not the primary personal store for the private pilot.

## Data minimisation

The ordinary observer feed carries only protocol-required, quality-qualified summaries with source time, gateway time, sequence, consent generation, device/firmware/schema and provenance. Raw ECG/PPG is retained locally or in a separately consented research repository; it is not continuously streamed to a clinician dashboard.

## Trust boundaries

1. The Watch collects only after exact consent and capability gates.
2. The phone authenticates, durably commits and acknowledges the packet before any derived sharing.
3. The clinical gateway requires a short-lived composite permit bound to the exact subject, consent generation, session, observer, metric, data class, destination and purpose.
4. Regulated mode additionally binds the exact medical feature/version/environment and current promotion evidence.
5. Every alert action requires a signed, short-lived actor/role/action/alert/version permit.
6. Alert state and its audit record commit atomically.
7. FHIR-shaped drafts preserve source/gateway provenance and the audit stores a canonical digest of the exact draft.

## Observer experience

The portal must distinguish:

- live;
- delayed;
- stale;
- no data;
- quality blocked;
- authorization blocked;
- session inactive;
- clock untrusted;
- sequence invalid.

It separately shows whether a named observer heartbeat is currently valid. A scheduled session without a current heartbeat says **not continuously observed**. Missing or low-quality data never appears as a normal physiological value.

## API contract

The versioned contract is [`backend/openapi/vitalsignal-research-observer-v1.yaml`](../backend/openapi/vitalsignal-research-observer-v1.yaml). It uses non-routable placeholder servers and defines the intended mutations, idempotency, permits, provenance and result states. It is not evidence that an HTTP server, database, authentication provider or FHIR destination exists.

## Deployment gates

- Australian intended-use and TGA classification review;
- health-service clinical governance and escalation ownership;
- independent clinical safety and human-factors review;
- IAM, OAuth/mTLS, managed keys, tenant isolation and penetration testing;
- data residency, retention, withdrawal, export and deletion execution;
- backup/restore, disaster recovery, incident response and observability drills;
- reference-device and prospective performance evidence for every displayed metric and alert rule;
- destination-specific FHIR/AU Core profile validation rather than a generic conformance claim.

The Australian Digital Health Agency's remote-patient-monitoring guidance and the ACSQHC virtual-care/acute-deterioration standards inform the service design; they do not certify this implementation ([RPM guidance](https://www.digitalhealth.gov.au/healthcare-providers/initiatives-and-programs/digital-health-standards/digital-health-standards-guidelines/get-started/5-standards-for-systems-and-technologies/remote-patient-monitoring), [ACSQHC deterioration standard](https://www.safetyandquality.gov.au/national-standards/nsqhs-standards/recognising-and-responding-acute-deterioration-standard)).
