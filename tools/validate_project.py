#!/usr/bin/env python3
"""Fast, dependency-free structural and safety validation for the source bundle.

This does not replace an Android/physical-device build. It makes missing modules,
unsafe UI phrases and critical architecture regressions fail loudly in any CI.
"""

from __future__ import annotations

import re
import sys
import tomllib
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXPECTED_APP_ID = "au.com.elied.vitalsignal"


class StrictHtmlParser(HTMLParser):
    def error(self, message: str) -> None:  # pragma: no cover - legacy hook
        raise ValueError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)
    print(f"PASS  {message}")


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def validate_repository_hygiene() -> None:
    ignored_roots = {".git", ".gradle", ".idea", "build", "__pycache__"}
    forbidden_names = {
        "local.properties",
        "secrets.properties",
        "VitalSignal-Ollama-Endpoint.txt",
    }
    forbidden_suffixes = {
        ".aar", ".apk", ".aab", ".jks", ".keystore", ".p12", ".pem",
        ".log", ".db", ".sqlite", ".sqlite3",
    }
    text_suffixes = {
        "", ".cmd", ".gradle", ".html", ".json", ".kts", ".kt", ".md",
        ".mjs", ".properties", ".pro", ".py", ".txt", ".xml", ".yaml", ".yml",
    }
    secret_patterns = {
        "private-key material": re.compile(rb"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"),
        "GitHub personal token": re.compile(rb"\b(?:github_pat_|ghp_)[A-Za-z0-9_]{20,}\b"),
        "AI provider secret": re.compile(rb"\bsk-(?:proj-|ant-)?[A-Za-z0-9_-]{20,}\b"),
        "Tailscale auth token": re.compile(rb"\btskey-[A-Za-z0-9_-]{20,}\b"),
        "private tailnet IPv4 endpoint": re.compile(
            rb"\b100\.(?:6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])(?:\.[0-9]{1,3}){2}\b",
        ),
        "private MagicDNS endpoint": re.compile(rb"\b[a-z0-9-]+\.[a-z0-9-]+\.ts\.net\b", re.I),
    }

    files: list[Path] = []
    forbidden: list[str] = []
    for path in ROOT.rglob("*"):
        relative = path.relative_to(ROOT)
        if any(part in ignored_roots for part in relative.parts):
            continue
        if not path.is_file():
            continue
        files.append(path)
        if path.name in forbidden_names or path.suffix.lower() in forbidden_suffixes:
            forbidden.append(relative.as_posix())
    require(not forbidden, "no private SDK, signing, endpoint or packaged-app files are committed")

    leaked: list[str] = []
    for path in files:
        if path.suffix.lower() not in text_suffixes:
            continue
        payload = path.read_bytes()
        for label, pattern in secret_patterns.items():
            if pattern.search(payload):
                leaked.append(f"{path.relative_to(ROOT).as_posix()} ({label})")
    require(not leaked,
            "source contains no private keys, provider/GitHub/Tailscale tokens or private tailnet endpoints")

    gitignore = read(".gitignore")
    for required in (
        "*.jks",
        "*.keystore",
        ".env.*",
        "phone/libs/*.aar",
        "wear/libs/*.aar",
        "tools/windows/VitalSignal-Ollama-Endpoint.txt",
        "phone/src/debug/assets/private-health-data/",
        "wear/src/debug/assets/private-health-data/",
        "private-health-data/",
        "exports/",
        "logs/",
    ):
        require(required in gitignore, f".gitignore protects {required}")


def validate_modules() -> None:
    settings = read("settings.gradle.kts")
    for module in (
        "core:model",
        "core:analytics",
        "core:transport",
        "core:storage",
        "core:audit",
        "core:reasoning",
        "core:governance",
        "core:monitoring",
        "phone",
        "wear",
    ):
        require(f'include(":{module}")' in settings, f"settings includes :{module}")
        require((ROOT / module.replace(":", "/") / "build.gradle.kts").is_file(), f":{module} has a build file")

    with (ROOT / "gradle/libs.versions.toml").open("rb") as handle:
        catalog = tomllib.load(handle)
    require(catalog["versions"]["agp"] == "9.3.1", "Android Gradle Plugin version is pinned")
    require(catalog["versions"]["kotlin"] == "2.4.10", "Kotlin version is pinned")

    wrapper = read("gradle/wrapper/gradle-wrapper.properties")
    require("gradle-9.5.1-bin.zip" in wrapper, "Gradle 9.5.1 wrapper is pinned")
    require("distributionSha256Sum=" in wrapper, "Gradle distribution checksum is pinned")
    require((ROOT / "gradle/wrapper/gradle-wrapper.jar").is_file(), "verified Gradle wrapper JAR exists")

    phone_build = read("phone/build.gradle.kts")
    require('versionName = "0.5.0-research"' in phone_build, "phone source version is 0.5.0-research")
    require("libs.androidx.compose.animation" in phone_build, "phone declares Compose animation dependency")
    require(
        "androidx-compose-animation" in read("gradle/libs.versions.toml"),
        "version catalog declares Compose animation",
    )
    wear_build = read("wear/build.gradle.kts")
    require('versionName = "0.5.0-research"' in wear_build, "wear source version is 0.5.0-research")


def validate_ci_supply_chain() -> None:
    workflow = read(".github/workflows/verify.yml")
    action_refs = re.findall(r"uses:\s+([^\s#]+)", workflow)
    require(len(action_refs) >= 7, "CI declares the expected verification actions")
    require(
        all(re.search(r"@[a-f0-9]{40}$", action_ref) for action_ref in action_refs),
        "every GitHub Action is pinned to an immutable commit SHA",
    )
    require("permissions:\n  contents: read" in workflow,
            "CI uses an explicit read-only default token permission")
    require("pull_request_target" not in workflow,
            "CI does not execute untrusted pull-request code with target-repository authority")
    require('sdkmanager "platforms;android-37.0"' in workflow,
            "CI installs the published Android API 37.0 SDK package")
    require('sdkmanager "platforms;android-37"' not in workflow,
            "CI does not request the nonexistent unqualified API 37 SDK package")
    require((ROOT / ".github/dependabot.yml").is_file(),
            "dependency update proposals are enabled without auto-merge")
    require((ROOT / ".github/CODEOWNERS").is_file(),
            "safety-critical changes have an explicit review owner")


def validate_android_targets() -> None:
    for module in ("phone", "wear"):
        build = read(f"{module}/build.gradle.kts")
        require("compileSdk = 37" in build, f"{module} compiles against API 37")
        require("targetSdk = 37" in build, f"{module} targets API 37")
        match = re.search(r'applicationId\s*=\s*"([^"]+)"', build)
        require(match is not None and match.group(1) == EXPECTED_APP_ID, f"{module} uses the paired application ID")
        ET.parse(ROOT / module / "src/main/AndroidManifest.xml")
        print(f"PASS  {module} manifest XML parses")


def validate_traceability_and_quality() -> None:
    model = read("core/model/src/main/kotlin/au/com/elied/vitalsignal/model/HealthModels.kt")
    for field in ("provenanceIds", "modelVersion", "featureSnapshotIds", "dataQuality"):
        require(field in model, f"shared model preserves {field}")

    interpretation = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/InterpretationEngine.kt")
    require("interpretationGrade" in interpretation, "user-visible evidence uses the stronger quality gate")
    require("independentFamilyFor" in interpretation, "correlated domains are grouped before corroboration")
    require("CONFLICTING_EVIDENCE" in interpretation and
            "FamilyEvidenceDirection.CONFLICTING" in interpretation and
            "representativeContribution = representative" in interpretation,
            "within-family opposing directions produce explicit zero-vote conflict assessment")
    require("PersistenceEvidenceEvaluator" in interpretation and
            "PersistenceEvidenceVerifier" in interpretation and
            "persistentWindowCount" not in interpretation,
            "persistence is computed from verifier-gated prior episodes, never a caller count")
    require("coverageWindows: List<MetricWindow>" in interpretation and
            "availableQualifiedFamilies" in interpretation,
            "interpretation exposes qualified expected-family coverage evidence")
    require("AcquisitionDependencyProfile" in model and
            "WRIST_OPTICAL_CONTACT_MOTION" in model and
            "UNKNOWN_SHARED_DEVICE_PIPELINE" in model,
            "metric windows carry conservative physical acquisition dependencies")
    require("ACQUISITION_DEPENDENCY_LIMITED" in interpretation and
            "assessAcquisitionGraph" in interpretation and
            "independentCoherentAcquisitionGroupCount" in interpretation,
            "shared sensor paths collapse into acquisition-level connected components")

    safety = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/SafetyPolicyEngine.kt")
    require("expectedQualifiedFamilies" in safety and
            "availableQualifiedFamilies" in safety and
            "required-qualified-family-unavailable" in safety,
            "safety policy prevents partial sensor-family loss from becoming typical")
    require("opposing-qualified-evidence-within-family" in safety,
            "safety policy routes within-family conflict to explicit abstention")
    require("shared-acquisition-dependency" in safety,
            "safety policy withholds apparent multi-domain corroboration from one acquisition path")

    quality = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/SignalQualityEngine.kt")
    require("hardPass" in quality and "weightedLog" in quality, "quality fusion cannot average away a failed hard gate")
    require("clipping <= 0.05" in quality, "clipping hard gate is at most five percent")

    baseline = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/RobustBaselineEngine.kt")
    require("targetMaturityDays: Int = 28" in baseline, "baseline requires at least 28 effective days")
    require("minimumSamples: Int = 20" in baseline, "baseline requires at least 20 matched samples")
    require("localHourBucket == key.localHourBucket" in baseline, "baseline is matched by local hour")
    require("baselineContext == key.context" in baseline,
            "baseline is matched by exact device, firmware, acquisition and environment context")
    require("value.isFinite()" in model,
            "sensor observations and metric windows reject non-finite values")

    response = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/StandardizedResponseEngine.kt")
    require("minimumReferenceEpisodes: Int = 12" in response, "response reference requires at least 12 episodes")
    require("minimumReferenceDays: Int = 28" in response, "response reference requires at least 28 days")
    require("reference.protocolVersion == current.protocolVersion" in response, "response comparison is protocol-version matched")
    require("reference.deviceGeneration == current.deviceGeneration" in response, "response comparison is device-generation matched")
    require("reference.firmwareGeneration == current.firmwareGeneration" in response, "response comparison is firmware-generation matched")
    require("minimumIndependentFamilies: Int = 2" in response, "response signal requires independent-family corroboration")
    require("cause is unknown" in response, "response signal preserves an unknown-cause boundary")
    require('standardizationFingerprint.matches(Regex("[a-f0-9]{64}"))' in response,
            "standardized response comparisons bind the physical protocol configuration")
    require("HUMAN_CONCERN_REVIEW" in response and
            response.index("current.humanConcern") < response.index("current.quality"),
            "human concern is evaluated before sensor quality in response assessment")
    require("StandardizedResponseEligibilityVerifier" in response and
            "eligibilityVerified(current)" in response,
            "standardized response engine verifies episode eligibility authority")

    function_gate = read(
        "core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/FunctionRecoveryCaptureGate.kt",
    )
    require("DRAFT_REQUIRES_EXTERNAL_REVIEW" in function_gate and
            "REVIEWED_FOR_RESEARCH_CAPTURE" in function_gate,
            "function capture requires an external research-protocol review state")
    require("HOLD_FOR_HUMAN_REVIEW" in function_gate and
            "sensor quality cannot override it" in function_gate,
            "function capture holds human concern independently of sensors")
    require("FunctionRecoveryReviewReceiptVerifier" in function_gate and
            "reviewReceiptVerifier.verify(input)" in function_gate,
            "function capture verifies authority over the exact immutable capture input")
    require("FunctionRecoveryTimingEvidence" in function_gate and
            "recordedDurationMillis" in function_gate and
            "observerAgreement" in function_gate,
            "function capture carries typed timing and observer-agreement evidence")
    require("DECLINED_BY_PARTICIPANT" in function_gate,
            "function capture records an explicit participant decline without creating an episode")
    require("mayCreateResearchEpisode" in function_gate,
            "function gate authorizes only research comparison, not participation")

    adaptive = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/AdaptiveSensingPlanner.kt")
    require("minimumIndependentFamilies: Int = 2" in adaptive, "adaptive sensing requires independent-family corroboration")
    require("foregroundUserInitiated" in adaptive, "adaptive sensing cannot silently launch an on-demand capture")
    require("validationReceiptId" in adaptive, "adaptive sensing requires a validated capture capability")
    require("Research remeasurement only" in adaptive, "adaptive sensing preserves a non-diagnostic claim boundary")

    cohort = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/EmpiricalCohortContextEngine.kt")
    require("advisoryOnly: Boolean = true" in cohort, "empirical cohort context is structurally advisory")
    require("deviceGeneration == request.deviceGeneration" in cohort, "cohort context is device-generation matched")
    require("the matched personal baseline remains authoritative" in cohort, "personal reference remains authoritative")
    require("EmpiricalCohortReferenceVerifier" in cohort and
            "referenceVerifier.verify" in cohort,
            "empirical cohort references require exact external evidence verification")

    reasoning = read("core/reasoning/src/main/kotlin/au/com/elied/vitalsignal/reasoning/LocalReasoningPolicy.kt")
    contracts = read("core/reasoning/src/main/kotlin/au/com/elied/vitalsignal/reasoning/LocalReasoningContracts.kt")
    signed_packet = read(
        "core/reasoning/src/main/kotlin/au/com/elied/vitalsignal/reasoning/SignedHealthStatePacket.kt",
    )
    require("class LocalReasoningRequest private constructor" in contracts,
            "local reasoning requests have no caller-forgeable construction path")
    require("ReviewedNarrativeTemplates" in contracts and "templateId" in contracts,
            "local reasoning selects reviewed semantic templates")
    narrative_claim = re.search(r"data class NarrativeClaim\((.*?)\n\)", contracts, re.S)
    require(narrative_claim is not None and "text:" not in narrative_claim.group(1),
            "model candidates have no free-prose clinical field")
    require("OllamaRunReceipt" in contracts and "modelDigest" in contracts, "local reasoning records exact model provenance")
    require("HealthStatePacketSignatureVerifier" in signed_packet,
            "health-state packet authority uses an injected signature verifier")
    require("MessageDigest.isEqual(suppliedCanonical, recomputedCanonical)" in signed_packet,
            "health-state authority verifies the exact canonical payload")
    require("LocalReasoningRequest.fromVerifiedPacket" in signed_packet and
            "sha256Hex(suppliedCanonical)" in signed_packet,
            "reasoning snapshot digest is recomputed from signed bytes")
    require("private val maxTtlMillis: Long = 120_000L" in signed_packet and
            "maxTtlMillis in 1L..300_000L" in signed_packet,
            "reasoning authority is short-lived with a hard TTL cap")
    require("SNAPSHOT_MISMATCH" in reasoning, "local reasoning rejects changed input snapshots")
    require("UNKNOWN_METRIC_REFERENCE" in reasoning, "local reasoning rejects invented metric references")
    require("UNAPPROVED_MEASUREMENT" in reasoning, "local reasoning cannot invent an intervention")
    require("UNAPPROVED_QUESTION" in reasoning, "local reasoning cannot invent a clinical question")
    require("UNGROUNDED_CLAIM" in reasoning, "local reasoning rejects ungrounded prose")
    require("OVERSTATED_CERTAINTY" in reasoning, "local reasoning cannot promote its own certainty")
    require("UNKNOWN_NARRATIVE_TEMPLATE" in reasoning and
            "UNAPPROVED_NARRATIVE_TEMPLATE" in reasoning and
            "NARRATIVE_TEMPLATE_KIND_MISMATCH" in reasoning,
            "unknown, unapproved and mis-bound narrative templates fail closed")

    reasoning_orchestrator = read(
        "core/reasoning/src/main/kotlin/au/com/elied/vitalsignal/reasoning/VerifiedLocalReasoningOrchestrator.kt",
    )
    require("auditSink.commit" in reasoning_orchestrator, "local narrative is durably audited before delivery")
    require("AUDIT_COMMIT_FAILED" in reasoning_orchestrator, "audit failure suppresses local narrative delivery")
    require(reasoning_orchestrator.count("authority.verify(packet)") >= 2,
            "signed packet authority is rechecked after model generation")

    access_gate = read("core/governance/src/main/kotlin/au/com/elied/vitalsignal/governance/PilotAccessGate.kt")
    require("CONSENT_GENERATION_MISMATCH" in access_gate, "pilot activation is consent-generation fenced")
    require("VALIDATION_ENVIRONMENT_MISMATCH" in access_gate, "pilot activation is exact-environment validated")
    require("sealed interface PilotGateDecision" in access_gate and
            "private class GateIssuedDecision" in access_gate and
            "data class PilotGateDecision" not in access_gate,
            "allowed pilot decisions are opaque and only minted by PilotAccessGate")
    require("fun authorizes(" in access_gate and
            "MAXIMUM_DECISION_LIFETIME_MILLIS = 60_000L" in access_gate,
            "pilot authority is exact-binding checked and short lived")
    require("consentGrantSha256 = consent.exactBindingSha256()" in access_gate and
            "validationReceiptSha256 = current.exactBindingSha256()" in access_gate,
            "pilot authority retains exact signed consent and validation bindings")
    history_gate = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/integration/HistoryPilotGate.kt",
    )
    require("class HistoryReadPermit private constructor" in history_gate and
            "HistoryReadPermit.evaluateGoverned(request, context)" in history_gate,
            "history read permits have no same-module direct construction path")
    require("governanceCapability" not in history_gate and
            "decision.authorizes(" in history_gate,
            "history reads consume the opaque exact pilot authority")
    samsung_gate = read(
        "wear/src/main/kotlin/au/com/elied/vitalsignal/wear/samsung/SamsungSensorContracts.kt",
    )
    require("class SamsungSensorCapturePermit private constructor" in samsung_gate and
            "SamsungSensorCapturePermit.evaluateGoverned(request, context)" in samsung_gate,
            "Samsung capture permits have no same-module direct construction path")
    require("java.util.Set.copyOf(allowedTrackers)" in samsung_gate and
            "java.util.Map.copyOf(trackers)" in samsung_gate,
            "Samsung consent and runtime inventories are immutable snapshots")
    watch_activation = read(
        "wear/src/main/kotlin/au/com/elied/vitalsignal/wear/governance/WearPilotActivation.kt",
    )
    require("class GovernedWatchAccessLease private constructor" in watch_activation and
            "decision.authorizes(" in watch_activation,
            "watch leases have no same-module direct construction path")
    raw_ecg = read(
        "wear/src/main/kotlin/au/com/elied/vitalsignal/wear/samsung/SamsungRawEcgEvent.kt",
    )
    require("java.util.List.copyOf(points)" in raw_ecg,
            "Samsung ECG callback points are immutable snapshots")
    history_records = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/integration/HistoryRecordContracts.kt",
    )
    history_merge = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/integration/HistoryReconciler.kt",
    )
    samsung_history = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/samsung/SamsungHealthDataSource.kt",
    )
    require("java.util.Map.copyOf(sourceMetadata)" in history_records and
            "java.util.List.copyOf(changes)" in history_records,
            "canonical history metadata and adapter pages are immutable snapshots")
    require("java.util.Map.copyOf(records)" in history_merge and
            "java.util.Map.copyOf(tombstones)" in history_merge,
            "history merge state cannot alias caller-owned maps")
    require("java.util.Map.copyOf(metadata)" in samsung_history and
            "val quality: SignalQuality = quality.copy()" in samsung_history,
            "Samsung history records deep-snapshot metadata and quality evidence")
    promotion = read("core/governance/src/main/kotlin/au/com/elied/vitalsignal/governance/ResearchPromotionGate.kt")
    require("PROSPECTIVE_CALIBRATION_PASSED" in promotion, "visible promotion requires prospective calibration")
    require("REGULATORY_AUTHORIZATION_GRANTED" in promotion, "medical intended use requires authorization evidence")
    require("MAXIMUM_MEDICAL_PERMIT_LIFETIME_MILLIS = 5 * 60_000L" in promotion,
            "medical promotion permits have a hard lifetime cap")
    governance_auth = read(
        "core/governance/src/main/kotlin/au/com/elied/vitalsignal/governance/GovernanceReceiptAuthentication.kt",
    )
    require('MAC_ALGORITHM = "HmacSHA256"' in governance_auth, "private-pilot governance receipts use HMAC-SHA-256")
    require("MessageDigest.isEqual" in governance_auth, "governance receipt MAC comparison is constant-time")
    privacy = read("core/governance/src/main/kotlin/au/com/elied/vitalsignal/governance/PrivacyCommandLedger.kt")
    for target in ("WATCH_OUTBOX", "LOCAL_REASONING_INDEX", "PERSONAL_MODEL_STATE"):
        require(target in privacy, f"privacy deletion tracks {target.lower().replace('_', ' ')}")

    monitoring_access = read(
        "core/monitoring/src/main/kotlin/au/com/elied/vitalsignal/monitoring/ClinicalMonitoringGate.kt",
    )
    require("MEDICAL_INTENDED_USE" in monitoring_access,
            "regulated monitoring requires the medical promotion surface")
    require("OBSERVER_NOT_AUTHORIZED" in monitoring_access,
            "clinician observer access is care-team fenced")
    require("class MonitoringAccessPermit private constructor" in monitoring_access,
            "clinical export authority is a private purpose-bound composite permit")
    require("medicalEvidenceReceiptIds" in monitoring_access,
            "regulated monitoring permit retains exact medical evidence binding")
    monitoring_freshness = read(
        "core/monitoring/src/main/kotlin/au/com/elied/vitalsignal/monitoring/StreamFreshnessEngine.kt",
    )
    for state in (
        "DELAYED",
        "STALE",
        "NO_DATA",
        "QUALITY_BLOCKED",
        "VALIDATION_BLOCKED",
        "AUTHORIZATION_BLOCKED",
        "SESSION_INACTIVE",
        "CLOCK_UNTRUSTED",
        "SEQUENCE_INVALID",
    ):
        require(f"StreamAvailability.{state}" in monitoring_freshness,
                f"observer surface handles {state.lower().replace('_', ' ')}")
    require("not continuously observed" in monitoring_freshness,
            "observer coverage cannot silently imply continuous monitoring")
    monitoring_scalar_policy = read(
        "core/monitoring/src/main/kotlin/au/com/elied/vitalsignal/monitoring/ClinicalScalarSamplePolicy.kt",
    )
    require("SensorSource.SIMULATOR" in monitoring_scalar_policy and
            "VALIDATION_BLOCKED" in monitoring_freshness,
            "simulator or unvalidated monitoring samples cannot appear live")
    require("value.isFinite()" in monitoring_scalar_policy and
            "HEART_RATE" in monitoring_scalar_policy and
            "OXYGEN_SATURATION" in monitoring_scalar_policy,
            "observer samples share fail-closed finite and plausibility gates")
    monitoring_alert = read(
        "core/monitoring/src/main/kotlin/au/com/elied/vitalsignal/monitoring/MonitoringAlertLedger.kt",
    )
    require("createAndAppendAudit" in monitoring_alert and "compareAndSetAndAppendAudit" in monitoring_alert,
            "monitoring alert state and audit commit through atomic store operations")
    require("ClinicalAlertActionPermit" in monitoring_alert,
            "monitoring alert mutations require signed actor/action authority")
    require("acknowledgement-deadline-exceeded" in monitoring_alert,
            "monitoring alert acknowledgement timeout is explicit")
    monitoring_fhir = read(
        "core/monitoring/src/main/kotlin/au/com/elied/vitalsignal/monitoring/FhirObservationProjection.kt",
    )
    require("sourceDeviceId" in monitoring_fhir and "gatewayDeviceId" in monitoring_fhir,
            "FHIR projection preserves source and gateway provenance")
    require("ClinicalScalarSamplePolicy.rejectionCode(sample)" in monitoring_fhir and
            "SensorSource.SIMULATOR" in monitoring_scalar_policy,
            "simulator data cannot become a patient observation")
    require("draftSha256 = canonicalFhirDraftSha256(draft)" in monitoring_fhir,
            "clinical export audit binds the exact canonical FHIR-shaped draft")
    require("ClinicalScalarSamplePolicy.rejectionCode(sample)" in monitoring_fhir,
            "clinical projection reuses reviewed metric plausibility bounds")

    alert_authority = read(
        "core/governance/src/main/kotlin/au/com/elied/vitalsignal/governance/ClinicalAlertAuthorization.kt",
    )
    require("class ClinicalAlertActionPermit private constructor" in alert_authority,
            "alert action permits cannot be caller constructed")
    require("MAXIMUM_ALERT_ACTION_PERMIT_LIFETIME_MILLIS = 5 * 60_000L" in alert_authority,
            "alert action permits have a hard lifetime cap")

    clinical_authority = read(
        "core/governance/src/main/kotlin/au/com/elied/vitalsignal/governance/ClinicalAuthorization.kt",
    )
    require("MAXIMUM_HEARTBEAT_AGE_MILLIS = 60_000L" in clinical_authority,
            "observer heartbeat authority has a hard age cap")
    require("MAXIMUM_CLINICAL_RULE_PERMIT_LIFETIME_MILLIS = 5 * 60_000L" in clinical_authority,
            "clinical-rule permits have a hard lifetime cap")

    safety = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/SafetyPolicyEngine.kt")
    require("MEASUREMENT_UNAVAILABLE" in safety, "safety policy separates low-quality data")
    require("reviewedUrgentSymptomFlag" in safety, "urgent symptom route is separate from model severity")
    require("USER_CONCERN" in model, "user concern is retained as an independent context signal")
    require("USER_CONCERN_REVIEW" in safety and "userConcernReported" in safety,
            "main safety policy has a direct human-concern override")
    require(safety.index("if (input.userConcernReported)") < safety.index("if (input.dataQuality"),
            "human concern is evaluated before sensor quality in the main safety policy")

    human_concern = read("core/audit/src/main/kotlin/au/com/elied/vitalsignal/audit/HumanConcernLedger.kt")
    encrypted_human_concern = read(
        "core/audit/src/main/kotlin/au/com/elied/vitalsignal/audit/EncryptedHumanConcernJournal.kt",
    )
    require("RESOLVE_BY_HUMAN" in human_concern and
            "HumanConcernAuthorityVerifier" in human_concern,
            "human concern is latched until an exact authorized human resolution")
    require("EncryptedAppendOnlyRecordStore" in encrypted_human_concern and
            "Trailing concern bytes are not allowed" in encrypted_human_concern,
            "human concern audit has encrypted restart-safe persistence")

    forecast = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/PersonalForecastEngine.kt")
    require("cutoffEpochMillis < target.cutoffEpochMillis" in forecast, "forecast excludes future cases")
    require("minimumReadyCases: Int = 30" in forecast, "forecast has a 30-case readiness gate")
    require("MessageDigest.getInstance(\"SHA-256\")" in forecast, "forecast snapshots are hashed")
    require("forecastId(" in forecast and "snapshotDigest" in forecast, "forecast IDs bind model and snapshot content")
    require(
        "featureSnapshotHash.matches(Regex(\"[a-f0-9]{64}\"))" in model,
        "forecast contracts require canonical SHA-256 snapshot hashes",
    )

    ingestion = read("core/analytics/src/main/kotlin/au/com/elied/vitalsignal/analytics/IngestionLedger.kt")
    for state in ("REPLAY_CONFLICT", "OUT_OF_ORDER", "BAD_UNIT", "FIRMWARE_TRANSITION", "SCHEMA_TRANSITION"):
        require(state in ingestion, f"ingestion quarantines {state.lower().replace('_', ' ')}")
    require("packetIdBySequenceByDevice" in ingestion, "delayed non-overlapping packet sequences remain recoverable")
    require("installReviewedTransition" in ingestion, "firmware/schema changes require an explicit reviewed transition")
    require("baselineRewarmRequired = true" in ingestion, "reviewed generation transitions require baseline rewarming")


def validate_data_plane() -> None:
    storage = read("core/storage/src/main/kotlin/au/com/elied/vitalsignal/storage/EncryptedAppendOnlyRecordStore.kt")
    require('CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"' in storage, "local records use AES-GCM authenticated encryption")
    require("channel.force(true)" in storage, "temporary encrypted records are fsynced before publication")
    require("Files.move(source, target, ATOMIC_MOVE)" in storage, "record publication requires an atomic move")
    require("AtomicMoveNotSupportedException" not in storage, "storage has no unsafe non-atomic publication fallback")
    require("latestReport.canAppend" in storage, "storage quarantine blocks later appends")

    payload = read("core/transport/src/main/kotlin/au/com/elied/vitalsignal/transport/AuthenticatedBatchPayload.kt")
    for field in (
        "batchId",
        "sessionId",
        "deviceId",
        "sequence",
        "createdAtEpochMillis",
        "contentSchemaVersion",
        "contentType",
        "keyId",
        "nonce",
    ):
        require(field in payload, f"transport AAD binds {field}")
    require('TRANSFORMATION = "AES/GCM/NoPadding"' in payload, "watch batches use application-level AES-GCM")

    authenticated_ack = read(
        "core/transport/src/main/kotlin/au/com/elied/vitalsignal/transport/AuthenticatedAcknowledgement.kt",
    )
    require('MAC_ALGORITHM = "HmacSHA256"' in authenticated_ack, "ACK deletion authority uses HMAC-SHA-256")
    require("MessageDigest.isEqual" in authenticated_ack, "ACK MAC comparison is constant-time")

    receiver = read("core/transport/src/main/kotlin/au/com/elied/vitalsignal/transport/BatchReceiverCoordinator.kt")
    require("payloadAuthenticator: BatchPayloadAuthenticator" in receiver, "phone receipt path requires payload authentication")
    require("durableSink.commit" in receiver and "DurableCommitResult.Committed" in receiver, "ACK follows durable commit only")
    require("DURABLE_DUPLICATE" in receiver, "lost ACK can be reissued for exact durable duplicates")

    receipt_store = read("core/storage/src/main/kotlin/au/com/elied/vitalsignal/storage/EncryptedBatchJournalSink.kt")
    require("payloadAuthenticator.authenticate" in receipt_store, "receipt recovery re-authenticates stored wire records")
    require("BatchOrdinal" in receipt_store, "receipt journal rejects ordinal reuse while allowing delayed batches")

    outbox = read("core/transport/src/main/kotlin/au/com/elied/vitalsignal/transport/OutboxAcknowledgementValidator.kt")
    require("decodeAndAuthenticate" in outbox, "watch deletion accepts only a keyed authenticated ACK wrapper")
    for field in ("batchId", "sessionId", "sequence", "wireSha256Hex"):
        require(f"acknowledgement.{field}" in outbox, f"watch deletion matches ACK {field}")
    require("replayStore.claim" in outbox, "watch deletion requires a durable replay claim")

    audit = read("core/audit/src/main/kotlin/au/com/elied/vitalsignal/audit/ProspectiveForecastLedger.kt")
    locked_match = re.search(r"data class LockedForecastView\((.*?)\) : ProspectiveForecastView", audit, re.S)
    require(locked_match is not None, "forecast audit defines a locked public projection")
    locked_fields = locked_match.group(1).lower()
    require("probability" not in locked_fields and "lowerbound" not in locked_fields and "upperbound" not in locked_fields,
            "locked forecast projection structurally omits probability and bounds")
    for state in (
        "COMMITTED_HIDDEN",
        "PRE_REVEAL_CHECKIN_STORED",
        "REVEALED",
        "RESOLUTION_DUE",
        "RESOLVED",
        "INDETERMINATE",
    ):
        require(state in audit, f"forecast audit persists {state.lower().replace('_', ' ')}")
    encrypted_audit = read("core/audit/src/main/kotlin/au/com/elied/vitalsignal/audit/EncryptedForecastAuditJournal.kt")
    require("EncryptedAppendOnlyRecordStore" in encrypted_audit, "forecast audit has encrypted restart-safe persistence")
    require("Trailing forecast audit bytes are not allowed" in encrypted_audit, "forecast audit rejects trailing bytes")

    keystore = read("phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/security/AndroidKeystoreAesKeyProvider.kt")
    require("loadExisting" in keystore and "initialiseFresh" in keystore, "Keystore load and fresh initialization are separate")
    require("setUserAuthenticationRequired(false)" in keystore, "background storage key does not require biometric interaction")
    require("RecoveryRequired" in keystore, "missing or invalid keys fail into recovery")

    wear_transport = read("wear/src/main/kotlin/au/com/elied/vitalsignal/wear/transport/DataLayerBatchTransport.kt")
    require("removeAuthorized" in wear_transport, "Data Layer deletion requires an authorization object")
    require("removeAcknowledged" not in wear_transport, "arbitrary-URI acknowledged deletion API is absent")
    require('dataItemUri.path != "$BATCH_PATH/${authorization.batchId}"' in wear_transport,
            "Data Layer deletion is constrained to the exact VitalSignal batch path")
    require("CONSENT_GENERATION_KEY" in wear_transport, "watch Data Layer payload carries consent generation")
    require("CANONICAL_WIRE_KEY" in wear_transport, "watch Data Layer payload preserves canonical wire bytes")
    require("WearDataItemPayloadPolicy.rejectionCode(encoded.size)" in wear_transport,
            "ordinary DataItem payload budget is checked before publication")

    watch_outbox = read("wear/src/main/kotlin/au/com/elied/vitalsignal/wear/transport/CrashSafeWatchOutbox.kt")
    require("ATOMIC_MOVE" in watch_outbox, "watch outbox snapshot publication is atomic")
    require("channel.force(true)" in watch_outbox, "watch outbox snapshot is fsynced")
    require("consent_generation_mismatch" in watch_outbox, "watch outbox rejects stale consent generations")
    require("deleteExactAuthorized" in watch_outbox, "watch outbox deletion is exact and authorized")
    require("WearDataItemPayloadPolicy.rejectionCode(wire.size)" in watch_outbox,
            "oversize ordinary DataItems are rejected before durable outbox enqueue")
    payload_policy = read("wear/src/main/kotlin/au/com/elied/vitalsignal/wear/transport/WearDataItemPayloadPolicy.kt")
    require("MAX_CANONICAL_WIRE_BYTES" in payload_policy and
            'OVERSIZE_CODE: String = "data_item_payload_budget_exceeded"' in payload_policy,
            "ordinary DataItem transport has a stable bounded oversize policy")

    continuity = read(
        "wear/src/main/kotlin/au/com/elied/vitalsignal/wear/continuity/WatchCollectionContinuity.kt",
    )
    encrypted_continuity = read(
        "wear/src/main/kotlin/au/com/elied/vitalsignal/wear/continuity/EncryptedWatchContinuityJournal.kt",
    )
    for interruption in (
        "LOW_BATTERY",
        "CHARGING",
        "THERMAL_LIMIT",
        "OFF_WRIST",
        "PROCESS_RESTART",
        "REBOOT",
        "CLOCK_DISCONTINUITY",
        "CONSENT_GENERATION_CHANGED",
        "STORAGE_UNAVAILABLE",
    ):
        require(interruption in continuity,
                f"watch continuity records {interruption.lower().replace('_', ' ')}")
    require("EXPLICIT_MISSING_NEVER_IMPUTE_NORMAL" in continuity,
            "watch lifecycle gaps can never be imputed as normal physiology")
    require("WatchResumePermit" in continuity and
            "requiredSnapshotSha256" in continuity and
            "nextSequence" in continuity,
            "watch resume is bound to exact prior state and monotonic sequence")
    require("provenanceChainSha256" in continuity and
            "previousSnapshotSha256" in continuity,
            "watch continuity snapshots maintain a provenance hash chain")
    require("WatchRecoveryEvidence" in continuity and
            "requiredSnapshotSha256" in continuity and
            "runtimeSignalSha256" in continuity and
            "recoveryMaterialSha256" in continuity and
            "recoveryEvidenceVerifier.verify" in continuity,
            "watch recovery requires short-lived evidence bound to state, runtime and material")
    require("EncryptedAppendOnlyRecordStore" in encrypted_continuity and
            "fun confirmResume(" in encrypted_continuity and
            "engine.confirmResume(current, permit, signal, recoveryEvidence)" in encrypted_continuity,
            "restart state is encrypted and resume requires explicit permit confirmation")

    phone_bridge = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/bridge/PhoneDataLayerBridgeCoordinator.kt",
    )
    crash_safe_receipts = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/bridge/CrashSafeReceiptDeliveryOutbox.kt",
    )
    require("ConsentFencedDurableSink" in phone_bridge, "phone bridge rechecks consent at durable commit")
    require("consent_changed_before_receipt" in phone_bridge, "phone bridge suppresses receipts after consent rotation")
    require("AuthenticatedAcknowledgementCodec.encode" in crash_safe_receipts,
            "crash-safe phone receipt coordinator emits authenticated receipts")
    require("EncryptedAppendOnlyRecordStore" in crash_safe_receipts and
            "outbox.stage(binding)" in crash_safe_receipts and
            "recordDelivered" in crash_safe_receipts,
            "phone receipt redelivery state is encrypted, durably staged and terminally recorded")

    phone_android_bridge = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/bridge/android/VitalSignalPhoneDataLayerListenerService.kt",
    )
    phone_android_runtime = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/bridge/android/PhoneDataLayerAndroidRuntime.kt",
    )
    require("PhoneDataLayerDataMapContract.CANONICAL_WIRE_KEY" in phone_android_bridge and
            'CANONICAL_WIRE_KEY = "canonical_batch_envelope"' in phone_android_runtime,
            "physical phone listener copies the exact canonical batch envelope")
    require("PhoneDataLayerDataMapContract.CONSENT_GENERATION_KEY" in phone_android_bridge and
            'CONSENT_GENERATION_KEY = "consent_generation"' in phone_android_runtime,
            "physical phone listener requires a consent generation")
    watch_android_receipt = read(
        "wear/src/main/kotlin/au/com/elied/vitalsignal/wear/transport/android/VitalSignalWatchReceiptListenerService.kt",
    )
    require("MESSAGE_RECEIVED" not in watch_android_receipt or "WearableListenerService" in watch_android_receipt,
            "physical watch receipt listener uses the Wearable service boundary")

    passive_android = read(
        "wear/src/main/kotlin/au/com/elied/vitalsignal/wear/baseline/android/AndroidxHealthServicesPassiveAdapter.kt",
    )
    require("PassiveListenerService" in passive_android,
            "public Health Services passive listener is source-wired")
    require("getTimeInstant" in passive_android and "getStartInstant" in passive_android,
            "passive adapter uses measurement timestamps rather than callback time")
    passive_runtime = read(
        "wear/src/main/kotlin/au/com/elied/vitalsignal/wear/baseline/android/ConsentFencedPassiveRuntime.kt",
    )
    require("durable_storage_not_ready_for_generation" in passive_runtime,
            "passive collection requires generation-bound durable storage")
    require("automaticRestoreEnabled: Boolean = false" in passive_runtime,
            "unsafe passive reboot restoration remains disabled")

    samsung_ecg = read("wear/src/main/kotlin/au/com/elied/vitalsignal/wear/samsung/SamsungRawEcgEvent.kt")
    for field in ("embeddedGreenPpgRaw", "rawLeadOff", "minimumThresholdMillivolts", "rawSequence"):
        require(field in samsung_ecg, f"Samsung ECG research record preserves {field}")
    require("canUseForExperimentalCrossModalTiming" in samsung_ecg,
            "ECG-PPG timing use remains behind physical validation")

    history = read("phone/src/main/kotlin/au/com/elied/vitalsignal/phone/data/integration/HistoryReconciler.kt")
    for state in ("DUPLICATE_IGNORED", "STALE_IGNORED", "CONFLICT_REJECTED", "DELETED"):
        require(state in history, f"history reconciliation handles {state.lower().replace('_', ' ')}")

    for module in ("phone", "wear"):
        manifest = read(f"{module}/src/main/AndroidManifest.xml")
        require('android:allowBackup="false"' in manifest, f"{module} disables Android backup")


def validate_safe_copy() -> None:
    app_sources = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for root in (ROOT / "phone/src/main", ROOT / "wear/src/main", ROOT / "prototype")
        if root.exists()
        for path in root.rglob("*")
        if path.suffix.lower() in {".kt", ".xml", ".html", ".js"}
    )
    prohibited = (
        "IBD flare detected",
        "Infection predicted",
        "Your adrenal function is low",
        "You are dehydrated",
        "It is safe to exercise",
        "No arrhythmia",
        "No action required",
        "Use a lower-load day",
        "CALIBRATED",
    )
    for phrase in prohibited:
        require(phrase.lower() not in app_sources.lower(), f'UI omits prohibited claim: "{phrase}"')
    require("cannot diagnose" in app_sources.lower(), "UI contains an explicit non-diagnostic boundary")
    require("how you feel matters more than the score" in app_sources.lower(), "UI contains the symptom-first safety boundary")
    model = read("core/model/src/main/kotlin/au/com/elied/vitalsignal/model/HealthModels.kt")
    require("URGENT" not in re.search(r"enum class InsightSeverity[^\n]*", model).group(0), "model severity cannot issue urgent status")


def validate_simulator_truthfulness() -> None:
    repository = read("phone/src/main/kotlin/au/com/elied/vitalsignal/phone/presentation/dashboard/DashboardRepository.kt")
    pipeline = read("phone/src/main/kotlin/au/com/elied/vitalsignal/phone/presentation/dashboard/SimulatorHealthPipeline.kt")
    watch_runtime = read("wear/src/main/kotlin/au/com/elied/vitalsignal/wear/capture/ResearchCaptureContracts.kt")
    watch_ui = read("wear/src/main/kotlin/au/com/elied/vitalsignal/wear/ui/VitalSignalWatchApp.kt")
    prototype = read("prototype/index.html")

    require("SIMULATED DATA · NOT YOUR HEALTH DATA" in repository, "phone labels simulated data explicitly")
    require("SimulationScenario.LOW_QUALITY" in repository, "phone exposes a low-quality abstention scenario")
    require("ForecastStatus.LOCKED" in repository, "forecast begins behind the pre-forecast check-in gate")
    require("ForecastStatus.AVAILABLE" in repository, "check-in can reveal the precommitted simulator forecast")
    require("probability = null" in repository, "locked simulator forecast has no probability in its UI model")
    require("REAL DATA LOCKED" in repository, "phone visibly locks personal-data ingestion")
    require("Memory-only simulator" in repository, "phone does not claim durable UI persistence")
    require("SimulatorHealthPipeline" in repository and "SignalQualityEngine" in pipeline, "phone simulator runs through core quality analytics")
    require("RobustBaselineEngine" in pipeline and "SafetyPolicyEngine" in pipeline, "phone simulator runs through baseline and safety analytics")
    require("PersonalForecastEngine" in pipeline, "phone simulator runs through the forecast control model")
    dashboard_models = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/presentation/dashboard/DashboardModels.kt",
    )
    dashboard_ui = read(
        "phone/src/main/kotlin/au/com/elied/vitalsignal/phone/presentation/dashboard/DashboardScreen.kt",
    )
    require("userConcernReported" in dashboard_models and
            "userConcernReported = concernActive" in repository and
            "activeHumanConcern" in dashboard_models,
            "phone check-in carries a latched human concern into the safety pipeline")
    require("I feel concerned" in dashboard_ui and
            "I feel unwell or concerned — hold wearable output" in dashboard_ui and
            "Resolve this app hold?" in dashboard_ui and
            "CONCERN HOLD" in repository and
            "reportHumanConcern" in repository,
            "phone UI applies and displays an immediate user-reported concern hold")
    require("Int? = null" in dashboard_models and
            "hasCompleteForecastContext" in dashboard_models and
            "Not answered" in dashboard_ui,
            "phone self-reports begin missing and partial context cannot reveal a forecast")
    require("ResearchAssistantStatus" in dashboard_models and
            "Governed assistant" in dashboard_ui and
            "no model or cloud call" in repository,
            "phone labels the assistant fixture without claiming that a model ran")
    require("ResearchAssistantStatus.BLOCKED" in repository and
            "medical clearance" in repository and
            "ResearchAssistantStatus.ABSTAINED" in repository,
            "assistant follows concern and evidence abstention gates")

    require("const val isSimulationMode: Boolean = true" in watch_runtime, "watch runtime is explicitly simulator-only")
    require("SIMULATOR_MEMORY_ONLY" in watch_runtime, "watch packets are labelled memory-only")
    require("SIMULATOR · NO LIVE HEALTH DATA" in watch_ui, "watch UI denies live health collection")
    require("Run simulator" in watch_ui and "Start research capture" in watch_ui,
            "watch accessibility semantics distinguish simulator from research capture")
    require("availabilityLabel" in watch_ui and "contentDescription" in watch_ui,
            "watch sensor availability is conveyed by text and accessibility semantics")
    require("SAVING" not in watch_ui, "watch UI does not imply durable saving")

    for phrase in (
        "SIMULATED DATA · NOT YOUR HEALTH DATA",
        "NO GALAXY WATCH IS CONNECTED",
        "Check-in required",
        "Pre-reveal context captured in memory",
        "REAL DATA LOCKED",
        "Absent from locked view",
    ):
        require(phrase in prototype, f'prototype contains truthful state: "{phrase}"')
    require("forecast-value" in prototype and "38%" in prototype, "prototype check-in reveals a simulated forecast")
    require("VitalSignal Scientist" in prototype and
            "Reviewed template · no model call · no cloud call" in prototype and
            "Release-policy gate" in prototype and
            "display eligibility—not medical truth" in prototype,
            "prototype exposes the governed replaceable-assistant concept truthfully")


def validate_test_assets() -> None:
    test_files = list(ROOT.rglob("src/test/**/*.kt"))
    test_count = sum(path.read_text(encoding="utf-8").count("@Test") for path in test_files)
    require(test_count >= 350, f"at least 350 Kotlin unit tests are present ({test_count})")
    for relative in (
        "core/analytics/src/test/kotlin/au/com/elied/vitalsignal/analytics/SafetyPolicyEngineTest.kt",
        "core/analytics/src/test/kotlin/au/com/elied/vitalsignal/analytics/PersonalForecastEngineTest.kt",
        "core/analytics/src/test/kotlin/au/com/elied/vitalsignal/analytics/IngestionLedgerTest.kt",
        "core/transport/src/test/kotlin/au/com/elied/vitalsignal/transport/AuthenticatedAcknowledgementTest.kt",
        "core/transport/src/test/kotlin/au/com/elied/vitalsignal/transport/BatchReceiverCoordinatorTest.kt",
        "core/storage/src/test/kotlin/au/com/elied/vitalsignal/storage/EncryptedAppendOnlyRecordStoreTest.kt",
        "core/storage/src/test/kotlin/au/com/elied/vitalsignal/storage/EncryptedBatchJournalSinkTest.kt",
        "core/audit/src/test/kotlin/au/com/elied/vitalsignal/audit/EncryptedForecastAuditJournalTest.kt",
        "core/audit/src/test/kotlin/au/com/elied/vitalsignal/audit/HumanConcernLedgerTest.kt",
        "core/audit/src/test/kotlin/au/com/elied/vitalsignal/audit/EncryptedHumanConcernJournalTest.kt",
        "core/analytics/src/test/kotlin/au/com/elied/vitalsignal/analytics/StandardizedResponseEngineTest.kt",
        "core/analytics/src/test/kotlin/au/com/elied/vitalsignal/analytics/FunctionRecoveryCaptureGateTest.kt",
        "core/reasoning/src/test/kotlin/au/com/elied/vitalsignal/reasoning/LocalReasoningPolicyTest.kt",
        "core/reasoning/src/test/kotlin/au/com/elied/vitalsignal/reasoning/SignedHealthStatePacketTest.kt",
        "core/reasoning/src/test/kotlin/au/com/elied/vitalsignal/reasoning/VerifiedLocalReasoningOrchestratorTest.kt",
        "core/governance/src/test/kotlin/au/com/elied/vitalsignal/governance/PilotAccessGateTest.kt",
        "core/governance/src/test/kotlin/au/com/elied/vitalsignal/governance/ResearchPromotionGateTest.kt",
        "core/governance/src/test/kotlin/au/com/elied/vitalsignal/governance/GovernanceReceiptAuthenticationTest.kt",
        "core/governance/src/test/kotlin/au/com/elied/vitalsignal/governance/ClinicalAuthorizationTest.kt",
        "core/monitoring/src/test/kotlin/au/com/elied/vitalsignal/monitoring/ClinicalMonitoringGateTest.kt",
        "core/monitoring/src/test/kotlin/au/com/elied/vitalsignal/monitoring/StreamFreshnessEngineTest.kt",
        "core/monitoring/src/test/kotlin/au/com/elied/vitalsignal/monitoring/MonitoringAlertLedgerTest.kt",
        "core/monitoring/src/test/kotlin/au/com/elied/vitalsignal/monitoring/FhirObservationProjectionTest.kt",
        "core/analytics/src/test/kotlin/au/com/elied/vitalsignal/analytics/AdaptiveSensingPlannerTest.kt",
        "core/analytics/src/test/kotlin/au/com/elied/vitalsignal/analytics/EmpiricalCohortContextEngineTest.kt",
        "phone/src/test/kotlin/au/com/elied/vitalsignal/phone/data/bridge/PhoneDataLayerBridgeCoordinatorTest.kt",
        "phone/src/test/kotlin/au/com/elied/vitalsignal/phone/data/bridge/CrashSafeReceiptDeliveryOutboxTest.kt",
        "phone/src/test/kotlin/au/com/elied/vitalsignal/phone/data/integration/HistoryReconcilerTest.kt",
        "phone/src/test/kotlin/au/com/elied/vitalsignal/phone/presentation/dashboard/SimulatorHealthPipelineTest.kt",
        "wear/src/test/kotlin/au/com/elied/vitalsignal/wear/capture/ResearchCaptureControllerTest.kt",
        "wear/src/test/kotlin/au/com/elied/vitalsignal/wear/transport/CrashSafeWatchOutboxTest.kt",
        "wear/src/test/kotlin/au/com/elied/vitalsignal/wear/continuity/WatchCollectionContinuityTest.kt",
        "wear/src/test/kotlin/au/com/elied/vitalsignal/wear/transport/WearDataItemPayloadPolicyTest.kt",
        "wear/src/test/kotlin/au/com/elied/vitalsignal/wear/samsung/SamsungRawEcgEventTest.kt",
        "core/model/src/test/kotlin/au/com/elied/vitalsignal/model/FatigueContextModelsTest.kt",
    ):
        require((ROOT / relative).is_file(), f"critical test exists: {Path(relative).name}")
    require((ROOT / ".github/workflows/verify.yml").is_file(), "CI verification workflow exists")


def validate_deliverables() -> None:
    for relative in (
        "SECURITY.md",
        "CONTRIBUTING.md",
        "README.md",
        "docs/ARCHITECTURE.md",
        "docs/RESEARCH_EVIDENCE.md",
        "docs/VALIDATION_PROTOCOL.md",
        "docs/SAFETY_CASE.md",
        "docs/SAMSUNG_SETUP.md",
        "docs/PRIVACY.md",
        "docs/BUILD_REPORT.md",
        "docs/SESSION_HANDOFF.md",
        "docs/STATUS_MATRIX.md",
        "docs/THREAT_MODEL.md",
        "docs/DATA_PLANE_PROTOCOL.md",
        "docs/FAULT_INJECTION_MATRIX.md",
        "docs/DISCOVERY_BLUEPRINT.md",
        "docs/SENSOR_SIGNAL_MATRIX.md",
        "docs/LOCAL_AI_OLLAMA.md",
        "docs/INSTALL_AND_PILOT_RUNBOOK.md",
        "docs/PILOT_EVIDENCE_PLAN.md",
        "docs/FATIGUE_ADRENAL_CONTEXT_PROTOCOL.md",
        "docs/CLINICAL_PRIORITY_ROADMAP.md",
        "docs/COMPETITIVE_MOAT.md",
        "docs/FUNCTION_RECOVERY_PROTOCOL.md",
        "docs/BACKEND_CLINICIAN_PLATFORM.md",
        "backend/README.md",
        "backend/openapi/vitalsignal-research-observer-v1.yaml",
        "research/signal_hypotheses.json",
        "prototype/index.html",
    ):
        require((ROOT / relative).is_file(), f"deliverable exists: {relative}")
    StrictHtmlParser().feed(read("prototype/index.html"))
    print("PASS  interactive prototype parses as HTML")
    backend_api = read("backend/openapi/vitalsignal-research-observer-v1.yaml")
    require("openapi: 3.1.0" in backend_api, "backend contract declares OpenAPI 3.1")
    require("https://observer.invalid/v1" in backend_api,
            "backend contract uses a deliberately non-routable placeholder")
    require("type: mutualTLS" in backend_api and "Idempotency-Key" in backend_api,
            "backend contract requires mutual TLS and idempotent mutations")
    require("VitalSignal-Alert-Action-Permit" in backend_api and
            "expectedVersion" in backend_api,
            "backend alert actions are permit- and version-bound")
    report = read("docs/BUILD_REPORT.md")
    require("0.5.0-research" in report, "build report matches source version")
    require("not run" in report.lower(), "build report records unexecuted verification honestly")


def main() -> int:
    try:
        validate_repository_hygiene()
        validate_modules()
        validate_ci_supply_chain()
        validate_android_targets()
        validate_traceability_and_quality()
        validate_data_plane()
        validate_safe_copy()
        validate_simulator_truthfulness()
        validate_test_assets()
        validate_deliverables()
    except (AssertionError, AttributeError, KeyError, OSError, ET.ParseError, ValueError) as exc:
        print(f"FAIL  {exc}", file=sys.stderr)
        return 1
    print("\nVitalSignal structural and safety validation passed.")
    print("Physical-device SDK integration and reference-device validation remain required.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
