# Evidessa Research brand and experience system

Status: working brand system for `0.5.0-research`  
Product line: **Evidessa Research**  
Tagline: **Your pattern, made clear.**

Internal R&D programme codename: **HumanCurrent**. This name is for planning,
research workstreams and model-development discussion only; it is not a public
sub-brand, protocol identifier, medical claim or replacement for the preserved
`VitalSignal` technical identifiers in version 0.5.

This document defines a reversible user-facing brand and an original product experience. It does not rename the repository, Android package, Kotlin namespaces, storage aliases, Data Layer paths, OpenAPI headers, signed schemas, audit identifiers or version. Those identifiers remain `VitalSignal` in version 0.5 so a visual/name change cannot silently break data compatibility, cryptographic authority or validation traceability.

`Evidessa Research` is a working candidate. It has not completed trademark, company-name, domain, app-store or international language clearance. Do not present it as a registered or cleared commercial brand until that work is complete.

## 1. Brand proposition

Evidessa turns qualified personal evidence into a calm, traceable explanation of:

1. what changed;
2. what remained stable or contradicted the change;
3. how the observation compares with the person's matched baseline;
4. how much usable evidence supports it;
5. what reviewed next step is appropriate; and
6. whether a prior forecast was later correct.

The brand promise is clarity, not certainty. It should feel observant, rigorous, humane and quietly capable. It must never feel omniscient, diagnostic, alarmist or like an unattended system is watching over the person.

The tagline, **Your pattern, made clear.**, has three deliberate constraints:

- **Your** prioritises the person's matched baseline over a generic league table.
- **pattern** describes a multi-signal observation without naming a disease.
- **made clear** promises explanation and traceability, not prediction accuracy or medical benefit that has not been proven.

## 2. Working name architecture

| Context | Working label | Rule |
|---|---|---|
| Launcher and product | Evidessa Research | Keep `Research` visible for the current unvalidated checkpoint |
| Short conversational reference | Evidessa | Use only after the full name has been established |
| Internal R&D programme | HumanCurrent | Internal planning/model-development codename only; never imply a separate product or silently rename technical identifiers |
| Governed explanation surface | Evidessa Scientist | Advisory explanation over verified evidence; never `doctor`, `nurse`, `clinician` or an attended role |
| Daily landing surface | Today | One clear story, current input quality and one reviewed next step |
| Evidence drill-down | Evidence | Observation-to-source trace, contradictions and provenance |
| Longitudinal view | Timeline | Sensor, context, medication, exercise, forecast and outcome events |
| Consented observer research | Observer preview | Must remain explicitly non-operational until its separate authorization and validation gates pass |
| Internal engineering identifiers | VitalSignal | Preserve through version 0.5; never perform a cosmetic search-and-replace across protocols |

Do not add `Samsung`, `Galaxy`, `Apple`, `Health`, `Medical`, `Clinical`, `Care`, `Monitor`, `AI`, `Doctor` or `Nurse` to the product name without legal, platform, clinical and claims review.

## 3. Original identity: the Evidence Weave

The primary mark is the **Evidence Weave**, an original two-ribbon symbol:

- the upper mint ribbon represents the person's living matched baseline;
- the lower blue ribbon represents the observed response through time;
- the light proof point represents a qualified intersection that can be traced to evidence.

The ribbons approach, cross and continue. They are not an ECG trace, heart outline, activity ring, medical cross, infinity mark or vendor logo. The mark communicates that meaning appears when an observation is compared with its proper context—not when a single number is isolated.

### Living baseline ribbon

The Evidence Weave can extend into a **living baseline ribbon** in trend views:

- a translucent band shows the matched personal reference and its uncertainty;
- an observed line is drawn only where coverage and quality are sufficient;
- gaps remain visible gaps rather than being interpolated into apparent normality;
- contextual events sit on a separate aligned lane and are not drawn as causes;
- a forecast interval, when validated and eligible, is visually different from observed history;
- population or age context is secondary and explicitly labelled as advisory.

The ribbon may animate once when a new qualified record is committed. It must not pulse continuously, simulate a heartbeat or imply a live/attended connection. Reduced-motion settings remove the animation without losing meaning.

### Mark construction rules

- Keep clear space at least one quarter of the mark width.
- Minimum digital mark size: 24 px; use the wordmark below that threshold.
- Render the two ribbons with equal visual weight.
- Keep the proof point subordinate; it is not an alert dot.
- Never rotate the mark into a heart, replace a ribbon with a proprietary vendor glyph, or put it inside an activity-ring composition.
- Monochrome use is allowed when colour cannot carry meaning.

The prototype and Android header use code-native versions of this construction. A production vector master and optical-size review remain future design work.

## 4. Experience synthesis: Samsung-first, platform-respectful

The immediate product is a Samsung-first Android and Wear OS pilot. Samsung Health Sensor SDK, Samsung Health Data SDK, Android Health Services and Health Connect have different provenance and permission boundaries; Evidessa must not flatten them into a generic `Samsung data` claim.

The experience can learn from the strongest public interaction principles associated with Samsung Health and Apple Health while remaining visually and structurally original.

| Reference strength | Principle to learn | Evidessa transformation | Do not copy |
|---|---|---|---|
| Samsung/One UI | Glanceable cards, strong numeric legibility, approachable dark surfaces and watch-to-phone continuity | Calm story card, generous touch targets, one-handed Android actions and a compact Wear capture/quality view | Exact card geometry, icons, colour values, navigation, illustrations or Samsung wordmarks |
| Samsung Health | Exercise/session depth and practical daily context | Matched-workload response, qualified HR/recovery, distance, steps, gaps and provenance below the daily story | Samsung scores, labels, dashboard order or screenshots |
| Apple Health | Editorial hierarchy, restrained density and user-selected summary emphasis | Five-second summary followed by progressive evidence disclosure; important items can later be pinned without hiding safety state | Summary/Favorites screen cloning, Apple heart icon, ring system, tab bar or SF Symbols as product identity |
| Apple privacy patterns | Clear source, permission and sharing boundaries | Every observation can reveal source, device, time, coverage, quality and destination authority | Apple permission wording, artwork or claims of HealthKit integration before implementation |
| Both ecosystems | Accessibility, native motion and platform conventions | Shared Evidessa information architecture expressed through native Android/Wear components now and native iOS components only in a future iOS app | A pixel-identical cross-platform skin that ignores platform behavior |

This is a synthesis of interaction principles, not co-branding. The current interface must state that it is an independent research prototype with no Samsung or Apple affiliation. Samsung and Apple trademarks belong to their respective owners.

## 5. Cross-platform product direction

### Samsung/Android now

- Compose/Material-based phone experience with platform-native accessibility and permission flows.
- Wear OS surface limited to capture state, quality, sensor availability, safe stop/resume and essential concern messaging.
- Samsung-specific data retain exact source, device, firmware, permission and algorithm provenance.
- Samsung Health remains a permissioned history source; it is not the Evidessa database and is not evidence of live streaming.
- The phone owns deeper explanation, evidence trace, forecast ledger, check-ins and privacy controls.

### Apple ecosystem later

A future iPhone/Apple Watch lane would require a separately designed and validated HealthKit/Apple Watch adapter, its own permission model, provenance tests, background-delivery and battery evidence, and App Store/platform review. None is implemented or claimed in version 0.5.

If that lane is approved:

- share the Evidessa evidence schema and information hierarchy, not Samsung-specific assumptions;
- build the interface with native Apple platform components and accessibility behavior;
- preserve source/device provenance when data pass through HealthKit;
- validate feature equivalence rather than assuming Samsung and Apple measurements are interchangeable;
- never compare users across devices until device/firmware/protocol effects are quantified.

## 6. Information architecture

Evidessa uses a top-down story and bottom-up proof path:

```text
One clear story
  -> state + trajectory + input quality + next step
    -> domain observations and contradictions
      -> matched personal reference and contextual comparison
        -> qualified features, missingness and sensor agreement
          -> source record, device, firmware, time and model/version receipt
```

The primary `Today` screen follows this order:

1. explicit data mode and freshness;
2. human-readable state and trajectory;
3. what changed, evidence state and reviewed next step;
4. living baseline ribbon;
5. contributors and contradictions;
6. activity/sleep/context response;
7. trace-to-source affordance;
8. safety and non-diagnostic boundary.

The person's concern always overrides interpretation. When a concern hold is active, forecasts and reassuring analytics are withheld; the UI says no clinician or emergency service was notified.

## 7. Visual system

### Colour roles

| Token | Working value | Meaning |
|---|---:|---|
| Evidence Ink | `#031011` | Primary dark ground |
| Evidence Panel | `#0A1D1E` | Elevated research surface |
| Baseline Mint | `#78EBCB` | Qualified reference and constructive action |
| Observation Blue | `#91CFFF` | Observed signal and context |
| Proof Violet | `#B9B0FF` | Audit/provenance layer |
| Review Amber | `#FFCB72` | Uncertainty, locked or review-needed state |
| Concern Rose | `#FF919E` | Human concern or action-requiring safety state |
| Evidence Ice | `#E7FBF5` | Primary dark-mode text |
| Quiet | `#91AAA7` | Secondary text that still meets contrast requirements |

Colour is never the only carrier of status. Rose must not decorate ordinary trends, and mint must not mean `medically normal`. Every status has text, accessible semantics and a non-colour state marker.

### Typography and spacing

- Use the platform's legible system typeface; do not bundle Samsung or Apple proprietary fonts.
- Use sentence case for human explanations and uppercase only for compact state labels.
- Prefer 12 px/sp or larger for meaningful prototype/status copy and 48 dp minimum interactive targets.
- Use large numerals only when unit, time window, quality and comparison remain visible.
- Use spacing and progressive disclosure to create calm; do not hide uncertainty to make a screen cleaner.

### Voice

Use: `Your overnight resting heart rate was above your matched range, while temperature stayed within its expected band.`

Avoid: `Your health is declining`, `AI detected illness`, `You are safe`, `Your lungs are normal`, `Adrenal fatigue detected`, `Doctor alert sent` or any sentence that outruns the validated evidence and actual service state.

Copy should be:

- direct but not abrupt;
- specific about time, source and uncertainty;
- symptom-first and non-reassuring when evidence is absent;
- explicit about missing data;
- honest about simulator, research and validation state.

## 8. Motion, state and continuity

- The Evidence Weave can settle into its latest qualified state after commit; it does not animate from uncommitted values.
- Reconnect, off-wrist, charging, reboot and battery-drain states display their data gaps before showing a resumed trend.
- A smooth visual resume cannot imply complete physiological coverage. The first post-gap point begins a new qualified segment until continuity rules pass.
- Loading skeletons must not resemble populated measurements.
- Stale, delayed, no-data and low-quality states are distinct in text and semantics.
- Watch and phone can share brand cues, but only the phone exposes dense provenance and analysis.

## 9. Privacy and trust cues

Trust is a product behavior, not a shield icon. The interface must make these answers discoverable:

- What data were used?
- Which device and source produced them?
- What time and coverage do they represent?
- What was missing or rejected?
- Was a provider model used, and if so under which signed policy/model receipt?
- Was anything shared, with whom, for what purpose and until when?
- Can collection, sharing and analysis be paused independently?
- What remains on the watch if the phone is offline?

Never use a decorative `secure`, `private`, `medical grade`, `clinician connected` or `live` badge without the exact operational evidence behind that state.

## 10. Anti-copy and independence boundary

The following are release blockers:

- Samsung or Apple logos, health icons, proprietary illustrations or screenshots in the Evidessa identity;
- a heart outline, four-colour activity rings, copied vendor card order, copied tab structure or vendor-identical motion as the primary identity;
- Samsung/Apple names in the product name, logo lock-up or endorsement language;
- importing proprietary fonts, icons, sounds or design files without an explicit licence;
- claiming Samsung Health, HealthKit, Galaxy Watch or Apple Watch integration before the exact adapter is implemented and tested;
- describing UI familiarity as a formal collaboration, endorsement or certification;
- removing the independent-research disclosure from simulator and pre-release pilot surfaces.

Platform-standard controls, accessibility behavior and navigation conventions may be used where required for a native, safe experience. The product-level composition, Evidence Weave, story-to-source trace and brand tokens must remain original.

## 11. Reversible implementation map

| Layer | User-facing brand location | Internal identifier preserved |
|---|---|---|
| Phone launcher | `phone/src/main/res/values/strings.xml` | Application ID and namespace `au.com.elied.vitalsignal` |
| Phone Compose copy | `ProductBrand.kt` | Kotlin packages/classes and `Theme.VitalSignal` |
| Wear launcher/notification | `wear/src/main/res/values/strings.xml` | Wear package, services and protocol routes |
| Browser prototype | `prototype/index.html` | Fixture schema/version and internal data contracts |
| Repository entry point | `README.md` | Repository name, backend headers and signed schemas |

A future brand change should edit only these user-facing tokens and reviewed marketing/help copy. Renaming internal identifiers requires a separate migration proposal, compatibility tests, cryptographic/audit review and explicit versioning.

## 12. Pre-test experience acceptance gates

The working brand is ready for simulator evaluation only when:

- a person can state what changed, what it means and what to do next after five seconds;
- a person can trace every displayed conclusion to source/quality/provenance;
- low quality, missing data and off-wrist time cannot look normal;
- human concern visibly suppresses reassuring analytics;
- simulator/research status and non-affiliation remain visible;
- important text and controls meet the accessibility floor;
- the mark remains readable in dark, light, monochrome and reduced-motion contexts;
- no independent reviewer mistakes Evidessa for a Samsung or Apple product;
- the app name and tagline pass legal, trademark, linguistic, domain and store clearance before commercial release;
- claims and user comprehension are evaluated separately from visual appeal.

Visual polish can be rated in usability testing. It cannot raise the system's clinical or scientific validation score.
