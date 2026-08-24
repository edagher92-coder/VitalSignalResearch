# Server PC hosting

## Current deployable surface

Version `0.6.0-research` can host only the simulated browser interface in
`prototype/index.html`. The phone and watch applications are installed as APKs;
they are not hosted as websites. `backend/` contains OpenAPI contracts, not a
runnable server, database, identity service, clinician portal or attended
monitoring service.

## Private Windows server deployment

The supported private deployment uses Tailscale Serve. It exposes the simulator
over automatically provisioned HTTPS to devices in the same Tailnet. It does not
use Tailscale Funnel and does not make the simulator public on the internet.

On the Windows server PC:

1. Clone or update this repository.
2. Confirm Tailscale is connected.
3. Double-click `tools/windows/Host-Evidessa-Simulator.cmd`.
4. If Windows reports a permission error, right-click the file and choose
   **Run as administrator**.
5. Open the URL printed by the script from a device connected to the same
   Tailnet. The URL is also written locally to
   `tools/windows/Evidessa-Simulator-URL.txt`.

The route is mounted at `/evidessa`, so an existing root Tailscale Serve route
such as the health-free Ollama connectivity check is left intact. The script
does not reset or replace other Serve routes.

Tailscale Serve is configured with `--bg`, so it resumes after a server or
Tailscale restart. Because Serve reads the repository's `prototype/index.html`
directly, a later `git pull` updates the hosted simulator without another
deployment command. Refresh the browser after the pull.

## Verification

Run on the server:

```powershell
tailscale serve status
$status = tailscale status --json | ConvertFrom-Json
"https://$($status.Self.DNSName.TrimEnd('.'))/evidessa"
```

The page must retain the visible simulator/research labels and must not be
described as real-data collection, diagnosis, emergency monitoring or a
clinician-attended service.

## Stop hosting

Double-click `tools/windows/Stop-Evidessa-Simulator.cmd`. It removes only the
`/evidessa` route and deliberately does not call `tailscale serve reset`, so
unrelated server routes remain untouched.

## Future backend boundary

Do not connect the prototype to personal health data, Ollama, the observer
contracts or a database merely because the static interface is hosted. A future
backend needs the separate authenticated gateway, encryption, tenant isolation,
consent fencing, revocation, audit, retention/deletion, incident response,
penetration testing, clinical governance and validation gates already recorded
in the architecture and safety documents.
