# BOTFARM native navigation lab

This local prototype uses Appium and Ollama `qwen3:8b` to select an observed native navigation tab. Accounts must be signed in manually. It never enters credentials, creates accounts, bypasses verification, or uses engagement/composer controls.

## Run

On the configured Windows workstation, with the Android emulator running:

```powershell
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App instagram -Device emulator-5554
```

The script starts local Appium/Ollama if necessary, selects the explicitly named device, and compiles the Java worker. The machine uses JDK 26.0.1, Maven 3.9.16 under `D:\Tools`, and the Android SDK under `%LOCALAPPDATA%\Android\Sdk`. Java source targets release 17.

## What PASS means

The worker first verifies signed-in Home from the observed selected tab and Home content. If it starts on a recognized Profile or Search screen, it may tap the observed Home tab as preparation; that tap never counts as success.

Only unique, visible, enabled, unselected Profile and Search navigation tabs are offered to the local LLM. Resource IDs, labels, navigation-bar ancestry, selected child state, and the foreground package are checked. Before tapping, the worker observes the screen again and rejects stale or ambiguous targets.

PASS requires the LLM-requested destination's selected tab **and** independently observed destination content markers. The destination must differ from Home and remain verified across at least three observations spanning two seconds, followed by a post-screenshot check. Repeated controls, Home/Back taps, changed feed content, a wrong destination, or a single transient selected-tab state cannot satisfy this condition.

A run writes `report.txt`, `result.json`, UI trees, and screenshots under `lab-runs/<app>-<timestamp>/`. The report includes model choice, target, elapsed observation evidence, stop reason, and result. Failures also capture bounded Android crash and process-exit diagnostics when ADB is available. Screenshots/UI trees stay local; only navigation labels are sent to local Ollama. `lab-runs/` and `startup-diagnosis/` are Git-ignored.

## Regression tests

```powershell
powershell -NoProfile -File D:\BOTFARM\verify-navigation.ps1
```

This performs a clean compile of production/test sources and explicitly executes the dependency-free `NavigationPolicyTest` runner through Maven's exec plugin. `mvn test` alone does not execute this runner. Synthetic accessibility trees exercise real transitions, unchanged screens, wrong destinations, selected-tab/content disagreement, hidden or duplicate controls, sign-in/verification/crash prompts, and stability resets. No account data is embedded in tests.

## TikTok status and device preservation

The original `Pixel_10_-_First_Device_Test` AVD (`emulator-5554`) retains its image and app data. TikTok 47.0.3 aborted outside Appium on its 16 KB Android image. Do not alter selectors to conceal that startup failure.

The separate `BOTFARM_API35_4KB` AVD (`emulator-5556`) uses Android 35 Google Play, 4 KB pages, and 4 GB RAM. It is left visible for manual verification/sign-in. Neither AVD was wiped and no account data was migrated.

```powershell
powershell -NoProfile -File D:\BOTFARM\run-social-lab.ps1 -App tiktok -Device emulator-5556
```

TikTok sign-in and age-verification prompts stop the worker explicitly. Its signed-in destination adapter still requires inspection and validation after manual sign-in; an unknown screen stops safely instead of borrowing Instagram's markers or claiming PASS. A successful startup observation does not certify signed-in navigation or long-run app stability.
