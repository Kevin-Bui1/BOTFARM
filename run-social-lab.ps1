param(
    [ValidateSet('tiktok','instagram')][string]$App = 'tiktok',
    [ValidatePattern('^[A-Za-z0-9._:-]+$')][string]$Device,
    [ValidateSet('search','profile')][string]$Goal,
    [switch]$ListGoals
)
$ErrorActionPreference = 'Stop'
if ($ListGoals) {
    Write-Output 'instagram: search (Home -> Search), profile (Home -> Profile)'
    Write-Output 'tiktok: profile (Home -> Profile), search (Home -> Search form; no query submission)'
    return
}
if (!$Device) { $Device = if ($App -eq 'tiktok') { 'emulator-5556' } else { 'emulator-5554' } }
if (!$Goal) { $Goal = if ($App -eq 'tiktok') { 'profile' } else { 'search' } }
Set-Location $PSScriptRoot
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-26.0.1'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
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
