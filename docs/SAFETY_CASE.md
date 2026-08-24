# Safety case and language policy

## Intended use

Evidessa Research is intended to become a private wellness/research tool for one adult. Version `0.6.0-research` remains personal-data locked while adding a simulator-only forecast explanation, safety-aware motion and contract-alignment tests on top of the tested platform-neutral watch/phone bridging, history, outbox, governance, response-research, clinician-observer and local-AI orchestration components. It is not a medical device, emergency monitor or validated predictor and is not intended to diagnose, treat, prevent, monitor or rule out disease.

The current release decision is GO only for simulator engineering evaluation, including an explicitly generated and unvalidated fixture forecast after prospective check-in/reveal chronology. It is NO-GO for personal-data capture, personal-data forecasts, physical monitoring, model-generated output, clinician reliance, public release or clinical use.

## Core hazards and controls

| Hazard | Current control | Status |
|---|---|---|
| False reassurance | Exact non-diagnostic/symptom-first copy, unavailable state and prohibited-claim validator | Implemented for simulator UI |
| False alarm/anxiety | Correlated-family grouping, coherent-direction test, corroboration gate and calm copy | Partial; persistence episodes, hysteresis, cooldown and nuisance budget are future gates |
| Poor contact mistaken for physiology | Hard quality gate and distinct measurement-unavailable state | Implemented + domain-tested |
| Confounded change attributed to illness | Context-only labels and no causal selection | Implemented in fixture copy; real context pipeline absent |
| Baseline learns an acute event as typical | Mature matched robust baseline with bounded source age, unique provenance and exact context/time checks | Partial; fast/slow freeze policy and physical acquisition-dependency validation are not implemented |
| Model drift after firmware/device update | In-memory firmware/schema quarantine and abstention flag | Partial; bridge study/rewarming not implemented |
| LLM invents diagnosis or instruction | Model output has no free clinical-prose field; it selects reviewed template IDs from a short-lived signature-verified packet and is reverified/audited before delivery | Implemented + adversarial platform-neutral tests; no real model connected or benchmarked |
| Medication harm | Prohibited treatment language and context-only policy | Implemented in simulator copy; no real medication store |
| Missed emergency | Model severity excludes urgent; separate reviewed-symptom flag exists in policy | Boundary only; no reviewed/localized questionnaire or emergency UI exists |
| Corrupt or missing storage treated as normal physiology | Authenticated storage quarantine, unavailable forecast/receipt state and no ACK on uncertainty | Implemented in pure core + fault tests; manifest adapters exist but Android app-startup composition and device execution are pending |
| Hidden forecast leaks before context is captured | Locked forecast projection has no probability/bounds; context and later outcome are separate events | Implemented + domain-tested; instrumented accessibility/log leakage tests pending |
| Withheld forecast leaks through an explanation surface | Explanation exists only with an available probability; concern, learning, abstention and failed reveal clear probability, interval and explanation together | Implemented + repository failure-path tests; device accessibility/log leakage tests pending |
| Explanation asserts a reason not derived from the estimator | Typed diagnostics bind raw, weighted, prior and posterior rates; scenario-specific reasons derive from the sealed feature snapshot | Implemented for simulator; external human-factors review pending |
| Motion implies a live connection or rewards a health result | No continuous score, pulse, heartbeat or observer-live animation; concern stops ornamental surfaces; system reduced-motion disables value transitions | Implemented in 0.6 phone/prototype source; device reduced-motion verification pending |
| False phone receipt causes watch deletion | ACK requires authenticated bytes and durable commit; exact identity/digest plus durable replay claim gates deletion | Implemented in pure core; complete physical outbox crash recovery pending |
| Stale clinician feed appears normal | Separate live/delayed/stale/no-data states; physiology cannot replace availability; observer coverage is explicit | Implemented + platform-neutral tested; no portal/backend exists |
| Patient assumes a clinician is always watching | Research and regulated modes have different immutable labels; active observation requires both live data and active coverage | Implemented + platform-neutral tested; human-factors testing pending |
| Unacknowledged research item is treated as handled | Signed actor/role/action/version permits plus atomic alert-state-and-audit CAS, acknowledgement deadline and escalation state | Implemented + platform-neutral tested; no clinical escalation service exists |
| Watch respiratory context is mistaken for lung monitoring | Product boundary states that SpO₂/breathing estimates do not measure airflow, lung volumes, carbon dioxide or blood gases | Policy implemented; reference testing pending |
| Wearable fatigue pattern is mistaken for adrenal function | Fatigue/function are prospectively user-scored; glucocorticoid, illness and symptom fields are context; cortisol/ACTH/electrolytes/BP remain external clinical evidence | Typed context + protocol implemented; no adrenal detection or symptom triage active |
| Normal sensors override how the person feels | `USER_CONCERN` is an independent context event and the UI/safety policy says symptoms outrank scores | Contract/policy implemented; reviewed escalation UI remains pending |
| A recorded concern is mistaken for a notified professional | Simulator copy states that nobody was notified; report/resolve authority is journalled and an unavailable journal fails safe | Platform-neutral ledger/journal implemented; no attended service, notification integration or clinical response exists |
| Functional test causes harm or becomes exercise clearance | Protocol activation requires separate screening/stop-rule review and valid same-protocol conditions; output is change-from-personal-reference only | Research protocol/engine gate only; no unsupervised clinical activation |
| Research feed is assumed to be a deployed backend | API uses a non-routable placeholder and documentation states contract-only/not attended | Contract implemented; service, IAM, portal and operations absent |

## Safe copy

Allowed:

> Your overnight cardiovascular and sleep patterns have been more unusual than your personal baseline for two nights. Higher resting heart rate, lower HRV and shorter sleep contributed. This can occur with exertion, disrupted sleep, stress, illness or other factors. Confidence: moderate.

> Unable to interpret reliably because motion or watch contact reduced measurement quality.

Prohibited:

- “IBD flare detected.”
- “Infection predicted.”
- “Your adrenal function is low.”
- “You are dehydrated.”
- “It is safe to exercise.”
- “No arrhythmia.”
- Any instruction to change a medicine, dose, taper, infusion or antibiotic.

## Escalation boundaries

The model may produce informational, watch or check research states, while the UI also exposes learning, unavailable, typical and remeasure states. These simulated states are not yet validated for personal display. The model cannot independently issue an emergency diagnosis. Urgent guidance may be added only through a separate deterministic route whose questions, wording, geography and emergency contacts have been medically and legally reviewed; that route is not present in version 0.6.

The same boundary applies to adrenal-risk context: the app cannot detect or exclude adrenal insufficiency/crisis or generate a glucocorticoid dose. User-reported concern and symptoms must bypass model availability; a normal watch pattern cannot downgrade a clinician-authored emergency plan.

A scheduled research observer can review quality-labelled data, but that does not replace hospital telemetry, medical alarms, ordinary clinical review or emergency services. Regulated monitoring and clinical-rule alerts stay locked until clinical performance, human factors, quality-system and regulatory evidence authorises the exact intended use.

The UI must always include:

- “This app cannot diagnose or rule out a medical condition.”
- “How you feel matters more than the score.”
- a visible data-quality/confidence state;
- a path to repeat a measurement or record context;
- the local emergency instruction when a user reports a reviewed red-flag symptom.

## Release gates

No public/commercial release until:

1. privacy/security threat modelling and penetration testing pass;
2. Samsung partnership and distribution requirements are satisfied;
3. intended use and regulatory classification are reviewed in each launch jurisdiction;
4. clinical, human-factors and accessibility reviewers approve copy and flows;
5. analytical performance is demonstrated on exact supported hardware/firmware;
6. prospective performance, calibration, abstention and nuisance alert burden meet locked criteria;
7. incident response, model rollback, consent withdrawal and data deletion are operational.
