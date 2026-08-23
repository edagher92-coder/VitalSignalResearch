import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const html = await readFile(new URL('./index.html', import.meta.url), 'utf8');

test('keeps simulator and medical safety boundaries visible', () => {
  assert.match(html, /SIMULATED DATA · NOT YOUR HEALTH DATA/);
  assert.match(html, /cannot diagnose or rule out a medical condition/i);
  assert.match(html, /cannot ingest personal data/i);
  assert.match(html, /NOT AFFILIATED WITH SAMSUNG OR APPLE/);
});

test('uses the reversible Evidessa working brand and original Evidence Weave', () => {
  assert.match(html, /<title>Evidessa Research — Product prototype<\/title>/);
  assert.match(html, /<strong>Evidessa Research<\/strong>/);
  assert.match(html, /Your pattern, made clear\./);
  assert.match(html, /data-brand-system="evidence-weave-v1"/);
  assert.match(html, /brand-weave-baseline/);
  assert.match(html, /brand-weave-observed/);
  assert.match(html, /brand-weave-proof/);
  assert.doesNotMatch(html, /VitalSignal Research/);
  assert.doesNotMatch(html, /VitalSignal Scientist/);
});

test('observer preview covers freshness states without implying monitoring', () => {
  for (const state of ['SIMULATED LIVE-STATE · NO STREAM', 'DELAYED · SIMULATED', 'STALE · SIMULATED', 'NO DATA · SIMULATED']) {
    assert.ok(html.includes(state), `missing observer state: ${state}`);
  }
  assert.match(html, /NON-OPERATIONAL OBSERVER PREVIEW/);
  assert.match(html, /NO ATTENDED MONITORING/);
  assert.match(html, /no participants connected/i);
  assert.match(html, /no alerts or paging/i);
});

test('supports top-down summary and bottom-up traceability', () => {
  assert.match(html, /Five-second summary/);
  assert.match(html, /Story → source/);
  assert.match(html, /generated, versioned/);
  assert.match(html, /personal baseline band/i);
});

test('shows a governed replaceable assistant without pretending a model ran', () => {
  assert.match(html, /Evidessa Scientist/);
  assert.match(html, /Reviewed template · no model call · no cloud call/);
  assert.match(html, /Ollama on your server/);
  assert.match(html, /OpenAI Responses/);
  assert.match(html, /Claude Messages/);
  assert.match(html, /Release-policy gate/);
  assert.match(html, /display eligibility—not medical truth/);
  assert.match(html, /cannot diagnose, prescribe or recommend treatment/i);
  assert.match(html, /cannot.*improve itself in production/i);
});

test('keeps navigation and forecast reveal accessible', () => {
  assert.match(html, /aria-controls="today" aria-current="page"/);
  assert.match(html, /id="forecast-title" tabindex="-1"/);
  assert.match(html, /Unvalidated simulated fixture estimate revealed; this is not a health prediction/);
  assert.match(html, /Lower-than-personal-usual energy\/function at \+72h to \+73h/);
  assert.match(html, /requestAnimationFrame\(\(\) => document\.getElementById\('forecast-title'\)\.focus\(\)\)/);
  assert.doesNotMatch(html, /\.evidence-item[^}]+cursor:\s*pointer/);
});

test('human concern visibly overrides the simulated forecast', () => {
  assert.match(html, /id="concern" aria-pressed="false"/);
  assert.match(html, /id="global-concern-action">I feel concerned/);
  assert.match(html, /id="global-concern-hold" role="alert" aria-live="assertive" hidden/);
  assert.match(html, /concernReported \? 'Withheld' : reveal \? '38% fixture probability' : 'Absent from locked view'/);
  assert.match(html, /Simulator concern hold active\. Nobody was notified; wearable forecast withheld/);
  assert.match(html, /Do not rely on the wearable score for reassurance/);
  assert.match(html, /let concernLatched = false/);
  assert.match(html, /Resolve simulator hold by explicit human action/);
  assert.match(html, /This is not medical clearance/);
  assert.match(html, /applyImmediateConcernHold/);
  assert.match(html, /document\.body\.classList\.add\('concern-mode'\)/);
  assert.match(html, /body\.concern-mode #scientist \.grid/);
  assert.match(html, /body\.concern-mode #evidence \.grid/);
  assert.match(html, /No clinician or emergency service was notified/);
});

test('uses qualified non-clinical labels and an accessibility floor', () => {
  assert.match(html, /internal evidence score/);
  assert.match(html, /robust units/);
  assert.doesNotMatch(html, /fixture confidence/);
  assert.doesNotMatch(html, /VERIFIED FIXTURE/);
  assert.match(html, /Accessibility floor: no meaningful status, evidence, provenance or control copy below 12 px/);
  assert.match(html, /select, \.toggle, \.button \{ min-height: 48px; \}/);
});

test('self reports start missing and partial context cannot reveal a forecast', () => {
  assert.match(html, /id="energy-out">Not answered/);
  assert.match(html, /id="fatigue-out">Not answered/);
  assert.match(html, /id="stress-out">Not answered/);
  assert.match(html, /<option value="" selected>Choose 0–10…<\/option>/);
  assert.match(html, /const completeContext = scaleIds\.every/);
  assert.match(html, /Unanswered values stayed missing; forecast remains locked/);
  assert.match(html, /72-hour point assessment · \+72h to \+73h/);
});

test('activity card exposes qualified dose response and recovery as an unvalidated fixture', () => {
  for (const label of [
    'Session steps',
    'Distance',
    'Active time',
    'Qualified avg HR',
    'Persistent peak HR',
    'HR recovery',
    'Cardiac cost',
    'Coverage',
    'Gap state',
  ]) {
    assert.ok(html.includes(label), `missing activity label: ${label}`);
  }
  for (const value of ['1,000', '0.90 km', '10 min', '125 bpm', '130 bpm', '20 bpm', '65.0']) {
    assert.ok(html.includes(value), `missing simulated activity value: ${value}`);
  }
  assert.match(html, /SIMULATED MATCHED WALK · RESEARCH ONLY/);
  assert.match(html, /QUALIFIED FIXTURE · UNVALIDATED/);
  assert.match(html, /activity-exercise-response-v1/);
  assert.match(html, /no cross-family response-change rule met/);
  assert.match(html, /time-weighted heart rate above the session's protocol resting reference/);
  assert.match(html, /cannot establish fitness, readiness, illness, cause, diagnosis, treatment, exercise clearance or medical clearance/i);
});

test('activity low-quality and concern states abstain without manufacturing normality', () => {
  assert.match(html, /id="activity-low-quality-state" aria-pressed="false"/);
  assert.match(html, /id="activity-abstain" role="status" aria-live="polite" hidden/);
  assert.match(html, /Missing time is not inactivity or recovery/);
  assert.match(html, /Low-quality values are absent rather than displayed as zero, normal, or a completed session/);
  assert.match(html, /'ABSTAINED · LOW QUALITY'/);
  assert.match(html, /document\.getElementById\('activity-values'\)\.hidden = !qualified/);
  assert.match(html, /Off-wrist and motion-contaminated fixture time is explicitly missing/);
  assert.match(html, /body\.concern-mode \.activity-response \.activity-analytics/);
  assert.match(html, /HUMAN PRIORITY · ANALYTICS HIDDEN/);
  assert.match(html, /Exercise analytics are hidden because steps, heart rate or recovery cannot reassure/);
});
