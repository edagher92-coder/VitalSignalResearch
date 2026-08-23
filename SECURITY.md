# Security policy

VitalSignal Research handles safety-critical design concepts but is currently a simulator-first engineering prototype. Do not submit personal health information, credentials, signing material, private SDK binaries or tailnet addresses in an issue, pull request, test fixture or log.

## Reporting a vulnerability

Use this private repository's **Security → Report a vulnerability** workflow when available. Include the affected version, component, reproduction steps and impact, but use generated fixtures only. Do not publish an exploit or health-data sample in a normal issue.

If private reporting is unavailable, open a minimal issue asking the repository owner to enable a private channel; omit technical exploit details until that channel exists.

## Supported checkpoint

Only the current `main` branch is maintained during the private research phase. There is no deployed service, public APK, clinical monitoring service or supported medical-use release.

## Secrets and health data

- Never commit Android signing keys, transport keys, receipts, OAuth material, `.env` files, proprietary Samsung AARs or generated Ollama endpoint files.
- Never commit raw or derived personal sensor records, symptom notes, medical history, identifiers or exports.
- Use deterministic generated fixtures in tests.
- Treat logs, crash reports and screenshots as potentially sensitive until reviewed.
- Rotate any exposed key and invalidate affected receipts; deleting it from a later commit is not sufficient.
- Keep third-party GitHub Actions pinned to reviewed immutable commit SHAs; mutable tags are not accepted by the project validator.

## Safety-impacting defects

Missing-data coercion, false reassurance, stale data shown as live, unaudited alert mutation, replay acceptance, forecast leakage, concern-hold clearing, provenance loss and simulator/real-mode confusion are release-blocking security and safety defects.
