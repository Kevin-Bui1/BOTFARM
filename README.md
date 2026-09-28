# BOTFARM native navigation goals

Select an app, emulator, and goal. Local Ollama `qwen3:8b` chooses among the observed, permitted navigation controls; deterministic checks decide whether the requested destination was reached. Accounts must be signed in manually. The worker never enters credentials, creates accounts, or uses engagement/composer controls. Only the explicit `user-search` goal may enter and submit a supplied exact handle; ordinary navigation goals never submit searches.

## Choose and run a goal

```powershell
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -ListGoals
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App instagram -Goal search
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App instagram -Goal profile
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App tiktok -Goal profile
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App tiktok -Goal search
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App instagram -Goal search-to-profile
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App tiktok -Goal search-to-profile
```

| App | Goal | Deterministic destination evidence |
|---|---|---|
| Instagram | `search` | Selected Search tab and Explore action bar |
| Instagram | `profile` | Selected Profile tab and profile header containers |
| TikTok | `profile` | Selected Profile tab, account-handle marker, profile metric container, and Following/Followers/Likes labels |
| TikTok | `search` | Unique editable search field, Search button, and observed return icon inside the Search header |
| Instagram | `search-to-profile` | Home → Search → Profile; each screen uses the markers above |
| TikTok | `search-to-profile` | Home → Search form → Home → Profile; Search return must restore selected Home and Home content markers |

Every goal starts from verified Home. If necessary, the worker uses the observed Home tab or Search return control to prepare Home first. Preparation is logged separately and cannot satisfy PASS. TikTok Search means opening the Search form, not performing a query or opening a result. App-provided suggestions/hint text may appear; the worker does not type or submit anything.

The launcher resolves the online device by AVD name (`Pixel_10_-_First_Device_Test` for Instagram and `BOTFARM_API35_4KB` for TikTok), so emulator port order may change between launches. `-Device` selects a port explicitly and rejects a connected AVD mismatch with a corrective hint. Without arguments, the runner uses TikTok's default goal. `-ListGoals` only lists supported goals and does not start services. Direct Java invocation accepts `SocialAppLabWorker <app> [goal]` with device from `LAB_DEVICE`.

The two fixed multi-step routes use controls inspected on the signed-in devices. Instagram's Search tab exposes the Profile tab. TikTok's Search form exposes only its return icon, so the route verifies Home again before selecting Profile. These routes submit no query and open no content item. Settings, Inbox, followers lists, general searches, content items, and arbitrary routes are unsupported. Unsupported goal names are rejected rather than approximated. The worker stops at login, verification, tutorial, crash prompts, permission dialogs, or unrecognized layouts.

## Exact-handle user search

Both apps passed live exact-handle account search on the configured devices using a user-supplied handle. The four single-step goals and both multi-step routes have separate live evidence. Account values, inspected UI contracts, screenshots, and reports remain local and Git-ignored.

With the locally calibrated UI contract present:

```powershell
$handle = Read-Host 'Exact Instagram username (not display name or URL)'
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App instagram -Goal user-search -Handle $handle
$handle = Read-Host 'Exact TikTok username (not display name or URL)'
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App tiktok -Goal user-search -Handle $handle
```

There is no default account. Missing handles are rejected before services or device actions. An optional leading `@` is accepted; URLs, whitespace, general queries, and non-ASCII handles are rejected. Matching ignores ASCII case only, with no substring, display-name, ranking, or Unicode-confusable fallback. The observed TikTok whole-username LRM + FSI/PDI formatting wrapper is accepted; embedded or incomplete directional controls are rejected. Supported input is a conservative subset of username syntax (letters, digits, underscores, and internal periods; maximum 30 characters for Instagram and 24 for TikTok).

The pipeline first runs the verified LLM Home → Search navigation as preparation, linking its separate report. It then verifies the input and enters the supplied handle. Instagram uses the observed typed-query suggestion surface, where account usernames have a distinct native field from keyword and video fields. TikTok submits the query, selects **Users**, and requires that category to be selected. It opens only one exact username match in the inspected account-row username field. Duplicate or missing matches stop the run. Display names, keywords, and video captions cannot authorize profile opening. The profile must independently expose the exact handle and its profile markers, with the results screen gone, across stable observations and again after its screenshot. It never taps Follow, Message, media, or suggested fallback results. It does not scroll for additional matches. Start from signed-in Home or a recognized navigation screen; after a successful search, return to Home before another run. Other-account profiles are not treated as the signed-in Profile regression screen.

App-version-specific result fields are explicit rather than guessed. The worker reads `startup-diagnosis/user-search/<app>-ui.local.json`, or the file selected by `-SearchUiContract`. The JSON fields are `app`, `queryId` (results-page EditText or static TextView query label), `tabId` (Accounts/Users tab resource ID, or empty for the inspected Instagram Button chip / TikTok FrameLayout tab), `tabContainerId`, `resultsId`, `rowId` (clickable account row), `usernameId` (actual username, never display name), `profileHandleId`, and a nonempty `profileMarkers` array. All nonempty resource IDs must belong to the selected app. Id-less tabs are scoped to the configured container, exact category label, and inspected per-app class. These fields must be calibrated by inspecting the actual account-results/profile UI for the user-supplied handle; no speculative selector defaults or sample account are used. A missing contract stops with `SEARCH_UI_CONTRACT_REQUIRED` before device interaction. UI contracts and account-bearing evidence stay Git-ignored.

User-search reports record every verified stage with before/after screenshots, the exact requested and verified handle in local `context`, setup/preparation separately, and an explicit stop reason. Only allowed navigation labels go to Ollama; the handle and result contents do not.

## Goal verification and reports

Observed permitted controls are offered to the LLM with the current verified screen and the next required destination. Every route step requires a fresh model decision. If the model selects a different destination, the worker stops with `MODEL_GOAL_MISMATCH` before tapping it. It does not change the route to match the model's choice. Route progress advances only in order; returning Home within the TikTok route is an LLM-selected, verified step, not preparation.

Before tapping, resource ID, label, container ancestry, visibility, enabled state, uniqueness, and foreground app are rechecked. After tapping, the destination must differ from its source screen and satisfy its app-specific content/state checks across at least three observations spanning two seconds, followed by a post-screenshot check. A single Home/Back tap, repeated controls, changed feed content, the wrong destination, or incomplete destination markers cannot pass. TikTok Search is an overlay: background Home tabs are excluded while its form is visible. Partial forms remain unverified until the bounded wait expires.

For multi-step routes, each destination must differ from that step's source, including Search → Home. Every intermediate and final destination receives the same stability check, and the source is checked again before the next tap. Unknown/transient screens may be observed for up to 20 seconds without further actions. The worker stops if they do not become the required verified destination. It never attempts exploratory taps to recover. A final screen check after the route screenshot must also pass.

Each run writes these Git-ignored files under `lab-runs/<app>-<timestamp>/`:

- `report.md`: readable exact route, result, stop reason, preparation and LLM actions, verified screens, and a per-step before/after screenshot trace.
- `result.json`: the same structured evidence, including `route`, `steps`, actions, and the observation log. Each step records source, observed control/locator, requested and verified destination, screenshot names, and result. A failed later step preserves earlier verified evidence and cannot produce PASS.
- `report.txt`: full model decisions, elapsed verification samples, result, and duration.
- `launch`, `before`, `after`, or `failure` screenshots and native XML when available.
- `step-N-before` / `step-N-after` screenshots and XML for every tapped route step; `preparation-before` / `preparation-after` when Home preparation is needed. Preparation is excluded from the route's completed-step count.
- On device-session failure, bounded Android crash/process-exit diagnostics when ADB is available.

Account values and UI trees never go to the model: only allowed navigation labels and the requested goal are sent to local Ollama. Screenshots and UI trees stay local, and both `lab-runs/` and `startup-diagnosis/` are ignored by Git. Reports link only screenshots that actually exist.

## Tests and local dependencies

```powershell
powershell -NoProfile -File D:\BOTFARM\verify-navigation.ps1
```

This clean-compiles production/test sources and explicitly executes the dependency-free `NavigationPolicyTest` runner through Maven's exec plugin. `mvn test` alone does not execute this runner. Synthetic trees cover existing destinations, skipped/out-of-order route steps, wrong model choices, Search overlays/incomplete forms, duplicate/hidden controls, sign-in/verification/crash/onboarding prompts, stability resets, partial-route failures, and missing report screenshots. Tests contain no account data.

The configured workstation uses JDK 26.0.1, Maven 3.9.16 under `D:\Tools`, the Android SDK under `%LOCALAPPDATA%\Android\Sdk`, local Appium, and Ollama `qwen3:8b`. Java targets release 17. The run script starts local services if needed and compiles the worker.

## Device and version limits

The original `Pixel_10_-_First_Device_Test` AVD retains its image and app data; TikTok previously aborted outside Appium on that 16 KB image. TikTok runs on the separate `BOTFARM_API35_4KB` AVD (Android 35 Google Play, 4 KB pages, 4 GB RAM, physical keyboard enabled). Emulator ports are assigned at launch and are not treated as stable identities. Neither device is wiped or reconfigured by the goal runner.

TikTok selectors were inspected on version 47.0.3. Unknown or changed layouts stop safely and require fresh inspection. Bounded startup/navigation checks do not certify indefinite stability or other app versions.
