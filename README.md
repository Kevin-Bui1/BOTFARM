# BOTFARM native navigation goals

Select an app, emulator, and goal. Local Ollama `qwen3:8b` chooses among the observed, permitted navigation controls; deterministic checks decide whether the requested destination was reached. Accounts must be signed in manually. The worker never enters credentials, submits search queries, creates accounts, or uses engagement/composer controls.

## Choose and run a goal

```powershell
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -ListGoals
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App instagram -Device emulator-5554 -Goal search
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App instagram -Device emulator-5554 -Goal profile
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App tiktok -Device emulator-5556 -Goal profile
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App tiktok -Device emulator-5556 -Goal search
```

| App | Goal | Deterministic destination evidence |
|---|---|---|
| Instagram | `search` | Selected Search tab and Explore action bar |
| Instagram | `profile` | Selected Profile tab and profile header containers |
| TikTok | `profile` | Selected Profile tab, account-handle marker, profile metric container, and Following/Followers/Likes labels |
| TikTok | `search` | Unique editable search field, Search button, and observed return icon inside the Search header |

Every goal starts from verified Home. If necessary, the worker uses the observed Home tab or Search return control to prepare Home first. Preparation is logged separately and cannot satisfy PASS. TikTok Search means opening the Search form, not performing a query or opening a result. App-provided suggestions/hint text may appear; the worker does not type or submit anything.

Defaults preserve the existing regression cases: Instagram uses `emulator-5554` / `search`; TikTok uses `emulator-5556` / `profile`. `-Device` overrides the device explicitly. Without arguments, the runner uses TikTok. `-ListGoals` only lists supported goals and does not start services. Direct Java invocation accepts `SocialAppLabWorker <app> [goal]` with device from `LAB_DEVICE`.

Other goals, including Settings, Inbox, followers lists, submitted searches, content items, and multi-step routes, are unsupported. Unsupported goal names are rejected rather than approximated. The worker stops at login, verification, tutorial, crash prompts, or unrecognized layouts.

## Goal verification and reports

Both observed destination controls may be offered to the LLM, along with the requested goal. If the model selects a different destination, the worker stops with `MODEL_GOAL_MISMATCH` before tapping it. It does not change the requested goal to match the model's choice.

Before tapping, resource ID, label, container ancestry, visibility, enabled state, uniqueness, and foreground app are rechecked. After tapping, the destination must differ from Home and satisfy its app-specific content/state checks across at least three observations spanning two seconds, followed by a post-screenshot check. A single Home/Back tap, repeated controls, changed feed content, the wrong destination, or incomplete destination markers cannot pass. TikTok Search is an overlay: background Home tabs are excluded while its form is visible. Partial forms remain unverified until the bounded wait expires.

Each run writes these Git-ignored files under `lab-runs/<app>-<timestamp>/`:

- `report.md`: readable goal, result, stop reason, preparation and LLM actions, verified screens, and screenshot links.
- `result.json`: the same structured evidence, plus the observation log.
- `report.txt`: full model decisions, elapsed verification samples, result, and duration.
- `launch`, `before`, `after`, or `failure` screenshots and native XML when available.
- On device-session failure, bounded Android crash/process-exit diagnostics when ADB is available.

Account values and UI trees never go to the model: only allowed navigation labels and the requested goal are sent to local Ollama. Screenshots and UI trees stay local, and both `lab-runs/` and `startup-diagnosis/` are ignored by Git. Reports link only screenshots that actually exist.

## Tests and local dependencies

```powershell
powershell -NoProfile -File D:\BOTFARM\verify-navigation.ps1
```

This clean-compiles production/test sources and explicitly executes the dependency-free `NavigationPolicyTest` runner through Maven's exec plugin. `mvn test` alone does not execute this runner. Synthetic trees cover both existing destinations, explicit-goal mismatch, Search overlays/incomplete forms, duplicate/hidden controls, sign-in/verification/crash prompts, stability resets, and report contents. Tests contain no account data.

The configured workstation uses JDK 26.0.1, Maven 3.9.16 under `D:\Tools`, the Android SDK under `%LOCALAPPDATA%\Android\Sdk`, local Appium, and Ollama `qwen3:8b`. Java targets release 17. The run script starts local services if needed and compiles the worker.

## Device and version limits

The original `Pixel_10_-_First_Device_Test` AVD (`emulator-5554`) retains its image and app data; TikTok previously aborted outside Appium on that 16 KB image. TikTok runs on the separate `BOTFARM_API35_4KB` AVD (`emulator-5556`, Android 35 Google Play, 4 KB pages, 4 GB RAM, physical keyboard enabled). Neither device is wiped or reconfigured by the goal runner.

TikTok selectors were inspected on version 47.0.3. Unknown or changed layouts stop safely and require fresh inspection. Bounded startup/navigation checks do not certify indefinite stability or other app versions.
