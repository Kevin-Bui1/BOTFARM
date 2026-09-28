param(
    [ValidateSet('tiktok','instagram')][string]$App = 'tiktok',
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Device,
    [ValidateSet('search','profile','search-to-profile','user-search')][string]$Goal,
    [string]$Handle,
    [string]$SearchUiContract,
    [switch]$ListGoals
)
$ErrorActionPreference = 'Stop'
if ($ListGoals) {
    Write-Output 'instagram: search (Home -> Search), profile (Home -> Profile)'
    Write-Output 'tiktok: profile (Home -> Profile), search (Home -> Search form; no query submission)'
    Write-Output 'instagram: search-to-profile (Home -> Search -> Profile)'
    Write-Output 'tiktok: search-to-profile (Home -> Search form -> Home -> Profile)'
    Write-Output 'instagram/tiktok: user-search (-Handle required; exact account-only result and profile identity; calibrated local UI contract required)'
    return
}
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$expectedAvd = if ($App -eq 'tiktok') { 'BOTFARM_API35_4KB' } else { 'Pixel_10_-_First_Device_Test' }
function Find-AvdDevice([string]$AvdName) {
    if (!(Test-Path $adb)) { return $null }
    $found = @()
    foreach ($line in @(& $adb devices 2>$null)) {
        if ($line -notmatch '^(emulator-\d+)\s+device$') { continue }
        $candidate = $matches[1]
        $name = ((& $adb -s $candidate emu avd name 2>$null | Select-Object -First 1) -as [string]).Trim()
        if ($name -eq $AvdName) { $found += $candidate }
    }
    if ($found.Count -gt 1) { throw "AMBIGUOUS_AVD: more than one online device is $AvdName" }
    if ($found.Count -eq 1) { return $found[0] }
    return $null
}
$resolvedDevice = Find-AvdDevice $expectedAvd
if (!$Device) {
    if (!$resolvedDevice) { throw "AVD_NOT_CONNECTED: start $expectedAvd, or specify its current emulator port with -Device." }
    $Device = $resolvedDevice
} elseif ($Device -match '^emulator-\d+$' -and $resolvedDevice -and $Device -ne $resolvedDevice) {
    throw "DEVICE_AVD_MISMATCH: $expectedAvd is $resolvedDevice, not $Device. Use -Device $resolvedDevice or omit -Device."
}
if (!$Goal) { $Goal = if ($App -eq 'tiktok') { 'profile' } else { 'search' } }
if ($Goal -eq 'user-search') {
    if (!$Handle) { throw 'HANDLE_REQUIRED: provide -Handle; no account is chosen by default.' }
    $limit = if ($App -eq 'instagram') { 30 } else { 24 }
    $username = $Handle -replace '^@',''
    if ($username -notmatch "^[A-Za-z0-9._]{1,$limit}$" -or $username.StartsWith('.') -or $username.EndsWith('.') -or $username.Contains('..')) {
        throw 'INVALID_HANDLE: provide an exact username, not a URL or display name.'
    }
    $env:LAB_SEARCH_HANDLE = $Handle
    if ($SearchUiContract) { $env:LAB_SEARCH_UI_CONTRACT = [IO.Path]::GetFullPath($SearchUiContract) }
    else { Remove-Item Env:LAB_SEARCH_UI_CONTRACT -ErrorAction SilentlyContinue }
} else {
    if ($Handle -or $SearchUiContract) { throw '-Handle and -SearchUiContract apply only to -Goal user-search.' }
    Remove-Item Env:LAB_SEARCH_HANDLE -ErrorAction SilentlyContinue
    Remove-Item Env:LAB_SEARCH_UI_CONTRACT -ErrorAction SilentlyContinue
}
Set-Location $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-26.0.1'
$env:LAB_MODEL = 'qwen3:8b'
$env:LAB_DEVICE = $Device
$env:OLLAMA_HOST = '127.0.0.1:11434'

function Wait-LocalApi([string]$Url) {
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try { return Invoke-RestMethod $Url -TimeoutSec 2 } catch { Start-Sleep -Seconds 1 }
    }
    throw "Local service did not start: $Url"
}
try { $null = Invoke-RestMethod http://127.0.0.1:11434/api/version -TimeoutSec 2 }
catch {
    $ollamaExe = "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe"
    if (!(Test-Path $ollamaExe)) { throw 'Official Ollama runtime is not installed in the default location' }
    Start-Process -FilePath $ollamaExe -ArgumentList 'serve' -WindowStyle Hidden -RedirectStandardOutput "$PSScriptRoot\ollama-serve.stdout.log" -RedirectStandardError "$PSScriptRoot\ollama-serve.stderr.log"
    $null = Wait-LocalApi 'http://127.0.0.1:11434/api/version'
}
try { $null = Invoke-RestMethod http://127.0.0.1:4723/status -TimeoutSec 2 }
catch {
    $appiumJs = "$env:APPDATA\npm\node_modules\appium\index.js"
    if (!(Test-Path $appiumJs)) { throw 'Appium is not installed in the expected npm location' }
    Start-Process -FilePath 'C:\Program Files\nodejs\node.exe' -ArgumentList @($appiumJs,'server','--address','127.0.0.1','--port','4723','--allow-insecure','uiautomator2:chromedriver_autodownload','--log-level','warn') -WindowStyle Hidden -RedirectStandardOutput "$PSScriptRoot\appium-social-lab.stdout.log" -RedirectStandardError "$PSScriptRoot\appium-social-lab.stderr.log"
    $null = Wait-LocalApi 'http://127.0.0.1:4723/status'
}
& 'D:\Tools\apache-maven-3.9.16\bin\mvn.cmd' '-Dmaven.repo.local=D:\BOTFARM\.m2\repository' '-Dexec.mainClass=SocialAppLabWorker' "-Dexec.args=$App $Goal" compile exec:java
exit $LASTEXITCODE
