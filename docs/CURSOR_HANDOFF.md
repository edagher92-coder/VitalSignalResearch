# Cursor development handoff

This guide is the shortest safe route from a fresh computer to a verified Evidessa Research development workspace in Cursor. The repository remains a simulator-first research foundation. It is not authorised to collect personal health data, show a health forecast, act as an attended monitor or make a medical claim.

## 1. Clone the canonical repository

```bash
git clone https://github.com/edagher92-coder/VitalSignalResearch.git
cd VitalSignalResearch
git switch main
git pull --ff-only
```

Open the repository root in Cursor. Do not open only `phone/` or `wear/`; Gradle and the shared modules are rooted at the repository level.

## 2. Install the development prerequisites

- JDK 17 (Temurin is the CI reference distribution).
- Android Studio and Android SDK/API 37.0. Cursor is suitable for editing and terminal-driven checks; Android Studio remains the supported surface for SDK management, emulators, Logcat, profiling and physical-device deployment.
- Python 3 for the structural and safety validator.
- Node.js 24 for the browser prototype tests.
- Git and GitHub authentication. Use the Git Credential Manager, GitHub CLI or Cursor's supported sign-in flow; never save a token in the repository.

The Gradle wrapper uses Gradle's official `gradle/gradle-distributions` GitHub release for version 9.5.1 instead of `services.gradle.org`. The distribution remains pinned by the same SHA-256 checksum. This allows GitHub-connected development environments that block Gradle's distribution host to retrieve the verified wrapper distribution without vendoring a large executable archive in this repository.

Let Android Studio create `local.properties` with the local SDK path. That file is intentionally ignored by Git.

Confirm the terminal is using JDK 17 before Gradle runs:

```bash
java -version
```

## 3. Verify the clean baseline

Run these from the repository root before changing code:

```bash
python3 tools/validate_project.py
node --test prototype/prototype.test.mjs
./gradlew test lint :phone:assembleDebug :wear:assembleDebug
```

On Windows, use `gradlew.bat` for the Gradle command. If API 37 is not installed, install `platforms;android-37.0` through Android Studio's SDK Manager. A successful build produces simulator-only APKs under:

- `phone/build/outputs/apk/debug/`
- `wear/build/outputs/apk/debug/`

The simulator APKs do not enable Samsung raw sensors, Samsung Health history, personal collection, clinical monitoring or validated forecasts.

PowerShell equivalents:

```powershell
java -version
py -3 tools\validate_project.py
node --test prototype\prototype.test.mjs
.\gradlew.bat test lint :phone:assembleDebug :wear:assembleDebug --stacktrace
```

In Android Studio, set the Gradle JDK to 17. A newer system Java installation does not replace this project requirement.

## 4. Read the governing files before coding

1. `README.md` — product scope and current release posture.
2. `docs/SESSION_HANDOFF.md` — decisions and continuation prompt.
3. `docs/STATUS_MATRIX.md` — implemented versus locked capabilities.
4. `docs/BUILD_REPORT.md` — reproduced checks and unverified boundaries.
5. `CONTRIBUTING.md` — required checks and non-negotiable invariants.
6. `docs/THREAT_MODEL.md` and `SECURITY.md` — security and privacy constraints.
7. The protocol document for the feature being changed.

Cursor automatically receives the project rules in `.cursor/rules/evidessa-research.mdc` when the repository root is opened.

## 5. Safe development workflow

```bash
git switch -c feature/<short-name>
# make one narrow, tested change
python3 tools/validate_project.py
node --test prototype/prototype.test.mjs
./gradlew test lint :phone:assembleDebug :wear:assembleDebug
git status
git add <reviewed-files>
git commit -m "Describe the verified checkpoint"
git push -u origin feature/<short-name>
```

Open a pull request into `main`. Record what was tested, what was not tested, and whether any release gate changed. Test presence is not test execution, simulator behaviour is not hardware evidence, and a watch correlation is not a medical conclusion.

## 6. Current smallest useful implementation checkpoint

The next engineering checkpoint is runtime composition for the **simulator/public-API path only**:

1. Assemble and install both debug applications.
2. Wire the existing consent-fenced public Health Services passive source to the existing encrypted watch outbox.
3. Wire the phone Data Layer listener to durable encrypted commit and authenticated acknowledgement.
4. Exercise process-kill, reboot, phone-offline, charging, depleted-battery, off-wrist, clock-change and duplicate-delivery cases on the exact devices.
5. Keep raw Samsung SDK and real Samsung Health history adapters disabled until the licensed AARs, exact permission fences and device evidence receipts are present.

Do not skip directly to prediction UI or a clinician alert. The collection, continuity, authority, privacy and data-quality gates must become real on-device evidence first.

## 7. Files that must never be committed

- Samsung proprietary SDK AARs.
- API keys, GitHub tokens, Tailscale addresses, passwords or private endpoints.
- Keystores, signing material or exported encryption keys.
- Personal health data, screenshots containing personal readings, clinical records or device identifiers.
- Built APK/AAB files, logs, databases, exports or private Ollama endpoint files.

The `.gitignore`, validator and CI scan for these categories, but the developer remains responsible for reviewing every staged file.

## 8. Cursor continuation prompt

Paste this into a new Cursor Agent conversation after opening the repository:

> Continue Evidessa Research from `docs/CURSOR_HANDOFF.md` and `docs/SESSION_HANDOFF.md`. Inspect the existing source, `docs/STATUS_MATRIX.md`, `docs/BUILD_REPORT.md`, `CONTRIBUTING.md` and the relevant protocol before editing. Preserve simulator-only fail-closed release gates. State the next smallest testable checkpoint, implement it with failure-path tests, run the required checks and report exact evidence and remaining physical-device boundaries. Do not enable personal health-data collection, clinical monitoring, medical claims, cloud health-data transfer or model-authored medical advice without the signed governance and validation evidence required by the repository.

## 9. Keeping two computers in sync

Before starting work on either computer:

```bash
git status
git fetch --prune origin
git switch <your-branch>
git pull --ff-only
```

Before changing computers, commit and push the narrow checkpoint, then verify the commit appears on GitHub. Do not use an uncommitted working directory as the handoff mechanism.

### Debug signing across computers

Android normally creates a different debug keystore on each computer. A build signed on computer B may therefore not update an app installed from computer A. For simulator-only testing, uninstall the old simulator app and accept that its local simulator state is reset. Before any authorised private pilot, create one dedicated pilot signing identity outside Git, store it in an approved password-managed encrypted location, configure each workstation locally, document rotation/recovery and verify the signing-certificate digest on both apps and devices. Never copy a keystore into the repository, chat, Cursor index or ordinary cloud-sync folder.

Changing signing identity can make encrypted application state inaccessible and invalidate continuity assumptions. Do not begin personal-data collection until signing, Keystore lifecycle, export/delete and recovery tests have passed on the exact phone and watch.
