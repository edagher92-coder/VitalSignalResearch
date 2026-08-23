# VitalSignal backend contract

This directory defines versioned network contracts for a future research observer and governed assistant gateway and, only after separate authorization, a regulated clinical service. It is not a running backend and is not deployed by app version `0.6.0-research`. The OpenAPI `info.version` values are independent technical contract versions.

The observer draft is [`openapi/vitalsignal-research-observer-v1.yaml`](openapi/vitalsignal-research-observer-v1.yaml). The separate contract-only assistant gateway is [`openapi/vitalsignal-assistant-gateway-v1.yaml`](openapi/vitalsignal-assistant-gateway-v1.yaml). The latter is the only component permitted to hold cloud-provider credentials; no provider key, provider authorization header or secret-bearing field exists in its phone-facing schema.

## Required implementation properties

- TLS 1.3 at the public boundary and mutual TLS between managed service components.
- Short-lived OAuth access plus the purpose-, subject-, session-, destination- and consent-generation-bound permits defined in `core:governance` and `core:monitoring`.
- Encrypted storage with separate tenant/subject key scopes and managed rotation.
- Idempotency and exact request-body digests on every mutation.
- Atomic alert-state plus audit commits; no state transition can exist without its audit record.
- Explicit source measurement time, gateway receipt time, sequence, device/firmware/schema and quality provenance.
- Live, delayed, stale, no-data, authorization-blocked and clock-untrusted states; missing data never becomes a normal value.
- Region-appropriate retention, export, pause, withdrawal and deletion executors with completion receipts.
- No general model, database, shell or arbitrary-query access to the clinical data plane.
- Separate research and regulated environments, identities, keys, datasets and release evidence.

Before deployment, the implementation needs independent threat modelling, penetration testing, privacy review, clinical safety/human-factors review, incident response, backup/restore drills, observability, disaster recovery and the applicable Australian regulatory and health-service approvals.
